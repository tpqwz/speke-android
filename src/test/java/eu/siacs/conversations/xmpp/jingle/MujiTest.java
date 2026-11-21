package eu.siacs.conversations.xmpp.jingle;

import eu.siacs.conversations.xml.Element;
import org.junit.Assert;
import org.junit.Test;

/**
 * XEP-0272 Muji stanza helpers + the per-leg OMEMO verification bookkeeping. These run on wire
 * content from other room occupants, so malformed input must degrade rather than throw.
 */
public class MujiTest {

    private static Element presenceWith(final Element muji) {
        final Element presence = new Element("presence");
        presence.addChild(muji);
        return presence;
    }

    @Test
    public void readyPayloadRoundTrips() {
        final Muji.Advertisement ad = Muji.parse(presenceWith(Muji.payload(true, true, 12345)));
        Assert.assertNotNull(ad);
        Assert.assertEquals(Muji.State.READY, ad.state);
        Assert.assertEquals(Integer.valueOf(12345), ad.deviceId);
    }

    @Test
    public void preparingPayloadIsNotReady() {
        final Muji.Advertisement ad = Muji.parse(presenceWith(Muji.payload(false, false, null)));
        Assert.assertNotNull(ad);
        Assert.assertEquals(Muji.State.PREPARING, ad.state);
        Assert.assertNull(ad.deviceId);
    }

    @Test
    public void noMujiMeansNotParticipating() {
        Assert.assertNull(Muji.parse(new Element("presence")));
    }

    @Test
    public void invalidDeviceIdsAreDropped() {
        for (final String bad : new String[] {"0", "-5", "abc", "99999999999"}) {
            final Element muji = Muji.payload(true, false, null);
            muji.setAttribute("device", bad);
            final Muji.Advertisement ad = Muji.parse(presenceWith(muji));
            Assert.assertNotNull(ad);
            Assert.assertNull("device=" + bad, ad.deviceId);
        }
    }

    @Test
    public void tieBreakPicksExactlyOneInitiator() {
        final String a = "room@muc.example/alice";
        final String b = "room@muc.example/bob";
        Assert.assertNotEquals(Muji.shouldInitiate(a, b), Muji.shouldInitiate(b, a));
        Assert.assertFalse(Muji.shouldInitiate(a, a));
        // the room's spelling must not decide it — only the nicks
        Assert.assertEquals(
                Muji.shouldInitiate("Room@MUC.example/bob", "room@muc.example/alice"),
                Muji.shouldInitiate("room@muc.example/bob", "room@muc.example/alice"));
        Assert.assertTrue(Muji.shouldInitiate("Room@muc.example/bob", "room@muc.example/alice"));
        Assert.assertFalse(Muji.shouldInitiate("room@muc.example/alice", "Room@muc.example/bob"));
    }

    @Test
    public void preSetDeviceIdMismatchLeavesNoFingerprint() {
        final OmemoVerification verification = new OmemoVerification();
        verification.setDeviceId(1);
        Assert.assertThrows(SecurityException.class, () -> verification.setOrEnsureEqual(2, "fp"));
        Assert.assertFalse(verification.hasFingerprint());
        verification.setOrEnsureEqual(1, "fp");
        Assert.assertEquals("fp", verification.getFingerprint());
    }

    @Test
    public void laterFingerprintMustMatch() {
        final OmemoVerification verification = new OmemoVerification();
        verification.setOrEnsureEqual(7, "fp");
        verification.setOrEnsureEqual(7, "fp");
        Assert.assertThrows(
                SecurityException.class, () -> verification.setOrEnsureEqual(7, "other"));
    }
}
