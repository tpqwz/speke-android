package eu.siacs.conversations.xmpp.jingle;

import com.google.common.io.BaseEncoding;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.jingle.transports.InbandBytestreamsTransport;
import java.io.InputStream;
import org.junit.Assert;
import org.junit.Test;

/** Incoming IBB data blocks are peer-controlled: bad ones get an error, never an exception. */
public class IbbReceiveTest {

    private static final Jid PEER = Jid.of("peer@example.com/res");

    private static Element data(final String content) {
        final var data = new Element("data", Namespace.IBB);
        data.setAttribute("sid", "s1");
        data.setAttribute("seq", 0);
        data.setContent(content);
        return data;
    }

    private static InbandBytestreamsTransport transport() {
        // the XmppConnection is only used for sending, which this test never does
        return new InbandBytestreamsTransport(null, PEER, false, "s1", 4096);
    }

    @Test
    public void malformedBase64IsRefused() {
        Assert.assertFalse(
                transport()
                        .deliverPacket(
                                InbandBytestreamsTransport.PacketType.DATA, PEER, data("@@@@")));
    }

    @Test
    public void oversizedBlockIsRefused() {
        final String huge = BaseEncoding.base64().encode(new byte[64 * 1024]);
        Assert.assertFalse(
                transport()
                        .deliverPacket(
                                InbandBytestreamsTransport.PacketType.DATA, PEER, data(huge)));
    }

    @Test
    public void validBlockIsAccepted() throws Exception {
        final var transport = transport();
        final InputStream in = transport.getInputStream();
        Assert.assertTrue(
                transport.deliverPacket(
                        InbandBytestreamsTransport.PacketType.DATA,
                        PEER,
                        data(BaseEncoding.base64().encode(new byte[] {1, 2, 3}))));
        Assert.assertEquals(1, in.read());
    }

    @Test
    public void wrongSenderIsRefused() {
        Assert.assertFalse(
                transport()
                        .deliverPacket(
                                InbandBytestreamsTransport.PacketType.DATA,
                                Jid.of("mallory@example.com/x"),
                                data("AQID")));
    }
}
