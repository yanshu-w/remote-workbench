package com.yanshuwang.remoteworkbench.ssh;

import com.yanshuwang.remoteworkbench.connection.ConnectionProfile;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.keyverifier.DefaultKnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class SshConnectionService implements AutoCloseable {
    private static final long OPERATION_TIMEOUT_SECONDS = 10;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<ConnectionProfile, ActiveConnection> activeConnections = new ConcurrentHashMap<>();

    public CompletableFuture<Void> connect(
            ConnectionProfile profile,
            String password,
            ServerKeyConfirmation serverKeyConfirmation
    ) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(serverKeyConfirmation, "serverKeyConfirmation");

        return CompletableFuture.runAsync(() -> {
            disconnect(profile);

            SshClient client = SshClient.setUpDefaultClient();

            try {
                Path knownHosts = getKnownHostsPath();
                Files.createDirectories(knownHosts.getParent());
                client.setServerKeyVerifier(createServerKeyVerifier(profile, serverKeyConfirmation, knownHosts));
                client.start();

                ClientSession session = client
                        .connect(profile.username(), profile.host(), profile.port())
                        .verify(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .getSession();

                session.addPasswordIdentity(password);
                session.auth().verify(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                activeConnections.put(profile, new ActiveConnection(client, session));
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
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(outputListener, "outputListener");

        return CompletableFuture.runAsync(() -> {
            ActiveConnection connection = requireConnection(profile);
            try {
                connection.openTerminal(outputListener);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    public void sendTerminalInput(ConnectionProfile profile, String input) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(input, "input");

        try {
            requireConnection(profile).sendTerminalInput(input);
        } catch (IOException exception) {
            throw new CompletionException(exception);
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

    public boolean isConnected(ConnectionProfile profile) {
        ActiveConnection connection = activeConnections.get(profile);
        return connection != null && connection.session().isOpen();
    }

    public boolean isTerminalOpen(ConnectionProfile profile) {
        ActiveConnection connection = activeConnections.get(profile);
        return connection != null && connection.isTerminalOpen();
    }

    public void disconnect(ConnectionProfile profile) {
        ActiveConnection connection = activeConnections.remove(profile);
        if (connection != null) {
            connection.close();
        }
    }

    @Override
    public void close() {
        activeConnections.values().forEach(ActiveConnection::close);
        activeConnections.clear();
        executor.shutdownNow();
    }

    private ActiveConnection requireConnection(ConnectionProfile profile) {
        ActiveConnection connection = activeConnections.get(profile);
        if (connection == null || !connection.session().isOpen()) {
            throw new CompletionException(new IllegalStateException("SSH session is not connected"));
        }
        return connection;
    }

    private static Path getKnownHostsPath() {
        return Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
    }

    private static DefaultKnownHostsServerKeyVerifier createServerKeyVerifier(
            ConnectionProfile profile,
            ServerKeyConfirmation serverKeyConfirmation,
            Path knownHosts
    ) {
        ServerKeyVerifier unknownHostVerifier = (session, address, serverKey) ->
                serverKeyConfirmation.confirm(
                        profile.host(),
                        profile.port(),
                        KeyUtils.getFingerPrint(serverKey)
                );

        return new DefaultKnownHostsServerKeyVerifier(
                unknownHostVerifier,
                false,
                knownHosts
        );
    }

    @FunctionalInterface
    public interface ServerKeyConfirmation {
        boolean confirm(String host, int port, String fingerprint);
    }

    private static final class ActiveConnection implements AutoCloseable {
        private final SshClient client;
        private final ClientSession session;
        private ChannelShell shell;
        private PipedOutputStream shellInput;

        private ActiveConnection(SshClient client, ClientSession session) {
            this.client = client;
            this.session = session;
        }

        private synchronized void openTerminal(Consumer<String> outputListener) throws IOException {
            if (shell != null) {
                return;
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
                shellChannel.open().verify(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                shell = shellChannel;
                shellInput = localInput;
            } catch (IOException | RuntimeException exception) {
                localInput.close();
                remoteInput.close();
                shellChannel.close(false);
                throw exception;
            }
        }

        private synchronized boolean isTerminalOpen() {
            return shell != null && shell.isOpen();
        }

        private synchronized void sendTerminalInput(String input) throws IOException {
            if (!isTerminalOpen()) {
                throw new IOException("SSH terminal is not open");
            }

            shellInput.write(input.getBytes(StandardCharsets.UTF_8));
            shellInput.flush();
        }

        private ClientSession session() {
            return session;
        }

        @Override
        public synchronized void close() {
            if (shell != null) {
                shell.close(false);
                shell = null;
            }
            if (shellInput != null) {
                try {
                    shellInput.close();
                } catch (IOException ignored) {
                    // The SSH session is already being closed.
                }
                shellInput = null;
            }
            session.close(false);
            client.stop();
        }
    }

    private static final class ListenerOutputStream extends OutputStream {
        private final Consumer<String> listener;

        private ListenerOutputStream(Consumer<String> listener) {
            this.listener = listener;
        }

        @Override
        public void write(int value) {
            listener.accept(new String(new byte[]{(byte) value}, StandardCharsets.UTF_8));
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            if (length > 0) {
                listener.accept(new String(bytes, offset, length, StandardCharsets.UTF_8));
            }
        }
    }
}
