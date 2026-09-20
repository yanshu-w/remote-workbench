package com.yanshuwang.remoteworkbench.ui.terminal;

import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;

public final class TerminalBuffer {
    private static final int DEFAULT_MAX_SCROLLBACK = 3000;
    private final int maxScrollback = DEFAULT_MAX_SCROLLBACK;
    private final List<TerminalCell[]> scrollback = new ArrayList<>();
    private int scrollOffset = 0; // 0 = at bottom, >0 = scrolled up into history

    private int cols;
    private int rows;

    private TerminalCell[][] primaryGrid;
    private TerminalCell[][] alternateGrid;
    private boolean usingAlternate;

    private int cursorCol;
    private int cursorRow;
    private int savedCol;
    private int savedRow;
    private boolean cursorVisible = true;

    private Color defaultFg = TerminalColor.DEFAULT_FOREGROUND;
    private Color defaultBg = TerminalColor.DEFAULT_BACKGROUND;
    private Color currentFg = TerminalColor.DEFAULT_FOREGROUND;
    private Color currentBg = TerminalColor.DEFAULT_BACKGROUND;
    private boolean bold;
    private boolean inverse;
    private boolean underline;

    public TerminalBuffer(int cols, int rows) {
        this.cols = Math.max(20, cols);
        this.rows = Math.max(5, rows);
        this.primaryGrid = createGrid(this.cols, this.rows);
        this.alternateGrid = createGrid(this.cols, this.rows);
        this.usingAlternate = false;
    }

    private static TerminalCell[][] createGrid(int cols, int rows) {
        TerminalCell[][] grid = new TerminalCell[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                grid[r][c] = new TerminalCell();
            }
        }
        return grid;
    }

    public synchronized TerminalCell getCell(int r, int c) {
        if (r < 0 || r >= rows || c < 0 || c >= cols) {
            return null;
        }

        if (!usingAlternate && scrollOffset > 0 && !scrollback.isEmpty()) {
            int lineIndex = scrollback.size() - scrollOffset + r;
            if (lineIndex >= 0 && lineIndex < scrollback.size()) {
                TerminalCell[] row = scrollback.get(lineIndex);
                return (c < row.length) ? row[c] : null;
            } else {
                int gridRow = lineIndex - scrollback.size();
                if (gridRow >= 0 && gridRow < rows) {
                    return activeGrid()[gridRow][c];
                }
                return null;
            }
        }

        TerminalCell[][] grid = activeGrid();
        return grid[r][c];
    }

    public static boolean isWideChar(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_FORMS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.ENCLOSED_CJK_LETTERS_AND_MONTHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || (c >= 0x2E80 && c <= 0x9FFF)
                || (c >= 0xAC00 && c <= 0xD7AF)
                || (c >= 0xFF01 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }

    public synchronized void writeChar(char ch) {
        boolean wide = isWideChar(ch);
        if (wide) {
            if (cursorCol >= cols - 1) {
                cursorCol = 0;
                newLine();
            }
        } else {
            if (cursorCol >= cols) {
                cursorCol = 0;
                newLine();
            }
        }

        TerminalCell cell = activeGrid()[cursorRow][cursorCol];
        cell.set(ch, currentFg, currentBg, bold, inverse, underline);
        cell.setWide(wide);
        cell.setContinuation(false);

        if (wide && cursorCol + 1 < cols) {
            TerminalCell nextCell = activeGrid()[cursorRow][cursorCol + 1];
            nextCell.reset();
            nextCell.setContinuation(true);
            cursorCol += 2;
        } else {
            cursorCol++;
        }
    }

    private int scrollTop = 0;
    private int scrollBottom = -1;

    public synchronized void setScrollRegion(int top, int bottom) {
        this.scrollTop = Math.max(0, Math.min(rows - 1, top));
        this.scrollBottom = Math.max(scrollTop, Math.min(rows - 1, bottom));
    }

    public synchronized void resetScrollRegion() {
        this.scrollTop = 0;
        this.scrollBottom = rows - 1;
    }

    public synchronized void newLine() {
        int bottom = (scrollBottom < 0 || scrollBottom >= rows) ? rows - 1 : scrollBottom;
        if (cursorRow < bottom) {
            cursorRow++;
        } else if (cursorRow == bottom) {
            scrollUp();
        }
    }

    public synchronized void carriageReturn() {
        cursorCol = 0;
    }

    public synchronized void backspace() {
        if (cursorCol > 0) {
            cursorCol--;
        }
    }

    public synchronized void tab() {
        int nextTab = (cursorCol / 8 + 1) * 8;
        cursorCol = Math.min(cols - 1, nextTab);
    }

    public synchronized void scrollUp() {
        int bottom = (scrollBottom < 0 || scrollBottom >= rows) ? rows - 1 : scrollBottom;
        int top = Math.max(0, Math.min(bottom, scrollTop));

        TerminalCell[][] grid = activeGrid();

        // Save scrolled out line into scrollback buffer only when in normal stream mode
        if (!usingAlternate && top == 0 && bottom == rows - 1) {
            TerminalCell[] historyRow = new TerminalCell[cols];
            for (int c = 0; c < cols; c++) {
                historyRow[c] = new TerminalCell();
                historyRow[c].copyFrom(grid[0][c]);
            }
            if (scrollback.size() >= maxScrollback) {
                scrollback.remove(0);
            }
            scrollback.add(historyRow);
        }

        for (int r = top; r < bottom; r++) {
            for (int c = 0; c < cols; c++) {
                grid[r][c].copyFrom(grid[r + 1][c]);
            }
        }
        for (int c = 0; c < cols; c++) {
            grid[bottom][c].reset();
        }
    }

    public synchronized void eraseChars(int count) {
        TerminalCell[][] grid = activeGrid();
        int end = Math.min(cols, cursorCol + Math.max(1, count));
        for (int c = cursorCol; c < end; c++) {
            grid[cursorRow][c].reset();
        }
    }

    public synchronized void setCursor(int r, int c) {
        this.cursorRow = Math.max(0, Math.min(rows - 1, r));
        this.cursorCol = Math.max(0, Math.min(cols - 1, c));
    }

    public synchronized void moveCursor(int dr, int dc) {
        setCursor(cursorRow + dr, cursorCol + dc);
    }

    public synchronized void setCursorCol(int c) {
        this.cursorCol = Math.max(0, Math.min(cols - 1, c));
    }

    public synchronized void setCursorRow(int r) {
        this.cursorRow = Math.max(0, Math.min(rows - 1, r));
    }

    public synchronized void saveCursor() {
        this.savedCol = cursorCol;
        this.savedRow = cursorRow;
    }

    public synchronized void restoreCursor() {
        this.cursorCol = Math.max(0, Math.min(cols - 1, savedCol));
        this.cursorRow = Math.max(0, Math.min(rows - 1, savedRow));
    }

    public synchronized void clearScreen(int mode) {
        TerminalCell[][] grid = activeGrid();
        if (mode == 2 || mode == 3) {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    grid[r][c].reset();
                }
            }
        } else if (mode == 0) {
            // From cursor to end of screen
            for (int c = cursorCol; c < cols; c++) {
                grid[cursorRow][c].reset();
            }
            for (int r = cursorRow + 1; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    grid[r][c].reset();
                }
            }
        } else if (mode == 1) {
            // From top of screen to cursor
            for (int r = 0; r < cursorRow; r++) {
                for (int c = 0; c < cols; c++) {
                    grid[r][c].reset();
                }
            }
            for (int c = 0; c <= cursorCol && c < cols; c++) {
                grid[cursorRow][c].reset();
            }
        }
    }

    public synchronized void clearLine(int mode) {
        TerminalCell[][] grid = activeGrid();
        if (mode == 0) {
            // From cursor to end of line
            for (int c = cursorCol; c < cols; c++) {
                grid[cursorRow][c].reset();
            }
        } else if (mode == 1) {
            // From start of line to cursor
            for (int c = 0; c <= cursorCol && c < cols; c++) {
                grid[cursorRow][c].reset();
            }
        } else if (mode == 2) {
            // Entire line
            for (int c = 0; c < cols; c++) {
                grid[cursorRow][c].reset();
            }
        }
    }

    public synchronized void setAlternateBuffer(boolean enable) {
        if (this.usingAlternate == enable) {
            return;
        }
        this.usingAlternate = enable;
        resetScrollRegion();
        if (enable) {
            clearScreen(2);
            cursorRow = 0;
            cursorCol = 0;
        }
    }

    public synchronized void resize(int newCols, int newRows) {
        int targetCols = Math.max(20, newCols);
        int targetRows = Math.max(5, newRows);

        if (this.cols == targetCols && this.rows == targetRows) {
            return;
        }

        TerminalCell[][] newPrimary = createGrid(targetCols, targetRows);
        TerminalCell[][] newAlternate = createGrid(targetCols, targetRows);

        copyGridContents(this.primaryGrid, newPrimary);
        copyGridContents(this.alternateGrid, newAlternate);

        this.cols = targetCols;
        this.rows = targetRows;
        this.primaryGrid = newPrimary;
        this.alternateGrid = newAlternate;
        this.cursorCol = Math.min(cursorCol, cols - 1);
        this.cursorRow = Math.min(cursorRow, rows - 1);
    }

    private void copyGridContents(TerminalCell[][] src, TerminalCell[][] dest) {
        int minR = Math.min(src.length, dest.length);
        int minC = Math.min(src[0].length, dest[0].length);
        for (int r = 0; r < minR; r++) {
            for (int c = 0; c < minC; c++) {
                dest[r][c].copyFrom(src[r][c]);
            }
        }
    }

    private TerminalCell[][] activeGrid() {
        return usingAlternate ? alternateGrid : primaryGrid;
    }

    public synchronized String getAllText() {
        StringBuilder sb = new StringBuilder();
        TerminalCell[][] grid = activeGrid();
        for (int r = 0; r < rows; r++) {
            int lastNonSpace = -1;
            for (int c = 0; c < cols; c++) {
                if (grid[r][c].getCharacter() != ' ' || grid[r][c].isContinuation()) {
                    lastNonSpace = c;
                }
            }
            if (lastNonSpace >= 0) {
                for (int c = 0; c <= lastNonSpace; c++) {
                    if (grid[r][c].isContinuation()) {
                        continue;
                    }
                    sb.append(grid[r][c].getCharacter());
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public synchronized void resetSgr() {
        this.currentFg = defaultFg;
        this.currentBg = defaultBg;
        this.bold = false;
        this.inverse = false;
        this.underline = false;
    }

    public synchronized void applyTheme(TerminalTheme theme) {
        if (theme == null) {
            return;
        }
        this.defaultFg = theme.foreground();
        this.defaultBg = theme.background();
        this.currentFg = this.defaultFg;
        this.currentBg = this.defaultBg;

        if (primaryGrid != null) {
            updateGridTheme(primaryGrid);
        }
        if (alternateGrid != null) {
            updateGridTheme(alternateGrid);
        }
        for (TerminalCell[] row : scrollback) {
            updateRowTheme(row);
        }
    }

    private void updateGridTheme(TerminalCell[][] targetGrid) {
        if (targetGrid == null) return;
        for (TerminalCell[] row : targetGrid) {
            updateRowTheme(row);
        }
    }

    private void updateRowTheme(TerminalCell[] row) {
        if (row == null) return;
        for (TerminalCell cell : row) {
            if (cell == null) continue;
            Color bg = cell.getBackground();
            Color fg = cell.getForeground();
            if (bg == null || isKnownDefaultBackground(bg)) {
                cell.setBackground(this.defaultBg);
            }
            if (fg == null || isKnownDefaultForeground(fg)) {
                cell.setForeground(this.defaultFg);
            }
        }
    }

    private static boolean isKnownDefaultBackground(Color c) {
        if (c == null) return true;
        if (TerminalColor.DEFAULT_BACKGROUND.equals(c)) return true;
        for (TerminalTheme th : TerminalTheme.getAllThemes()) {
            if (th.background().equals(c)) return true;
        }
        return false;
    }

    private static boolean isKnownDefaultForeground(Color c) {
        if (c == null) return true;
        if (TerminalColor.DEFAULT_FOREGROUND.equals(c)) return true;
        for (TerminalTheme th : TerminalTheme.getAllThemes()) {
            if (th.foreground().equals(c)) return true;
        }
        return false;
    }

    public Color getDefaultFg() {
        return defaultFg;
    }

    public Color getDefaultBg() {
        return defaultBg;
    }

    // Getters and Setters for SGR
    public void setCurrentFg(Color fg) {
        this.currentFg = fg;
    }

    public void setCurrentBg(Color bg) {
        this.currentBg = bg;
    }

    public void setBold(boolean bold) {
        this.bold = bold;
    }

    public void setInverse(boolean inverse) {
        this.inverse = inverse;
    }

    public void setUnderline(boolean underline) {
        this.underline = underline;
    }

    public int getCols() {
        return cols;
    }

    public int getRows() {
        return rows;
    }

    public int getCursorCol() {
        return cursorCol;
    }

    public int getCursorRow() {
        return cursorRow;
    }

    public boolean isCursorVisible() {
        return cursorVisible && (scrollOffset == 0 || usingAlternate);
    }

    public void setCursorVisible(boolean cursorVisible) {
        this.cursorVisible = cursorVisible;
    }

    public synchronized int getScrollOffset() {
        return scrollOffset;
    }

    public synchronized void setScrollOffset(int offset) {
        int max = usingAlternate ? 0 : scrollback.size();
        this.scrollOffset = Math.max(0, Math.min(max, offset));
    }

    public synchronized int getScrollbackSize() {
        return usingAlternate ? 0 : scrollback.size();
    }

    public synchronized void clearScrollback() {
        scrollback.clear();
        scrollOffset = 0;
    }

    public synchronized List<String> getAllLines() {
        List<String> lines = new ArrayList<>();
        if (!usingAlternate) {
            for (TerminalCell[] row : scrollback) {
                StringBuilder sb = new StringBuilder();
                int lastChar = -1;
                for (int c = 0; c < row.length; c++) {
                    if (row[c].getCharacter() > ' ' || row[c].isContinuation()) {
                        lastChar = c;
                    }
                }
                for (int c = 0; c <= lastChar; c++) {
                    if (row[c].isContinuation()) {
                        continue;
                    }
                    sb.append(row[c].getCharacter());
                }
                lines.add(sb.toString());
            }
        }
        TerminalCell[][] grid = activeGrid();
        for (int r = 0; r < rows; r++) {
            StringBuilder sb = new StringBuilder();
            int lastChar = -1;
            for (int c = 0; c < cols; c++) {
                if (grid[r][c].getCharacter() > ' ' || grid[r][c].isContinuation()) {
                    lastChar = c;
                }
            }
            for (int c = 0; c <= lastChar; c++) {
                if (grid[r][c].isContinuation()) {
                    continue;
                }
                sb.append(grid[r][c].getCharacter());
            }
            lines.add(sb.toString());
        }
        return lines;
    }
}
