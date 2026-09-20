package com.yanshuwang.remoteworkbench.ui.terminal;

public enum CursorStyle {
    BLOCK("方块 (Block)"),
    UNDERLINE("下划线 (Underline)"),
    BAR("竖线 (Bar)");

    private final String displayName;

    CursorStyle(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static CursorStyle fromString(String name) {
        if (name == null || name.isBlank()) {
            return BLOCK;
        }
        for (CursorStyle s : values()) {
            if (s.name().equalsIgnoreCase(name) || s.displayName.equalsIgnoreCase(name)) {
                return s;
            }
        }
        return BLOCK;
    }
}
