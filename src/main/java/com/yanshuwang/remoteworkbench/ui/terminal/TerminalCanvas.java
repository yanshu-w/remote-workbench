package com.yanshuwang.remoteworkbench.ui.terminal;

import com.yanshuwang.remoteworkbench.ui.theme.ThemeManager;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.concurrent.atomic.AtomicBoolean;

public final class TerminalCanvas extends Canvas {
    private final TerminalBuffer buffer;
    private String fontFamily;
    private int fontSize;

    private TerminalTheme theme = TerminalTheme.AUTO.resolveEffectiveTheme(ThemeManager.isDarkMode());
    private CursorStyle cursorStyle = CursorStyle.BLOCK;
    private boolean cursorBlink = true;
    private boolean cursorBlinkVisible = true;
    private Timeline blinkTimeline;

    private Font normalFont;
    private Font boldFont;
    private double charWidth = 8.5;
    private double charHeight = 18.0;
    private double baselineOffset = 14.0;

    private final AtomicBoolean renderScheduled = new AtomicBoolean(false);
    private boolean focused = false;

    // Selection tracking
    private int selStartRow = -1;
    private int selStartCol = -1;
    private int selEndRow = -1;
    private int selEndCol = -1;
    private boolean isSelecting = false;

    public TerminalCanvas(TerminalBuffer buffer, String fontFamily, int fontSize) {
        this.buffer = buffer;
        this.fontFamily = fontFamily;
        this.fontSize = fontSize;
        this.theme = TerminalTheme.AUTO.resolveEffectiveTheme(ThemeManager.isDarkMode());
        if (buffer != null) {
            buffer.applyTheme(this.theme);
        }
        setCursor(Cursor.TEXT);
        updateFontMetrics();
        setupMouseSelection();
        setupCursorBlinking();
    }

    private void setupCursorBlinking() {
        blinkTimeline = new Timeline(new KeyFrame(Duration.millis(500), event -> {
            if (focused && cursorBlink && buffer.isCursorVisible()) {
                cursorBlinkVisible = !cursorBlinkVisible;
                requestRender();
            } else if (!cursorBlinkVisible) {
                cursorBlinkVisible = true;
                requestRender();
            }
        }));
        blinkTimeline.setCycleCount(Timeline.INDEFINITE);
        blinkTimeline.play();
    }

    public void resetCursorBlink() {
        cursorBlinkVisible = true;
        if (blinkTimeline != null) {
            blinkTimeline.stop();
            blinkTimeline.playFromStart();
        }
    }

    private void setupMouseSelection() {
        setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                int col = Math.max(0, Math.min(buffer.getCols() - 1, (int) (event.getX() / charWidth)));
                int row = Math.max(0, Math.min(buffer.getRows() - 1, (int) (event.getY() / charHeight)));

                if (event.getClickCount() == 2) {
                    selectWordAt(row, col);
                } else if (event.getClickCount() == 3) {
                    selectLineAt(row);
                } else {
                    selStartRow = row;
                    selStartCol = col;
                    selEndRow = row;
                    selEndCol = col;
                    isSelecting = true;
                }
                requestRender();
            }
        });

        setOnMouseDragged(event -> {
            if (isSelecting) {
                int col = Math.max(0, Math.min(buffer.getCols() - 1, (int) (event.getX() / charWidth)));
                int row = Math.max(0, Math.min(buffer.getRows() - 1, (int) (event.getY() / charHeight)));
                selEndRow = row;
                selEndCol = col;
                requestRender();
            }
        });

        setOnMouseReleased(event -> {
            if (isSelecting) {
                isSelecting = false;
                if (selStartRow == selEndRow && selStartCol == selEndCol) {
                    clearSelection();
                } else {
                    requestRender();
                }
            }
        });

        setOnScroll(event -> {
            double deltaY = event.getDeltaY();
            if (deltaY != 0) {
                int lines = (int) Math.round(deltaY / 20.0);
                if (lines == 0) {
                    lines = deltaY > 0 ? 1 : -1;
                }
                buffer.setScrollOffset(buffer.getScrollOffset() + lines);
                requestRender();
                if (scrollListener != null) {
                    scrollListener.run();
                }
            }
        });
    }

    private Runnable scrollListener;

    public void setScrollListener(Runnable listener) {
        this.scrollListener = listener;
    }

    public boolean hasSelection() {
        return selStartRow >= 0 && selEndRow >= 0 && (selStartRow != selEndRow || selStartCol != selEndCol);
    }

    public void clearSelection() {
        selStartRow = -1;
        selStartCol = -1;
        selEndRow = -1;
        selEndCol = -1;
        isSelecting = false;
        requestRender();
    }

    public void selectAll() {
        selStartRow = 0;
        selStartCol = 0;
        selEndRow = buffer.getRows() - 1;
        selEndCol = buffer.getCols() - 1;
        requestRender();
    }

    public void setSelection(int startRow, int startCol, int endRow, int endCol) {
        this.selStartRow = startRow;
        this.selStartCol = startCol;
        this.selEndRow = endRow;
        this.selEndCol = endCol;
        requestRender();
    }

    public String getSelectedText() {
        if (!hasSelection()) {
            return "";
        }

        int sr = selStartRow, sc = selStartCol;
        int er = selEndRow, ec = selEndCol;

        if (sr > er || (sr == er && sc > ec)) {
            int tr = sr; sr = er; er = tr;
            int tc = sc; sc = ec; ec = tc;
        }

        StringBuilder sb = new StringBuilder();
        int totalCols = buffer.getCols();

        for (int r = sr; r <= er; r++) {
            int fromC = (r == sr) ? sc : 0;
            int toC = (r == er) ? ec : totalCols - 1;

            StringBuilder line = new StringBuilder();
            for (int c = fromC; c <= toC; c++) {
                TerminalCell cell = buffer.getCell(r, c);
                if (cell != null && cell.isContinuation()) {
                    continue;
                }
                char ch = (cell != null && cell.getCharacter() >= 0x20) ? cell.getCharacter() : ' ';
                line.append(ch);
            }

            if (r < er) {
                sb.append(line.toString().stripTrailing()).append('\n');
            } else {
                sb.append(line.toString().stripTrailing());
            }
        }
        return sb.toString();
    }

    private void selectWordAt(int row, int col) {
        int cols = buffer.getCols();
        int start = col;
        while (start > 0) {
            TerminalCell cell = buffer.getCell(row, start - 1);
            if (cell == null || cell.getCharacter() <= 0x20) {
                break;
            }
            start--;
        }

        int end = col;
        while (end < cols - 1) {
            TerminalCell cell = buffer.getCell(row, end + 1);
            if (cell == null || cell.getCharacter() <= 0x20) {
                break;
            }
            end++;
        }

        selStartRow = row;
        selStartCol = start;
        selEndRow = row;
        selEndCol = end;
    }

    private void selectLineAt(int row) {
        selStartRow = row;
        selStartCol = 0;
        selEndRow = row;
        selEndCol = buffer.getCols() - 1;
    }

    private boolean isCellSelected(int r, int c) {
        if (!hasSelection()) {
            return false;
        }

        int sr = selStartRow, sc = selStartCol;
        int er = selEndRow, ec = selEndCol;

        if (sr > er || (sr == er && sc > ec)) {
            int tr = sr; sr = er; er = tr;
            int tc = sc; sc = ec; ec = tc;
        }

        if (r < sr || r > er) {
            return false;
        }
        if (r == sr && r == er) {
            return c >= sc && c <= ec;
        }
        if (r == sr) {
            return c >= sc;
        }
        if (r == er) {
            return c <= ec;
        }
        return true;
    }

    public void setTerminalFont(String fontFamily, int fontSize) {
        this.fontFamily = fontFamily;
        this.fontSize = fontSize;
        updateFontMetrics();
        requestRender();
    }

    private void updateFontMetrics() {
        this.normalFont = Font.font(fontFamily, FontWeight.NORMAL, FontPosture.REGULAR, fontSize);
        this.boldFont = Font.font(fontFamily, FontWeight.BOLD, FontPosture.REGULAR, fontSize);

        Text sample = new Text("01234567890123456789012345678901234567890123456789012345678901234567890123456789");
        sample.setFont(normalFont);
        double sampleWidth = sample.getLayoutBounds().getWidth();
        double sampleHeight = sample.getLayoutBounds().getHeight();

        this.charWidth = Math.max(5.0, sampleWidth / 80.0);
        this.charHeight = Math.max(10.0, Math.ceil(sampleHeight * 1.12));
        this.baselineOffset = charHeight * 0.80;
    }

    public void requestRender() {
        if (renderScheduled.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                renderScheduled.set(false);
                render();
            });
        }
    }

    public void render() {
        GraphicsContext gc = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();

        if (w <= 0 || h <= 0) {
            return;
        }

        // 1. Draw overall terminal background
        gc.setFill(theme.background());
        gc.fillRect(0, 0, w, h);

        gc.setTextBaseline(VPos.BASELINE);

        int rows = buffer.getRows();
        int cols = buffer.getCols();

        // 2. Draw cell backgrounds and characters
        for (int r = 0; r < rows; r++) {
            double y = r * charHeight;
            if (y > h) {
                break;
            }

            for (int c = 0; c < cols; c++) {
                double x = c * charWidth;
                if (x > w) {
                    break;
                }

                TerminalCell cell = buffer.getCell(r, c);
                if (cell == null || cell.isContinuation()) {
                    continue;
                }

                boolean selected = isCellSelected(r, c);
                Color cellBg = cell.getBackground();
                Color cellFg = cell.getForeground();
                double cellWidth = cell.isWide() ? (charWidth * 2.0) : charWidth;

                Color drawBg;
                if (cellBg == null || isKnownDefaultBackground(cellBg)) {
                    drawBg = theme.background();
                } else if (TerminalColor.DEFAULT_FOREGROUND.equals(cellBg) || isKnownDefaultForeground(cellBg)) {
                    drawBg = theme.foreground();
                } else {
                    drawBg = cellBg;
                }

                if (selected) {
                    gc.setFill(theme.selectionColor());
                    gc.fillRect(x, y, cellWidth + 0.5, charHeight);
                } else if (!theme.background().equals(drawBg)) {
                    gc.setFill(drawBg);
                    gc.fillRect(x, y, cellWidth + 0.5, charHeight);
                }

                char ch = cell.getCharacter();
                if (ch > 0x20) {
                    gc.setFont(cell.isBold() ? boldFont : normalFont);
                    Color drawFg;
                    if (selected) {
                        drawFg = (theme == TerminalTheme.MACOS_LIGHT) ? theme.foreground() : Color.WHITE;
                    } else if (cellFg == null || isKnownDefaultForeground(cellFg)) {
                        drawFg = theme.foreground();
                    } else if (TerminalColor.DEFAULT_BACKGROUND.equals(cellFg) || isKnownDefaultBackground(cellFg)) {
                        drawFg = theme.background();
                    } else {
                        drawFg = cellFg;
                    }
                    gc.setFill(drawFg);
                    gc.fillText(String.valueOf(ch), x, y + baselineOffset);

                    if (cell.isUnderline()) {
                        gc.setStroke(drawFg);
                        gc.setLineWidth(1.0);
                        gc.strokeLine(x, y + charHeight - 1, x + cellWidth, y + charHeight - 1);
                    }
                }
            }
        }

        // 3. Draw cursor
        if (buffer.isCursorVisible()) {
            int cr = buffer.getCursorRow();
            int cc = buffer.getCursorCol();
            if (cr >= 0 && cr < rows && cc >= 0 && cc < cols) {
                double cx = cc * charWidth;
                double cy = cr * charHeight;

                TerminalCell cell = buffer.getCell(cr, cc);
                double cursorWidth = (cell != null && cell.isWide()) ? (charWidth * 2.0) : charWidth;

                if (focused) {
                    if (!cursorBlink || cursorBlinkVisible) {
                        gc.setFill(theme.cursorColor());
                        switch (cursorStyle) {
                            case BLOCK -> {
                                gc.fillRect(cx, cy, cursorWidth, charHeight);
                                if (cell != null && cell.getCharacter() > 0x20) {
                                    gc.setFont(normalFont);
                                    gc.setFill(theme.background());
                                    gc.fillText(String.valueOf(cell.getCharacter()), cx, cy + baselineOffset);
                                }
                            }
                            case UNDERLINE -> {
                                gc.fillRect(cx, cy + charHeight - 3.0, cursorWidth, 3.0);
                            }
                            case BAR -> {
                                gc.fillRect(cx, cy, 2.0, charHeight);
                            }
                        }
                    }
                } else {
                    gc.setStroke(theme.cursorColor());
                    gc.setLineWidth(1.2);
                    switch (cursorStyle) {
                        case BLOCK -> gc.strokeRect(cx + 0.5, cy + 0.5, cursorWidth - 1, charHeight - 1);
                        case UNDERLINE -> gc.strokeLine(cx, cy + charHeight - 1.5, cx + cursorWidth, cy + charHeight - 1.5);
                        case BAR -> gc.strokeLine(cx + 0.5, cy, cx + 0.5, cy + charHeight);
                    }
                }
            }
        }
    }

    public void setTheme(TerminalTheme theme) {
        TerminalTheme target = (theme != null) ? theme : TerminalTheme.AUTO;
        this.theme = target.resolveEffectiveTheme(ThemeManager.isDarkMode());
        buffer.applyTheme(this.theme);
        requestRender();
    }

    public TerminalTheme getTheme() {
        return theme;
    }

    public void setCursorStyle(CursorStyle cursorStyle) {
        this.cursorStyle = (cursorStyle != null) ? cursorStyle : CursorStyle.BLOCK;
        requestRender();
    }

    public CursorStyle getCursorStyle() {
        return cursorStyle;
    }

    public void setCursorBlink(boolean cursorBlink) {
        this.cursorBlink = cursorBlink;
        if (!cursorBlink) {
            cursorBlinkVisible = true;
        }
        requestRender();
    }

    public boolean isCursorBlink() {
        return cursorBlink;
    }

    public double getCharWidth() {
        return charWidth;
    }

    public double getCharHeight() {
        return charHeight;
    }

    public void setTerminalFocused(boolean focused) {
        this.focused = focused;
        resetCursorBlink();
        requestRender();
    }

    public void dispose() {
        if (blinkTimeline != null) {
            blinkTimeline.stop();
        }
    }

    private static boolean isKnownDefaultBackground(Color c) {
        if (c == null) {
            return true;
        }
        if (TerminalColor.DEFAULT_BACKGROUND.equals(c)) {
            return true;
        }
        for (TerminalTheme th : TerminalTheme.getAllThemes()) {
            if (th.background().equals(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKnownDefaultForeground(Color c) {
        if (c == null) {
            return true;
        }
        if (TerminalColor.DEFAULT_FOREGROUND.equals(c)) {
            return true;
        }
        for (TerminalTheme th : TerminalTheme.getAllThemes()) {
            if (th.foreground().equals(c)) {
                return true;
            }
        }
        return false;
    }
}
