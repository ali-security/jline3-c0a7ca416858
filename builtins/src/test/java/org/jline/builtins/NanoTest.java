/*
 * Copyright (c) 2002-2017, the original author or authors.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.builtins;

import org.jline.keymap.KeyMap;
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NanoTest {

    @Test(timeout = 1000)
    public void nanoBufferLineOverflow() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = new LineDisciplineTerminal("nano", "xterm", output, StandardCharsets.UTF_8);
        terminal.setSize(new Size(80, 25));
        for (int i = 0; i < 100; i++) {
            terminal.processInputByte(' ');
        }
        terminal.processInputByte(KeyMap.ctrl('X').getBytes()[0]);
        terminal.processInputByte('n');
        String[] argv = {"--ignorercfiles"};
        Nano nano = new Nano(terminal, Paths.get("target/test.txt"), Options.compile(Nano.usage()).parse(argv));
        nano.run();
    }

    private static Nano nanoWithLines(String... lines) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LineDisciplineTerminal terminal = new LineDisciplineTerminal("nano", "xterm", output, StandardCharsets.UTF_8);
        terminal.setSize(new Size(80, 25));
        String[] argv = {"--ignorercfiles"};
        Nano nano = new Nano(terminal, Paths.get("target"), Options.compile(Nano.usage()).parse(argv));
        nano.size.copy(terminal.getSize());
        nano.shortcuts = new LinkedHashMap<>();
        nano.shortcuts.put("^X", "Exit");
        nano.shortcuts.put("^W", "Where Is");
        nano.buffer = nano.new Buffer(null);
        nano.buffer.open();
        nano.buffer.lines = new ArrayList<>(Arrays.asList(lines));
        nano.buffer.computeAllOffsets();
        return nano;
    }

    private static String evilLine() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append('a');
        }
        return sb.append('!').toString();
    }

    @Test(timeout = 20000)
    public void testSearchReDoS() throws Exception {
        String evil = evilLine();
        List<String> lines = new ArrayList<>();
        lines.add("first");
        for (int i = 0; i < 10; i++) {
            lines.add(evil);
        }
        lines.add("last");
        Nano nano = nanoWithLines(lines.toArray(new String[0]));
        nano.searchTerm = SafeRegexTest.EVIL_PATTERN;
        nano.searchRegexp = true;

        long start = System.nanoTime();
        boolean found = nano.buffer.nextSearch();
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertFalse(found);
        assertTrue("Search timeout should be reported, got " + nano.message,
                nano.message != null && nano.message.contains("timed out"));
        assertEquals(0, nano.buffer.line);
        assertEquals(0, nano.buffer.column);
        assertTrue("search took " + elapsedMs + " ms", elapsedMs < 10000);
    }

    @Test(timeout = 20000)
    public void testBackwardSearchReDoS() throws Exception {
        String evil = evilLine();
        Nano nano = nanoWithLines("first", evil, evil, evil, evil, evil, evil, evil, evil, "last");
        nano.searchTerm = SafeRegexTest.EVIL_PATTERN;
        nano.searchRegexp = true;
        nano.searchBackwards = true;

        long start = System.nanoTime();
        boolean found = nano.buffer.nextSearch();
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertFalse(found);
        assertTrue("Search timeout should be reported, got " + nano.message,
                nano.message != null && nano.message.contains("timed out"));
        assertEquals(0, nano.buffer.line);
        assertTrue("search took " + elapsedMs + " ms", elapsedMs < 10000);
    }

    @Test(timeout = 20000)
    public void testCurrentLineSearchReDoS() throws Exception {
        String evil = evilLine();
        Nano nano = nanoWithLines(evil, evil, evil, evil, evil, "last");
        nano.searchTerm = SafeRegexTest.EVIL_PATTERN;
        nano.searchRegexp = true;

        long start = System.nanoTime();
        boolean found = nano.buffer.nextSearch();
        long elapsedMs = (System.nanoTime() - start) / 1000000;

        assertFalse(found);
        assertTrue("Search timeout should be reported, got " + nano.message,
                nano.message != null && nano.message.contains("timed out"));
        assertEquals(0, nano.buffer.line);
        assertTrue("search took " + elapsedMs + " ms", elapsedMs < 10000);
    }

    @Test(timeout = 10000)
    public void testSearch() throws Exception {
        Nano nano = nanoWithLines("foo", "bar baz", "qux");
        nano.searchTerm = "ba[rz]";
        nano.searchRegexp = true;

        assertTrue(nano.buffer.nextSearch());
        assertEquals(1, nano.buffer.line);
        assertEquals(0, nano.buffer.offsetInLine + nano.buffer.column);
        assertEquals(3, nano.matchedLength);

        assertTrue(nano.buffer.nextSearch());
        assertEquals(1, nano.buffer.line);
        assertEquals(4, nano.buffer.offsetInLine + nano.buffer.column);

        nano.searchTerm = "nothere";
        assertFalse(nano.buffer.nextSearch());
        assertEquals("\"nothere\" not found", nano.message);
    }

    @Test(timeout = 10000)
    public void testLiteralSearch() throws Exception {
        String evil = evilLine();
        Nano nano = nanoWithLines("first", evil, "x(a+)+$y");
        // Without the regexp flag the term is matched literally
        nano.searchTerm = "(a+)+$";
        nano.searchRegexp = false;

        assertTrue(nano.buffer.nextSearch());
        assertEquals(2, nano.buffer.line);
        assertEquals(1, nano.buffer.offsetInLine + nano.buffer.column);
        assertEquals(6, nano.matchedLength);
    }
}
