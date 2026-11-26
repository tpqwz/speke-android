package de.monocles.chat.feeds;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public class FeedTest {

    @Test
    public void rssItemKeepsTheLinkAndTheAudioEnclosure() {
        FeedXml.Feed feed =
                FeedXml.parse(
                        """
                        <?xml version="1.0"?>
                        <rss version="2.0"><channel>
                          <title>Show</title>
                          <item>
                            <title><b>Hello</b></title>
                            <link>https://example.com/hello</link>
                            <enclosure url="https://cdn.example.com/ep.mp3" type="audio/mpeg"/>
                            <pubDate>Wed, 26 Nov 2026 00:00:00 GMT</pubDate>
                          </item>
                          <item>
                            <title>Note</title>
                            <link>https://example.com/note</link>
                          </item>
                          <item>
                            <title>Clip</title>
                            <link>https://example.com/clip</link>
                            <enclosure url="https://cdn.example.com/clip.mp4" type="video/mp4"/>
                          </item>
                        </channel></rss>
                        """
                                .getBytes(StandardCharsets.UTF_8),
                        "https://example.com/feed.xml");
        assertEquals("Show", feed.title);
        assertEquals(3, feed.items.size());
        assertEquals("Hello", feed.items.get(0).title);
        assertEquals("https://example.com/hello", feed.items.get(0).link);
        assertEquals("https://cdn.example.com/ep.mp3", feed.items.get(0).audio);
        assertEquals("Wed, 26 Nov 2026 00:00:00 GMT", feed.items.get(0).when);
        assertNull(feed.items.get(1).audio);
        assertNull(feed.items.get(2).audio);
    }

    @Test
    public void atomResolvesARelativeLinkAndAnEnclosure() {
        FeedXml.Feed feed =
                FeedXml.parse(
                        """
                        <?xml version="1.0"?>
                        <feed xmlns="http://www.w3.org/2005/Atom">
                          <title>Blog</title>
                          <entry>
                            <title>Post</title>
                            <link rel="alternate" href="/posts/1"/>
                            <link rel="enclosure" href="ep.m4a" type="audio/mp4"/>
                            <updated>2026-11-26T00:00:00Z</updated>
                          </entry>
                        </feed>
                        """
                                .getBytes(StandardCharsets.UTF_8),
                        "https://example.com/feed.xml");
        assertEquals("https://example.com/posts/1", feed.items.get(0).link);
        assertEquals("https://example.com/ep.m4a", feed.items.get(0).audio);
        assertEquals("2026-11-26T00:00:00Z", feed.items.get(0).when);
    }

    @Test
    public void mediaContentCountsAsAudioWhenTheTypeOrNameSaysSo() {
        FeedXml.Feed feed =
                FeedXml.parse(
                        """
                        <?xml version="1.0"?>
                        <rss version="2.0"><channel><item>
                          <title>Ep</title>
                          <link>https://example.com/ep</link>
                          <media:content xmlns:media="http://search.yahoo.com/mrss/"
                            url="https://cdn.example.com/a.ogg" type="audio/ogg"/>
                        </item></channel></rss>
                        """
                                .getBytes(StandardCharsets.UTF_8),
                        "https://example.com/feed.xml");
        assertEquals("https://cdn.example.com/a.ogg", feed.items.get(0).audio);
    }

    @Test
    public void aDoctypeIsRefused() {
        byte[] xml =
                """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <rss><channel><item><title>&xxe;</title></item></channel></rss>
                """
                        .getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> FeedXml.parse(xml, "https://example.com/feed.xml"));
    }

    @Test
    public void onlyTheFirstThirtyItemsAreKept() {
        StringBuilder xml = new StringBuilder("<rss><channel>");
        for (int i = 0; i < 40; i++) {
            xml.append("<item><title>n").append(i).append("</title></item>");
        }
        xml.append("</channel></rss>");
        FeedXml.Feed feed =
                FeedXml.parse(xml.toString().getBytes(StandardCharsets.UTF_8), "https://example.com/feed.xml");
        assertEquals(30, feed.items.size());
        assertEquals("n0", feed.items.get(0).title);
        assertEquals("n29", feed.items.get(29).title);
    }

    @Test
    public void addressesAreHttpsOrHiddenHttpAndNeverLocal() {
        assertEquals("https://example.com/a", FeedList.canonical("https://Example.com/a"));
        assertEquals("https://example.com/a", FeedList.canonical("https://example.com/a#part"));
        assertEquals("https://example.com/", FeedList.canonical("https://example.com"));
        assertEquals("http://news.onion/feed", FeedList.canonical("http://news.onion/feed"));
        assertEquals("http://news.i2p/feed", FeedList.canonical("http://news.i2p/feed"));
        assertNull(FeedList.canonical("http://example.com/feed"));
        assertNull(FeedList.canonical("https://127.0.0.1/feed"));
        assertNull(FeedList.canonical("https://10.1.2.3/feed"));
        assertNull(FeedList.canonical("https://192.168.0.8/a"));
        assertNull(FeedList.canonical("https://172.16.0.1/a"));
        assertNull(FeedList.canonical("https://169.254.1.1/a"));
        assertNull(FeedList.canonical("https://100.64.0.1/a"));
        assertNull(FeedList.canonical("https://localhost/feed"));
        assertNull(FeedList.canonical("https://printer.local/feed"));
        assertNull(FeedList.canonical("https://[::1]/feed"));
        assertNull(FeedList.canonical("https://user:pass@example.com/a"));
        assertNull(FeedList.canonical("javascript:alert(1)"));
        assertNull(FeedList.canonical("file:///etc/passwd"));
        assertNull(FeedList.canonical("https://2130706433/feed"));
    }

    @Test
    public void theListDropsBlanksDuplicatesAndDisallowedLines() {
        List<String> urls =
                FeedList.read(
                        """
                        https://Example.com/a

                        https://example.com/a#x
                        http://127.0.0.1/secret
                        http://news.onion/b
                        """);
        assertEquals(List.of("https://example.com/a", "http://news.onion/b"), urls);
        assertEquals("https://example.com/a\nhttp://news.onion/b\n", FeedList.write(urls));
        assertTrue(FeedList.read(FeedList.write(urls)).size() == 2);
    }
}
