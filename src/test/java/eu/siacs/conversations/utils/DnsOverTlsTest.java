package eu.siacs.conversations.utils;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLSession;
import org.junit.Assert;
import org.junit.Test;

/** Covers what the DNS-over-TLS setting accepts as a server. */
public class DnsOverTlsTest {

    @Test
    public void hostnamesAndIpLiteralsAreAccepted() {
        Assert.assertEquals("dns.quad9.net", DnsOverTls.normalize("dns.quad9.net"));
        Assert.assertEquals("one.one.one.one", DnsOverTls.normalize("one.one.one.one"));
        Assert.assertEquals("9.9.9.9", DnsOverTls.normalize("9.9.9.9"));
        Assert.assertEquals("2620:fe::fe", DnsOverTls.normalize("2620:fe::fe"));
    }

    @Test
    public void surroundingWhitespaceIsTrimmed() {
        Assert.assertEquals("dns.quad9.net", DnsOverTls.normalize("  dns.quad9.net \n"));
    }

    /** A hidden-service DoT server is never bootstrapped through the system resolver. */
    @Test
    public void hiddenServiceServersAreNotResolved() {
        Assert.assertTrue(DnsOverTls.serverAddresses("dns.abcdefghijklmnop.onion").isEmpty());
        Assert.assertTrue(DnsOverTls.serverAddresses("DNS.EXAMPLE.I2P.").isEmpty());
    }

    @Test
    public void emptyMeansNotConfigured() {
        Assert.assertNull(DnsOverTls.normalize(null));
        Assert.assertNull(DnsOverTls.normalize(""));
        Assert.assertNull(DnsOverTls.normalize("   "));
    }

    @Test
    public void invalidInputIsRejected() {
        Assert.assertNull(DnsOverTls.normalize("localhost"));
        Assert.assertNull(DnsOverTls.normalize("dns quad9.net"));
        Assert.assertNull(DnsOverTls.normalize("https://dns.quad9.net"));
        Assert.assertNull(DnsOverTls.normalize("dns.quad9.net:853"));
        Assert.assertNull(DnsOverTls.normalize(".quad9.net"));
        Assert.assertNull(DnsOverTls.normalize("dns.quad9.net."));
        Assert.assertNull(DnsOverTls.normalize("dns..quad9.net"));
        Assert.assertNull(DnsOverTls.normalize("dns.bücher.de"));
    }

    // Leaf certificate of dns.quad9.net:853 (fetched 2026-10-27). Its validity doesn't matter, the
    // name check doesn't look at it.
    private static SSLSession quad9Session() throws Exception {
        final Certificate certificate;
        try (InputStream in = DnsOverTlsTest.class.getResourceAsStream("quad9-dot.pem")) {
            certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        return (SSLSession)
                Proxy.newProxyInstance(
                        SSLSession.class.getClassLoader(),
                        new Class<?>[] {SSLSession.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getPeerCertificates")) {
                                return new Certificate[] {certificate};
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }

    @Test
    public void certificateMatchesHostnameAndIpAddresses() throws Exception {
        final SSLSession session = quad9Session();
        Assert.assertTrue(DnsOverTls.matchesCertificate("dns.quad9.net", session));
        Assert.assertTrue(DnsOverTls.matchesCertificate("DNS.Quad9.NET", session));
        Assert.assertTrue(DnsOverTls.matchesCertificate("9.9.9.9", session));
        Assert.assertTrue(DnsOverTls.matchesCertificate("2620:fe::fe", session));
    }

    @Test
    public void certificateRejectsOtherNames() throws Exception {
        final SSLSession session = quad9Session();
        Assert.assertFalse(DnsOverTls.matchesCertificate("dns.google", session));
        Assert.assertFalse(DnsOverTls.matchesCertificate("quad9.net", session));
        Assert.assertFalse(DnsOverTls.matchesCertificate("evil.dns.quad9.net", session));
        Assert.assertFalse(DnsOverTls.matchesCertificate("1.1.1.1", session));
        Assert.assertFalse(DnsOverTls.matchesCertificate("2620:fe::1", session));
    }

    @Test
    public void appDotServerBeatsAndroidPrivateDns() {
        Assert.assertEquals(
                "dns.quad9.net", DnsOverTls.select("dns.quad9.net", false, "one.one.one.one"));
        Assert.assertEquals(
                "dns.quad9.net", DnsOverTls.select(" dns.quad9.net ", true, "one.one.one.one"));
    }

    @Test
    public void appPlainDnsServerBeatsAndroidPrivateDns() {
        Assert.assertNull(DnsOverTls.select("", true, "one.one.one.one"));
    }

    @Test
    public void androidPrivateDnsIsUsedWhenTheAppHasNoDnsServer() {
        Assert.assertEquals("one.one.one.one", DnsOverTls.select("", false, "one.one.one.one"));
        Assert.assertEquals("one.one.one.one", DnsOverTls.select(null, false, "one.one.one.one"));
    }

    @Test
    public void noDotWithoutAnySetting() {
        Assert.assertNull(DnsOverTls.select("", false, null));
        Assert.assertNull(DnsOverTls.select("", false, "invalid name"));
    }
}
