package com.yanshuwang.remoteworkbench.ui.terminal;

import javafx.scene.paint.Color;

public final class TerminalCell {
    private char character;
    private Color foreground;
    private Color background;
    private boolean bold;
    private boolean inverse;
    private boolean underline;
    private boolean wide;
    private boolean continuation;

    public TerminalCell() {
        reset();
    }

    public void reset() {
        this.character = ' ';
        this.foreground = TerminalColor.DEFAULT_FOREGROUND;
        this.background = TerminalColor.DEFAULT_BACKGROUND;
        this.bold = false;
        this.inverse = false;
        this.underline = false;
        this.wide = false;
        this.continuation = false;
    }

    public void set(char c, Color fg, Color bg, boolean bold, boolean inverse, boolean underline) {
        this.character = c;
        this.foreground = fg;
        this.background = bg;
        this.bold = bold;
        this.inverse = inverse;
        this.underline = underline;
        this.wide = false;
        this.continuation = false;
    }

    public void copyFrom(TerminalCell other) {
        this.character = other.character;
        this.foreground = other.foreground;
        this.background = other.background;
        this.bold = other.bold;
        this.inverse = other.inverse;
        this.underline = other.underline;
        this.wide = other.wide;
        this.continuation = other.continuation;
    }

    public char getCharacter() {
        return character;
    }

    public void setCharacter(char character) {
        this.character = character;
    }

    public Color getForeground() {
        return inverse ? background : foreground;
    }

    public void setForeground(Color foreground) {
        this.foreground = foreground;
    }

    public Color getBackground() {
        return inverse ? foreground : background;
    }

    public void setBackground(Color background) {
        this.background = background;
    }

    public boolean isBold() {
        return bold;
    }

    public void setBold(boolean bold) {
        this.bold = bold;
    }

    public boolean isInverse() {
        return inverse;
    }

    public void setInverse(boolean inverse) {
        this.inverse = inverse;
    }

    public boolean isUnderline() {
        return underline;
    }

    public void setUnderline(boolean underline) {
        this.underline = underline;
    }

    public boolean isWide() {
        return wide;
    }

    public void setWide(boolean wide) {
        this.wide = wide;
    }

    public boolean isContinuation() {
        return continuation;
    }

    public void setContinuation(boolean continuation) {
        this.continuation = continuation;
    }
}
