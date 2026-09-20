package com.yanshuwang.remoteworkbench.ui.theme;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.stage.Window;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;
import java.util.function.Consumer;

public final class ThemeManager {
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(ThemeManager.class);
    private static AppAppearanceMode appearanceMode = resolveAppearanceMode();
    private static final List<Consumer<Boolean>> THEME_LISTENERS = new CopyOnWriteArrayList<>();
    private static boolean initialized = false;

    private ThemeManager() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        SystemThemeDetector.addListener(isDark -> {
            if (appearanceMode == AppAppearanceMode.SYSTEM) {
                Platform.runLater(ThemeManager::updateAllWindowsTheme);
            }
        });

        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    for (Window w : change.getAddedSubList()) {
                        attachThemeToWindow(w);
                    }
                }
            }
        });

        for (Window w : Window.getWindows()) {
            attachThemeToWindow(w);
        }
    }

    private static void attachThemeToWindow(Window window) {
        if (window == null) {
            return;
        }
        if (window.getScene() != null) {
            applyThemeToScene(window.getScene());
        }
        window.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                applyThemeToScene(newScene);
            }
        });
    }

    public static void applyThemeToScene(Scene scene) {
        if (scene == null) {
            return;
        }
        String css = ThemeManager.class.getResource("/com/yanshuwang/remoteworkbench/application.css").toExternalForm();
        if (!scene.getStylesheets().contains(css)) {
            scene.getStylesheets().add(css);
        }
        boolean isDark = isDarkMode();
        String themeClass = isDark ? "theme-dark" : "theme-light";
        String removeClass = isDark ? "theme-light" : "theme-dark";

        if (scene.getRoot() != null) {
            scene.getRoot().getStyleClass().remove(removeClass);
            if (!scene.getRoot().getStyleClass().contains(themeClass)) {
                scene.getRoot().getStyleClass().add(themeClass);
            }
        }
        scene.rootProperty().addListener((obs, oldRoot, newRoot) -> {
            if (newRoot != null) {
                newRoot.getStyleClass().remove(removeClass);
                if (!newRoot.getStyleClass().contains(themeClass)) {
                    newRoot.getStyleClass().add(themeClass);
                }
            }
        });
    }

    public static AppAppearanceMode getAppearanceMode() {
        return appearanceMode;
    }

    public static void setAppearanceMode(AppAppearanceMode mode) {
        appearanceMode = (mode != null) ? mode : AppAppearanceMode.SYSTEM;
        PREFERENCES.put("app.appearance.mode", appearanceMode.name());
        updateAllWindowsTheme();
    }

    public static AppAppearanceMode resolveAppearanceMode() {
        String name = PREFERENCES.get("app.appearance.mode", AppAppearanceMode.SYSTEM.name());
        return AppAppearanceMode.fromString(name);
    }

    public static boolean isDarkMode() {
        if (appearanceMode == AppAppearanceMode.SYSTEM) {
            return SystemThemeDetector.isDarkMode();
        }
        return appearanceMode == AppAppearanceMode.DARK;
    }

    public static void addThemeListener(Consumer<Boolean> listener) {
        if (listener != null && !THEME_LISTENERS.contains(listener)) {
            THEME_LISTENERS.add(listener);
        }
    }

    public static void removeThemeListener(Consumer<Boolean> listener) {
        THEME_LISTENERS.remove(listener);
    }

    public static void updateAllWindowsTheme() {
        boolean isDark = isDarkMode();
        String themeClass = isDark ? "theme-dark" : "theme-light";
        String removeClass = isDark ? "theme-light" : "theme-dark";

        for (Window w : Window.getWindows()) {
            if (w.getScene() != null) {
                Scene s = w.getScene();
                String css = ThemeManager.class.getResource("/com/yanshuwang/remoteworkbench/application.css").toExternalForm();
                if (!s.getStylesheets().contains(css)) {
                    s.getStylesheets().add(css);
                }
                if (s.getRoot() != null) {
                    s.getRoot().getStyleClass().remove(removeClass);
                    if (!s.getRoot().getStyleClass().contains(themeClass)) {
                        s.getRoot().getStyleClass().add(themeClass);
                    }
                }
            }
        }

        for (Consumer<Boolean> listener : THEME_LISTENERS) {
            try {
                listener.accept(isDark);
            } catch (Exception ignored) {
            }
        }
    }

    public static void applyDialogTheme(Dialog<?> dialog, Window owner) {
        if (dialog == null || dialog.getDialogPane() == null) {
            return;
        }
        if (owner != null && dialog.getOwner() == null) {
            dialog.initOwner(owner);
        }
        String css = ThemeManager.class.getResource("/com/yanshuwang/remoteworkbench/application.css").toExternalForm();
        if (!dialog.getDialogPane().getStylesheets().contains(css)) {
            dialog.getDialogPane().getStylesheets().add(css);
        }
        boolean isDark = isDarkMode();
        String themeClass = isDark ? "theme-dark" : "theme-light";
        String removeClass = isDark ? "theme-light" : "theme-dark";
        dialog.getDialogPane().getStyleClass().remove(removeClass);
        if (!dialog.getDialogPane().getStyleClass().contains(themeClass)) {
            dialog.getDialogPane().getStyleClass().add(themeClass);
        }
    }
}
