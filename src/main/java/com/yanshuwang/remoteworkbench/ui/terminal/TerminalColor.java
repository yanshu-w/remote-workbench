package com.yanshuwang.remoteworkbench.ui.terminal;

import javafx.scene.paint.Color;

public final class TerminalColor {
    public static final Color DEFAULT_BACKGROUND = TerminalTheme.MACOS_DARK.background();
    public static final Color DEFAULT_FOREGROUND = TerminalTheme.MACOS_DARK.foreground();
    public static final Color CURSOR_COLOR = TerminalTheme.MACOS_DARK.cursorColor();

    private TerminalColor() {
    }

    public static Color getAnsiColor(int index) {
        return TerminalTheme.MACOS_DARK.getAnsiColor(index);
    }

    public static Color get256Color(int index) {
        return TerminalTheme.MACOS_DARK.get256Color(index);
    }
}
