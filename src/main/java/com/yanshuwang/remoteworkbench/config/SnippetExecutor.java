package com.yanshuwang.remoteworkbench.config;

import com.yanshuwang.remoteworkbench.ui.SnippetVariableDialog;
import com.yanshuwang.remoteworkbench.ui.theme.ThemeManager;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for parsing snippet variables and executing snippets with interactive parameter prompts.
 */
public final class SnippetExecutor {

    public static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([a-zA-Z0-9_.-]+(?::[^}]*)?)\\}");

    public record VariableDefinition(String placeholder, String name, String defaultValue) {}

    private SnippetExecutor() {}

    /**
     * Checks if the command string contains variable placeholders like ${var} or ${var:default}.
     */
    public static boolean hasVariables(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        return VARIABLE_PATTERN.matcher(command).find();
    }

    /**
     * Extracts unique variable definitions from the command string in appearance order.
     */
    public static List<VariableDefinition> extractVariables(String command) {
        List<VariableDefinition> list = new ArrayList<>();
        if (command == null || command.isBlank()) {
            return list;
        }

        Map<String, VariableDefinition> uniqueVars = new LinkedHashMap<>();
        Matcher matcher = VARIABLE_PATTERN.matcher(command);
        while (matcher.find()) {
            String placeholder = matcher.group(0); // e.g. ${service:nginx}
            String inner = matcher.group(1);       // e.g. service:nginx

            String name;
            String defaultValue = "";
            int colonIdx = inner.indexOf(':');
            if (colonIdx >= 0) {
                name = inner.substring(0, colonIdx).trim();
                defaultValue = inner.substring(colonIdx + 1).trim();
            } else {
                name = inner.trim();
            }

            if (!uniqueVars.containsKey(name)) {
                uniqueVars.put(name, new VariableDefinition(placeholder, name, defaultValue));
            }
        }

        return new ArrayList<>(uniqueVars.values());
    }

    /**
     * Substitutes variable values into the command template.
     */
    public static String substituteVariables(String template, Map<String, String> values) {
        if (template == null) {
            return "";
        }
        String result = template;
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        while (matcher.find()) {
            String placeholder = matcher.group(0);
            String inner = matcher.group(1);
            String name = inner.contains(":") ? inner.substring(0, inner.indexOf(':')).trim() : inner.trim();

            String val = values != null ? values.get(name) : null;
            if (val == null) {
                val = inner.contains(":") ? inner.substring(inner.indexOf(':') + 1).trim() : "";
            }
            result = result.replace(placeholder, val);
        }
        return result;
    }

    /**
     * Executes a snippet. If variables exist, prompts the user via dialog; otherwise sends directly.
     */
    public static void executeSnippet(Window owner, CommandSnippet snippet, Consumer<String> terminalSender) {
        if (snippet == null || terminalSender == null) {
            return;
        }

        String command = snippet.command();
        if (!hasVariables(command)) {
            terminalSender.accept(command + "\r");
            return;
        }

        SnippetVariableDialog dialog = new SnippetVariableDialog(snippet);
        ThemeManager.applyDialogTheme(dialog, owner);
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(cmd -> {
            if (!cmd.isBlank()) {
                terminalSender.accept(cmd + "\r");
            }
        });
    }
}
