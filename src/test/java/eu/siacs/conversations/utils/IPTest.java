package eu.siacs.conversations.utils;

import org.junit.Assert;
import org.junit.Test;

/** IP literal detection (Guava based, Conversations 4512a4aa3). */
public class IPTest {

    @Test
    public void detectsLiterals() {
        Assert.assertTrue(IP.matches("192.0.2.1"));
        Assert.assertTrue(IP.matches("::1"));
        Assert.assertTrue(IP.matches("2001:db8::1"));
        // the old regex had a stray space and never matched this form
        Assert.assertTrue(IP.matches("::ffff:192.0.2.1"));
    }

    @Test
    public void rejectsNonLiterals() {
        Assert.assertFalse(IP.matches(null));
        Assert.assertFalse(IP.matches(""));
        Assert.assertFalse(IP.matches("example.org"));
        Assert.assertFalse(IP.matches("256.0.0.1"));
        Assert.assertFalse(IP.matches("[::1]"));
    }

    @Test
    public void wrapsOnlyIpv6() {
        Assert.assertEquals("[2001:db8::1]", IP.wrapIPv6("2001:db8::1"));
        Assert.assertEquals("192.0.2.1", IP.wrapIPv6("192.0.2.1"));
        Assert.assertEquals("example.org", IP.wrapIPv6("example.org"));
        Assert.assertEquals("::1", IP.unwrapIPv6("[::1]"));
    }
}
