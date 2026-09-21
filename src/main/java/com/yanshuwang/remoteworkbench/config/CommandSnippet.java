package com.yanshuwang.remoteworkbench.config;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a saved command snippet (quick command) for the terminal.
 */
public record CommandSnippet(
        String id,
        String name,
        String command,
        String category
) {
    public CommandSnippet {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        name = Objects.requireNonNullElse(name, "").trim();
        command = Objects.requireNonNullElse(command, "").trim();
        category = (category == null || category.isBlank()) ? "常用命令" : category.trim();
    }

    public CommandSnippet(String name, String command, String category) {
        this(UUID.randomUUID().toString(), name, command, category);
    }
}
