package eu.siacs.conversations.utils;

import static eu.siacs.conversations.utils.LinkPreviewParser.OGP_NS;

import eu.siacs.conversations.xml.Element;
import org.junit.Assert;
import org.junit.Test;

/** OpenGraph metadata -> rdf:Description link-preview payload. */
public class LinkPreviewParserTest {

    private static Element describe(final String html) {
        return LinkPreviewParser.describe(
                html, "https://example.org/a", "https://example.org/a?x=1");
    }

    @Test
    public void allOpenGraphFieldsAreMapped() {
        final Element rdf =
                describe(
                        "<html><head><meta property=\"og:title\" content=\"Title\"><meta"
                            + " property=\"og:description\" content=\"Desc\"><meta"
                            + " property=\"og:url\" content=\"https://example.org/canonical\"><meta"
                            + " property=\"og:image\" content=\"https://example.org/i.png\"><meta"
                            + " property=\"og:type\" content=\"article\"><meta"
                            + " property=\"og:site_name\" content=\"Example\"><meta"
                            + " property=\"og:video\" content=\"https://example.org/v.mp4\">"
                            + "</head><body></body></html>");
        Assert.assertNotNull(rdf);
        Assert.assertEquals("Description", rdf.getName());
        Assert.assertEquals(LinkPreviewParser.RDF_NS, rdf.getNamespace());
        Assert.assertEquals("https://example.org/a?x=1", rdf.getAttribute("rdf:about"));
        Assert.assertEquals("Title", rdf.findChildContent("title", OGP_NS));
        Assert.assertEquals("Desc", rdf.findChildContent("description", OGP_NS));
        Assert.assertEquals("https://example.org/canonical", rdf.findChildContent("url", OGP_NS));
        Assert.assertEquals("https://example.org/i.png", rdf.findChildContent("image", OGP_NS));
        Assert.assertEquals("article", rdf.findChildContent("type", OGP_NS));
        Assert.assertEquals("Example", rdf.findChildContent("site_name", OGP_NS));
        Assert.assertEquals("https://example.org/v.mp4", rdf.findChildContent("video", OGP_NS));
    }

    @Test
    public void descriptionFallsBackToMetaDescription() {
        final Element rdf =
                describe("<head><meta name=\"description\" content=\" Plain \"></head>");
        Assert.assertNotNull(rdf);
        Assert.assertEquals("Plain", rdf.findChildContent("description", OGP_NS));
        Assert.assertNull(rdf.findChildContent("title", OGP_NS));
    }

    @Test
    public void secureVideoUrlWins() {
        final Element rdf =
                describe(
                        "<meta property=\"og:title\" content=\"T\"><meta property=\"og:video\""
                                + " content=\"http://v/plain\"><meta property=\"og:video:url\""
                                + " content=\"http://v/url\"><meta property=\"og:video:secure_url\""
                                + " content=\"https://v/secure\">");
        Assert.assertEquals("https://v/secure", rdf.findChildContent("video", OGP_NS));
        final Element noSecure =
                describe(
                        "<meta property=\"og:title\" content=\"T\">"
                                + "<meta property=\"og:video\" content=\"http://v/plain\">"
                                + "<meta property=\"og:video:url\" content=\"http://v/url\">");
        Assert.assertEquals("http://v/url", noSecure.findChildContent("video", OGP_NS));
    }

    @Test
    public void propertyMatchIsCaseInsensitiveAndBlankContentIgnored() {
        final Element rdf =
                describe(
                        "<meta property=\"OG:Title\" content=\"   \">"
                                + "<meta property=\"og:title\" content=\" Second \">");
        Assert.assertEquals("Second", rdf.findChildContent("title", OGP_NS));
    }

    @Test
    public void pageWithoutTitleOrDescriptionGivesNoPreview() {
        Assert.assertNull(
                describe(
                        "<html><head><meta property=\"og:image\""
                                + " content=\"https://x/i.png\"></head></html>"));
        Assert.assertNull(describe(""));
    }

    @Test
    public void relativeUrlsAreResolvedAgainstTheFinalPageUrl() {
        final Element rdf =
                LinkPreviewParser.describe(
                        "<meta property=\"og:title\" content=\"T\">"
                                + "<meta property=\"og:image\" content=\"/img/a.png\">"
                                + "<meta property=\"og:url\" content=\"../canonical\">"
                                + "<meta property=\"og:video\" content=\"v.mp4\">",
                        "https://example.org/dir/page",
                        "https://example.org/short");
        Assert.assertEquals("https://example.org/img/a.png", rdf.findChildContent("image", OGP_NS));
        Assert.assertEquals("https://example.org/canonical", rdf.findChildContent("url", OGP_NS));
        Assert.assertEquals("https://example.org/dir/v.mp4", rdf.findChildContent("video", OGP_NS));
    }

    @Test
    public void nonHttpUrlsAreDropped() {
        final Element rdf =
                describe(
                        "<meta property=\"og:title\" content=\"T\"><meta property=\"og:image\""
                            + " content=\"javascript:alert(1)\"><meta property=\"og:url\""
                            + " content=\"intent://x#Intent;end\"><meta"
                            + " property=\"og:video:secure_url\""
                            + " content=\"data:video/mp4;base64,AAAA\"><meta property=\"og:video\""
                            + " content=\"https://v/ok.mp4\">");
        Assert.assertNull(rdf.findChildContent("image", OGP_NS));
        Assert.assertNull(rdf.findChildContent("url", OGP_NS));
        Assert.assertEquals("https://v/ok.mp4", rdf.findChildContent("video", OGP_NS));
    }

    @Test
    public void truncatedPageStillParses() {
        final String full =
                "<html><head><meta property=\"og:title\" content=\"Cut\"><script>var a = \"";
        final Element rdf = describe(full + "x".repeat(1000));
        Assert.assertNotNull(rdf);
        Assert.assertEquals("Cut", rdf.findChildContent("title", OGP_NS));
    }
}
