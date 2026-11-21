package eu.siacs.conversations.entities;

import static eu.siacs.conversations.parser.AbstractParser.parseTimestamp;

import android.util.Log;
import eu.siacs.conversations.utils.XmppUriEscaper;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;
import java.text.ParseException;
import java.util.Date;

public class Comment {

    private final String id;
    private final String title;
    private final Jid author;
    private final Date published;

    public Comment(String id, String title, Jid author, Date published) {
        this.id = id;
        this.title = title;
        this.author = author;
        this.published = published;
    }

    public static Comment fromElement(Element item) {
        Element entry = item.findChild("entry", "http://www.w3.org/2005/Atom");
        if (entry == null) {
            return null;
        }
        String id = item.getAttribute("id");
        // An Atom text construct; Movim may send it as xhtml.
        String title =
                eu.siacs.conversations.utils.AtomText.toMarkdown(
                        entry.findChild("title", "http://www.w3.org/2005/Atom"));
        Element authorElement = entry.findChild("author");
        Jid author = null;
        if (authorElement != null) {
            String uri = authorElement.findChildContent("uri");
            if (uri != null && uri.startsWith("xmpp:")) {
                author = XmppUriEscaper.parseJidOrNull(uri.substring(5));
            }
        }
        // Comments nodes are open for anyone to publish to, so the Atom <author> is only a
        // claim. When the service stamps the item's publisher (pubsub#itempublisher, which we
        // enable on our own comments nodes), that is authoritative: the claim must match it.
        final Jid publisher = XmppUriEscaper.parseJidOrNull(item.getAttribute("publisher"));
        if (publisher != null) {
            if (author == null) {
                author = publisher.asBareJid();
            } else if (!author.asBareJid().equals(publisher.asBareJid())) {
                Log.w(
                        eu.siacs.conversations.Config.LOGTAG,
                        "Comment author ("
                                + author
                                + ") does not match publisher ("
                                + publisher
                                + "). Ignoring comment.");
                return null;
            }
        }
        Date published = null;
        String publishedString = entry.findChildContent("published");
        if (publishedString == null) {
            publishedString = entry.findChildContent("updated");
        }
        if (publishedString != null) {
            try {
                published = new Date(parseTimestamp(publishedString));
            } catch (ParseException e) {
                Log.e("Feeds", "Couldn't parse timestamp " + publishedString);
            }
        }
        return new Comment(id, title, author, published);
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Jid getAuthor() {
        return author;
    }

    public Date getPublished() {
        return published;
    }
}
