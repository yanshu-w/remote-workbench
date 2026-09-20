package com.yanshuwang.remoteworkbench.ui.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnsiParserTest {

    @Test
    void testPlainTextWriting() {
        TerminalBuffer buffer = new TerminalBuffer(80, 24);
        AnsiParser parser = new AnsiParser(buffer);

        parser.parse("Hello, World!\r\nNext Line");

        assertEquals('H', buffer.getCell(0, 0).getCharacter());
        assertEquals('e', buffer.getCell(0, 1).getCharacter());
        assertEquals('N', buffer.getCell(1, 0).getCharacter());
        assertEquals('e', buffer.getCell(1, 1).getCharacter());
    }

    @Test
    void testCursorPositioningForTop() {
        TerminalBuffer buffer = new TerminalBuffer(80, 24);
        AnsiParser parser = new AnsiParser(buffer);

        // Position cursor at row 5, col 10 (1-indexed => 4, 9)
        parser.parse("\u001B[5;10H");
        assertEquals(4, buffer.getCursorRow());
        assertEquals(9, buffer.getCursorCol());

        parser.parse("CPU");
        assertEquals('C', buffer.getCell(4, 9).getCharacter());
        assertEquals('P', buffer.getCell(4, 10).getCharacter());
        assertEquals('U', buffer.getCell(4, 11).getCharacter());
    }

    @Test
    void testClearScreenAndLine() {
        TerminalBuffer buffer = new TerminalBuffer(80, 24);
        AnsiParser parser = new AnsiParser(buffer);

        parser.parse("Old content on line 0\r\nLine 1");
        assertEquals('O', buffer.getCell(0, 0).getCharacter());

        // Clear screen (2J)
        parser.parse("\u001B[2J");
        assertEquals(' ', buffer.getCell(0, 0).getCharacter());
        assertEquals(' ', buffer.getCell(1, 0).getCharacter());
    }

    @Test
    void testSgrInverseAndColors() {
        TerminalBuffer buffer = new TerminalBuffer(80, 24);
        AnsiParser parser = new AnsiParser(buffer);

        // Inverse text (like top table header)
        parser.parse("\u001B[7mPID\u001B[0m");

        TerminalCell cellP = buffer.getCell(0, 0);
        assertEquals('P', cellP.getCharacter());
        assertTrue(cellP.isInverse());

        // Normal text after reset
        parser.parse(" NORMAL");
        TerminalCell cellN = buffer.getCell(0, 4);
        assertEquals('N', cellN.getCharacter());
        assertFalse(cellN.isInverse());
    }

    @Test
    void testAlternateScreenBufferSwitch() {
        TerminalBuffer buffer = new TerminalBuffer(80, 24);
        AnsiParser parser = new AnsiParser(buffer);

        parser.parse("Primary buffer line");
        assertEquals('P', buffer.getCell(0, 0).getCharacter());

        // Enter alternate buffer (?1049h) used by top / vim
        parser.parse("\u001B[?1049h");
        assertEquals(' ', buffer.getCell(0, 0).getCharacter());

        parser.parse("top running here");
        assertEquals('t', buffer.getCell(0, 0).getCharacter());

        // Exit alternate buffer (?1049l)
        parser.parse("\u001B[?1049l");
        assertEquals('P', buffer.getCell(0, 0).getCharacter());
    }

    @Test
    void testCharacterSetSequenceIgnored() {
        TerminalBuffer buffer = new TerminalBuffer(80, 24);
        AnsiParser parser = new AnsiParser(buffer);

        // \u001B(B is Select Character Set (US-ASCII). The 'B' should NOT be written as text!
        parser.parse("\u001B(BText\u001B(0Other");

        assertEquals('T', buffer.getCell(0, 0).getCharacter());
        assertEquals('e', buffer.getCell(0, 1).getCharacter());
        assertEquals('x', buffer.getCell(0, 2).getCharacter());
        assertEquals('t', buffer.getCell(0, 3).getCharacter());
        assertEquals('O', buffer.getCell(0, 4).getCharacter());
    }

    @Test
    void testScrollMarginsKeepHeaderFixed() {
        TerminalBuffer buffer = new TerminalBuffer(80, 5);
        AnsiParser parser = new AnsiParser(buffer);

        // Write header on line 0
        parser.parse("Header Line\r\n");
        assertEquals('H', buffer.getCell(0, 0).getCharacter());

        // Set scroll margins to line 2-5 (1-indexed: 2;5r => 0-indexed: 1..4)
        parser.parse("\u001B[2;5r");

        // Move cursor to bottom margin and trigger multiple newlines
        parser.parse("\u001B[5;1HLine 5\nLine 6\nLine 7\n");

        // Header Line on line 0 must remain intact!
        assertEquals('H', buffer.getCell(0, 0).getCharacter());
        assertEquals('e', buffer.getCell(0, 1).getCharacter());
    }

    @Test
    void testTrueColorSupport() {
        TerminalBuffer buffer = new TerminalBuffer(80, 24);
        AnsiParser parser = new AnsiParser(buffer);

        // 38;2;255;128;64 => TrueColor foreground
        // 48;2;10;20;30 => TrueColor background
        parser.parse("\u001B[38;2;255;128;64;48;2;10;20;30mColorText\u001B[0m");

        TerminalCell cell = buffer.getCell(0, 0);
        assertEquals('C', cell.getCharacter());
        javafx.scene.paint.Color fg = cell.getForeground();
        javafx.scene.paint.Color bg = cell.getBackground();

        assertEquals(255, (int) Math.round(fg.getRed() * 255));
        assertEquals(128, (int) Math.round(fg.getGreen() * 255));
        assertEquals(64, (int) Math.round(fg.getBlue() * 255));

        assertEquals(10, (int) Math.round(bg.getRed() * 255));
        assertEquals(20, (int) Math.round(bg.getGreen() * 255));
        assertEquals(30, (int) Math.round(bg.getBlue() * 255));
    }
}
