package com.yanshuwang.remoteworkbench.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service for loading, saving, and managing terminal command snippets.
 */
public final class CommandSnippetService {
    private static final String SNIPPETS_FILE_NAME = "snippets.json";
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private CommandSnippetService() {
    }

    public static Path getSnippetsFile() {
        return ConfigStorageService.getAppDataDirectory().resolve(SNIPPETS_FILE_NAME);
    }

    public static synchronized List<CommandSnippet> loadSnippets() {
        Path file = getSnippetsFile();
        if (!Files.exists(file)) {
            List<CommandSnippet> defaults = getDefaultSnippets();
            saveSnippets(defaults);
            return defaults;
        }

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            List<CommandSnippet> list = parseSnippetsJson(json);
            if (list.isEmpty()) {
                list = getDefaultSnippets();
                saveSnippets(list);
            }
            return list;
        } catch (Exception e) {
            System.err.println("[CommandSnippetService] 加载常用命令失败: " + e.getMessage());
            return getDefaultSnippets();
        }
    }

    public static synchronized void saveSnippets(List<CommandSnippet> snippets) {
        Path file = getSnippetsFile();
        String json = serializeSnippetsJson(snippets);
        try {
            Path parent = file.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, json, StandardCharsets.UTF_8);
            notifyListeners();
        } catch (IOException e) {
            System.err.println("[CommandSnippetService] 保存常用命令失败: " + e.getMessage());
        }
    }

    public static void addListener(Runnable listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private static void notifyListeners() {
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (Exception ignored) {
            }
        }
    }

    public static List<CommandSnippet> getDefaultSnippets() {
        List<CommandSnippet> list = new ArrayList<>();
        list.add(new CommandSnippet("系统版本与内核", "uname -a && cat /etc/os-release", "系统信息"));
        list.add(new CommandSnippet("CPU 硬件架构", "lscpu || cat /proc/cpuinfo | head -20", "系统信息"));
        list.add(new CommandSnippet("内存占用情况 (free -h)", "free -h", "系统状态"));
        list.add(new CommandSnippet("磁盘空间使用率 (df -h)", "df -h", "系统状态"));
        list.add(new CommandSnippet("系统负载与开机时间 (uptime)", "uptime", "系统状态"));
        list.add(new CommandSnippet("高内存消耗进程 TOP10", "ps aux --sort=-%mem | head -n 10", "进程与日志"));
        list.add(new CommandSnippet("高 CPU 消耗进程 TOP10", "ps aux --sort=-%cpu | head -n 10", "进程与日志"));
        list.add(new CommandSnippet("最新 50 条系统日志", "journalctl -n 50 --no-pager", "进程与日志"));
        list.add(new CommandSnippet("实时追踪服务日志 (tail -f)", "tail -f /var/log/${service:nginx}.log", "进程与日志"));
        list.add(new CommandSnippet("网卡接口与 IP 地址", "ip addr || ifconfig", "网络与端口"));
        list.add(new CommandSnippet("监听端口与服务 (ss -tulnp)", "ss -tulnp || netstat -tulnp", "网络与端口"));
        list.add(new CommandSnippet("查看指定端口占用 (lsof)", "lsof -i :${port:8080} || ss -tulnp | grep :${port:8080}", "网络与端口"));
        list.add(new CommandSnippet("查看已建立的 TCP 连接", "ss -tan state established", "网络与端口"));
        list.add(new CommandSnippet("运行中的 Docker 容器", "docker ps", "Docker"));
        list.add(new CommandSnippet("全部 Docker 容器 (含已停止)", "docker ps -a", "Docker"));
        list.add(new CommandSnippet("查看容器实时日志 (docker)", "docker logs -f --tail 100 ${container_id}", "Docker"));
        list.add(new CommandSnippet("Docker 资源实时消耗统计", "docker stats --no-stream", "Docker"));
        return list;
    }

    private static String serializeSnippetsJson(List<CommandSnippet> snippets) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < snippets.size(); i++) {
            CommandSnippet s = snippets.get(i);
            sb.append("  {\n");
            sb.append("    \"id\": \"").append(escapeJson(s.id())).append("\",\n");
            sb.append("    \"name\": \"").append(escapeJson(s.name())).append("\",\n");
            sb.append("    \"command\": \"").append(escapeJson(s.command())).append("\",\n");
            sb.append("    \"category\": \"").append(escapeJson(s.category())).append("\"\n");
            sb.append("  }");
            if (i < snippets.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("]\n");
        return sb.toString();
    }

    private static List<CommandSnippet> parseSnippetsJson(String json) {
        List<CommandSnippet> list = new ArrayList<>();
        List<Map<String, String>> objects = parseJsonObjects(json);
        for (Map<String, String> obj : objects) {
            String id = obj.get("id");
            String name = obj.get("name");
            String command = obj.get("command");
            String category = obj.get("category");
            if (name != null && !name.isBlank() && command != null && !command.isBlank()) {
                list.add(new CommandSnippet(id, name, command, category));
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
                    while (i < len && json.charAt(i) != '"' && json.charAt(i) != '}') {
                        i++;
                    }
                    if (i >= len || json.charAt(i) == '}') {
                        break;
                    }
                    i++;
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
                    if (i < len) i++;

                    while (i < len && json.charAt(i) != ':') {
                        i++;
                    }
                    if (i < len) i++;

                    while (i < len && Character.isWhitespace(json.charAt(i))) {
                        i++;
                    }
                    if (i >= len) break;

                    if (json.charAt(i) == '"') {
                        i++;
                        StringBuilder val = new StringBuilder();
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
                        if (i < len) i++;
                        obj.put(key.toString(), val.toString());
                    } else {
                        StringBuilder val = new StringBuilder();
                        while (i < len && json.charAt(i) != ',' && json.charAt(i) != '}') {
                            val.append(json.charAt(i));
                            i++;
                        }
                        obj.put(key.toString(), val.toString().trim());
                    }

                    while (i < len && (json.charAt(i) == ',' || Character.isWhitespace(json.charAt(i)))) {
                        i++;
                    }
                }
                if (i < len && json.charAt(i) == '}') {
                    i++;
                }
                result.add(obj);
            } else {
                i++;
            }
        }
        return result;
    }
}
