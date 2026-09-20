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

    // Built-in Themes
    public static final TerminalTheme DEFAULT_DARK = new TerminalTheme(
            "default_dark",
            "默认暗夜黑 (Default Dark)",
            Color.web("#17181b"),
            Color.web("#e8e9ed"),
            Color.web("#4c82e8"),
            Color.web("#264f78"),
            new Color[]{
                    Color.web("#1f2024"), Color.web("#f05d5e"), Color.web("#62c073"), Color.web("#e5c07b"),
                    Color.web("#4c82e8"), Color.web("#c678dd"), Color.web("#56b6c2"), Color.web("#abb2bf"),
                    Color.web("#5c6370"), Color.web("#ff6c6b"), Color.web("#98c379"), Color.web("#e5c07b"),
                    Color.web("#61afef"), Color.web("#c678dd"), Color.web("#56b6c2"), Color.web("#ffffff")
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

    public static final List<TerminalTheme> BUILTIN_THEMES = List.of(
            DEFAULT_DARK,
            DRACULA,
            ONE_DARK_PRO,
            NORD,
            MONOKAI_PRO,
            SOLARIZED_DARK
    );

    public static TerminalTheme getTheme(String id) {
        if (id == null || id.isBlank()) {
            return DEFAULT_DARK;
        }
        for (TerminalTheme theme : BUILTIN_THEMES) {
            if (theme.id().equalsIgnoreCase(id) || theme.displayName().equalsIgnoreCase(id)) {
                return theme;
            }
        }
        return DEFAULT_DARK;
    }

    public static List<TerminalTheme> getAllThemes() {
        return BUILTIN_THEMES;
    }
}
