package com.yanshuwang.remoteworkbench.ui.theme;

public enum AppAppearanceMode {
    SYSTEM("跟随系统 (Auto)"),
    DARK("默认黑 (macOS 15)"),
    LIGHT("默认白 (macOS 15)");

    private final String displayName;

    AppAppearanceMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static AppAppearanceMode fromString(String name) {
        if (name == null || name.isBlank()) {
            return SYSTEM;
        }
        for (AppAppearanceMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name) || mode.displayName.equalsIgnoreCase(name)) {
                return mode;
            }
        }
        return SYSTEM;
    }
}
