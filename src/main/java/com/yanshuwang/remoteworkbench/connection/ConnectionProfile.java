package com.yanshuwang.remoteworkbench.connection;

import java.util.Objects;
import java.util.UUID;

public record ConnectionProfile(
        String id,
        String name,
        String host,
        int port,
        String username,
        String authType,
        String password,
        String privateKeyPath,
        String passphrase,
        boolean rememberPassword,
        String initialShell
) {
    public static final String AUTH_PASSWORD = "PASSWORD";
    public static final String AUTH_KEY = "KEY";

    public ConnectionProfile(String name, String host, int port, String username) {
        this(UUID.randomUUID().toString(), name, host, port, username, AUTH_PASSWORD, "", "", "", false, "default");
    }

    public ConnectionProfile(String name, String host, int port, String username, String initialShell) {
        this(UUID.randomUUID().toString(), name, host, port, username, AUTH_PASSWORD, "", "", "", false, initialShell);
    }

    public ConnectionProfile {
        id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id.trim();
        name = Objects.requireNonNull(name, "name").trim();
        host = Objects.requireNonNull(host, "host").trim();
        username = Objects.requireNonNull(username, "username").trim();
        authType = (authType == null || authType.isBlank()) ? AUTH_PASSWORD : authType.trim();
        password = (password == null) ? "" : password;
        privateKeyPath = (privateKeyPath == null) ? "" : privateKeyPath.trim();
        passphrase = (passphrase == null) ? "" : passphrase;
        initialShell = (initialShell == null || initialShell.isBlank()) ? "default" : initialShell.trim();

        if (name.isBlank()) {
            throw new IllegalArgumentException("Connection name must not be blank");
        }
        if (host.isBlank()) {
            throw new IllegalArgumentException("Host must not be blank");
        }
        if (username.isBlank()) {
            throw new IllegalArgumentException("Username must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
    }

    public boolean isKeyAuth() {
        return AUTH_KEY.equalsIgnoreCase(authType);
    }
}
