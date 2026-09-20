package com.yanshuwang.remoteworkbench.ssh;

import com.yanshuwang.remoteworkbench.config.ConfigStorageService;
import com.yanshuwang.remoteworkbench.connection.ConnectionProfile;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.keyverifier.DefaultKnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelListener;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.time.Duration;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class SshConnectionService implements AutoCloseable {
    private static final long OPERATION_TIMEOUT_SECONDS = 10;
    private static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 10 * 60 * 1000L; // 10 minutes

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService idleMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ssh-idle-disconnect-monitor");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, ActiveConnection> activeConnections = new ConcurrentHashMap<>();

    private volatile long idleTimeoutMillis = DEFAULT_IDLE_TIMEOUT_MILLIS;
    private Consumer<String> onIdleTimeoutCallback;

    public SshConnectionService() {
        idleMonitor.scheduleWithFixedDelay(this::checkIdleConnections, 10, 10, TimeUnit.SECONDS);
    }

    private void checkIdleConnections() {
        if (idleTimeoutMillis <= 0 || activeConnections.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        List<String> expiredIds = new ArrayList<>();
        for (Map.Entry<String, ActiveConnection> entry : activeConnections.entrySet()) {
            if (now - entry.getValue().getLastActivityTime() >= idleTimeoutMillis) {
                expiredIds.add(entry.getKey());
            }
        }

        for (String profileId : expiredIds) {
            disconnect(profileId);
            if (onIdleTimeoutCallback != null) {
                try {
                    onIdleTimeoutCallback.accept(profileId);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void setOnIdleTimeout(Consumer<String> callback) {
        this.onIdleTimeoutCallback = callback;
    }

    public void setIdleTimeoutMinutes(int minutes) {
        this.idleTimeoutMillis = minutes > 0 ? (long) minutes * 60 * 1000L : 0;
    }

    public void recordUserActivity(ConnectionProfile profile) {
        if (profile != null) {
            recordUserActivity(profile.id());
        }
    }

    public void recordUserActivity(String profileId) {
        if (profileId != null) {
            ActiveConnection connection = activeConnections.get(profileId);
            if (connection != null) {
                connection.touch();
            }
        }
    }

    public CompletableFuture<Void> connect(
            ConnectionProfile profile,
            String password,
            ServerKeyConfirmation serverKeyConfirmation
    ) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(serverKeyConfirmation, "serverKeyConfirmation");

        return CompletableFuture.runAsync(() -> {
            disconnect(profile);

            SshClient client = SshClient.setUpDefaultClient();
            CoreModuleProperties.HEARTBEAT_INTERVAL.set(client, Duration.ofSeconds(30));
            CoreModuleProperties.IDLE_TIMEOUT.set(client, Duration.ZERO);
            CoreModuleProperties.NIO2_READ_TIMEOUT.set(client, Duration.ZERO);

            try {
                Path appKnownHosts = ConfigStorageService.getAppDataDirectory().resolve("known_hosts");
                Path systemKnownHosts = getKnownHostsPath();
                client.setServerKeyVerifier(createServerKeyVerifier(profile, serverKeyConfirmation, appKnownHosts, systemKnownHosts));
                client.start();

                ClientSession session = client
                        .connect(profile.username(), profile.host(), profile.port())
                        .verify(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .getSession();

                if (profile.isKeyAuth() && profile.privateKeyPath() != null && !profile.privateKeyPath().isBlank()) {
                    Path keyFile = Path.of(profile.privateKeyPath());
                    if (!Files.exists(keyFile)) {
                        throw new IOException("指定的私钥文件不存在：" + keyFile);
                    }
                    FileKeyPairProvider keyPairProvider = new FileKeyPairProvider(keyFile);
                    String pass = (profile.passphrase() != null && !profile.passphrase().isBlank())
                            ? profile.passphrase()
                            : password;
                    if (pass != null && !pass.isBlank()) {
                        keyPairProvider.setPasswordFinder(FilePasswordProvider.of(pass));
                    }
                    Iterable<KeyPair> keyPairs = keyPairProvider.loadKeys(session);
                    boolean hasKeys = false;
                    for (KeyPair kp : keyPairs) {
                        session.addPublicKeyIdentity(kp);
                        hasKeys = true;
                    }
                    if (!hasKeys) {
                        throw new IOException("无法从私钥文件解析出有效的 SSH 密钥对：" + keyFile);
                    }
                } else {
                    String pwd = (password != null && !password.isEmpty()) ? password : profile.password();
                    session.addPasswordIdentity(pwd);
                }

                session.auth().verify(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                activeConnections.put(profile.id(), new ActiveConnection(client, session));
            } catch (Exception exception) {
                client.stop();
                throw new CompletionException(exception);
            }
        }, executor);
    }

    public CompletableFuture<Void> openTerminal(
            ConnectionProfile profile,
            Consumer<String> outputListener
    ) {
        return openTerminal(profile, "default", outputListener, null);
    }

    public CompletableFuture<Void> openTerminal(
            ConnectionProfile profile,
            String channelId,
            Consumer<String> outputListener
    ) {
        return openTerminal(profile, channelId, outputListener, null);
    }

    public CompletableFuture<Void> openTerminal(
            ConnectionProfile profile,
            String channelId,
            Consumer<String> outputListener,
            Runnable onExit
    ) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(outputListener, "outputListener");

        return CompletableFuture.runAsync(() -> {
            ActiveConnection connection = requireConnection(profile);
            try {
                connection.openTerminal(channelId, outputListener, onExit);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    public void sendTerminalInput(ConnectionProfile profile, String input) {
        sendTerminalInput(profile, "default", input);
    }

    public void sendTerminalInput(ConnectionProfile profile, String channelId, String input) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(input, "input");

        try {
            ActiveConnection connection = requireConnection(profile);
            connection.touch();
            connection.sendTerminalInput(channelId, input);
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    public void resizeTerminal(ConnectionProfile profile, int cols, int rows, int width, int height) {
        resizeTerminal(profile, "default", cols, rows, width, height);
    }

    public void resizeTerminal(ConnectionProfile profile, String channelId, int cols, int rows, int width, int height) {
        if (profile == null || channelId == null) {
            return;
        }
        ActiveConnection connection = activeConnections.get(profile.id());
        if (connection != null) {
            connection.resizeTerminal(channelId, cols, rows, width, height);
        }
    }

    public void closeTerminal(ConnectionProfile profile, String channelId) {
        if (profile == null || channelId == null) {
            return;
        }
        ActiveConnection connection = activeConnections.get(profile.id());
        if (connection != null) {
            connection.closeTerminal(channelId);
        }
    }

    public CompletableFuture<String> execute(ConnectionProfile profile, String command) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(command, "command");

        return CompletableFuture.supplyAsync(() -> {
            ActiveConnection connection = requireConnection(profile);
            try {
                return connection.session().executeRemoteCommand(command);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    @FunctionalInterface
    public interface SftpOperation<T> {
        T execute(SftpClient client) throws Exception;
    }

    public <T> CompletableFuture<T> withSftpClient(ConnectionProfile profile, SftpOperation<T> operation) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(operation, "operation");

        return CompletableFuture.supplyAsync(() -> {
            ActiveConnection connection = requireConnection(profile);
            connection.touch();
            try {
                SftpClient sftpClient = connection.getOrCreateSftpClient();
                return operation.execute(sftpClient);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    public boolean isConnected(ConnectionProfile profile) {
        return profile != null && isConnected(profile.id());
    }

    public boolean isConnected(String profileId) {
        if (profileId == null) {
            return false;
        }
        ActiveConnection connection = activeConnections.get(profileId);
        return connection != null && connection.session().isOpen();
    }

    public boolean isTerminalOpen(ConnectionProfile profile) {
        return isTerminalOpen(profile, "default");
    }

    public boolean isTerminalOpen(ConnectionProfile profile, String channelId) {
        if (profile == null || channelId == null) {
            return false;
        }
        ActiveConnection connection = activeConnections.get(profile.id());
        return connection != null && connection.isTerminalOpen(channelId);
    }

    public void disconnect(ConnectionProfile profile) {
        if (profile == null) {
            return;
        }
        disconnect(profile.id());
    }

    public void disconnect(String profileId) {
        if (profileId == null) {
            return;
        }
        ActiveConnection connection = activeConnections.remove(profileId);
        if (connection != null) {
            connection.close();
        }
    }

    @Override
    public void close() {
        idleMonitor.shutdownNow();
        activeConnections.values().forEach(ActiveConnection::close);
        activeConnections.clear();
        executor.shutdownNow();
    }

    private ActiveConnection requireConnection(ConnectionProfile profile) {
        if (profile == null) {
            throw new CompletionException(new IllegalStateException("SSH session is not connected"));
        }
        ActiveConnection connection = activeConnections.get(profile.id());
        if (connection == null || !connection.session().isOpen()) {
            throw new CompletionException(new IllegalStateException("SSH session is not connected: " + profile.name()));
        }
        return connection;
    }

    private static Path getKnownHostsPath() {
        return Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
    }

    private static ServerKeyVerifier createServerKeyVerifier(
            ConnectionProfile profile,
            ServerKeyConfirmation serverKeyConfirmation,
            Path appKnownHosts,
            Path systemKnownHosts
    ) {
        return (clientSession, remoteAddress, serverKey) -> {
            String host = profile.host();
            int port = profile.port();
            String hostAddress = null;
            if (remoteAddress instanceof InetSocketAddress inet) {
                if (inet.getAddress() != null) {
                    hostAddress = inet.getAddress().getHostAddress();
                }
                if (port <= 0) {
                    port = inet.getPort();
                }
            }
            if (port <= 0) {
                port = 22;
            }

            String keyString = PublicKeyEntry.toString(serverKey);
            String fingerprint = KeyUtils.getFingerPrint(serverKey);

            // 1. Check if already trusted in app's known_hosts
            if (isHostKeyTrusted(appKnownHosts, host, port, hostAddress, keyString)) {
                return true;
            }

            // 2. Check if already trusted in system ~/.ssh/known_hosts (if accessible)
            if (systemKnownHosts != null && isHostKeyTrusted(systemKnownHosts, host, port, hostAddress, keyString)) {
                recordTrustedHostKey(appKnownHosts, host, port, hostAddress, keyString);
                return true;
            }

            // 3. Not yet trusted, ask user for confirmation
            boolean confirmed = serverKeyConfirmation.confirm(host, port, fingerprint);
            if (confirmed) {
                // Permanently record in app's known_hosts
                recordTrustedHostKey(appKnownHosts, host, port, hostAddress, keyString);
                // Also attempt to append to system known_hosts if permitted
                if (systemKnownHosts != null) {
                    recordTrustedHostKey(systemKnownHosts, host, port, hostAddress, keyString);
                }
                return true;
            }

            return false;
        };
    }

    private static boolean isHostKeyTrusted(Path file, String host, int port, String hostAddress, String keyString) {
        if (file == null || !Files.exists(file) || !Files.isReadable(file)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                int offset = 0;
                if (parts[0].startsWith("@")) {
                    offset = 1;
                }
                if (parts.length < offset + 3) {
                    continue;
                }
                String patternPart = parts[offset];
                String lineKeyType = parts[offset + 1];
                String lineKeyData = parts[offset + 2];
                String lineFullKey = lineKeyType + " " + lineKeyData;

                if (matchesHostPattern(patternPart, host, port) || (hostAddress != null && matchesHostPattern(patternPart, hostAddress, port))) {
                    if (lineFullKey.equals(keyString)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean matchesHostPattern(String patternPart, String host, int port) {
        if (patternPart == null || patternPart.isBlank() || host == null) {
            return false;
        }
        String expectedWithPort = (port == 22) ? host : "[" + host + "]:" + port;
        String[] items = patternPart.split(",");
        for (String item : items) {
            item = item.trim();
            if (item.equalsIgnoreCase(host) || item.equalsIgnoreCase(expectedWithPort)) {
                return true;
            }
            if (port == 22 && item.equalsIgnoreCase("[" + host + "]:22")) {
                return true;
            }
        }
        return false;
    }

    private static void recordTrustedHostKey(Path file, String host, int port, String hostAddress, String keyString) {
        if (file == null) {
            return;
        }
        if (isHostKeyTrusted(file, host, port, hostAddress, keyString)) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            String pattern;
            if (port == 22) {
                pattern = (hostAddress != null && !hostAddress.equalsIgnoreCase(host))
                        ? host + "," + hostAddress
                        : host;
            } else {
                pattern = (hostAddress != null && !hostAddress.equalsIgnoreCase(host))
                        ? "[" + host + "]:" + port + ",[" + hostAddress + "]:" + port
                        : "[" + host + "]:" + port;
            }
            String entryLine = pattern + " " + keyString + System.lineSeparator();
            Files.writeString(file, entryLine, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    @FunctionalInterface
    public interface ServerKeyConfirmation {
        boolean confirm(String host, int port, String fingerprint);
    }

    private static final class ActiveConnection implements AutoCloseable {
        private final SshClient client;
        private final ClientSession session;
        private final Map<String, TerminalChannel> channels = new ConcurrentHashMap<>();
        private SftpClient sftpClient;
        private volatile long lastActivityTime = System.currentTimeMillis();

        private ActiveConnection(SshClient client, ClientSession session) {
            this.client = client;
            this.session = session;
        }

        public void touch() {
            this.lastActivityTime = System.currentTimeMillis();
        }

        public long getLastActivityTime() {
            return lastActivityTime;
        }

        private synchronized void openTerminal(String channelId, Consumer<String> outputListener, Runnable onExit) throws IOException {
            TerminalChannel existing = channels.get(channelId);
            if (existing != null && existing.isOpen()) {
                return;
            }
            if (existing != null) {
                existing.close();
                channels.remove(channelId);
            }

            PipedInputStream remoteInput = new PipedInputStream(8192);
            PipedOutputStream localInput = new PipedOutputStream(remoteInput);
            ChannelShell shellChannel = session.createShellChannel();
            ListenerOutputStream remoteOutput = new ListenerOutputStream(outputListener);

            try {
                shellChannel.setupSensibleDefaultPty();
                shellChannel.setPtyType("xterm-256color");
                shellChannel.setPtyColumns(120);
                shellChannel.setPtyLines(40);
                shellChannel.setPtyWidth(1200);
                shellChannel.setPtyHeight(700);
                shellChannel.setIn(remoteInput);
                shellChannel.setOut(remoteOutput);
                shellChannel.setErr(remoteOutput);

                if (onExit != null) {
                    shellChannel.addChannelListener(new ChannelListener() {
                        public void channelClosed(Channel channel) {
                            try {
                                onExit.run();
                            } catch (Exception ignored) {
                            }
                        }
                    });
                }

                shellChannel.open().verify(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                channels.put(channelId, new TerminalChannel(shellChannel, localInput, remoteInput));
            } catch (IOException | RuntimeException exception) {
                localInput.close();
                remoteInput.close();
                shellChannel.close(false);
                throw exception;
            }
        }

        private synchronized void closeTerminal(String channelId) {
            TerminalChannel ch = channels.remove(channelId);
            if (ch != null) {
                ch.close();
            }
        }

        private synchronized SftpClient getOrCreateSftpClient() throws IOException {
            if (sftpClient == null || !sftpClient.isOpen()) {
                sftpClient = SftpClientFactory.instance().createSftpClient(session);
            }
            return sftpClient;
        }

        private synchronized boolean isTerminalOpen(String channelId) {
            TerminalChannel ch = channels.get(channelId);
            return ch != null && ch.isOpen();
        }

        private synchronized void sendTerminalInput(String channelId, String input) throws IOException {
            TerminalChannel ch = channels.get(channelId);
            if (ch == null || !ch.isOpen()) {
                throw new IOException("SSH terminal channel [" + channelId + "] is not open");
            }

            ch.sendInput(input);
        }

        private synchronized void resizeTerminal(String channelId, int cols, int rows, int width, int height) {
            TerminalChannel ch = channels.get(channelId);
            if (ch != null) {
                ch.resize(cols, rows, width, height);
            }
        }

        private ClientSession session() {
            return session;
        }

        @Override
        public synchronized void close() {
            channels.values().forEach(TerminalChannel::close);
            channels.clear();
            if (sftpClient != null) {
                try {
                    sftpClient.close();
                } catch (IOException ignored) {
                }
                sftpClient = null;
            }
            session.close(false);
            client.stop();
        }
    }

    private static final class TerminalChannel implements AutoCloseable {
        private final ChannelShell shell;
        private final PipedOutputStream shellInput;
        private final PipedInputStream remoteInput;

        private TerminalChannel(ChannelShell shell, PipedOutputStream shellInput, PipedInputStream remoteInput) {
            this.shell = shell;
            this.shellInput = shellInput;
            this.remoteInput = remoteInput;
        }

        private boolean isOpen() {
            return shell != null && shell.isOpen();
        }

        private void sendInput(String input) throws IOException {
            if (!isOpen()) {
                throw new IOException("SSH terminal channel is not open");
            }
            shellInput.write(input.getBytes(StandardCharsets.UTF_8));
            shellInput.flush();
        }

        private void resize(int cols, int rows, int width, int height) {
            if (isOpen()) {
                try {
                    shell.sendWindowChange(cols, rows, height, width);
                } catch (IOException ignored) {
                }
            }
        }

        @Override
        public void close() {
            if (shell != null) {
                shell.close(false);
            }
            if (shellInput != null) {
                try {
                    shellInput.close();
                } catch (IOException ignored) {
                }
            }
            if (remoteInput != null) {
                try {
                    remoteInput.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static final class ListenerOutputStream extends OutputStream {
        private final Consumer<String> listener;
        private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        private ByteBuffer byteBuffer = ByteBuffer.allocate(8192);
        private final CharBuffer charBuffer = CharBuffer.allocate(8192);

        private ListenerOutputStream(Consumer<String> listener) {
            this.listener = listener;
        }

        @Override
        public synchronized void write(int value) {
            ensureCapacity(1);
            byteBuffer.put((byte) value);
            flushDecoder(false);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            if (length <= 0) {
                return;
            }
            ensureCapacity(length);
            byteBuffer.put(bytes, offset, length);
            flushDecoder(false);
        }

        @Override
        public synchronized void flush() {
            flushDecoder(false);
        }

        @Override
        public synchronized void close() {
            flushDecoder(true);
        }

        private void ensureCapacity(int additionalBytes) {
            if (byteBuffer.remaining() < additionalBytes) {
                int newCapacity = Math.max(byteBuffer.capacity() * 2, byteBuffer.position() + additionalBytes);
                ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
                byteBuffer.flip();
                newBuffer.put(byteBuffer);
                byteBuffer = newBuffer;
            }
        }

        private void flushDecoder(boolean endOfInput) {
            byteBuffer.flip();
            while (true) {
                CoderResult result = decoder.decode(byteBuffer, charBuffer, endOfInput);
                charBuffer.flip();
                if (charBuffer.hasRemaining()) {
                    listener.accept(charBuffer.toString());
                    charBuffer.clear();
                } else {
                    charBuffer.clear();
                }
                if (!result.isOverflow()) {
                    break;
                }
            }
            byteBuffer.compact();
        }
    }
}
