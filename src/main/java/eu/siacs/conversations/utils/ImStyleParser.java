/*
 * Copyright (c) 2017, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * XEP-0393 (Message Styling) v1.1.1 parser.
 *
 * <p>The text is first split into blocks - quotations, preformatted text blocks and plain lines -
 * and the spans are then parsed inside each plain line. Quotations are parsed recursively with
 * their '&gt;' and the first whitespace character of every line removed. Positions in the returned
 * {@link Style}s always refer to the original text, so the quote markers can stay in place for the
 * quote rendering that runs afterwards.
 */
public class ImStyleParser {

    private static final List<Character> KEYWORDS = Arrays.asList('*', '_', '~', '`');
    private static final char PREFORMATTED_SPAN = '`';
    private static final char QUOTE = '>';
    private static final String PREFORMATTED_BLOCK = "```";

    public static List<Style> parse(CharSequence text) {
        return parse(text, 0, text.length() - 1);
    }

    /** Parses {@code text} from {@code start} to {@code end} (inclusive) as one parent block. */
    public static List<Style> parse(CharSequence text, int start, int end) {
        final List<Style> styles = new ArrayList<>();
        if (end < start) {
            return styles;
        }
        final int[] positions = new int[end - start + 1];
        for (int i = 0; i < positions.length; ++i) {
            positions[i] = start + i;
        }
        parseBlocks(new Region(text, positions), styles);
        return styles;
    }

    private static void parseBlocks(final Region region, final List<Style> styles) {
        int i = 0;
        while (i < region.length()) {
            final int lineEnd = region.lineEnd(i);
            if (region.charAt(i) == QUOTE) {
                i = parseQuotation(region, i, styles);
            } else if (region.startsWith(i, PREFORMATTED_BLOCK)) {
                i = parsePreformattedBlock(region, i, styles);
            } else {
                parseSpans(region, i, lineEnd, styles);
                i = lineEnd + 1;
            }
        }
    }

    /**
     * A quotation is a run of lines beginning with '&gt;'; it ends at the first line that does not.
     * Its child blocks are parsed with the '&gt;' and the first leading whitespace character of
     * each line trimmed.
     */
    private static int parseQuotation(final Region region, int i, final List<Style> styles) {
        final IntList child = new IntList();
        while (i < region.length() && region.charAt(i) == QUOTE) {
            final int lineEnd = region.lineEnd(i);
            int contentStart = i + 1;
            if (contentStart < lineEnd && Character.isWhitespace(region.charAt(contentStart))) {
                contentStart++;
            }
            for (int k = contentStart; k < lineEnd; ++k) {
                child.add(region.position(k));
            }
            final boolean nextLineQuoted =
                    lineEnd + 1 < region.length() && region.charAt(lineEnd + 1) == QUOTE;
            if (nextLineQuoted) {
                child.add(region.position(lineEnd)); // the newline separating two quoted lines
            }
            i = lineEnd + 1;
            if (!nextLineQuoted) {
                break;
            }
        }
        parseBlocks(new Region(region.text, child.toArray()), styles);
        return i;
    }

    /**
     * A preformatted block starts with a line beginning with "```" and ends with a line containing
     * only "```", or at the end of the parent block. Nothing inside it is parsed.
     */
    private static int parsePreformattedBlock(
            final Region region, final int start, final List<Style> styles) {
        int line = region.lineEnd(start) + 1;
        while (line < region.length()) {
            final int lineEnd = region.lineEnd(line);
            if (lineEnd - line == PREFORMATTED_BLOCK.length()
                    && region.startsWith(line, PREFORMATTED_BLOCK)) {
                styles.add(
                        new Style(
                                PREFORMATTED_BLOCK,
                                region.position(start),
                                region.position(lineEnd - 1),
                                true));
                return lineEnd + 1;
            }
            line = lineEnd + 1;
        }
        styles.add(
                new Style(
                        PREFORMATTED_BLOCK,
                        region.position(start),
                        region.position(region.length() - 1),
                        false));
        return region.length();
    }

    /**
     * Spans inside one line, {@code from} inclusive to {@code to} exclusive. Spans are matched
     * lazily from the beginning: the first valid closing directive closes the span. A match with
     * nothing between the two directives invalidates both.
     */
    private static void parseSpans(
            final Region region, final int from, final int to, final List<Style> styles) {
        // A directive without any closing candidate up to the end of the line can't be closed
        // from a later position either; remembering that keeps hostile input linear.
        final boolean[] unclosable = new boolean[KEYWORDS.size()];
        for (int i = from; i < to; ++i) {
            final char c = region.charAt(i);
            final int keyword = KEYWORDS.indexOf(c);
            if (keyword == -1 || unclosable[keyword] || !isOpening(region, i, from, to)) {
                continue;
            }
            final int close = seekClosing(region, c, i + 1, to);
            if (close == -1) {
                unclosable[keyword] = true;
                continue;
            }
            if (close == i + 1) {
                continue; // an empty span: neither directive is valid
            }
            styles.add(new Style(c, region.position(i), region.position(close)));
            if (c != PREFORMATTED_SPAN) {
                parseSpans(region, i + 1, close, styles);
            }
            i = close;
        }
    }

    /**
     * An opening directive sits at the beginning of the parent block, after whitespace or after a
     * different opening directive, and is not followed by whitespace.
     */
    private static boolean isOpening(
            final Region region, final int i, final int from, final int to) {
        if (i + 1 >= to || Character.isWhitespace(region.charAt(i + 1))) {
            return false;
        }
        return isOpeningPosition(region, i, from);
    }

    private static boolean isOpeningPosition(final Region region, final int i, final int from) {
        if (i == from) {
            return true;
        }
        final char previous = region.charAt(i - 1);
        if (Character.isWhitespace(previous)) {
            return true;
        }
        return KEYWORDS.contains(previous)
                && previous != region.charAt(i)
                && isOpeningPosition(region, i - 1, from);
    }

    /** The first {@code needle} in [from, to) that is not preceded by whitespace. */
    private static int seekClosing(
            final Region region, final char needle, final int from, final int to) {
        for (int i = from; i < to; ++i) {
            if (region.charAt(i) == needle && !Character.isWhitespace(region.charAt(i - 1))) {
                return i;
            }
        }
        return -1;
    }

    /** A view on (possibly non-contiguous) characters of the original text. */
    private static final class Region {
        private final CharSequence text;
        private final int[] positions;

        private Region(final CharSequence text, final int[] positions) {
            this.text = text;
            this.positions = positions;
        }

        int length() {
            return positions.length;
        }

        char charAt(final int i) {
            return text.charAt(positions[i]);
        }

        int position(final int i) {
            return positions[i];
        }

        /** Index of the newline ending the line that contains {@code i}, or {@link #length()}. */
        int lineEnd(final int i) {
            for (int k = i; k < positions.length; ++k) {
                if (charAt(k) == '\n') {
                    return k;
                }
            }
            return positions.length;
        }

        boolean startsWith(final int i, final String prefix) {
            if (i + prefix.length() > positions.length) {
                return false;
            }
            for (int k = 0; k < prefix.length(); ++k) {
                if (charAt(i + k) != prefix.charAt(k)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class IntList {
        private int[] values = new int[16];
        private int size = 0;

        void add(final int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        int[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }

    public static class Style {

        private final String keyword;
        private final int start;
        private final int end;
        private final boolean closed;

        public Style(char character, int start, int end) {
            this(String.valueOf(character), start, end);
        }

        public Style(String keyword, int start, int end) {
            this(keyword, start, end, true);
        }

        /**
         * @param closed false for a preformatted block that runs to the end of its parent block
         *     without a closing "```" line; {@code end} is then the last character of the block
         */
        public Style(String keyword, int start, int end, boolean closed) {
            this.keyword = keyword;
            this.start = start;
            this.end = end;
            this.closed = closed;
        }

        public String getKeyword() {
            return keyword;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }

        public boolean isClosed() {
            return closed;
        }
    }
}
