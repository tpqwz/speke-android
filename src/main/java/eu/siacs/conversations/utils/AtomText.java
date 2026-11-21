package eu.siacs.conversations.utils;

import com.google.common.base.Strings;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Node;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Converts Atom text constructs (RFC 4287 §3.1: {@code type="text"}, {@code "html"} or {@code
 * "xhtml"}) to the Markdown the feed view renders.
 *
 * <p>Movim and other XEP-0277 clients publish {@code <content type="xhtml">} with an XHTML {@code
 * <div>}; reading only the element text ran paragraphs together and dropped links. Only structure
 * is carried over (paragraphs, line breaks, lists, quotes, emphasis, links); any markup that isn't
 * understood contributes just its text. Links are limited to http(s)/xmpp.
 */
public final class AtomText {

    private static final String XHTML = "http://www.w3.org/1999/xhtml";
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\r\\n\\f]+");
    private static final Pattern MANY_NEWLINES = Pattern.compile("\\n{3,}");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private AtomText() {}

    /**
     * @return the construct as Markdown, or null if there is no text.
     */
    public static String toMarkdown(final Element construct) {
        if (construct == null) {
            return null;
        }
        final String type =
                Strings.nullToEmpty(construct.getAttribute("type")).toLowerCase(Locale.ROOT);
        final String result;
        if ("xhtml".equals(type)) {
            Element div = construct.findChild("div", XHTML);
            if (div == null) {
                div = construct.findChild("div");
            }
            final StringBuilder out = new StringBuilder();
            appendChildren(div != null ? div : construct, out, new ListState());
            result = tidy(out.toString());
        } else if ("html".equals(type) || "text/html".equals(type)) {
            result = tidy(htmlToText(construct.getContent()));
        } else {
            result = construct.getContent();
        }
        return Strings.isNullOrEmpty(result == null ? null : result.trim()) ? null : result.trim();
    }

    /**
     * Escaped HTML (type="html"): keeps paragraph and line breaks, drops the tags. Rare in
     * practice, so no attempt is made to keep links or emphasis.
     */
    static String htmlToText(final String html) {
        if (html == null) {
            return null;
        }
        String s =
                html.replaceAll("(?i)<br\\s*/?>", "\n")
                        .replaceAll("(?i)</(p|div|li|h[1-6]|blockquote)>", "\n\n");
        s = TAG.matcher(s).replaceAll("");
        return s.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
    }

    private static final class ListState {
        boolean ordered = false;
        int counter = 0;
    }

    private static void appendChildren(
            final Element element, final StringBuilder out, final ListState list) {
        for (final Node node : element.getChildNodes()) {
            if (node instanceof Element) {
                appendElement((Element) node, out, list);
            } else {
                final String text = node.getContent();
                if (text != null) {
                    out.append(WHITESPACE.matcher(text).replaceAll(" "));
                }
            }
        }
    }

    private static void appendElement(
            final Element e, final StringBuilder out, final ListState list) {
        final String name = e.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "br":
                out.append('\n');
                break;
            case "p":
            case "div":
            case "section":
            case "article":
            case "figure":
            case "figcaption":
                block(out);
                appendChildren(e, out, list);
                block(out);
                break;
            case "h1":
            case "h2":
            case "h3":
            case "h4":
            case "h5":
            case "h6":
                block(out);
                out.append(Strings.repeat("#", name.charAt(1) - '0')).append(' ');
                out.append(inline(e, list));
                block(out);
                break;
            case "blockquote":
                {
                    block(out);
                    final String inner = tidy(inline(e, list));
                    for (final String line : inner.split("\n", -1)) {
                        out.append("> ").append(line).append('\n');
                    }
                    block(out);
                    break;
                }
            case "pre":
                block(out);
                out.append("```\n").append(e.getContent().trim()).append("\n```");
                block(out);
                break;
            case "ul":
            case "ol":
                {
                    block(out);
                    final ListState nested = new ListState();
                    nested.ordered = "ol".equals(name);
                    appendChildren(e, out, nested);
                    block(out);
                    break;
                }
            case "li":
                {
                    lineBreak(out);
                    if (list.ordered) {
                        out.append(++list.counter).append(". ");
                    } else {
                        out.append("- ");
                    }
                    out.append(inline(e, list).trim());
                    out.append('\n');
                    break;
                }
            case "strong":
            case "b":
                wrap(out, "**", inline(e, list));
                break;
            case "em":
            case "i":
                wrap(out, "*", inline(e, list));
                break;
            case "del":
            case "s":
                wrap(out, "~~", inline(e, list));
                break;
            case "code":
                wrap(out, "`", e.getContent());
                break;
            case "a":
                {
                    final String href = e.getAttribute("href");
                    final String text = inline(e, list).trim();
                    if (isSafeLink(href)) {
                        final String label = text.isEmpty() ? href : text;
                        if (label.equals(href)) {
                            out.append(href);
                        } else {
                            out.append('[')
                                    .append(label.replace("]", "\\]"))
                                    .append("](")
                                    .append(href.replace(")", "%29"))
                                    .append(')');
                        }
                    } else {
                        out.append(text);
                    }
                    break;
                }
            case "img":
                {
                    final String src = e.getAttribute("src");
                    final String alt = Strings.nullToEmpty(e.getAttribute("alt")).trim();
                    if (isSafeLink(src)) {
                        // Rendered as a link: inline remote images would load without the user
                        // asking.
                        out.append('[')
                                .append(alt.isEmpty() ? src : alt.replace("]", "\\]"))
                                .append("](")
                                .append(src.replace(")", "%29"))
                                .append(')');
                    } else {
                        out.append(alt);
                    }
                    break;
                }
            case "script":
            case "style":
                break;
            default:
                appendChildren(e, out, list);
        }
    }

    private static String inline(final Element e, final ListState list) {
        final StringBuilder sb = new StringBuilder();
        appendChildren(e, sb, list);
        return sb.toString();
    }

    private static void wrap(final StringBuilder out, final String marker, final String text) {
        final String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        out.append(marker).append(trimmed).append(marker);
    }

    private static boolean isSafeLink(final String href) {
        if (href == null) {
            return false;
        }
        final String lower = href.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("https://")
                || lower.startsWith("http://")
                || lower.startsWith("xmpp:");
    }

    private static void block(final StringBuilder out) {
        trimTrailingSpaces(out);
        if (out.length() == 0) {
            return;
        }
        if (out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        if (out.length() < 2 || out.charAt(out.length() - 2) != '\n') {
            out.append('\n');
        }
    }

    private static void lineBreak(final StringBuilder out) {
        trimTrailingSpaces(out);
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    private static void trimTrailingSpaces(final StringBuilder out) {
        while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
            out.setLength(out.length() - 1);
        }
    }

    private static String tidy(final String s) {
        if (s == null) {
            return null;
        }
        final StringBuilder lines = new StringBuilder();
        for (final String line : s.split("\n", -1)) {
            lines.append(line.replaceAll("^ +| +$", "")).append('\n');
        }
        return MANY_NEWLINES.matcher(lines.toString()).replaceAll("\n\n").trim();
    }
}
