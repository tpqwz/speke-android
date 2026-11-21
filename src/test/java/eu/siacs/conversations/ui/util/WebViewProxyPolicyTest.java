package eu.siacs.conversations.ui.util;

import static eu.siacs.conversations.http.HttpConnectionManager.Route.DIRECT;
import static eu.siacs.conversations.http.HttpConnectionManager.Route.I2P;
import static eu.siacs.conversations.http.HttpConnectionManager.Route.TOR;

import eu.siacs.conversations.http.HttpConnectionManager;
import org.junit.Assert;
import org.junit.Test;

/** Which requests of remote WebView pages may leave, given the proxy override in effect. */
public class WebViewProxyPolicyTest {

    private static final HttpConnectionManager.Route[] ALL = {TOR, I2P, DIRECT, null};

    @Test
    public void hiddenHostsOnlyThroughTheirOwnOverlay() {
        for (final boolean pageIsLocal : new boolean[] {false, true}) {
            Assert.assertTrue(WebViewProxy.requestAllowed("abcdef.onion", TOR, pageIsLocal));
            Assert.assertFalse(WebViewProxy.requestAllowed("abcdef.onion", I2P, pageIsLocal));
            Assert.assertFalse(WebViewProxy.requestAllowed("abcdef.onion", DIRECT, pageIsLocal));
            Assert.assertFalse(
                    WebViewProxy.requestAllowed(
                            "abcdef.onion", null, pageIsLocal)); // override changing
            Assert.assertTrue(WebViewProxy.requestAllowed("site.i2p", I2P, pageIsLocal));
            Assert.assertFalse(WebViewProxy.requestAllowed("SITE.I2P.", TOR, pageIsLocal));
            Assert.assertFalse(WebViewProxy.requestAllowed("site.i2p", DIRECT, pageIsLocal));
        }
    }

    @Test
    public void clearnetHostsFollowTheOverride() {
        for (final var route : ALL) {
            Assert.assertTrue(WebViewProxy.requestAllowed("example.org", route, false));
            Assert.assertTrue(WebViewProxy.requestAllowed("93.184.216.34", route, false));
        }
    }

    /**
     * A remote page must not reach local services; a self-hosted LAN page may load its own
     * resources.
     */
    @Test
    public void localTargetsOnlyFromLocalPages() {
        for (final String local :
                new String[] {
                    "localhost", "printer.local", "127.0.0.1", "192.168.1.1", "[fd00::1]"
                }) {
            for (final var route : ALL) {
                Assert.assertFalse(local, WebViewProxy.requestAllowed(local, route, false));
                Assert.assertTrue(local, WebViewProxy.requestAllowed(local, route, true));
            }
        }
    }
}
