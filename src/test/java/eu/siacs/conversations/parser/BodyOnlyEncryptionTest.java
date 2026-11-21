package eu.siacs.conversations.parser;

import eu.siacs.conversations.generator.MessageGenerator;
import eu.siacs.conversations.xml.Element;
import org.junit.Assert;
import org.junit.Test;

/**
 * Legacy OMEMO / PGP encrypt only the body: what rides outside may not change what the decrypted
 * body shows.
 */
public class BodyOnlyEncryptionTest {

    private static Element replyFallback(final String start, final String end) {
        final Element fallback = new Element("fallback", "urn:xmpp:fallback:0");
        fallback.setAttribute("for", "urn:xmpp:reply:0");
        final Element span = fallback.addChild("body", "urn:xmpp:fallback:0");
        if (start != null) span.setAttribute("start", start);
        if (end != null) span.setAttribute("end", end);
        return fallback;
    }

    @Test
    public void realQuoteIsAccepted() {
        final String body = "> quoted line\n> second\n\nmy answer";
        final int quoteEnd = "> quoted line\n> second\n\n".length();
        Assert.assertTrue(
                MessageParser.isQuoteFallback(body, replyFallback("0", String.valueOf(quoteEnd))));
        Assert.assertTrue(MessageParser.isQuoteFallback("» quote\nanswer", "0", "8"));
    }

    @Test
    public void rangeWithoutBoundsIsRefused() {
        // would hide the whole decrypted body
        Assert.assertFalse(MessageParser.isQuoteFallback("secret", replyFallback(null, null)));
        Assert.assertFalse(MessageParser.isQuoteFallback("secret", replyFallback("0", null)));
    }

    @Test
    public void cuttingRealTextIsRefused() {
        Assert.assertFalse(MessageParser.isQuoteFallback("Hello world", "0", "5"));
        Assert.assertFalse(MessageParser.isQuoteFallback("> q\nHello", "0", "9"));
        Assert.assertFalse(MessageParser.isQuoteFallback("> q\nHello", "2", "3"));
        Assert.assertFalse(MessageParser.isQuoteFallback("> q", "0", "99"));
        Assert.assertFalse(MessageParser.isQuoteFallback("\n\n", "0", "2"));
        Assert.assertFalse(MessageParser.isQuoteFallback("> q", "0", "x"));
        Assert.assertFalse(MessageParser.isQuoteFallback("> q", "-1", "3"));
    }

    @Test
    public void linkCardsAndAttentionAreRefused() {
        Assert.assertTrue(
                MessageParser.isRefusedBesideBodyOnlyEncryption(
                        "Description", "http://www.w3.org/1999/02/22-rdf-syntax-ns#", null, true));
        Assert.assertTrue(
                MessageParser.isRefusedBesideBodyOnlyEncryption(
                        "Description", "http://www.w3.org/1999/02/22-rdf-syntax-ns#", null, false));
        Assert.assertTrue(
                MessageParser.isRefusedBesideBodyOnlyEncryption(
                        "attention", "urn:xmpp:attention:0", null, true));
    }

    @Test
    public void onlyTheRetractionTextMakesARetraction() {
        Assert.assertTrue(
                MessageParser.isLegacyRetractionBody(MessageGenerator.RETRACTION_FALLBACK_BODY));
        Assert.assertTrue(
                MessageParser.isLegacyRetractionBody(
                        " " + MessageGenerator.RETRACTION_FALLBACK_BODY + "\n"));
        Assert.assertTrue(
                MessageParser.isLegacyRetractionBody(
                        "/me retracted a previous message, but it's unsupported by your client."));
        Assert.assertFalse(MessageParser.isLegacyRetractionBody("an ordinary message"));
        Assert.assertFalse(
                MessageParser.isLegacyRetractionBody("I /me retracted a previous message"));
        Assert.assertFalse(MessageParser.isLegacyRetractionBody(null));
    }
}
