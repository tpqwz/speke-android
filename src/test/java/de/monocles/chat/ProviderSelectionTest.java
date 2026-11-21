package de.monocles.chat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * Free accounts are suggested only from the XMPP Providers A list, at random, without favouring (or
 * falling back to) any particular server.
 */
public class ProviderSelectionTest {

    private static final List<String> A_LIST =
            Arrays.asList("hookipa.net", "jabber.de", "jabber.fr", "jabber.vg", "yax.im");

    @After
    public void reset() {
        ProviderService.providers = new ArrayList<>();
    }

    @Test
    public void picksOnlyFromTheProviderList() {
        ProviderService.providers = new ArrayList<>(A_LIST);
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            final String domain = Config.DOMAIN.getRandomServer(null);
            Assert.assertTrue("not from the A list: " + domain, A_LIST.contains(domain));
            seen.add(domain);
        }
        Assert.assertEquals("every A-list server can be suggested", new HashSet<>(A_LIST), seen);
    }

    @Test
    public void neverSuggestsMonoclesEu() {
        ProviderService.providers = new ArrayList<>(A_LIST);
        for (int i = 0; i < 500; i++) {
            Assert.assertNotEquals("monocles.eu", Config.DOMAIN.getRandomServer(null));
        }
    }

    @Test
    public void noListMeansNoGuess() {
        ProviderService.providers = new ArrayList<>();
        Assert.assertNull(Config.DOMAIN.getRandomServer(null));
    }

    @Test
    public void noServerIsPinnedAsRecommended() {
        Assert.assertTrue(Config.DOMAIN.DOMAINS.isEmpty());
    }
}
