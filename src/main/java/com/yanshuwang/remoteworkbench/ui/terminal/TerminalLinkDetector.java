package com.yanshuwang.remoteworkbench.ui.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for detecting clickable URLs and Unix file/directory paths on terminal lines.
 */
public final class TerminalLinkDetector {

    public enum LinkType {
        URL,
        PATH
    }

    public record TerminalLink(
            LinkType type,
            String target,
            int row,
            int startCol,
            int endCol
    ) {}

    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[a-zA-Z0-9\\-._~:/?#\\[\\]@!$&'*+,;=%]+"
    );

    private static final Pattern PATH_PATTERN = Pattern.compile(
            "(?:/|~/)[a-zA-Z0-9_.\\-/]+"
    );

    private static final Pattern LINE_COL_SUFFIX = Pattern.compile("(:\\d+(?::\\d+)?)$");

    private TerminalLinkDetector() {}

    /**
     * Finds a clickable link or file path under the given visual row and column coordinates.
     */
    public static TerminalLink detectLinkAt(TerminalBuffer buffer, int row, int col) {
        if (buffer == null || row < 0 || row >= buffer.getRows()) {
            return null;
        }

        int cols = buffer.getCols();
        if (col < 0 || col >= cols) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        int[] colToChar = new int[cols];

        for (int c = 0; c < cols; c++) {
            TerminalCell cell = buffer.getCell(row, c);
            if (cell == null) {
                colToChar[c] = sb.length();
                sb.append(' ');
            } else if (cell.isContinuation()) {
                colToChar[c] = (c > 0) ? colToChar[c - 1] : 0;
            } else {
                colToChar[c] = sb.length();
                char ch = cell.getCharacter();
                sb.append((ch >= 0x20) ? ch : ' ');
            }
        }

        String line = sb.toString();
        int charIdx = colToChar[col];

        // 1. Check for URL first
        List<IntRange> urlRanges = new ArrayList<>();
        Matcher urlMatcher = URL_PATTERN.matcher(line);
        while (urlMatcher.find()) {
            int start = urlMatcher.start();
            int end = urlMatcher.end();
            while (end > start && isTrailingPunctuation(line.charAt(end - 1))) {
                end--;
            }
            if (end > start) {
                urlRanges.add(new IntRange(start, end));
                if (charIdx >= start && charIdx < end) {
                    String url = line.substring(start, end);
                    int[] colSpan = mapCharRangeToCols(colToChar, start, end, buffer, row);
                    return new TerminalLink(LinkType.URL, url, row, colSpan[0], colSpan[1]);
                }
            }
        }

        // 2. Check for Unix Path (/... or ~/...)
        Matcher pathMatcher = PATH_PATTERN.matcher(line);
        while (pathMatcher.find()) {
            int start = pathMatcher.start();
            int end = pathMatcher.end();

            // Ensure not inside or part of an existing URL
            if (isInsideRanges(start, end, urlRanges)) {
                continue;
            }

            // Must not be preceded by alphanumeric/email characters (e.g. avoid abc/def or user@host:/path)
            if (start > 0) {
                char prev = line.charAt(start - 1);
                if (Character.isLetterOrDigit(prev) || prev == '@') {
                    continue;
                }
            }

            // Trim trailing punctuation (.,;:)'"] etc.)
            while (end > start && isTrailingPunctuation(line.charAt(end - 1))) {
                end--;
            }

            // Strip line:col suffix like /path/file.c:45:10
            String rawPath = line.substring(start, end);
            Matcher lineColMatcher = LINE_COL_SUFFIX.matcher(rawPath);
            if (lineColMatcher.find()) {
                end = start + lineColMatcher.start();
                rawPath = line.substring(start, end);
            }

            if (isValidPath(rawPath)) {
                if (charIdx >= start && charIdx < end) {
                    int[] colSpan = mapCharRangeToCols(colToChar, start, end, buffer, row);
                    return new TerminalLink(LinkType.PATH, rawPath, row, colSpan[0], colSpan[1]);
                }
            }
        }

        return null;
    }

    private static boolean isValidPath(String path) {
        if (path == null || path.length() < 2) {
            return false;
        }
        if (path.equals("/") || path.equals("//") || path.equals("///")) {
            return false;
        }
        // At least one valid character after the slash
        if (path.startsWith("/")) {
            return path.length() > 1 && !path.matches("^/+$");
        }
        if (path.startsWith("~/")) {
            return path.length() > 2;
        }
        return false;
    }

    private static boolean isTrailingPunctuation(char c) {
        return c == '.' || c == ',' || c == ';' || c == ':' || c == ')'
                || c == ']' || c == '}' || c == '>' || c == '"' || c == '\''
                || c == '`';
    }

    private static boolean isInsideRanges(int start, int end, List<IntRange> ranges) {
        for (IntRange r : ranges) {
            if ((start >= r.start && start < r.end) || (end > r.start && end <= r.end)) {
                return true;
            }
        }
        return false;
    }

    private static int[] mapCharRangeToCols(int[] colToChar, int startChar, int endChar, TerminalBuffer buffer, int row) {
        int startCol = -1;
        int endCol = -1;

        for (int c = 0; c < colToChar.length; c++) {
            if (colToChar[c] >= startChar && colToChar[c] < endChar) {
                if (startCol == -1) {
                    startCol = c;
                }
                endCol = c;
            }
        }

        if (startCol == -1) {
            startCol = 0;
        }
        if (endCol == -1) {
            endCol = startCol;
        }

        // If the cell at endCol is wide, extend by 1 column
        TerminalCell lastCell = buffer.getCell(row, endCol);
        if (lastCell != null && lastCell.isWide()) {
            endCol = Math.min(buffer.getCols() - 1, endCol + 1);
        }

        return new int[]{startCol, endCol};
    }

    private record IntRange(int start, int end) {}
}
