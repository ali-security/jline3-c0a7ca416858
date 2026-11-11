/*
 * Copyright (c) 2002-2026, the original author or authors.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jline.utils.AttributedString;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SafeRegexTest {

    /**
     * Catastrophic backtracking pattern: exponential on "aaaa...!".
     * <p>
     * The outer repetition is bounded on purpose: since Java 9 the regex
     * engine remembers the positions where an unbounded greedy group loop
     * such as the classic {@code (a+)+$} already failed, which tames that
     * form, but it does not do so for bounded loops, so this pattern
     * backtracks exponentially on every Java version.
     */
    static final String EVIL_PATTERN = "(a+){1,100}$";

    static String evilInput() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append('a');
        }
        return sb.append('!').toString();
    }

    @Test
    public void testFindSameAsPlainMatcher() {
        Pattern p = Pattern.compile("b[a-z]+");
        String input = "foo bar baz qux bzz";
        List<String> expected = new ArrayList<>();
        Matcher plain = p.matcher(input);
        while (plain.find()) {
            expected.add(plain.start() + "-" + plain.end() + ":" + plain.group());
        }
        List<String> actual = new ArrayList<>();
        Matcher safe = SafeRegex.matcher(p, input);
        while (safe.find()) {
            actual.add(safe.start() + "-" + safe.end() + ":" + safe.group());
        }
        assertEquals(3, expected.size());
        assertEquals(expected, actual);
    }

    @Test
    public void testMatchesAndGroups() {
        Matcher m = SafeRegex.matcher(Pattern.compile("(\\w+)=(\\d+)"), "key=42");
        assertTrue(m.matches());
        assertEquals("key", m.group(1));
        assertEquals("42", m.group(2));
        assertFalse(SafeRegex.matcher(Pattern.compile("\\d+"), "abc").matches());
    }

    @Test
    public void testAttributedStringInput() {
        AttributedString line = AttributedString.fromAnsi("\u001B[1mhello\u001B[0m world");
        Matcher m = SafeRegex.matcher(Pattern.compile("wor"), line);
        assertTrue(m.find());
        assertEquals(6, m.start());
        assertEquals(9, m.end());
    }

    @Test
    public void testLongInputDoesNotTimeOut() {
        // Linear matching over a large input must not trip the deadline
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200000; i++) {
            sb.append('x');
        }
        sb.append("needle");
        Matcher m = SafeRegex.matcher(Pattern.compile("needle"), sb);
        assertTrue(m.find());
        assertEquals(200000, m.start());
    }

    @Test
    public void testDeadlineStartsLazily() throws Exception {
        Matcher m = SafeRegex.matcher(Pattern.compile("x+y"), longString('x', 10000) + "y", 100);
        // The timeout measures matching time, not the time since the matcher was created
        Thread.sleep(300);
        assertTrue(m.find());
    }

    @Test(timeout = 10000)
    public void testCatastrophicBacktrackingTimesOut() {
        Matcher m = SafeRegex.matcher(Pattern.compile(EVIL_PATTERN), evilInput(), 200);
        long start = System.nanoTime();
        try {
            m.find();
            fail("Expected RegexTimeoutException");
        } catch (RegexTimeoutException e) {
            // expected
        }
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        assertTrue("Timed out after " + elapsedMs + " ms", elapsedMs < 5000);
    }

    @Test(timeout = 10000)
    public void testCatastrophicBacktrackingTimesOutWithDefaultTimeout() {
        Matcher m = SafeRegex.matcher(Pattern.compile(EVIL_PATTERN), evilInput());
        long start = System.nanoTime();
        try {
            m.matches();
            fail("Expected RegexTimeoutException");
        } catch (RegexTimeoutException e) {
            // expected
        }
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        assertTrue("Timed out after " + elapsedMs + " ms", elapsedMs >= SafeRegex.DEFAULT_TIMEOUT_MS - 100);
        assertTrue("Timed out after " + elapsedMs + " ms", elapsedMs < SafeRegex.DEFAULT_TIMEOUT_MS + 3000);
    }

    @Test
    public void testSubSequenceDelegates() {
        Matcher m = SafeRegex.matcher(Pattern.compile("(b+)"), "aabbbcc");
        assertTrue(m.find());
        assertEquals("bbb", m.group(1));
        CharSequence seq = m.group(1);
        assertEquals(3, seq.length());
        assertEquals("bbb", seq.toString());
    }

    @Test(timeout = 10000)
    public void testMatchesAndFindReturnFalseOnTimeout() {
        Pattern evil = Pattern.compile(EVIL_PATTERN);
        long start = System.nanoTime();
        assertFalse(SafeRegex.matches(evil, evilInput()));
        assertFalse(SafeRegex.find(evil, evilInput()));
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        assertTrue("Timed out after " + elapsedMs + " ms", elapsedMs < 2 * SafeRegex.DEFAULT_TIMEOUT_MS + 4000);
    }

    @Test
    public void testMatchesAndFind() {
        Pattern p = Pattern.compile("ba[rz]");
        assertTrue(SafeRegex.matches(p, "bar"));
        assertFalse(SafeRegex.matches(p, "foo bar"));
        assertTrue(SafeRegex.find(p, "foo bar"));
        assertFalse(SafeRegex.find(p, "foo qux"));
    }

    @Test
    public void testCompileGlob() {
        Pattern p = SafeRegex.compileGlob("foo*bar");
        assertTrue(p.matcher("foobar").matches());
        assertTrue(p.matcher("foo and bar").matches());
        assertFalse(p.matcher("foo and baz").matches());
        // Regex metacharacters are matched literally
        Pattern meta = SafeRegex.compileGlob("a.b(c)+[d]{2}|^$?\\");
        assertTrue(meta.matcher("a.b(c)+[d]{2}|^$?\\").matches());
        assertFalse(meta.matcher("axbcc[d]{2}").matches());
        // '\' escapes the next character
        Pattern escaped = SafeRegex.compileGlob("a\\*b");
        assertTrue(escaped.matcher("a*b").matches());
        assertFalse(escaped.matcher("axb").matches());
        // Flags are honoured
        Pattern dotall = SafeRegex.compileGlob("a*b", Pattern.DOTALL);
        assertTrue(dotall.matcher("a\nb").matches());
        assertFalse(SafeRegex.compileGlob("a*b").matcher("a\nb").matches());
    }

    @Test(timeout = 10000)
    public void testCompileGlobNestedQuantifierIsLiteral() {
        Pattern p = SafeRegex.compileGlob(EVIL_PATTERN);
        long start = System.nanoTime();
        Matcher m = SafeRegex.matcher(p, evilInput());
        // Must neither backtrack catastrophically nor time out
        assertFalse(m.matches());
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        assertTrue("Matching took " + elapsedMs + " ms", elapsedMs < 1000);
        assertTrue(SafeRegex.matches(p, EVIL_PATTERN));
    }

    private static String longString(char c, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
