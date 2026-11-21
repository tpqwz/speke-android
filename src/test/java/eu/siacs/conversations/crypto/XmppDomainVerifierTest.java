package eu.siacs.conversations.crypto;

import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;

/** Covers certificate name matching (RFC 6125 §6.4.3 wildcards). */
public class XmppDomainVerifierTest {

    @Test
    public void exactNameMatches() {
        Assert.assertTrue(
                XmppDomainVerifier.matchDomain("example.org", Arrays.asList("example.org")));
        Assert.assertTrue(
                XmppDomainVerifier.matchDomain("Example.ORG", Arrays.asList("example.org")));
        Assert.assertFalse(
                XmppDomainVerifier.matchDomain("evil.org", Arrays.asList("example.org")));
    }

    @Test
    public void wildcardMatchesExactlyOneLeftmostLabel() {
        Assert.assertTrue(
                XmppDomainVerifier.matchDomain("chat.example.org", Arrays.asList("*.example.org")));
        Assert.assertFalse(
                XmppDomainVerifier.matchDomain("a.b.example.org", Arrays.asList("*.example.org")));
        Assert.assertFalse(
                XmppDomainVerifier.matchDomain("example.org", Arrays.asList("*.example.org")));
    }

    /** "*.org" must not match every .org host. */
    @Test
    public void wildcardNeedsAtLeastTwoLabels() {
        Assert.assertFalse(XmppDomainVerifier.matchDomain("example.org", Arrays.asList("*.org")));
        // a rejected wildcard entry must not stop later, valid entries from matching
        Assert.assertTrue(
                XmppDomainVerifier.matchDomain(
                        "example.org", Arrays.asList("*.org", "example.org")));
    }
}
