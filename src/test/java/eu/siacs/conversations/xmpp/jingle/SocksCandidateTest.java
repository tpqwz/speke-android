package eu.siacs.conversations.xmpp.jingle;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.jingle.transports.SocksByteStreamsTransport;
import org.junit.Assert;
import org.junit.Test;

/** S5B candidates come from the peer; a port outside 1..65535 must not reach a socket call. */
public class SocksCandidateTest {

    private static Element candidate(final String port) {
        final var element = new Element("candidate", Namespace.JINGLE_TRANSPORTS_S5B);
        element.setAttribute("cid", "c1");
        element.setAttribute("host", "192.0.2.1");
        element.setAttribute("jid", "peer@example.com/res");
        element.setAttribute("port", port);
        element.setAttribute("priority", "8257536");
        element.setAttribute("type", "direct");
        return element;
    }

    @Test
    public void validPortIsAccepted() {
        Assert.assertEquals(5222, SocksByteStreamsTransport.Candidate.of(candidate("5222")).port);
    }

    @Test
    public void outOfRangePortsAreRejected() {
        for (final String port : new String[] {"0", "-1", "65536", "70000", "x"}) {
            try {
                SocksByteStreamsTransport.Candidate.of(candidate(port));
                Assert.fail("port " + port + " should be rejected");
            } catch (final IllegalArgumentException expected) {
                // NumberFormatException is an IllegalArgumentException too
            }
        }
    }

    @Test
    public void missingTypeDefaultsToDirect() {
        final var element = candidate("5222");
        element.removeAttribute("type");
        Assert.assertEquals(
                SocksByteStreamsTransport.CandidateType.DIRECT,
                SocksByteStreamsTransport.Candidate.of(element).type);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownTypeIsRejected() {
        final var element = candidate("5222");
        element.setAttribute("type", "assisted");
        SocksByteStreamsTransport.Candidate.of(element);
    }
}
