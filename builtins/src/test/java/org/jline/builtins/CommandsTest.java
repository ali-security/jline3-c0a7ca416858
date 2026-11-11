/*
 * Copyright (c) 2002-2018, the original author or authors.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.builtins;

import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.jline.utils.AttributedString;
import org.junit.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CommandsTest {

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Runs {@code history -n -m <match>} over the given history lines and
     * returns the listed entries.
     */
    private static List<String> historyMatch(String match, String... lines) throws Exception {
        LineDisciplineTerminal terminal = new LineDisciplineTerminal(
                "history", "xterm", new ByteArrayOutputStream(), StandardCharsets.UTF_8);
        terminal.setSize(new Size(200, 30));
        History history = new DefaultHistory();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .history(history)
                .build();
        history.attach(reader);
        for (String line : lines) {
            history.add(line);
        }
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(os, true, "UTF-8");
        Commands.history(reader, out, out, Paths.get(""), new String[] {"-n", "-m", match});
        out.flush();
        List<String> listed = new ArrayList<>();
        for (String l : os.toString("UTF-8").split("\\r?\\n")) {
            String s = AttributedString.stripAnsi(l).trim();
            if (!s.isEmpty()) {
                listed.add(s);
            }
        }
        return listed;
    }

    @Test(timeout = 10000)
    public void testHistoryMatchGlob() throws Exception {
        String[] lines = {"foo bar", "foobaz", "bar foo", "f.o", "fxo", "a*b", "axb"};
        // '*' matches any string
        assertEquals(Arrays.asList("foo bar", "foobaz"), historyMatch("foo*", lines));
        assertEquals(Arrays.asList("foo bar", "foobaz", "bar foo"), historyMatch("*foo*", lines));
        // Every other character matches literally
        assertEquals(Arrays.asList("f.o"), historyMatch("f.o", lines));
        // '\' escapes the next character
        assertEquals(Arrays.asList("a*b"), historyMatch("a\\*b", lines));
    }

    @Test(timeout = 20000)
    public void testHistoryMatchNestedQuantifierReDoS() throws Exception {
        // The match used to be compiled as a regular expression, so nested
        // quantifiers backtracked catastrophically on "aaaa...!"
        String evil = repeat('a', 50) + "!";
        String match = SafeRegexTest.EVIL_PATTERN;
        long start = System.nanoTime();
        List<String> listed = historyMatch(match,
                "first", evil + "1", evil + "2", evil + "3", match, evil + "4", "last");
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        // The match is a glob: the regex metacharacters are taken literally
        assertEquals(Arrays.asList(match), listed);
        assertTrue("history -m took " + elapsedMs + " ms", elapsedMs < 15000);
    }

    @Test(timeout = 20000)
    public void testHistoryMatchStarBacktrackingReDoS() throws Exception {
        // Each '*' becomes ".*": many of them backtrack polynomially on a
        // long non-matching entry, which the matching timeout must bound
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append("*a");
        }
        String glob = sb.append("*b").toString();
        long start = System.nanoTime();
        List<String> listed = historyMatch(glob,
                "first", repeat('a', 100), repeat('a', 101), repeat('a', 12) + "b", "last");
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        assertEquals(Arrays.asList(repeat('a', 12) + "b"), listed);
        assertTrue("history -m took " + elapsedMs + " ms", elapsedMs < 15000);
    }

    @Test
    public void testHistoryForFileWithMoreHistoryRecordsThanAtHistoryFileSize() {
        try {
            final ByteArrayOutputStream os = new ByteArrayOutputStream();
            final File tmpHistoryFile = Files.createTempFile("tmpHistory", "temp").toFile();
            tmpHistoryFile.deleteOnExit();
            try (BufferedWriter bw =
                         new BufferedWriter(
                                 new OutputStreamWriter(
                                         new FileOutputStream(tmpHistoryFile)))) {
                bw.write("1536743099591:SELECT \\n CURRENT_TIMESTAMP \\n as \\n c1;\n"
                        + "1536743104551:SELECT \\n 'asd' as \"sdf\", 4 \\n \\n as \\n c2;\\n\n"
                        + "1536743104551:SELECT \\n 'asd' \\n as \\n c2;\\n\n"
                        + "1536743104551:!/ 2\n"
                        + "1536743104551:SELECT \\n 2123 \\n as \\n c2 from dual;\\n\n"
                        + "1536743107526:!history\n"
                        + "1536743115431:SELECT \\n 2 \\n as \\n c2;\n"
                        + "1536743115431:SELECT \\n '213' \\n as \\n c1;\n"
                        + "1536743115431:!/ 8\n");
                bw.flush();
            }
            Terminal terminal = TerminalBuilder.builder().streams(System.in, System.out).build();
            terminal.setSize(new Size(50, 30));
            final History historyFromFile = new DefaultHistory();
            final LineReaderBuilder lineReaderBuilder = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .variable(LineReader.HISTORY_FILE, tmpHistoryFile.getAbsolutePath())
                    .history(historyFromFile);
            final LineReader lineReader = lineReaderBuilder.build();
            historyFromFile.attach(lineReader);
            final int maxLines = 3;
            lineReader.setVariable(LineReader.HISTORY_FILE_SIZE, maxLines);
            lineReader.getHistory().save();
            PrintStream out = new PrintStream(os, false);
            Commands.history(lineReader, out, out, Paths.get(""), new String[] {"-d"});
            assertEquals(maxLines + 1,
                    os.toString("UTF8").split("\\s+\\d{2}:\\d{2}:\\d{2}\\s+").length);
        } catch (Exception e) {
            throw new RuntimeException("Test failed", e);
        }
    }
} 