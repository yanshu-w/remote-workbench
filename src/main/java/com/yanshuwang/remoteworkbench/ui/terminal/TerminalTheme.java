package com.yanshuwang.remoteworkbench.ui.terminal;

import javafx.scene.paint.Color;

import java.util.List;

public record TerminalTheme(
        String id,
        String displayName,
        Color background,
        Color foreground,
        Color cursorColor,
        Color selectionColor,
        Color[] ansiColors
) {
    public Color getAnsiColor(int index) {
        if (index >= 0 && index < ansiColors.length) {
            return ansiColors[index];
        }
        return foreground;
    }

    public Color get256Color(int index) {
        if (index >= 0 && index < 16) {
            return getAnsiColor(index);
        }
        if (index >= 16 && index <= 231) {
            int colorIndex = index - 16;
            int r = (colorIndex / 36) % 6;
            int g = (colorIndex / 6) % 6;
            int b = colorIndex % 6;
            return Color.rgb(
                    r == 0 ? 0 : 55 + r * 40,
                    g == 0 ? 0 : 55 + g * 40,
                    b == 0 ? 0 : 55 + b * 40
            );
        }
        if (index >= 232 && index <= 255) {
            int gray = 8 + (index - 232) * 10;
            return Color.rgb(gray, gray, gray);
        }
        return foreground;
    }

    public static final TerminalTheme AUTO = new TerminalTheme(
            "auto",
            "跟随界面外观 (Auto)",
            Color.web("#1e1e1e"),
            Color.web("#f5f5f7"),
            Color.web("#0a84ff"),
            Color.web("#264f78"),
            new Color[]{
                    Color.web("#1e1e1e"), Color.web("#ff453a"), Color.web("#30d158"), Color.web("#ffd60a"),
                    Color.web("#0a84ff"), Color.web("#bf5af2"), Color.web("#64d2ff"), Color.web("#f5f5f7"),
                    Color.web("#636366"), Color.web("#ff6961"), Color.web("#4cd964"), Color.web("#ffd426"),
                    Color.web("#409cff"), Color.web("#da8fff"), Color.web("#70d7ff"), Color.web("#ffffff")
            }
    );

    // Built-in Themes
    public static final TerminalTheme MACOS_DARK = new TerminalTheme(
            "macos_dark",
            "默认黑 (macOS 15)",
            Color.web("#1e1e1e"),
            Color.web("#f5f5f7"),
            Color.web("#0a84ff"),
            Color.web("#264f78"),
            new Color[]{
                    Color.web("#1e1e1e"), Color.web("#ff453a"), Color.web("#30d158"), Color.web("#ffd60a"),
                    Color.web("#0a84ff"), Color.web("#bf5af2"), Color.web("#64d2ff"), Color.web("#f5f5f7"),
                    Color.web("#636366"), Color.web("#ff6961"), Color.web("#4cd964"), Color.web("#ffd426"),
                    Color.web("#409cff"), Color.web("#da8fff"), Color.web("#70d7ff"), Color.web("#ffffff")
            }
    );

    public static final TerminalTheme MACOS_LIGHT = new TerminalTheme(
            "macos_light",
            "默认白 (macOS 15)",
            Color.web("#ffffff"),
            Color.web("#1d1d1f"),
            Color.web("#0071e3"),
            Color.web("#b3d7ff"),
            new Color[]{
                    Color.web("#000000"), Color.web("#d70000"), Color.web("#008700"), Color.web("#b76e00"),
                    Color.web("#0071e3"), Color.web("#af00db"), Color.web("#00838f"), Color.web("#707070"),
                    Color.web("#8e8e93"), Color.web("#ff3b30"), Color.web("#34c759"), Color.web("#ff9500"),
                    Color.web("#0071e3"), Color.web("#af52de"), Color.web("#5ac8fa"), Color.web("#ffffff")
            }
    );

    public static final TerminalTheme DRACULA = new TerminalTheme(
            "dracula",
            "Dracula (吸血鬼)",
            Color.web("#282a36"),
            Color.web("#f8f8f2"),
            Color.web("#f8f8f2"),
            Color.web("#44475a"),
            new Color[]{
                    Color.web("#21222c"), Color.web("#ff5555"), Color.web("#50fa7b"), Color.web("#f1fa8c"),
                    Color.web("#bd93f9"), Color.web("#ff79c6"), Color.web("#8be9fd"), Color.web("#f8f8f2"),
                    Color.web("#6272a4"), Color.web("#ff6e6e"), Color.web("#69ff94"), Color.web("#ffffa5"),
                    Color.web("#d6acff"), Color.web("#ff92df"), Color.web("#a4ffff"), Color.web("#ffffff")
            }
    );

    public static final TerminalTheme ONE_DARK_PRO = new TerminalTheme(
            "one_dark_pro",
            "One Dark Pro (Atom/VSCode)",
            Color.web("#282c34"),
            Color.web("#abb2bf"),
            Color.web("#528bff"),
            Color.web("#3e4451"),
            new Color[]{
                    Color.web("#282c34"), Color.web("#e06c75"), Color.web("#98c379"), Color.web("#e5c07b"),
                    Color.web("#61afef"), Color.web("#c678dd"), Color.web("#56b6c2"), Color.web("#abb2bf"),
                    Color.web("#5c6370"), Color.web("#be5046"), Color.web("#98c379"), Color.web("#d19a66"),
                    Color.web("#61afef"), Color.web("#c678dd"), Color.web("#56b6c2"), Color.web("#ffffff")
            }
    );

    public static final TerminalTheme NORD = new TerminalTheme(
            "nord",
            "Nord (北极蓝)",
            Color.web("#2e3440"),
            Color.web("#d8dee9"),
            Color.web("#88c0d0"),
            Color.web("#434c5e"),
            new Color[]{
                    Color.web("#3b4252"), Color.web("#bf616a"), Color.web("#a3be8c"), Color.web("#ebcb8b"),
                    Color.web("#81a1c1"), Color.web("#b48ead"), Color.web("#88c0d0"), Color.web("#e5e9f0"),
                    Color.web("#4c566a"), Color.web("#bf616a"), Color.web("#a3be8c"), Color.web("#ebcb8b"),
                    Color.web("#81a1c1"), Color.web("#b48ead"), Color.web("#8fbcbb"), Color.web("#eceff4")
            }
    );

    public static final TerminalTheme MONOKAI_PRO = new TerminalTheme(
            "monokai_pro",
            "Monokai Pro",
            Color.web("#2d2a2e"),
            Color.web("#fcfcfa"),
            Color.web("#ffd866"),
            Color.web("#403e41"),
            new Color[]{
                    Color.web("#403e41"), Color.web("#ff6188"), Color.web("#a9dc76"), Color.web("#ffd866"),
                    Color.web("#78dce8"), Color.web("#ab9df2"), Color.web("#78dce8"), Color.web("#fcfcfa"),
                    Color.web("#727072"), Color.web("#ff6188"), Color.web("#a9dc76"), Color.web("#ffd866"),
                    Color.web("#78dce8"), Color.web("#ab9df2"), Color.web("#78dce8"), Color.web("#ffffff")
            }
    );

    public static final TerminalTheme SOLARIZED_DARK = new TerminalTheme(
            "solarized_dark",
            "Solarized Dark",
            Color.web("#002b36"),
            Color.web("#839496"),
            Color.web("#2aa198"),
            Color.web("#073642"),
            new Color[]{
                    Color.web("#073642"), Color.web("#dc322f"), Color.web("#859900"), Color.web("#b58900"),
                    Color.web("#268bd2"), Color.web("#d33682"), Color.web("#2aa198"), Color.web("#eee8d5"),
                    Color.web("#002b36"), Color.web("#cb4b16"), Color.web("#586e75"), Color.web("#657b83"),
                    Color.web("#839496"), Color.web("#6c71c4"), Color.web("#93a1a1"), Color.web("#fdf6e3")
            }
    );

    public TerminalTheme resolveEffectiveTheme(boolean isDark) {
        if (!isDark) {
            // 在浅色外观下（默认白 / 跟随系统浅色），终端统一呈现 macOS 15 默认白风格
            return MACOS_LIGHT;
        } else {
            // 在暗色外观下（默认黑 / 跟随系统深色），如果当前为自适应、默认白或已废弃的默认暗夜黑，自动转换为默认黑
            if (this == AUTO || this == MACOS_LIGHT || "auto".equalsIgnoreCase(id) || "macos_light".equalsIgnoreCase(id) || "default_dark".equalsIgnoreCase(id)) {
                return MACOS_DARK;
            }
            return this;
        }
    }

    public static final List<TerminalTheme> BUILTIN_THEMES = List.of(
            AUTO,
            MACOS_LIGHT,
            MACOS_DARK,
            DRACULA,
            ONE_DARK_PRO,
            NORD,
            MONOKAI_PRO,
            SOLARIZED_DARK
    );

    public static TerminalTheme getTheme(String id) {
        if (id == null || id.isBlank() || id.equalsIgnoreCase("auto") || id.equalsIgnoreCase("default_dark")) {
            return AUTO;
        }
        for (TerminalTheme theme : BUILTIN_THEMES) {
            if (theme.id().equalsIgnoreCase(id) || theme.displayName().equalsIgnoreCase(id)) {
                return theme;
            }
        }
        return AUTO;
    }

    public static List<TerminalTheme> getAllThemes() {
        return BUILTIN_THEMES;
    }
}
