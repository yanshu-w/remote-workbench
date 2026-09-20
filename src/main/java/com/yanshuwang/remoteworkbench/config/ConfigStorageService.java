package com.yanshuwang.remoteworkbench.config;

import com.yanshuwang.remoteworkbench.connection.ConnectionProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ConfigStorageService {
    private static final String APP_NAME = "RemoteWorkbench";
    private static final String CONFIG_FILE_NAME = "connections.json";

    private ConfigStorageService() {
    }

    public static Path getAppDataDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String userHome = System.getProperty("user.home", ".");
        Path appDataDir;

        if (os.contains("mac")) {
            // Standard macOS user application data: ~/Library/Application Support/RemoteWorkbench
            appDataDir = Paths.get(userHome, "Library", "Application Support", APP_NAME);
        } else if (os.contains("win")) {
            // Standard Windows app data: %APPDATA%\RemoteWorkbench
            String appDataEnv = System.getenv("APPDATA");
            appDataDir = (appDataEnv != null && !appDataEnv.isBlank())
                    ? Paths.get(appDataEnv, APP_NAME)
                    : Paths.get(userHome, "." + APP_NAME.toLowerCase());
        } else {
            // Linux / Unix XDG config directory: ~/.config/remote-workbench
            String xdgConfig = System.getenv("XDG_CONFIG_HOME");
            appDataDir = (xdgConfig != null && !xdgConfig.isBlank())
                    ? Paths.get(xdgConfig, "remote-workbench")
                    : Paths.get(userHome, ".config", "remote-workbench");
        }

        try {
            if (!Files.exists(appDataDir)) {
                Files.createDirectories(appDataDir);
                applySecureDirectoryPermissions(appDataDir);
            }
        } catch (IOException ignored) {
        }
        return appDataDir;
    }

    public static Path getConfigFile() {
        return getAppDataDirectory().resolve(CONFIG_FILE_NAME);
    }

    public static synchronized List<ConnectionProfile> loadConnections() {
        Path configFile = getConfigFile();
        if (!Files.exists(configFile)) {
            return new ArrayList<>();
        }

        try {
            String json = Files.readString(configFile, StandardCharsets.UTF_8);
            return parseProfilesJson(json);
        } catch (Exception exception) {
            System.err.println("[ConfigStorageService] 加载连接配置失败：" + exception.getMessage());
            return new ArrayList<>();
        }
    }

    public static synchronized void saveConnections(List<ConnectionProfile> profiles) {
        Path configFile = getConfigFile();
        String json = serializeProfilesJson(profiles);
        writeFileAtomically(configFile, json);
    }

    public static void exportConnections(Path targetFile, List<ConnectionProfile> profiles) throws IOException {
        String json = serializeProfilesJson(profiles);
        Files.writeString(targetFile, json, StandardCharsets.UTF_8);
    }

    public static List<ConnectionProfile> importConnections(Path sourceFile) throws IOException {
        String json = Files.readString(sourceFile, StandardCharsets.UTF_8);
        return parseProfilesJson(json);
    }

    private static void writeFileAtomically(Path targetFile, String content) {
        try {
            Path parent = targetFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                applySecureDirectoryPermissions(parent);
            }

            Path tempFile = Files.createTempFile(parent, "connections_", ".tmp");
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            applySecureFilePermissions(tempFile);

            try {
                Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
            applySecureFilePermissions(targetFile);
        } catch (IOException exception) {
            System.err.println("[ConfigStorageService] 保存连接配置失败：" + exception.getMessage());
        }
    }

    private static void applySecureDirectoryPermissions(Path dir) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            try {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            } catch (Exception ignored) {
            }
        }
    }

    private static void applySecureFilePermissions(Path file) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {
            }
        }
    }

    public static String serializeProfilesJson(List<ConnectionProfile> profiles) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        if (profiles != null) {
            for (int i = 0; i < profiles.size(); i++) {
                ConnectionProfile p = profiles.get(i);
                sb.append("  {\n");
                sb.append("    \"id\": \"").append(escapeJson(p.id())).append("\",\n");
                sb.append("    \"name\": \"").append(escapeJson(p.name())).append("\",\n");
                sb.append("    \"host\": \"").append(escapeJson(p.host())).append("\",\n");
                sb.append("    \"port\": ").append(p.port()).append(",\n");
                sb.append("    \"username\": \"").append(escapeJson(p.username())).append("\",\n");
                sb.append("    \"authType\": \"").append(escapeJson(p.authType())).append("\",\n");
                sb.append("    \"password\": \"").append(escapeJson(p.rememberPassword() ? p.password() : "")).append("\",\n");
                sb.append("    \"privateKeyPath\": \"").append(escapeJson(p.privateKeyPath())).append("\",\n");
                sb.append("    \"passphrase\": \"").append(escapeJson(p.rememberPassword() ? p.passphrase() : "")).append("\",\n");
                sb.append("    \"rememberPassword\": ").append(p.rememberPassword()).append(",\n");
                sb.append("    \"initialShell\": \"").append(escapeJson(p.initialShell())).append("\"\n");
                sb.append("  }");
                if (i < profiles.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
        }
        sb.append("]\n");
        return sb.toString();
    }

    public static List<ConnectionProfile> parseProfilesJson(String json) {
        List<ConnectionProfile> list = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return list;
        }

        List<Map<String, String>> rawObjects = parseJsonObjects(json);
        for (Map<String, String> map : rawObjects) {
            try {
                String id = map.getOrDefault("id", "");
                String name = map.getOrDefault("name", "未命名连接");
                String host = map.getOrDefault("host", "");
                int port = 22;
                try {
                    port = Integer.parseInt(map.getOrDefault("port", "22"));
                } catch (NumberFormatException ignored) {
                }
                String username = map.getOrDefault("username", "root");
                String authType = map.getOrDefault("authType", ConnectionProfile.AUTH_PASSWORD);
                String password = map.getOrDefault("password", "");
                String privateKeyPath = map.getOrDefault("privateKeyPath", "");
                String passphrase = map.getOrDefault("passphrase", "");
                boolean rememberPassword = Boolean.parseBoolean(map.getOrDefault("rememberPassword", "false"));
                String initialShell = map.getOrDefault("initialShell", "default");

                if (!host.isBlank() && !username.isBlank()) {
                    list.add(new ConnectionProfile(
                            id,
                            name,
                            host,
                            port,
                            username,
                            authType,
                            password,
                            privateKeyPath,
                            passphrase,
                            rememberPassword,
                            initialShell
                    ));
                }
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        String hex = Integer.toHexString(c);
                        sb.append("\\u0000".substring(0, 6 - hex.length())).append(hex);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static List<Map<String, String>> parseJsonObjects(String json) {
        List<Map<String, String>> result = new ArrayList<>();
        int i = 0;
        int len = json.length();

        while (i < len) {
            char c = json.charAt(i);
            if (c == '{') {
                Map<String, String> obj = new HashMap<>();
                i++;
                while (i < len && json.charAt(i) != '}') {
                    // find key
                    while (i < len && json.charAt(i) != '"' && json.charAt(i) != '}') {
                        i++;
                    }
                    if (i >= len || json.charAt(i) == '}') {
                        break;
                    }
                    i++; // skip opening quote of key
                    StringBuilder key = new StringBuilder();
                    while (i < len && json.charAt(i) != '"') {
                        if (json.charAt(i) == '\\' && i + 1 < len) {
                            i++;
                            key.append(json.charAt(i));
                        } else {
                            key.append(json.charAt(i));
                        }
                        i++;
                    }
                    if (i < len) i++; // skip closing quote of key

                    // find colon
                    while (i < len && json.charAt(i) != ':') {
                        i++;
                    }
                    if (i < len) i++; // skip colon

                    // skip whitespace
                    while (i < len && Character.isWhitespace(json.charAt(i))) {
                        i++;
                    }
                    if (i >= len) break;

                    // parse value
                    StringBuilder val = new StringBuilder();
                    if (json.charAt(i) == '"') {
                        i++; // skip opening quote
                        while (i < len && json.charAt(i) != '"') {
                            if (json.charAt(i) == '\\' && i + 1 < len) {
                                i++;
                                char esc = json.charAt(i);
                                switch (esc) {
                                    case 'n' -> val.append('\n');
                                    case 'r' -> val.append('\r');
                                    case 't' -> val.append('\t');
                                    case 'b' -> val.append('\b');
                                    case 'f' -> val.append('\f');
                                    default -> val.append(esc);
                                }
                            } else {
                                val.append(json.charAt(i));
                            }
                            i++;
                        }
                        if (i < len) i++; // skip closing quote
                    } else {
                        while (i < len && json.charAt(i) != ',' && json.charAt(i) != '}' && !Character.isWhitespace(json.charAt(i))) {
                            val.append(json.charAt(i));
                            i++;
                        }
                    }

                    obj.put(key.toString(), val.toString());

                    // skip to comma or }
                    while (i < len && json.charAt(i) != ',' && json.charAt(i) != '}') {
                        i++;
                    }
                    if (i < len && json.charAt(i) == ',') {
                        i++;
                    }
                }
                result.add(obj);
            }
            i++;
        }
        return result;
    }
}
