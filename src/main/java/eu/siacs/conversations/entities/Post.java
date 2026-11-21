package eu.siacs.conversations.entities;

import static eu.siacs.conversations.parser.AbstractParser.parseTimestamp;

import android.util.Log;
import com.google.common.base.Strings;
import eu.siacs.conversations.utils.AtomText;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.utils.XmppUriEscaper;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import java.text.ParseException;
import java.util.Date;
import java.util.Objects;

public class Post {

    public static final String TABLENAME = "posts";
    public static final String UUID = "uuid";
    public static final String ACCOUNT_UUID = "account_uuid";
    public static final String AUTHOR_JID = "author_jid";
    public static final String TITLE = "title";
    public static final String CONTENT = "content";
    public static final String ATTACHMENT_URL = "attachment_url";
    public static final String ATTACHMENT_TYPE = "attachment_type";
    public static final String PUBLISHED = "published";
    public static final String COMMENTS_NODE = "comments_node";
    public static final String LINK_URL = "link_url";

    private final String id;
    private final String title;
    private final String content;
    private final Jid author;
    private final Date published;
    private final String commentsNode;
    private final String attachmentUrl;
    private final String attachmentType;
    private String linkUrl;
    // The account the post was received with (not part of equality). Remote attachments are
    // loaded with its routing, e.g. through Tor for a .onion account (RoutedUrl).
    private volatile String accountUuid;

    public String getAccountUuid() {
        return accountUuid;
    }

    public void setAccountUuid(final String accountUuid) {
        this.accountUuid = accountUuid;
    }

    public Post(
            String id,
            String title,
            String content,
            Jid author,
            Date published,
            String commentsNode,
            String attachmentUrl,
            String attachmentType,
            String linkUrl) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.author = author;
        this.published = published;
        this.commentsNode = commentsNode;
        this.attachmentUrl = attachmentUrl;
        this.attachmentType = attachmentType;
        this.linkUrl = linkUrl;
    }

    /**
     * Parses a microblog item published on {@code publisher}'s node (the PEP owner, i.e. the
     * stanza's {@code from} for events or the JID we queried for fetches). The Atom {@code
     * <author>} is free text written by whoever published the item, so it must name the publisher -
     * otherwise anyone could push a post that appears to come from one of our contacts. A missing
     * author is taken to be the publisher. Returns null on mismatch.
     */
    public static Post fromElement(final Element item, final Jid publisher) {
        if (publisher == null) {
            return null;
        }
        final Post post = fromElement(item);
        if (post == null) {
            return null;
        }
        final Jid owner = publisher.asBareJid();
        if (post.author != null && !post.author.asBareJid().equals(owner)) {
            Log.w(
                    eu.siacs.conversations.Config.LOGTAG,
                    "Post author ("
                            + post.author
                            + ") does not match publisher ("
                            + owner
                            + "). Ignoring post.");
            return null;
        }
        // Always store the (bare) publisher as the author.
        return new Post(
                post.id,
                post.title,
                post.content,
                owner,
                post.published,
                post.commentsNode,
                post.attachmentUrl,
                post.attachmentType,
                post.linkUrl);
    }

    private static Post fromElement(Element item) {
        final Element entry = item.findChild("entry", Namespace.ATOM);
        if (entry == null) {
            return null;
        }
        final String id = item.getAttribute("id");
        // Title and content are Atom text constructs: Movim and other XEP-0277 clients send
        // type="xhtml" (an XHTML <div>), whose plain element text loses paragraphs and links.
        final String title = AtomText.toMarkdown(entry.findChild("title", Namespace.ATOM));
        String content = AtomText.toMarkdown(preferredContent(entry));
        Jid author = null;
        final Element authorElement = entry.findChild("author", Namespace.ATOM);
        if (authorElement != null) {
            final String uri = authorElement.findChildContent("uri", Namespace.ATOM);
            if (uri != null && uri.startsWith("xmpp:")) {
                author = XmppUriEscaper.parseJidOrNull(uri.substring(5));
            }
        }
        // Edited entries keep <published>; entries without it are dated by <updated>.
        Date published = parseDate(entry.findChildContent("published", Namespace.ATOM));
        if (published == null) {
            published = parseDate(entry.findChildContent("updated", Namespace.ATOM));
        }

        String commentsNode = null;
        String attachmentUrl = null;
        String attachmentType = null;
        String linkUrl = null;

        for (final Element child : entry.getChildren()) {
            if (!"link".equals(child.getName()) || !Namespace.ATOM.equals(child.getNamespace())) {
                continue;
            }
            final String rel = child.getAttribute("rel");
            final String href = child.getAttribute("href");
            if (href == null || href.isEmpty()) {
                continue;
            }
            if ("replies".equals(rel)) {
                // XEP-0277 marks the comments node with title="comments"; a post may carry
                // other replies links too.
                if (commentsNode == null || "comments".equals(child.getAttribute("title"))) {
                    commentsNode = href;
                }
            } else if ("enclosure".equals(rel)) {
                // Keep the first enclosure (Movim can attach several pictures).
                if (attachmentUrl == null) {
                    attachmentUrl = href;
                    attachmentType = child.getAttribute("type");
                    if (Strings.isNullOrEmpty(attachmentType)) {
                        attachmentType =
                                MimeUtils.guessMimeTypeFromExtension(
                                        MimeUtils.extractRelevantExtension(href));
                    }
                }
            } else if ("related".equals(rel)) {
                if (linkUrl == null) {
                    linkUrl = href;
                }
            }
        }

        content = appendCategories(entry, title, content);
        return new Post(
                id,
                title,
                content,
                author,
                published,
                commentsNode,
                attachmentUrl,
                attachmentType,
                linkUrl);
    }

    /**
     * Movim and monocles publish the Markdown source as {@code type="text"} next to the rendered
     * {@code type="xhtml"} body. The source is exact, so it wins; otherwise the first other one.
     */
    private static Element preferredContent(final Element entry) {
        Element fallback = null;
        for (final Element child : entry.getChildren()) {
            if (!"content".equals(child.getName())
                    || !Namespace.ATOM.equals(child.getNamespace())) {
                continue;
            }
            final String type = child.getAttribute("type");
            if ((type == null || "text".equals(type))
                    && !Strings.isNullOrEmpty(child.getContent().trim())) {
                return child;
            }
            if (fallback == null) {
                fallback = child;
            }
        }
        return fallback;
    }

    private static Date parseDate(final String value) {
        if (value == null) {
            return null;
        }
        try {
            return new Date(parseTimestamp(value));
        } catch (final ParseException | RuntimeException e) {
            Log.d("Feeds", "Couldn't parse timestamp " + value);
            return null;
        }
    }

    /**
     * Atom {@code <category term>} tags (how Movim and XEP-0277 tag posts) are shown as hashtags at
     * the end of the content, so they are visible and searchable like inline ones.
     */
    private static String appendCategories(
            final Element entry, final String title, final String content) {
        final StringBuilder tags = new StringBuilder();
        final String existing =
                (Strings.nullToEmpty(title) + " " + Strings.nullToEmpty(content))
                        .toLowerCase(java.util.Locale.ROOT);
        for (final Element child : entry.getChildren()) {
            if (!"category".equals(child.getName())
                    || !Namespace.ATOM.equals(child.getNamespace())) {
                continue;
            }
            final String term =
                    Strings.nullToEmpty(child.getAttribute("term"))
                            .trim()
                            .replaceAll("[\\s#]+", "");
            if (term.isEmpty()
                    || existing.contains("#" + term.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            if (tags.indexOf("#" + term + " ") >= 0) {
                continue;
            }
            tags.append('#').append(term).append(' ');
        }
        if (tags.length() == 0) {
            return content;
        }
        final String tagLine = tags.toString().trim();
        return Strings.isNullOrEmpty(content) ? tagLine : content + "\n\n" + tagLine;
    }

    /** Hashtags in {@code text}, without the '#', for publishing as Atom categories. */
    public static java.util.List<String> extractHashtags(final String text) {
        final java.util.List<String> tags = new java.util.ArrayList<>();
        if (text == null) {
            return tags;
        }
        final java.util.regex.Matcher matcher = HASHTAG.matcher(text);
        while (matcher.find()) {
            final String tag = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            if (!tags.contains(tag)) {
                tags.add(tag);
            }
        }
        return tags;
    }

    private static final java.util.regex.Pattern HASHTAG =
            java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}_#@/&])#([\\p{L}\\p{N}_]+)");

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public Jid getAuthor() {
        return author;
    }

    public Date getPublished() {
        return published;
    }

    public String getCommentsNode() {
        return commentsNode;
    }

    public String getAttachmentUrl() {
        return attachmentUrl;
    }

    public String getAttachmentType() {
        return attachmentType;
    }

    public String getLinkUrl() {
        return linkUrl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Post post = (Post) o;
        // Item ids are only unique per author.
        return Objects.equals(id, post.id) && Objects.equals(bareAuthor(), post.bareAuthor());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, bareAuthor());
    }

    private Jid bareAuthor() {
        return author == null ? null : author.asBareJid();
    }

    public static Post fromCursor(android.database.Cursor cursor) {
        final String uuid = cursor.getString(cursor.getColumnIndex(UUID));
        final String authorJidStr = cursor.getString(cursor.getColumnIndex(AUTHOR_JID));
        eu.siacs.conversations.xmpp.Jid authorJid = null;
        try {
            if (authorJidStr != null) {
                authorJid = eu.siacs.conversations.xmpp.Jid.of(authorJidStr);
            }
        } catch (IllegalArgumentException e) {
            // ignore
        }
        final String title = cursor.getString(cursor.getColumnIndex(TITLE));
        final String content = cursor.getString(cursor.getColumnIndex(CONTENT));
        final String attachmentUrl = cursor.getString(cursor.getColumnIndex(ATTACHMENT_URL));
        final String attachmentType = cursor.getString(cursor.getColumnIndex(ATTACHMENT_TYPE));
        final String linkUrl = cursor.getString(cursor.getColumnIndex(LINK_URL));
        final long published = cursor.getLong(cursor.getColumnIndex(PUBLISHED));
        final String commentsNode = cursor.getString(cursor.getColumnIndex(COMMENTS_NODE));
        final Post post =
                new Post(
                        uuid,
                        title,
                        content,
                        authorJid,
                        new java.util.Date(published),
                        commentsNode,
                        attachmentUrl,
                        attachmentType,
                        linkUrl);
        final int accountColumn = cursor.getColumnIndex(ACCOUNT_UUID);
        if (accountColumn >= 0) post.setAccountUuid(cursor.getString(accountColumn));
        return post;
    }

    public android.content.ContentValues getContentValues(
            eu.siacs.conversations.entities.Account account) {
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(UUID, getId());
        values.put(ACCOUNT_UUID, account.getUuid());
        values.put(AUTHOR_JID, getAuthor() != null ? getAuthor().toString() : null);
        values.put(TITLE, getTitle());
        values.put(CONTENT, getContent());
        values.put(ATTACHMENT_URL, getAttachmentUrl());
        values.put(ATTACHMENT_TYPE, getAttachmentType());
        values.put(LINK_URL, getLinkUrl());
        values.put(PUBLISHED, getPublished() != null ? getPublished().getTime() : 0);
        values.put(COMMENTS_NODE, getCommentsNode());
        return values;
    }
}
