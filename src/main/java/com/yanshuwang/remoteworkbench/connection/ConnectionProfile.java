package com.yanshuwang.remoteworkbench.connection;

import java.util.Objects;

public record ConnectionProfile(String name, String host, int port, String username) {
    public ConnectionProfile {
        name = Objects.requireNonNull(name, "name").trim();
        host = Objects.requireNonNull(host, "host").trim();
        username = Objects.requireNonNull(username, "username").trim();

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
}
