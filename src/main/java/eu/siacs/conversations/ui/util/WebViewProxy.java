package eu.siacs.conversations.ui.util;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import de.monocles.chat.WebRtcKeeper;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.http.HttpConnectionManager;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

/**
 * Routes in-app WebViews that load remote pages (XEP-0050 command pages, the WebXDC store)
 * according to {@link HttpConnectionManager#route}. WebView ignores the app's OkHttp proxy
 * settings, so without this a page would connect directly even with Tor/I2P enabled, and a
 * .onion/.i2p name would be resolved by the system resolver.
 *
 * <p>The override is process-wide: it applies to every WebView of the app, so only one route (Tor
 * or I2P) can be in effect at a time. A page that needs the other route is refused while a page
 * using the current one is still open, instead of switching the override underneath it. The
 * override uses the HTTP proxy ports on purpose (Chromium resolves names at an HTTP proxy, whereas
 * its {@code socks://} scheme means SOCKS4 with local DNS), drops Chromium's implicit bypass rules
 * (localhost, link-local) and has no {@code direct://} fallback: if Tor/I2P is not running the page
 * fails to load instead of going out directly. WebRTC is blocked separately (STUN is direct UDP and
 * is not covered by any WebView proxy setting).
 */
public final class WebViewProxy {

    private static final String TOR_HTTP_PROXY = "127.0.0.1:8118";
    private static final String I2P_HTTP_PROXY = "127.0.0.1:4444";

    // Main-thread state. ProxyController calls are asynchronous and applied in the order issued,
    // so once no call is in flight the override equals the last requested route.
    private static HttpConnectionManager.Route requested = HttpConnectionManager.Route.DIRECT;
    private static int inFlight = 0;
    private static final List<Pending> waiting = new ArrayList<>();
    // WebViews whose current page was loaded through the override, with its route. While any of
    // them is alive the override is neither cleared nor switched to another route: their later
    // requests (XHR, subresources, possibly to a .onion/.i2p host) would otherwise use a
    // different network than the page was loaded with.
    private static final Map<WebView, HttpConnectionManager.Route> proxiedViews =
            new WeakHashMap<>();
    // The route the override has in effect, null while a change is being applied. Read by
    // shouldInterceptRequest on WebView's network threads.
    private static volatile HttpConnectionManager.Route applied =
            HttpConnectionManager.Route.DIRECT;

    private record Pending(HttpConnectionManager.Route target, Runnable then) {}

    private WebViewProxy() {}

    /**
     * Prepares {@code webView} for loading a page that must use {@code route}, then runs {@code
     * onReady} on the main thread. Runs {@code onRefused} with a message resource instead if the
     * page cannot be loaded safely: no proxy override or WebRTC block available, or another open
     * page uses the other route. Main thread only.
     */
    public static void apply(
            final Context context,
            final WebView webView,
            final HttpConnectionManager.Route route,
            final Runnable onReady,
            final IntConsumer onRefused) {
        final Executor main = ContextCompat.getMainExecutor(context);
        // Pages loaded through here never need WebRTC; block it for every one of them, whatever
        // the route (a DIRECT page can still end up running under a retained override).
        final boolean documentBackstop = blockWebRtc(webView);
        if (route == HttpConnectionManager.Route.DIRECT) {
            // This WebView is getting a new page; its old one no longer needs the proxy.
            proxiedViews.remove(webView);
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
                    || (requested == HttpConnectionManager.Route.DIRECT && inFlight == 0)) {
                onReady.run(); // no override: direct, WebRTC blocked on a best-effort basis
                return;
            }
            if (inFlight > 0 || !proxiedViews.isEmpty() || hasPendingProxied()) {
                // Another page relies on (or is about to get) the override: keep it. This page
                // then runs under it too, so it needs the same WebRTC guarantee as a proxied page.
                whenWebRtcBlocked(context, documentBackstop, route, onReady, onRefused);
                return;
            }
            ensure(main, HttpConnectionManager.Route.DIRECT, onReady);
            return;
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.w(
                    Config.LOGTAG,
                    "WebView cannot apply a proxy override; refusing to load page via " + route);
            onRefused.accept(R.string.command_page_needs_proxy);
            return;
        }
        if (conflicts(webView, route)) {
            Log.w(
                    Config.LOGTAG,
                    "another page uses the other overlay; refusing to load page via " + route);
            onRefused.accept(R.string.webview_other_route_open);
            return;
        }
        whenWebRtcBlocked(
                context,
                documentBackstop,
                route,
                () -> {
                    // re-check: another page may have claimed the other route while we waited
                    if (conflicts(webView, route)) {
                        onRefused.accept(R.string.webview_other_route_open);
                        return;
                    }
                    ensure(
                            main,
                            route,
                            () -> {
                                proxiedViews.put(webView, route);
                                onReady.run();
                            });
                },
                onRefused);
    }

    /**
     * Stops the page in {@code webView} and forgets it, so its route no longer keeps the override
     * in place. Call when the page is closed. Main thread only.
     */
    public static void release(final WebView webView) {
        proxiedViews.remove(webView);
        webView.stopLoading();
        webView.loadUrl("about:blank");
    }

    /**
     * For {@code WebViewClient.shouldInterceptRequest} of remote pages: an empty 403 for requests
     * that must not leave, null otherwise. Refused are .onion/.i2p hosts unless the override in
     * effect is their own overlay (a direct page would make WebView resolve the name locally, and a
     * Tor page must not reach .i2p or the other way round), and local targets (localhost, loopback,
     * link-local, private addresses) unless the page itself is local: a remote page must not reach
     * local services, while a self-hosted page on the LAN may load its own resources.
     *
     * @param pageOrigin origin (scheme://host[:port]) of the page loaded in this WebView, or null
     */
    @Nullable
    public static WebResourceResponse blockRequest(
            @Nullable final Uri url, @Nullable final String pageOrigin) {
        final String host = url == null ? null : url.getHost();
        if (host == null) return null;
        final String pageHost = pageOrigin == null ? null : Uri.parse(pageOrigin).getHost();
        final boolean pageIsLocal =
                pageHost != null && HttpConnectionManager.isLocalTarget(pageHost);
        if (requestAllowed(host, applied, pageIsLocal)) return null;
        Log.d(Config.LOGTAG, "blocked WebView request to " + host);
        return new WebResourceResponse(
                "text/plain",
                "UTF-8",
                403,
                "Forbidden",
                Collections.emptyMap(),
                new ByteArrayInputStream(new byte[0]));
    }

    /** {@link #blockRequest}'s decision; {@code applied} null = the override is being changed. */
    @VisibleForTesting
    static boolean requestAllowed(
            final String host,
            @Nullable final HttpConnectionManager.Route applied,
            final boolean pageIsLocal) {
        if (HttpConnectionManager.isOnionHost(host))
            return applied == HttpConnectionManager.Route.TOR;
        if (HttpConnectionManager.isI2PHost(host))
            return applied == HttpConnectionManager.Route.I2P;
        if (HttpConnectionManager.isLocalTarget(host)) return pageIsLocal;
        return true;
    }

    /**
     * Whether loading {@code route} in {@code webView} would need the override switched away from a
     * live page.
     */
    private static boolean conflicts(
            final WebView webView, final HttpConnectionManager.Route route) {
        for (final Map.Entry<WebView, HttpConnectionManager.Route> entry :
                proxiedViews.entrySet()) {
            if (entry.getKey() != webView && entry.getValue() != route) return true;
        }
        for (final Pending pending : waiting) {
            if (pending.target() != HttpConnectionManager.Route.DIRECT && pending.target() != route)
                return true;
        }
        return inFlight > 0
                && requested != HttpConnectionManager.Route.DIRECT
                && requested != route;
    }

    private static boolean hasPendingProxied() {
        for (final Pending pending : waiting) {
            if (pending.target() != HttpConnectionManager.Route.DIRECT) return true;
        }
        return false;
    }

    /**
     * Runs {@code then} once the renderer's RTCPeerConnection budget is held (WebRtcKeeper) and, if
     * that could not be verified, only if the per-document backstop is in place. STUN is direct UDP
     * that no WebView proxy setting covers: without a verified block a page running under the
     * override could reveal the real IP, so it is refused instead.
     */
    private static void whenWebRtcBlocked(
            final Context context,
            final boolean documentBackstop,
            final HttpConnectionManager.Route route,
            final Runnable then,
            final IntConsumer onRefused) {
        WebRtcKeeper.whenReady(
                context,
                () -> {
                    if (!WebRtcKeeper.isBlocking() && !documentBackstop) {
                        Log.w(
                                Config.LOGTAG,
                                "WebRTC could not be blocked; refusing to load page via " + route);
                        onRefused.accept(R.string.command_page_needs_proxy);
                        return;
                    }
                    then.run();
                });
    }

    /** Runs {@code then} once the override is applied and points at {@code target}. */
    private static void ensure(
            final Executor main, final HttpConnectionManager.Route target, final Runnable then) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            // apply() refuses proxied pages earlier; never load one without the proxy anyway.
            if (target == HttpConnectionManager.Route.DIRECT) {
                then.run();
            } else {
                Log.w(
                        Config.LOGTAG,
                        "no proxy override available; dropping page load via " + target);
            }
            return;
        }
        if (requested == target && inFlight == 0) {
            then.run();
            return;
        }
        waiting.add(new Pending(target, then));
        if (requested == target) {
            return; // the last call in flight already switches to target
        }
        requested = target;
        inFlight++;
        applied = null; // in transition until the call completes
        final Runnable done =
                () -> {
                    inFlight--;
                    if (inFlight == 0) {
                        applied = requested;
                        drain(main);
                    }
                };
        // checked above; repeated so the call sites are visibly guarded (lint RequiresFeature)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (target == HttpConnectionManager.Route.DIRECT) {
                ProxyController.getInstance().clearProxyOverride(main, done);
            } else {
                final ProxyConfig config =
                        new ProxyConfig.Builder()
                                .addProxyRule(
                                        target == HttpConnectionManager.Route.I2P
                                                ? I2P_HTTP_PROXY
                                                : TOR_HTTP_PROXY)
                                // no implicit localhost/link-local bypass: nothing goes out
                                // directly
                                .removeImplicitRules()
                                .build();
                ProxyController.getInstance().setProxyOverride(config, main, done);
            }
        }
    }

    private static void drain(final Executor main) {
        final List<Pending> ready = new ArrayList<>(waiting);
        waiting.clear();
        for (final Pending pending : ready) {
            if (pending.target() == requested
                    || pending.target() == HttpConnectionManager.Route.DIRECT) {
                // DIRECT pages may run under a proxy; proxied pages only under their own one.
                pending.then().run();
            } else {
                ensure(main, pending.target(), pending.then());
            }
        }
    }

    /**
     * Per-document FILL500 backstop in every frame of this WebView, added once. Returns whether the
     * backstop is in place.
     */
    private static boolean blockWebRtc(final WebView webView) {
        if (webView.getTag(R.id.webview_webrtc_blocked) != null) return true;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                    webView, WebRtcKeeper.BLOCKER_JS, Collections.singleton("*"));
            webView.setTag(R.id.webview_webrtc_blocked, Boolean.TRUE);
            return true;
        }
        Log.w(
                Config.LOGTAG,
                "WebView has no document-start scripts; relying on WebRtcKeeper alone");
        return false;
    }
}
