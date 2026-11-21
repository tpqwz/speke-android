package eu.siacs.conversations.xmpp;

import com.google.common.collect.ImmutableList;
import org.junit.Assert;
import org.junit.Test;

public class OAuthAuthorizationTest {

    private static final Jid ALICE = Jid.of("alice@example.com");

    private static OAuthAuthorization.OpenIdConfiguration configuration() {
        final var configuration = new OAuthAuthorization.OpenIdConfiguration();
        configuration.authorizationEndpoint = "https://idp.example.com/authorize";
        configuration.tokenEndpoint = "https://idp.example.com/token";
        configuration.registrationEndpoint = "https://idp.example.com/register";
        return configuration;
    }

    private static OAuthAuthorization.ClientRegistrationResponse registration() {
        final var registration = new OAuthAuthorization.ClientRegistrationResponse();
        registration.clientId = "client-1";
        registration.clientSecret = "secret";
        registration.tokenEndpointAuthMethod = "client_secret_basic";
        registration.redirectUris = ImmutableList.of(OAuthAuthorization.REDIRECT_URI);
        return registration;
    }

    private static void assertRefused(final String url) {
        try {
            OAuthAuthorization.requireEndpoint(url, "endpoint");
            Assert.fail(url + " should be refused");
        } catch (final IllegalStateException expected) {
            // refused
        }
    }

    @Test
    public void httpsEndpointIsAccepted() {
        Assert.assertEquals(
                "idp.example.com",
                OAuthAuthorization.requireEndpoint("https://idp.example.com/x", "endpoint").host());
    }

    @Test
    public void unsafeEndpointsAreRefused() {
        assertRefused(null);
        assertRefused("");
        assertRefused("not a url");
        assertRefused("http://idp.example.com/x");
        assertRefused("ftp://idp.example.com/x");
        assertRefused("https://localhost/x");
        assertRefused("https://127.0.0.1/x");
        assertRefused("https://192.168.1.1/x");
        assertRefused("https://10.0.0.5/x");
        assertRefused("https://[::1]/x");
        assertRefused("https://router.local/x");
        assertRefused("https://user:pass@idp.example.com/x");
    }

    @Test
    public void stateFindsItsAccount() {
        final String state =
                OAuthAuthorization.buildState(Jid.of("alice@example.com/phone"), new byte[32]);
        Assert.assertEquals(ALICE, OAuthAuthorization.accountFromState(state));
    }

    @Test
    public void foreignStateHasNoAccount() {
        Assert.assertNull(OAuthAuthorization.accountFromState(null));
        Assert.assertNull(OAuthAuthorization.accountFromState(""));
        Assert.assertNull(OAuthAuthorization.accountFromState("***"));
        // no separator
        Assert.assertNull(OAuthAuthorization.accountFromState("YWxpY2VAZXhhbXBsZS5jb20"));
        // separator first: empty address
        Assert.assertNull(OAuthAuthorization.accountFromState("AGFiYw"));
    }

    /** RFC 7636 appendix B. */
    @Test
    public void pkceChallengeMatchesTheRfc() {
        Assert.assertEquals(
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                OAuthAuthorization.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    public void authorizationUrlCarriesPkceAndOurRedirect() {
        final byte[] verifier = new byte[32];
        java.util.Arrays.fill(verifier, (byte) 0x5a);
        final var pending =
                OAuthAuthorization.buildPendingAuthorization(
                        ALICE, configuration(), registration(), new byte[32], verifier);
        final var url = pending.authorizationUrl;
        Assert.assertEquals("idp.example.com", url.host());
        Assert.assertEquals("code", url.queryParameter("response_type"));
        Assert.assertEquals("client-1", url.queryParameter("client_id"));
        Assert.assertEquals(OAuthAuthorization.REDIRECT_URI, url.queryParameter("redirect_uri"));
        Assert.assertEquals("S256", url.queryParameter("code_challenge_method"));
        Assert.assertEquals(
                OAuthAuthorization.codeChallenge(pending.codeVerifier),
                url.queryParameter("code_challenge"));
        Assert.assertEquals(pending.state, url.queryParameter("state"));
        Assert.assertEquals(ALICE, OAuthAuthorization.accountFromState(pending.state));
        // the verifier itself never goes to the browser
        Assert.assertFalse(url.toString().contains(pending.codeVerifier));
    }

    @Test(expected = IllegalStateException.class)
    public void providerCannotSwapTheRedirect() {
        final var registration = registration();
        registration.redirectUris = ImmutableList.of("https://evil.example.com/cb");
        OAuthAuthorization.buildPendingAuthorization(
                ALICE, configuration(), registration, new byte[32], new byte[32]);
    }

    @Test(expected = IllegalStateException.class)
    public void plainHttpAuthorizationEndpointIsRefused() {
        final var configuration = configuration();
        configuration.authorizationEndpoint = "http://idp.example.com/authorize";
        OAuthAuthorization.buildPendingAuthorization(
                ALICE, configuration, registration(), new byte[32], new byte[32]);
    }

    @Test(expected = IllegalStateException.class)
    public void registrationWithoutClientIdIsRefused() {
        final var registration = registration();
        registration.clientId = "";
        OAuthAuthorization.buildPendingAuthorization(
                ALICE, configuration(), registration, new byte[32], new byte[32]);
    }

    private static java.net.InetAddress ip(final String address) {
        return com.google.common.net.InetAddresses.forString(address);
    }

    private static final java.net.InetAddress LAN_SERVER = ip("192.168.1.10");

    @Test
    public void publicTargetsAreAlwaysAllowed() {
        Assert.assertTrue(OAuthAuthorization.isAllowedTarget(ip("203.0.113.5"), null));
        Assert.assertTrue(OAuthAuthorization.isAllowedTarget(ip("203.0.113.5"), LAN_SERVER));
        Assert.assertTrue(OAuthAuthorization.isAllowedHost("idp.example.com", null));
    }

    @Test
    public void localTargetsNeedALocalServer() {
        // no server (proxied connection) or a public server: nothing local
        for (final var server : new java.net.InetAddress[] {null, ip("203.0.113.5")}) {
            Assert.assertFalse(OAuthAuthorization.isAllowedTarget(ip("192.168.1.20"), server));
            Assert.assertFalse(OAuthAuthorization.isAllowedTarget(ip("10.0.0.5"), server));
            Assert.assertFalse(OAuthAuthorization.isAllowedTarget(ip("fd00::5"), server));
            Assert.assertFalse(OAuthAuthorization.isAllowedHost("idp.local", server));
            Assert.assertFalse(OAuthAuthorization.isAllowedHost("[fd00::5]", server));
        }
    }

    @Test
    public void localServerUnlocksTheLocalNetwork() {
        Assert.assertTrue(OAuthAuthorization.isAllowedTarget(ip("192.168.1.20"), LAN_SERVER));
        Assert.assertTrue(OAuthAuthorization.isAllowedTarget(ip("10.0.0.5"), LAN_SERVER));
        Assert.assertTrue(OAuthAuthorization.isAllowedTarget(ip("fd00::5"), LAN_SERVER));
        Assert.assertTrue(OAuthAuthorization.isAllowedTarget(ip("100.64.1.2"), LAN_SERVER));
        Assert.assertTrue(OAuthAuthorization.isAllowedTarget(ip("100.64.1.2"), ip("100.100.1.1")));
        Assert.assertTrue(OAuthAuthorization.isAllowedHost("idp.local", LAN_SERVER));
        Assert.assertTrue(OAuthAuthorization.isAllowedHost("192.168.1.20", LAN_SERVER));
        Assert.assertEquals(
                "192.168.1.20",
                OAuthAuthorization.requireEndpoint("https://192.168.1.20/x", "endpoint", LAN_SERVER)
                        .host());
    }

    @Test
    public void deviceLoopbackOnlyForALoopbackServer() {
        Assert.assertFalse(OAuthAuthorization.isAllowedTarget(ip("127.0.0.1"), LAN_SERVER));
        Assert.assertFalse(OAuthAuthorization.isAllowedTarget(ip("::1"), LAN_SERVER));
        Assert.assertFalse(OAuthAuthorization.isAllowedTarget(ip("::ffff:127.0.0.1"), LAN_SERVER));
        Assert.assertFalse(OAuthAuthorization.isAllowedHost("localhost", LAN_SERVER));
        Assert.assertTrue(OAuthAuthorization.isAllowedTarget(ip("127.0.0.1"), ip("127.0.0.1")));
        Assert.assertTrue(OAuthAuthorization.isAllowedHost("localhost", ip("::1")));
    }

    @Test
    public void shorthandNumericHostsNeverAllowed() {
        for (final String host : new String[] {"127.1", "127.0.1", "2130706433", "1.2.3"}) {
            Assert.assertFalse(host, OAuthAuthorization.isAllowedHost(host, null));
            Assert.assertFalse(host, OAuthAuthorization.isAllowedHost(host, LAN_SERVER));
            Assert.assertFalse(host, OAuthAuthorization.isAllowedHost(host, ip("127.0.0.1")));
        }
        assertRefused("https://127.1/x");
        assertRefused("https://2130706433/x");
        Assert.assertTrue(OAuthAuthorization.isAllowedHost("192.168.1.20", LAN_SERVER));
    }

    @Test
    public void multicastAndUnspecifiedNeverAllowed() {
        Assert.assertFalse(OAuthAuthorization.isAllowedTarget(ip("0.0.0.0"), LAN_SERVER));
        Assert.assertFalse(OAuthAuthorization.isAllowedTarget(ip("224.0.0.1"), LAN_SERVER));
        Assert.assertFalse(OAuthAuthorization.isAllowedTarget(ip("ff02::1"), LAN_SERVER));
    }

    @Test
    public void localServerDoesNotRelaxHttpsOrCredentials() {
        for (final String url :
                new String[] {"http://192.168.1.20/x", "https://user:pw@192.168.1.20/x"}) {
            try {
                OAuthAuthorization.requireEndpoint(url, "endpoint", LAN_SERVER);
                Assert.fail(url + " should be refused");
            } catch (final IllegalStateException expected) {
                // refused
            }
        }
    }

    @Test(expected = IllegalStateException.class)
    public void lanAuthorizationEndpointRefusedWithoutLocalServer() {
        final var configuration = configuration();
        configuration.authorizationEndpoint = "https://192.168.1.20/authorize";
        OAuthAuthorization.buildPendingAuthorization(
                ALICE, configuration, registration(), new byte[32], new byte[32], null);
    }

    @Test
    public void lanAuthorizationEndpointAllowedWithLocalServer() {
        final var configuration = configuration();
        configuration.authorizationEndpoint = "https://192.168.1.20/authorize";
        final var pending =
                OAuthAuthorization.buildPendingAuthorization(
                        ALICE, configuration, registration(), new byte[32], new byte[32], LAN_SERVER);
        Assert.assertEquals("192.168.1.20", pending.authorizationUrl.host());
    }
}
