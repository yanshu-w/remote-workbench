package com.yanshuwang.remoteworkbench.ui.terminal;

import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;

public final class AnsiParser {
    private enum State {
        NORMAL,
        ESCAPE,
        CHARSET,
        CSI,
        OSC
    }

    private final TerminalBuffer buffer;
    private State state = State.NORMAL;
    private final StringBuilder paramBuffer = new StringBuilder();
    private boolean isPrivateMode = false;
    private java.util.function.Consumer<String> workingDirectoryListener;
    private java.util.function.Consumer<String> titleListener;

    public AnsiParser(TerminalBuffer buffer) {
        this.buffer = buffer;
    }

    public void setWorkingDirectoryListener(java.util.function.Consumer<String> listener) {
        this.workingDirectoryListener = listener;
    }

    public void setTitleListener(java.util.function.Consumer<String> listener) {
        this.titleListener = listener;
    }

    public synchronized void parse(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);

            switch (state) {
                case NORMAL -> {
                    if (c == '\u001B') {
                        state = State.ESCAPE;
                    } else if (c == '\r') {
                        buffer.carriageReturn();
                    } else if (c == '\n') {
                        buffer.newLine();
                    } else if (c == '\b') {
                        buffer.backspace();
                    } else if (c == '\t') {
                        buffer.tab();
                    } else if (c == '\u0007') {
                        // Bell, ignored
                    } else if (c >= 0x20) {
                        buffer.writeChar(c);
                    }
                }
                case ESCAPE -> {
                    if (c == '[') {
                        state = State.CSI;
                        paramBuffer.setLength(0);
                        isPrivateMode = false;
                    } else if (c == ']') {
                        state = State.OSC;
                        paramBuffer.setLength(0);
                    } else if (c == '(' || c == ')' || c == '*' || c == '+') {
                        state = State.CHARSET;
                    } else if (c == '=' || c == '>') {
                        state = State.NORMAL;
                    } else if (c == '7') {
                        buffer.saveCursor();
                        state = State.NORMAL;
                    } else if (c == '8') {
                        buffer.restoreCursor();
                        state = State.NORMAL;
                    } else if (c == 'M') {
                        // Reverse index (scroll down / move up)
                        buffer.moveCursor(-1, 0);
                        state = State.NORMAL;
                    } else {
                        state = State.NORMAL;
                    }
                }
                case CHARSET -> {
                    // Ignore G0/G1 character set specification (e.g. 'B' for US-ASCII, '0' for line drawing)
                    state = State.NORMAL;
                }
                case CSI -> {
                    if (c == '?') {
                        isPrivateMode = true;
                    } else if ((c >= '0' && c <= '9') || c == ';' || c == ':') {
                        paramBuffer.append(c);
                    } else {
                        handleCsi(c);
                        state = State.NORMAL;
                    }
                }
                case OSC -> {
                    if (c == '\u0007' || c == '\u001B') {
                        handleOsc(paramBuffer.toString());
                        state = State.NORMAL;
                    } else if (paramBuffer.length() < 2048) {
                        paramBuffer.append(c);
                    }
                }
            }
        }
    }

    private void handleOsc(String osc) {
        if (osc == null || osc.isEmpty()) {
            return;
        }

        // OSC 7: Current Working Directory notification (\033]7;file://hostname/path\007)
        if (osc.startsWith("7;")) {
            String url = osc.substring(2).trim();
            if (url.startsWith("file://")) {
                int slashIdx = url.indexOf('/', "file://".length());
                String path = slashIdx >= 0 ? url.substring(slashIdx) : url.substring("file://".length());
                try {
                    path = java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                if (workingDirectoryListener != null && !path.isBlank()) {
                    workingDirectoryListener.accept(path);
                }
            }
        } else if (osc.startsWith("0;") || osc.startsWith("2;")) {
            // OSC 0 / 2: Title notification (often contains current dir like "user@host: ~/projects")
            String title = osc.substring(2).trim();
            if (titleListener != null && !title.isBlank()) {
                titleListener.accept(title);
            }
            if (title.contains(":")) {
                String candidate = title.substring(title.indexOf(':') + 1).trim();
                if (candidate.startsWith("/") || candidate.startsWith("~")) {
                    if (workingDirectoryListener != null && !candidate.isBlank()) {
                        workingDirectoryListener.accept(candidate);
                    }
                }
            }
        }
    }

    private void handleCsi(char command) {
        List<Integer> params = parseParams(paramBuffer.toString());

        switch (command) {
            case 'H', 'f' -> { // Cursor Position
                int row = (params.isEmpty() || params.get(0) == 0) ? 1 : params.get(0);
                int col = (params.size() < 2 || params.get(1) == 0) ? 1 : params.get(1);
                buffer.setCursor(row - 1, col - 1);
            }
            case 'A' -> { // Cursor Up
                int count = params.isEmpty() ? 1 : Math.max(1, params.get(0));
                buffer.moveCursor(-count, 0);
            }
            case 'B' -> { // Cursor Down
                int count = params.isEmpty() ? 1 : Math.max(1, params.get(0));
                buffer.moveCursor(count, 0);
            }
            case 'C' -> { // Cursor Forward
                int count = params.isEmpty() ? 1 : Math.max(1, params.get(0));
                buffer.moveCursor(0, count);
            }
            case 'D' -> { // Cursor Back
                int count = params.isEmpty() ? 1 : Math.max(1, params.get(0));
                buffer.moveCursor(0, -count);
            }
            case 'G' -> { // Cursor Horizontal Absolute
                int col = params.isEmpty() ? 1 : Math.max(1, params.get(0));
                buffer.setCursorCol(col - 1);
            }
            case 'd' -> { // Line Position Absolute
                int row = params.isEmpty() ? 1 : Math.max(1, params.get(0));
                buffer.setCursorRow(row - 1);
            }
            case 'J' -> { // Erase in Display
                int mode = params.isEmpty() ? 0 : params.get(0);
                buffer.clearScreen(mode);
            }
            case 'K' -> { // Erase in Line
                int mode = params.isEmpty() ? 0 : params.get(0);
                buffer.clearLine(mode);
            }
            case 'm' -> handleSgr(params); // Select Graphic Rendition
            case 's' -> buffer.saveCursor();
            case 'u' -> buffer.restoreCursor();
            case 'r' -> { // DECSTBM (Set Top and Bottom Margins)
                if (params.isEmpty()) {
                    buffer.resetScrollRegion();
                } else {
                    int top = (params.get(0) <= 0) ? 1 : params.get(0);
                    int bottom = (params.size() < 2 || params.get(1) <= 0) ? buffer.getRows() : params.get(1);
                    buffer.setScrollRegion(top - 1, bottom - 1);
                }
                buffer.setCursor(0, 0);
            }
            case 'E' -> { // Cursor Next Line
                int count = params.isEmpty() ? 1 : Math.max(1, params.get(0));
                buffer.setCursor(buffer.getCursorRow() + count, 0);
            }
            case 'F' -> { // Cursor Previous Line
                int count = params.isEmpty() ? 1 : Math.max(1, params.get(0));
                buffer.setCursor(buffer.getCursorRow() - count, 0);
            }
            case 'X' -> { // Erase Character
                int count = params.isEmpty() ? 1 : Math.max(1, params.get(0));
                buffer.eraseChars(count);
            }
            case 'h' -> { // Set Mode
                if (isPrivateMode) {
                    for (int p : params) {
                        if (p == 1049 || p == 47) {
                            buffer.setAlternateBuffer(true);
                        } else if (p == 25) {
                            buffer.setCursorVisible(true);
                        }
                    }
                }
            }
            case 'l' -> { // Reset Mode
                if (isPrivateMode) {
                    for (int p : params) {
                        if (p == 1049 || p == 47) {
                            buffer.setAlternateBuffer(false);
                        } else if (p == 25) {
                            buffer.setCursorVisible(false);
                        }
                    }
                }
            }
            default -> {
                // Ignore unknown CSI commands safely
            }
        }
    }

    private void handleSgr(List<Integer> params) {
        if (params.isEmpty()) {
            buffer.resetSgr();
            return;
        }

        for (int i = 0; i < params.size(); i++) {
            int p = params.get(i);
            if (p == 0) {
                buffer.resetSgr();
            } else if (p == 1) {
                buffer.setBold(true);
            } else if (p == 4) {
                buffer.setUnderline(true);
            } else if (p == 7) {
                buffer.setInverse(true);
            } else if (p == 22) {
                buffer.setBold(false);
            } else if (p == 24) {
                buffer.setUnderline(false);
            } else if (p == 27) {
                buffer.setInverse(false);
            } else if (p >= 30 && p <= 37) {
                buffer.setCurrentFg(TerminalColor.getAnsiColor(p - 30));
            } else if (p == 39) {
                buffer.setCurrentFg(TerminalColor.DEFAULT_FOREGROUND);
            } else if (p >= 40 && p <= 47) {
                buffer.setCurrentBg(TerminalColor.getAnsiColor(p - 40));
            } else if (p == 49) {
                buffer.setCurrentBg(TerminalColor.DEFAULT_BACKGROUND);
            } else if (p >= 90 && p <= 97) {
                buffer.setCurrentFg(TerminalColor.getAnsiColor(p - 90 + 8));
            } else if (p >= 100 && p <= 107) {
                buffer.setCurrentBg(TerminalColor.getAnsiColor(p - 100 + 8));
            } else if (p == 38 && i + 2 < params.size() && params.get(i + 1) == 5) {
                // 256 foreground color: 38;5;index
                int colorIndex = params.get(i + 2);
                buffer.setCurrentFg(TerminalColor.get256Color(colorIndex));
                i += 2;
            } else if (p == 38 && i + 4 < params.size() && params.get(i + 1) == 2) {
                // 24-bit TrueColor foreground: 38;2;r;g;b
                int r = clampColor(params.get(i + 2));
                int g = clampColor(params.get(i + 3));
                int b = clampColor(params.get(i + 4));
                buffer.setCurrentFg(Color.rgb(r, g, b));
                i += 4;
            } else if (p == 48 && i + 2 < params.size() && params.get(i + 1) == 5) {
                // 256 background color: 48;5;index
                int colorIndex = params.get(i + 2);
                buffer.setCurrentBg(TerminalColor.get256Color(colorIndex));
                i += 2;
            } else if (p == 48 && i + 4 < params.size() && params.get(i + 1) == 2) {
                // 24-bit TrueColor background: 48;2;r;g;b
                int r = clampColor(params.get(i + 2));
                int g = clampColor(params.get(i + 3));
                int b = clampColor(params.get(i + 4));
                buffer.setCurrentBg(Color.rgb(r, g, b));
                i += 4;
            }
        }
    }

    private static int clampColor(int val) {
        return Math.max(0, Math.min(255, val));
    }

    private static List<Integer> parseParams(String paramStr) {
        List<Integer> list = new ArrayList<>();
        if (paramStr.isBlank()) {
            return list;
        }

        // Support both semicolon and colon delimiters (e.g. 38:2::r:g:b or 38;2;r;g;b)
        String[] parts = paramStr.replace(':', ';').split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                try {
                    list.add(Integer.parseInt(trimmed));
                } catch (NumberFormatException ignored) {
                    list.add(0);
                }
            } else {
                list.add(0);
            }
        }
        return list;
    }
}
