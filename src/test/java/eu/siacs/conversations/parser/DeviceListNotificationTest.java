package eu.siacs.conversations.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.xmpp.model.stanza.Iq;
import org.junit.Test;

/**
 * Pins the rules that keep OMEMO device lists from being emptied or forged by notifications that
 * don't really say what the list is:
 *
 * <ul>
 *   <li>{@link IqParser#itemCarrying}: only an item that carries the list counts; a retraction or a
 *       payload-less item must trigger a re-fetch, never an empty list.
 *   <li>{@link IqParser#isItemNotFound}: only a missing node is an empty list for a
 *       read-modify-write publish; any other failure must skip the publish (it would otherwise
 *       publish just this device and drop every other live one).
 *   <li>{@link MessageParser#pubsubEventOrigin}: only bare-JID publishers, so a MUC occupant can't
 *       publish a device list (or avatar, nick, ...) in the room's name.
 * </ul>
 */
public class DeviceListNotificationTest {

    private static final String LEGACY_NS = "eu.siacs.conversations.axolotl";

    private static Element items() {
        return new Element("items", "http://jabber.org/protocol/pubsub#event");
    }

    // ---- itemCarrying ----

    @Test
    public void anItemWithTheListIsFound() {
        final Element items = items();
        final Element item = items.addChild("item");
        item.setAttribute("id", "current");
        item.addChild("devices", Namespace.OMEMO2).addChild("device").setAttribute("id", "7");
        assertSame(item, IqParser.itemCarrying(items, "devices", Namespace.OMEMO2));
    }

    @Test
    public void aPublishedButEmptyListIsStillAList() {
        final Element items = items();
        final Element item = items.addChild("item");
        item.addChild("devices", Namespace.OMEMO2);
        assertSame(item, IqParser.itemCarrying(items, "devices", Namespace.OMEMO2));
        assertTrue(IqParser.omemo2DeviceIds(item).isEmpty());
    }

    @Test
    public void aPayloadLessItemOrARetractionCarriesNoList() {
        final Element payloadLess = items();
        payloadLess.addChild("item").setAttribute("id", "current");
        assertNull(IqParser.itemCarrying(payloadLess, "devices", Namespace.OMEMO2));

        final Element retraction = items();
        retraction.addChild("retract").setAttribute("id", "current");
        assertNull(IqParser.itemCarrying(retraction, "devices", Namespace.OMEMO2));
        assertNull(IqParser.itemCarrying(retraction, "list", null));
    }

    /** Several published items: the last one is the newest publish (same rule as the desktop). */
    @Test
    public void theNewestOfSeveralListsWins() {
        final Element items = items();
        items.addChild("item")
                .addChild("devices", Namespace.OMEMO2)
                .addChild("device")
                .setAttribute("id", "1");
        final Element newest = items.addChild("item");
        newest.addChild("devices", Namespace.OMEMO2).addChild("device").setAttribute("id", "2");
        assertSame(newest, IqParser.itemCarrying(items, "devices", Namespace.OMEMO2));
    }

    /** The OMEMO2 lookup is namespace-strict: a legacy-namespaced payload is not ours to read. */
    @Test
    public void theOmemo2LookupIgnoresOtherNamespaces() {
        final Element items = items();
        items.addChild("item").addChild("devices", "urn:xmpp:omemo:2");
        assertNull(IqParser.itemCarrying(items, "devices", Namespace.OMEMO2));
    }

    @Test
    public void theLegacyListIsFoundAndSkipsEarlierEmptyItems() {
        final Element items = items();
        items.addChild("item").setAttribute("id", "stale"); // no payload
        final Element withList = items.addChild("item");
        withList.addChild("list", LEGACY_NS).addChild("device").setAttribute("id", "42");
        assertSame(withList, IqParser.itemCarrying(items, "list", null));
        assertEquals(1, IqParser.deviceIds(withList).size());
    }

    // ---- isItemNotFound ----

    private static Iq error(final String condition) {
        final Iq iq = new Iq(Iq.Type.ERROR);
        iq.addChild("error")
                .setAttribute("type", "cancel")
                .addChild(condition, "urn:ietf:params:xml:ns:xmpp-stanzas");
        return iq;
    }

    @Test
    public void onlyAMissingNodeIsAnEmptyList() {
        assertTrue(IqParser.isItemNotFound(error("item-not-found")));
        assertFalse(IqParser.isItemNotFound(error("service-unavailable")));
        assertFalse(IqParser.isItemNotFound(error("internal-server-error")));
        assertFalse(IqParser.isItemNotFound(new Iq(Iq.Type.RESULT)));
        assertFalse(
                "a timeout leaves the list unknown",
                IqParser.isItemNotFound(new Iq(Iq.Type.TIMEOUT)));
    }

    // ---- pubsubEventOrigin ----

    private static final Jid ME = Jid.of("me@example.org");

    @Test
    public void bareJidsArePublishers() {
        final Jid contact = Jid.of("friend@example.net");
        assertEquals(contact, MessageParser.pubsubEventOrigin(contact, ME));
        final Jid service = Jid.of("pubsub.example.net");
        assertEquals(service, MessageParser.pubsubEventOrigin(service, ME));
        final Jid room = Jid.of("room@muc.example.net");
        assertEquals(room, MessageParser.pubsubEventOrigin(room, ME));
    }

    @Test
    public void aMissingFromIsOurOwnAccount() {
        assertEquals(ME, MessageParser.pubsubEventOrigin(null, ME));
    }

    /** A room occupant must not publish in the room's name, nor a resource in the account's. */
    @Test
    public void fullJidsAreRefusedNotStripped() {
        assertNull(MessageParser.pubsubEventOrigin(Jid.of("room@muc.example.net/mallory"), ME));
        assertNull(MessageParser.pubsubEventOrigin(Jid.of("friend@example.net/phone"), ME));
    }

    @Test
    public void invalidJidsAreRefused() {
        assertNull(MessageParser.pubsubEventOrigin(Jid.ofOrInvalid("@@not a jid@@"), ME));
    }
}
