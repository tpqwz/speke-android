package eu.siacs.conversations.parser;

import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 * Conversations 2.21.1 (079499630) attaches a XEP-0447 element to every cleartext file message,
 * next to the old {@code jabber:x:oob} one, and marks the whole body as its fallback. This pins
 * that our reader takes the description from it exactly as that client writes it.
 */
public class UpstreamSfsCompatTest {

    private static final String URL = "https://upload.example.com/abc/IMG_2040.jpg";

    private static Element oob() {
        final Element x = new Element("x", Namespace.OOB);
        x.addChild("url", Namespace.OOB).setContent(URL);
        return x;
    }

    /** {@code <file-sharing/>} the way upstream's MessageGenerator.generateChat builds it. */
    private static Element fileSharing() {
        final Element fileSharing = new Element("file-sharing", Namespace.SFS);
        fileSharing.setAttribute("disposition", "inline");
        final Element file = fileSharing.addChild("file", Namespace.FILE_METADATA);
        file.addChild("media-type", Namespace.FILE_METADATA).setContent("image/jpeg");
        file.addChild("name", Namespace.FILE_METADATA).setContent("IMG_2040.jpg");
        file.addChild("size", Namespace.FILE_METADATA).setContent("482113");
        file.addChild("width", Namespace.FILE_METADATA).setContent("1920");
        file.addChild("height", Namespace.FILE_METADATA).setContent("1080");
        final Element sources = fileSharing.addChild("sources", Namespace.SFS);
        sources.addChild("url-data", Namespace.URL_DATA).setAttribute("target", URL);
        return fileSharing;
    }

    @Test
    public void fileDescriptionIsRead() {
        final Message.FileParams params = new Message.FileParams(fileSharing());
        Assert.assertEquals(URL, params.url);
        Assert.assertEquals(Long.valueOf(482113), params.size);
        Assert.assertEquals(1920, params.width);
        Assert.assertEquals(1080, params.height);
        Assert.assertEquals("IMG_2040.jpg", params.getName());
        Assert.assertEquals("image/jpeg", params.getMediaType());
    }

    /**
     * The stanza carries both descriptions of the one file. MessageParser collects file-sharing
     * first and jabber:x:oob afterwards into a set: it must stay one file, and the rich one.
     */
    @Test
    public void oobDuplicateDoesNotReplaceTheDescription() {
        final Set<Message.FileParams> attachments = new LinkedHashSet<>();
        attachments.add(new Message.FileParams(fileSharing()));
        attachments.add(new Message.FileParams(oob()));
        Assert.assertEquals(1, attachments.size());
        Assert.assertEquals("IMG_2040.jpg", attachments.iterator().next().getName());
    }

    // Not covered here: the whole-body fallback (<fallback for='urn:xmpp:sfs:0'><body/></fallback>)
    // is stripped in Message.getBody(), which relies on android.util.Pair - a stub on the JVM.
}
