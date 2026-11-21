package eu.siacs.conversations.utils;

import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * Hard-coded endpoints (manual hostname, Tor) stay trusted for TLS/DANE but are flagged so the UI
 * does not claim a DNSSEC validation that never happened.
 */
public class ResolverHardCodedTest {

    @Test
    public void fromHardCodedIsHardCodedAndStillAuthenticated() {
        final List<Resolver.Result> results = Resolver.fromHardCoded("xmpp.example.org", 5222);
        Assert.assertEquals(1, results.size());
        final Resolver.Result result = results.get(0);
        Assert.assertTrue(result.isHardCoded());
        // the configured host must remain the TLS/DANE verification target
        Assert.assertTrue(result.isAuthenticated());
        Assert.assertEquals("xmpp.example.org", result.getHostname().toString());
        Assert.assertEquals(5222, result.getPort());
        Assert.assertFalse(result.isDirectTls());
    }

    @Test
    public void fromHardCodedDirectTlsPort() {
        final Resolver.Result result = Resolver.fromHardCoded("xmpp.example.org", 5223).get(0);
        Assert.assertTrue(result.isDirectTls());
        Assert.assertTrue(result.isHardCoded());
    }

    @Test
    public void seeOtherHostIsNotHardCoded() {
        final Resolver.Result origin = Resolver.fromHardCoded("xmpp.example.org", 5222).get(0);
        final Resolver.Result redirect = origin.seeOtherHost("other.example.org:5222");
        Assert.assertNotNull(redirect);
        // a redirect target was neither configured nor validated
        Assert.assertFalse(redirect.isHardCoded());
        Assert.assertFalse(redirect.isAuthenticated());
        Assert.assertEquals("other.example.org", redirect.getHostname().toString());
    }
}
