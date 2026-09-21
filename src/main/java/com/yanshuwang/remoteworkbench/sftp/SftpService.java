package com.yanshuwang.remoteworkbench.sftp;

import com.yanshuwang.remoteworkbench.connection.ConnectionProfile;
import com.yanshuwang.remoteworkbench.ssh.SshConnectionService;
import org.apache.sshd.sftp.client.SftpClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

public final class SftpService {
    private static final int BUFFER_SIZE = 64 * 1024;
    private final SshConnectionService sshConnectionService;

    public SftpService(SshConnectionService sshConnectionService) {
        this.sshConnectionService = Objects.requireNonNull(sshConnectionService, "sshConnectionService");
    }

    public CompletableFuture<String> resolveDefaultDirectory(ConnectionProfile profile) {
        return sshConnectionService.withSftpClient(profile, client -> {
            try {
                String path = client.canonicalPath(".");
                return (path == null || path.isBlank()) ? "/" : path;
            } catch (Exception exception) {
                return "/";
            }
        });
    }

    public CompletableFuture<List<RemoteFileItem>> listDirectory(ConnectionProfile profile, String remotePath) {
        Objects.requireNonNull(remotePath, "remotePath");
        final String normalizedPath = normalizeRemotePath(remotePath);

        return sshConnectionService.withSftpClient(profile, client -> {
            List<RemoteFileItem> items = new ArrayList<>();
            Iterable<SftpClient.DirEntry> entries = client.readDir(normalizedPath);
            for (SftpClient.DirEntry entry : entries) {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }

                SftpClient.Attributes attrs = entry.getAttributes();
                String itemPath = joinPath(normalizedPath, name);
                boolean isDir = attrs.isDirectory();
                boolean isLink = attrs.isSymbolicLink();
                long size = attrs.getSize();
                long modifyTime = attrs.getModifyTime() != null ? attrs.getModifyTime().toMillis() : 0;
                String permissions = formatPermissions(attrs);

                items.add(new RemoteFileItem(
                        name,
                        itemPath,
                        size,
                        isDir,
                        isLink,
                        permissions,
                        modifyTime
                ));
            }
            Collections.sort(items);
            return items;
        });
    }

    public CompletableFuture<Void> createDirectory(ConnectionProfile profile, String remotePath) {
        Objects.requireNonNull(remotePath, "remotePath");
        return sshConnectionService.withSftpClient(profile, client -> {
            client.mkdir(normalizeRemotePath(remotePath));
            return null;
        });
    }

    public CompletableFuture<Void> delete(ConnectionProfile profile, String remotePath, boolean isDirectory) {
        Objects.requireNonNull(remotePath, "remotePath");
        return sshConnectionService.withSftpClient(profile, client -> {
            String path = normalizeRemotePath(remotePath);
            if (isDirectory) {
                client.rmdir(path);
            } else {
                client.remove(path);
            }
            return null;
        });
    }

    public CompletableFuture<Void> rename(ConnectionProfile profile, String oldPath, String newPath) {
        Objects.requireNonNull(oldPath, "oldPath");
        Objects.requireNonNull(newPath, "newPath");
        return sshConnectionService.withSftpClient(profile, client -> {
            client.rename(normalizeRemotePath(oldPath), normalizeRemotePath(newPath));
            return null;
        });
    }

    public CompletableFuture<String> readTextFile(ConnectionProfile profile, String remotePath) {
        Objects.requireNonNull(remotePath, "remotePath");
        return sshConnectionService.withSftpClient(profile, client -> {
            try (InputStream in = client.read(normalizeRemotePath(remotePath))) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        });
    }

    public CompletableFuture<Void> writeTextFile(ConnectionProfile profile, String remotePath, String content) {
        Objects.requireNonNull(remotePath, "remotePath");
        Objects.requireNonNull(content, "content");
        return sshConnectionService.withSftpClient(profile, client -> {
            byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (OutputStream out = client.write(
                    normalizeRemotePath(remotePath),
                    EnumSet.of(SftpClient.OpenMode.Create, SftpClient.OpenMode.Write, SftpClient.OpenMode.Truncate)
            )) {
                out.write(bytes);
                out.flush();
            }
            return null;
        });
    }

    public CompletableFuture<Void> changePermissions(ConnectionProfile profile, String remotePath, int permissions) {
        Objects.requireNonNull(remotePath, "remotePath");
        return sshConnectionService.withSftpClient(profile, client -> {
            SftpClient.Attributes attrs = new SftpClient.Attributes();
            attrs.setPermissions(permissions);
            client.setStat(normalizeRemotePath(remotePath), attrs);
            return null;
        });
    }

    public CompletableFuture<Void> downloadFile(
            ConnectionProfile profile,
            String remotePath,
            Path localPath,
            LongConsumer progressListener
    ) {
        Objects.requireNonNull(remotePath, "remotePath");
        Objects.requireNonNull(localPath, "localPath");

        return sshConnectionService.withSftpClient(profile, client -> {
            Path parent = localPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (InputStream in = client.read(normalizeRemotePath(remotePath));
                 OutputStream out = Files.newOutputStream(localPath)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                long totalRead = 0;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    totalRead += read;
                    if (progressListener != null) {
                        progressListener.accept(totalRead);
                    }
                }
                out.flush();
            }
            return null;
        });
    }

    public CompletableFuture<Void> uploadFile(
            ConnectionProfile profile,
            Path localPath,
            String remotePath,
            LongConsumer progressListener
    ) {
        Objects.requireNonNull(localPath, "localPath");
        Objects.requireNonNull(remotePath, "remotePath");

        return sshConnectionService.withSftpClient(profile, client -> {
            try (InputStream in = Files.newInputStream(localPath);
                 OutputStream out = client.write(
                         normalizeRemotePath(remotePath),
                         EnumSet.of(SftpClient.OpenMode.Create, SftpClient.OpenMode.Write, SftpClient.OpenMode.Truncate)
                 )) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                long totalWritten = 0;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    totalWritten += read;
                    if (progressListener != null) {
                        progressListener.accept(totalWritten);
                    }
                }
                out.flush();
            }
            return null;
        });
    }

    public CompletableFuture<Void> uploadPath(
            ConnectionProfile profile,
            Path localPath,
            String remoteTargetDir,
            Consumer<String> fileListener,
            LongConsumer progressListener
    ) {
        Objects.requireNonNull(localPath, "localPath");
        Objects.requireNonNull(remoteTargetDir, "remoteTargetDir");

        return sshConnectionService.withSftpClient(profile, client -> {
            uploadRecursively(client, localPath, remoteTargetDir, fileListener, progressListener);
            return null;
        });
    }

    private void uploadRecursively(
            SftpClient client,
            Path localPath,
            String remoteTargetDir,
            Consumer<String> fileListener,
            LongConsumer progressListener
    ) throws Exception {
        String itemName = localPath.getFileName().toString();
        String destinationPath = joinPath(remoteTargetDir, itemName);

        if (Files.isDirectory(localPath)) {
            try {
                client.mkdir(normalizeRemotePath(destinationPath));
            } catch (Exception ignored) {
                // Directory may already exist
            }
            try (var stream = Files.list(localPath)) {
                List<Path> children = stream.toList();
                for (Path child : children) {
                    uploadRecursively(client, child, destinationPath, fileListener, progressListener);
                }
            }
        } else if (Files.isRegularFile(localPath)) {
            if (fileListener != null) {
                fileListener.accept(itemName);
            }
            try (InputStream in = Files.newInputStream(localPath);
                 OutputStream out = client.write(
                         normalizeRemotePath(destinationPath),
                         EnumSet.of(SftpClient.OpenMode.Create, SftpClient.OpenMode.Write, SftpClient.OpenMode.Truncate)
                 )) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                long totalWritten = 0;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    totalWritten += read;
                    if (progressListener != null) {
                        progressListener.accept(totalWritten);
                    }
                }
                out.flush();
            }
        }
    }

    public static String normalizeRemotePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String clean = path.trim().replace('\\', '/');
        if (!clean.startsWith("/")) {
            clean = "/" + clean;
        }
        while (clean.contains("//")) {
            clean = clean.replace("//", "/");
        }
        if (clean.length() > 1 && clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    public static String getParentDirectory(String path) {
        String normalized = normalizeRemotePath(path);
        if ("/".equals(normalized)) {
            return "/";
        }
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "/";
        }
        return normalized.substring(0, lastSlash);
    }

    public static String getFileName(String path) {
        String normalized = normalizeRemotePath(path);
        if ("/".equals(normalized)) {
            return "/";
        }
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash < 0) {
            return normalized;
        }
        return normalized.substring(lastSlash + 1);
    }

    public static String joinPath(String parent, String child) {
        String normalizedParent = normalizeRemotePath(parent);
        if ("/".equals(normalizedParent)) {
            return "/" + child;
        }
        return normalizedParent + "/" + child;
    }

    private static String formatPermissions(SftpClient.Attributes attrs) {
        int perms = attrs.getPermissions();
        if (perms <= 0) {
            return attrs.isDirectory() ? "drwxr-xr-x" : "-rw-r--r--";
        }
        char[] p = new char[10];
        p[0] = attrs.isDirectory() ? 'd' : (attrs.isSymbolicLink() ? 'l' : '-');
        p[1] = (perms & 0400) != 0 ? 'r' : '-';
        p[2] = (perms & 0200) != 0 ? 'w' : '-';
        p[3] = (perms & 0100) != 0 ? 'x' : '-';
        p[4] = (perms & 0040) != 0 ? 'r' : '-';
        p[5] = (perms & 0020) != 0 ? 'w' : '-';
        p[6] = (perms & 0010) != 0 ? 'x' : '-';
        p[7] = (perms & 0004) != 0 ? 'r' : '-';
        p[8] = (perms & 0002) != 0 ? 'w' : '-';
        p[9] = (perms & 0001) != 0 ? 'x' : '-';
        return new String(p);
    }
}
