package de.monocles.chat;

import java.nio.charset.StandardCharsets;
import org.junit.Assert;
import org.junit.Test;

/** The WebRTC blocker must be the first script of every served HTML document. */
public class WebxdcWebRtcBlockerTest {

    private static String inject(final String html) {
        return new String(
                WebxdcPage.injectWebRtcBlocker(html.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
    }

    @Test
    public void insertedRightAfterHead() {
        final String out =
                inject(
                        "<!DOCTYPE html><html><head"
                                + " lang=\"en\"><script>evil()</script></head></html>");
        Assert.assertTrue(
                out.startsWith("<!DOCTYPE html><html><head lang=\"en\"><script>(function(){"));
        Assert.assertTrue(out.indexOf("RTCPeerConnection") < out.indexOf("evil()"));
    }

    @Test
    public void insertedAfterHtmlOrDoctypeWithoutHead() {
        Assert.assertTrue(
                inject("<html><body><script>x()</script>")
                        .startsWith("<html><script>(function(){"));
        Assert.assertTrue(
                inject("<!doctype html><script>x()</script>")
                        .startsWith("<!doctype html><script>(function(){"));
    }

    @Test
    public void prependedToFragments() {
        Assert.assertTrue(inject("<script>x()</script>").startsWith("<script>(function(){"));
    }

    @Test
    public void headerLikeTagsAreNotMistakenForHead() {
        final String out = inject("<html><header>x</header>");
        Assert.assertTrue(out.startsWith("<html><script>(function(){"));
    }

    /** FILL500 runs before anything else, and verifies the 501st connection is refused. */
    @Test
    public void fillsTheConnectionLimitBeforeAppCode() {
        final String out =
                inject(
                        "<html><head><script>new"
                                + " RTCPeerConnection({iceServers:[{urls:'stun:evil'}]})</script>");
        final int fill = out.indexOf("for(var i=0;i<500;i++){new RTCPeerConnection();}");
        final int verify = out.indexOf("blocked=true");
        final int app = out.indexOf("stun:evil");
        Assert.assertTrue(fill > 0);
        Assert.assertTrue(fill < verify && verify < app);
    }
}
