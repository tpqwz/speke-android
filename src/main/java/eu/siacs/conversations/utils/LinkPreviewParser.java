package eu.siacs.conversations.utils;

import androidx.annotation.Nullable;
import eu.siacs.conversations.xml.Element;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * Turns the OpenGraph metadata of an HTML page into the rdf:Description payload that is attached to
 * an outgoing message as its link preview. Pure parsing: the page is fetched (through the
 * Tor/I2P-aware HTTP client) by XmppConnectionService.
 */
public final class LinkPreviewParser {

    public static final String OGP_NS = "https://ogp.me/ns#";
    public static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

    private LinkPreviewParser() {}

    /**
     * @param html the (possibly truncated) page
     * @param baseUrl the URL the page was finally served from, for resolving relative URLs
     * @param about the link as written in the message (rdf:about)
     * @return the description, or null if the page has neither a title nor a description
     */
    @Nullable
    public static Element describe(final String html, final String baseUrl, final String about) {
        final Document document = Jsoup.parse(html, baseUrl);
        final String title = ogContent(document, "og:title");
        String description = ogContent(document, "og:description");
        if (description == null) {
            final var meta = document.selectFirst("meta[name=description]");
            if (meta != null && !meta.attr("content").trim().isEmpty()) {
                description = meta.attr("content").trim();
            }
        }
        final String ogUrl = ogUrl(document, "og:url");
        final String image = ogUrl(document, "og:image");
        final String type = ogContent(document, "og:type");
        final String siteName = ogContent(document, "og:site_name");
        String video = ogUrl(document, "og:video:secure_url");
        if (video == null) video = ogUrl(document, "og:video:url");
        if (video == null) video = ogUrl(document, "og:video");
        if (title == null && description == null) return null;
        final Element rdf = new Element("Description", RDF_NS);
        rdf.setAttribute("xmlns:rdf", RDF_NS);
        rdf.setAttribute("rdf:about", about);
        if (title != null) rdf.addChild("title", OGP_NS).setContent(title);
        if (description != null) rdf.addChild("description", OGP_NS).setContent(description);
        if (ogUrl != null) rdf.addChild("url", OGP_NS).setContent(ogUrl);
        if (image != null) rdf.addChild("image", OGP_NS).setContent(image);
        if (type != null) rdf.addChild("type", OGP_NS).setContent(type);
        if (siteName != null) rdf.addChild("site_name", OGP_NS).setContent(siteName);
        if (video != null) rdf.addChild("video", OGP_NS).setContent(video);
        return rdf;
    }

    /**
     * A URL-valued property, resolved against the page's base URL (relative values are allowed by
     * OpenGraph in practice), and only if it is http(s): the receiver shows and opens it, so no
     * javascript:, intent:, file: or data: URLs.
     */
    @Nullable
    private static String ogUrl(final Document document, final String property) {
        for (final var meta : document.select("meta[property]")) {
            if (!property.equalsIgnoreCase(meta.attr("property"))) continue;
            if (meta.attr("content").trim().isEmpty()) continue;
            final String resolved = meta.absUrl("content").trim();
            if (resolved.isEmpty()) continue;
            final String lower = resolved.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("https://") || lower.startsWith("http://")) return resolved;
        }
        return null;
    }

    @Nullable
    private static String ogContent(final Document document, final String property) {
        for (final var meta : document.select("meta[property]")) {
            if (property.equalsIgnoreCase(meta.attr("property"))) {
                final String content = meta.attr("content").trim();
                if (!content.isEmpty()) return content;
            }
        }
        return null;
    }
}
