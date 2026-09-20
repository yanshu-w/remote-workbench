package com.yanshuwang.remoteworkbench.ui.theme;

import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class SystemThemeDetector {
    private static final String OS = System.getProperty("os.name", "").toLowerCase();
    private static final boolean IS_MAC = OS.contains("mac");
    private static final boolean IS_WINDOWS = OS.contains("win");

    private static volatile boolean currentDarkMode = detectSystemDarkMode();
    private static final List<Consumer<Boolean>> LISTENERS = new CopyOnWriteArrayList<>();
    private static ScheduledExecutorService scheduler;

    private SystemThemeDetector() {
    }

    public static synchronized void startListening() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "SystemThemeDetector-Thread");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(SystemThemeDetector::pollSystemTheme, 2, 2, TimeUnit.SECONDS);
        }
    }

    public static synchronized void stopListening() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public static void addListener(Consumer<Boolean> listener) {
        if (listener != null) {
            LISTENERS.add(listener);
            startListening();
        }
    }

    public static void removeListener(Consumer<Boolean> listener) {
        LISTENERS.remove(listener);
        if (LISTENERS.isEmpty()) {
            stopListening();
        }
    }

    public static boolean isDarkMode() {
        return currentDarkMode;
    }

    private static void pollSystemTheme() {
        try {
            boolean isDark = detectSystemDarkMode();
            if (isDark != currentDarkMode) {
                currentDarkMode = isDark;
                Platform.runLater(() -> {
                    for (Consumer<Boolean> listener : LISTENERS) {
                        try {
                            listener.accept(isDark);
                        } catch (Exception ignored) {
                        }
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    public static boolean detectSystemDarkMode() {
        if (IS_MAC) {
            return detectMacDarkMode();
        } else if (IS_WINDOWS) {
            return detectWindowsDarkMode();
        }
        return true; // Default fallback to dark
    }

    private static boolean detectMacDarkMode() {
        try {
            Process process = new ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                boolean finished = process.waitFor(1, TimeUnit.SECONDS);
                if (finished && process.exitValue() == 0 && line != null && line.trim().equalsIgnoreCase("Dark")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean detectWindowsDarkMode() {
        try {
            Process process = new ProcessBuilder(
                    "reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme"
            ).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("AppsUseLightTheme") && line.contains("0x0")) {
                        return true; // 0 means Dark mode
                    }
                }
            }
            process.waitFor(1, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        return false;
    }
}
