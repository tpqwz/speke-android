package eu.siacs.conversations.utils;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * XEP-0393 (Message Styling) v1.1.1 conformance of {@link ImStyleParser}. The cases are the
 * examples and rules of the specification; each style is rendered as {@code keyword[text]} where
 * text includes the directives.
 */
public class ImStyleParserTest {

    private static List<String> styles(final String text) {
        final List<String> out = new ArrayList<>();
        for (final ImStyleParser.Style style : ImStyleParser.parse(text)) {
            out.add(
                    style.getKeyword()
                            + (style.isClosed() ? "" : "(open)")
                            + "["
                            + text.substring(style.getStart(), style.getEnd() + 1)
                            + "]");
        }
        return out;
    }

    private static void assertStyles(final String text, final String... expected) {
        Assert.assertEquals("styles of: " + text, List.of(expected), styles(text));
    }

    // ---- §6.2 spans: the examples that are styled ----

    @Test
    public void plainSpan() {
        assertStyles("plain span");
    }

    @Test
    public void strongSpan() {
        assertStyles("*strong span*", "*[*strong span*]");
    }

    @Test
    public void emphasisBetweenPlain() {
        assertStyles("plain _emphasis_ plain", "_[_emphasis_]");
    }

    @Test
    public void preformattedThenStrong() {
        assertStyles("`pre` plain *strong*", "`[`pre`]", "*[*strong*]");
    }

    /** Lazy matching: the first valid closing directive ends the span. */
    @Test
    public void strongFollowedByPlainAndStray() {
        assertStyles("*strong*plain*", "*[*strong*]");
    }

    /** An opening directive followed by whitespace is not one. */
    @Test
    public void openingFollowedBySpaceIsPlain() {
        assertStyles("* plain *strong*", "*[*strong*]");
    }

    // ---- §6.2 spans: nothing is styled ----

    @Test
    public void notStrongMissingOpening() {
        assertStyles("not strong*");
    }

    @Test
    public void notStrongMissingClosing() {
        assertStyles("*not strong");
    }

    @Test
    public void spansDoNotCrossLines() {
        assertStyles("*not \n strong*");
    }

    @Test
    public void closingPrecededBySpaceIsNotOne() {
        assertStyles("*not *strong");
    }

    @Test
    public void emptySpansAreNotStyled() {
        assertStyles("**");
        assertStyles("***");
        assertStyles("****");
    }

    // ---- the individual span types ----

    @Test
    public void twoSpansAlikeInDignity() {
        assertStyles("Two spans, both *alike in dignity*", "*[*alike in dignity*]");
    }

    @Test
    public void emphasisExample() {
        assertStyles(
                "The full title is _Twelfth Night, or What You Will_ but\n"
                        + "_most_ people shorten it.",
                "_[_Twelfth Night, or What You Will_]",
                "_[_most_]");
    }

    @Test
    public void strikeThroughInsideAWord() {
        assertStyles("Everyone ~dis~likes cake.", "~[~dis~]");
    }

    @Test
    public void preformattedSpansAreNotParsedFurther() {
        assertStyles("This is `monospace`", "`[`monospace`]");
        assertStyles("This is `*monospace*`", "`[`*monospace*`]");
    }

    /** "after a different opening styling directive" */
    @Test
    public void nestedSpanDirectlyAfterOpeningDirective() {
        assertStyles(
                "This is *`monospace and bold`*",
                "*[*`monospace and bold`*]",
                "`[`monospace and bold`]");
        assertStyles("*_both_*", "*[*_both_*]", "_[_both_]");
    }

    /** The same directive twice in a row does not open a nested span. */
    @Test
    public void sameDirectiveDoesNotOpenAfterItself() {
        assertStyles("**bold**");
    }

    @Test
    public void openingMustFollowWhitespaceOrStart() {
        assertStyles("snake_case_name");
        assertStyles("a*b*c");
    }

    // ---- §6.1 blocks ----

    /** Each line is its own plain block; spans may not escape it. */
    @Test
    public void plainBlocksAreLines() {
        assertStyles(
                "There are three blocks in this body, one per line,\n"
                        + "but there is no *formatting\n"
                        + "as spans* may not escape blocks.");
    }

    @Test
    public void preformattedBlock() {
        final String text =
                "```ignored\n"
                        + "(println \"Hello, world!\")\n"
                        + "```\n\n"
                        + "This should show up as monospace, preformatted text";
        assertStyles(text, "```[```ignored\n(println \"Hello, world!\")\n```]");
    }

    /** Nothing inside a preformatted block is styled. */
    @Test
    public void preformattedBlockContentIsNotParsed() {
        assertStyles("```\n*not strong*\n```", "```[```\n*not strong*\n```]");
    }

    /** Without a closing line the block runs to the end of its parent block. */
    @Test
    public void unclosedPreformattedBlockRunsToTheEnd() {
        assertStyles("```\ncode\n*still code*", "```(open)[```\ncode\n*still code*]");
    }

    /** The closing line must contain only the three grave accents. */
    @Test
    public void closingLineMustBeExact() {
        assertStyles("```\ncode ```\nmore", "```(open)[```\ncode ```\nmore]");
        assertStyles("```\ncode\n``` x\n```", "```[```\ncode\n``` x\n```]");
    }

    /**
     * A preformatted block must start at the beginning of a line: the first "```" is plain text,
     * while the last line, beginning with "```", opens a new (unclosed) block.
     */
    @Test
    public void preformattedBlockOnlyAtLineStart() {
        assertStyles("text ```\ncode\n```", "```(open)[```]");
        assertStyles("text ```\ncode");
    }

    @Test
    public void blockAfterPlainLines() {
        assertStyles(
                "*bold*\n```\ncode\n```\n_after_",
                "*[*bold*]",
                "```[```\ncode\n```]",
                "_[_after_]");
    }

    // ---- quotations ----

    @Test
    public void spansInsideAQuotation() {
        assertStyles("> That that *is*, is.\n\nSaid the old hermit", "*[*is*]");
    }

    /** The quote marker is the start of the child block, so a span may open right after it. */
    @Test
    public void spanDirectlyAfterQuoteMarker() {
        assertStyles(">*strong*", "*[*strong*]");
        assertStyles("> *strong*", "*[*strong*]");
    }

    @Test
    public void nestedQuotation() {
        assertStyles(
                ">> _That_ that is, is.\n> Said the *old* hermit\n\nWho?", "_[_That_]", "*[*old*]");
    }

    /** "No closing preformatted text sequence": the block ends with the quotation. */
    @Test
    public void preformattedBlockEndsWithItsQuotation() {
        final String text =
                "> ```\n"
                        + "> (println \"Hello, world!\")\n\n"
                        + "The entire blockquote is a preformatted text block, but this *line* is"
                        + " plaintext!";
        assertStyles(text, "```(open)[```\n> (println \"Hello, world!\")]", "*[*line*]");
    }

    @Test
    public void closedPreformattedBlockInsideAQuotation() {
        assertStyles("> ```\n> *code*\n> ```\n*after*", "```[```\n> *code*\n> ```]", "*[*after*]");
    }

    // ---- the sub-range entry point ----

    @Test
    public void subRangePositionsReferToTheWholeText() {
        final String text = "ignored *bold* tail";
        final List<ImStyleParser.Style> styles = ImStyleParser.parse(text, 8, 13);
        Assert.assertEquals(1, styles.size());
        Assert.assertEquals(8, styles.get(0).getStart());
        Assert.assertEquals(13, styles.get(0).getEnd());
    }

    /** Remote input: a long line of unclosable openers must not take quadratic time. */
    @Test(timeout = 2000)
    public void manyUnclosableOpenersStayLinear() {
        final String text = "*a _b ~c `d ".repeat(50_000);
        Assert.assertTrue(ImStyleParser.parse(text).isEmpty());
    }

    @Test
    public void emptyTextHasNoStyles() {
        Assert.assertTrue(ImStyleParser.parse("").isEmpty());
    }
}
