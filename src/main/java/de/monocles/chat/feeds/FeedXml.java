package de.monocles.chat.feeds;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * RSS and Atom, parsed with no network and no Android types. A feed is untrusted XML: the parser
 * refuses a DOCTYPE and external entities, and the caller caps the bytes before they get here.
 */
public final class FeedXml {

    public static final int MAX_ITEMS = 30;
    public static final int MAX_BYTES = 1_000_000;

    public static final class Item {
        public final String title;
        public final String link;
        public final String audio;
        public final String when;

        Item(String title, String link, String audio, String when) {
            this.title = title;
            this.link = link;
            this.audio = audio;
            this.when = when;
        }
    }

    public static final class Feed {
        public final String title;
        public final List<Item> items;

        Feed(String title, List<Item> items) {
            this.title = title;
            this.items = items;
        }
    }

    private FeedXml() {}

    public static Feed parse(byte[] xml, String baseUrl) {
        if (xml == null || xml.length == 0 || xml.length > MAX_BYTES || hasDoctype(xml)) {
            throw new IllegalArgumentException("feed xml");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            // Android's parser throws ParserConfigurationException for these feature names. The
            // DOCTYPE scan above, and the empty entity resolver below, still apply there.
            feature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
            feature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            feature(factory, "http://xml.org/sax/features/external-general-entities", false);
            feature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            try {
                factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
                factory.setAttribute(
                        "http://javax.xml.XMLConstants/property/accessExternalSchema", "");
            } catch (IllegalArgumentException ignored) {
            }
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            Element root =
                    builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
            if (root == null) {
                throw new IllegalArgumentException("feed xml");
            }
            Element channel = child(root, "channel");
            Element scope = channel != null ? channel : root;
            String title = strip(text(scope, "title"));
            List<Element> nodes = items(scope);
            if (nodes.isEmpty() && scope != root) {
                nodes = items(root);
            }
            List<Item> items = new ArrayList<>();
            for (Element node : nodes) {
                if (items.size() == MAX_ITEMS) {
                    break;
                }
                Item item = item(node, baseUrl);
                if (item.title.isEmpty() && item.link == null && item.audio == null) {
                    continue;
                }
                items.add(item);
            }
            return new Feed(title, items);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("feed xml", e);
        }
    }

    private static Item item(Element node, String baseUrl) {
        String link = null;
        String audio = null;
        for (Element child : elements(node)) {
            String name = local(child);
            if ("link".equals(name)) {
                String href = child.getAttribute("href");
                String rel = child.getAttribute("rel");
                if (href != null && !href.isBlank()) {
                    if ("enclosure".equalsIgnoreCase(rel)) {
                        if (audio == null && audioRef(child.getAttribute("type"), href)) {
                            audio = resolve(baseUrl, href);
                        }
                    } else if (link == null
                            && (rel == null || rel.isBlank() || "alternate".equalsIgnoreCase(rel))) {
                        link = resolve(baseUrl, href);
                    }
                } else if (link == null) {
                    link = resolve(baseUrl, child.getTextContent());
                }
            } else if ("enclosure".equals(name) || "content".equals(name)) {
                String url = child.getAttribute("url");
                if (audio == null && url != null && !url.isBlank() && audioRef(child.getAttribute("type"), url)) {
                    audio = resolve(baseUrl, url);
                }
            }
        }
        String when = text(node, "pubDate");
        if (when == null || when.isBlank()) {
            when = text(node, "published");
        }
        if (when == null || when.isBlank()) {
            when = text(node, "updated");
        }
        return new Item(strip(text(node, "title")), link, audio, when == null ? "" : strip(when));
    }

    private static void feature(DocumentBuilderFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (Exception ignored) {
        }
    }

    /** A DOCTYPE is refused before the parser sees it. Android cannot disable that feature by name. */
    private static boolean hasDoctype(byte[] xml) {
        byte[] needle = "<!doctype".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i + needle.length <= xml.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                int b = xml[i + j] & 0xff;
                if (b >= 'A' && b <= 'Z') {
                    b += 32;
                }
                if (b != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    static boolean audioRef(String type, String url) {
        if (type != null && type.toLowerCase(Locale.ROOT).trim().startsWith("audio/")) {
            return true;
        }
        String path = path(url);
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3")
                || lower.endsWith(".m4a")
                || lower.endsWith(".ogg")
                || lower.endsWith(".opus")
                || lower.endsWith(".aac")
                || lower.endsWith(".wav");
    }

    private static String path(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String raw = URI.create(url.trim()).getPath();
            return raw == null ? url.trim() : raw;
        } catch (IllegalArgumentException e) {
            int query = url.indexOf('?');
            return query < 0 ? url.trim() : url.substring(0, query).trim();
        }
    }

    static String resolve(String baseUrl, String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        try {
            URI link = URI.create(ref.trim());
            if (!link.isAbsolute()) {
                if (baseUrl == null || baseUrl.isBlank()) {
                    return null;
                }
                link = URI.create(baseUrl.trim()).resolve(link);
            }
            if (!link.isAbsolute() || link.getScheme() == null) {
                return null;
            }
            String scheme = link.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                return null;
            }
            String text = link.toString();
            int hash = text.indexOf('#');
            return hash < 0 ? text : text.substring(0, hash);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<Element> items(Element parent) {
        List<Element> found = new ArrayList<>();
        for (Element child : elements(parent)) {
            String name = local(child);
            if ("item".equals(name) || "entry".equals(name)) {
                found.add(child);
            }
        }
        return found;
    }

    private static Element child(Element parent, String name) {
        for (Element child : elements(parent)) {
            if (name.equals(local(child))) {
                return child;
            }
        }
        return null;
    }

    private static String text(Element parent, String name) {
        Element child = child(parent, name);
        return child == null ? null : child.getTextContent();
    }

    private static List<Element> elements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element) {
                out.add((Element) node);
            }
        }
        return out;
    }

    private static String local(Element element) {
        String name = element.getLocalName();
        return name != null ? name : element.getNodeName();
    }

    static String strip(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }
}
