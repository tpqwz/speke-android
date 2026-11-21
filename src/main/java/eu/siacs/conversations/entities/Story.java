package eu.siacs.conversations.entities;

import static eu.siacs.conversations.parser.AbstractParser.parseTimestamp;

import android.content.ContentValues;
import android.database.Cursor;
import android.util.Log;
import eu.siacs.conversations.utils.XmppUriEscaper;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

public class Story extends AbstractEntity {

    /**
     * Story URLs whose media the user opened in this process. A contact's story is only loaded once
     * the user taps it: fetching it on sight would tell the contact (or whoever runs the host they
     * chose) our IP address and when we looked at the list.
     */
    private static final java.util.Set<String> OPENED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Story media only over https - no other scheme, no cleartext http. */
    public static boolean isAllowedUrl(final String url) {
        if (url == null) {
            return false;
        }
        final okhttp3.HttpUrl httpUrl = okhttp3.HttpUrl.parse(url);
        return httpUrl != null && httpUrl.isHttps();
    }

    public static void markOpened(final String url) {
        if (url != null) {
            OPENED.add(url);
        }
    }

    /** Whether a preview may be loaded without a tap: our own story, or one already opened. */
    public static boolean mayLoadPreview(final Story story, final boolean own) {
        return story != null
                && isAllowedUrl(story.getUrl())
                && (own || OPENED.contains(story.getUrl()));
    }

    public static final String TABLENAME = "stories";
    public static final String CONTACT = "contact";
    public static final String URL = "url";
    public static final String TYPE = "type";
    public static final String TITLE = "title";
    public static final String PUBLISHED = "published";

    private final Jid contact;
    private final String url;
    private final String type;
    private final String title;
    private final long published;

    public Story(
            final String uuid,
            final Jid contact,
            final String url,
            final String type,
            final String title,
            final long published) {
        this.uuid = uuid;
        this.contact = contact;
        this.url = url;
        this.type = type;
        this.title = title;
        this.published = published;
    }

    public static Story fromCursor(Cursor cursor) {
        return new Story(
                cursor.getString(cursor.getColumnIndex(UUID)),
                Jid.of(cursor.getString(cursor.getColumnIndex(CONTACT))),
                cursor.getString(cursor.getColumnIndex(URL)),
                cursor.getString(cursor.getColumnIndex(TYPE)),
                cursor.getString(cursor.getColumnIndex(TITLE)),
                cursor.getLong(cursor.getColumnIndex(PUBLISHED)));
    }

    public static Story fromElement(Element item, Jid contact) {
        return fromElement(item, contact, 0);
    }

    public static Story fromElement(Element item, Jid contact, long fallbackTimestamp) {
        final Element entry = item.findChild("entry", "http://www.w3.org/2005/Atom");
        if (entry == null) {
            return null;
        }

        final Element author = entry.findChild("author", "http://www.w3.org/2005/Atom");
        if (author != null) {
            final Element uri = author.findChild("uri", "http://www.w3.org/2005/Atom");
            String authorUri = uri != null ? uri.getContent() : null;
            if (authorUri != null && authorUri.startsWith("xmpp:")) {
                final Jid authorJid = XmppUriEscaper.parseJidOrNull(authorUri.substring(5));
                if (authorJid == null) {
                    android.util.Log.w(
                            eu.siacs.conversations.Config.LOGTAG,
                            "Invalid JID in story author URI: " + authorUri);
                    return null;
                }
                if (!authorJid.asBareJid().equals(contact.asBareJid())) {
                    android.util.Log.w(
                            eu.siacs.conversations.Config.LOGTAG,
                            "Story author JID ("
                                    + authorJid
                                    + ") does not match publisher JID ("
                                    + contact
                                    + "). Ignoring story.");
                    return null;
                }
            }
        }

        Element link = null;
        final List<Element> children = entry.getChildren();
        if (children != null) {
            for (Element child : children) {
                if ("link".equals(child.getName())
                        && "enclosure".equals(child.getAttribute("rel"))) {
                    link = child;
                    break;
                }
            }
        }

        if (link == null || !isAllowedUrl(link.getAttribute("href"))) {
            return null;
        }

        long timestamp = 0;

        if (fallbackTimestamp > 0) {
            timestamp = fallbackTimestamp;
        } else {
            Element published = entry.findChild("published", "http://www.w3.org/2005/Atom");
            String publishedContent = published == null ? null : published.getContent();
            if (publishedContent != null) {
                try {
                    timestamp = parseTimestamp(publishedContent);
                } catch (ParseException e) {
                    Log.e("Story", "Couldn't parse timestamp " + publishedContent);
                }
            }
        }

        if (timestamp == 0) {
            Element updated = entry.findChild("updated", "http://www.w3.org/2005/Atom");
            String updatedContent = updated == null ? null : updated.getContent();
            if (updatedContent != null) {
                try {
                    timestamp = parseTimestamp(updatedContent);
                } catch (ParseException e) {
                }
            }
        }

        if (timestamp == 0) {
            timestamp = System.currentTimeMillis();
        }

        return new Story(
                item.getAttribute("id"),
                contact,
                link.getAttribute("href"),
                link.getAttribute("type"),
                entry.findChildContent("title", "http://www.w3.org/2005/Atom"),
                timestamp);
    }

    public static List<Story> parseFromPubSub(Element pubsub, Jid contact) {
        List<Story> stories = new ArrayList<>();
        if (pubsub == null) {
            return stories;
        }
        Element items = pubsub.findChild("items");
        if (items != null) {
            final List<Element> children = items.getChildren();
            if (children != null) {
                for (Element item : children) {
                    if (item.getName().equals("item")) {
                        long timestamp = parseTimestamp(item, 0L);
                        Story story = fromElement(item, contact, timestamp);
                        if (story != null) {
                            stories.add(story);
                        }
                    }
                }
            }
        }
        return stories;
    }

    @Override
    public ContentValues getContentValues() {
        ContentValues values = new ContentValues();
        values.put(UUID, uuid);
        values.put(CONTACT, contact.toString());
        values.put(URL, url);
        values.put(TYPE, type);
        values.put(TITLE, title);
        values.put(PUBLISHED, published);
        return values;
    }

    public Jid getContact() {
        return contact;
    }

    public String getUrl() {
        return url;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public long getPublished() {
        return published;
    }
}
