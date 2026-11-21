package de.monocles.chat;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import eu.siacs.conversations.Config;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds Chromium's per-renderer-process RTCPeerConnection budget (500) for the lifetime of the app
 * process, so no webxdc document can ever open a WebRTC connection (see the FILL500 notes on {@link
 * WebxdcPage}'s WEBRTC_BLOCKER).
 *
 * <p>Why a dedicated WebView: the 500 connections live only as long as the document that created
 * them. If a webxdc document held them, closing that app - or it navigating to another of its pages
 * - would release them while another (possibly malicious) webxdc app is still open, and that app
 * could grab a connection before anything refilled the budget. This keeper's document never
 * navigates and the WebView is never destroyed. All WebViews of an app share one renderer process,
 * so its budget is theirs; each webxdc document additionally verifies (and, failing that,
 * fills/locks) on its own as a backstop.
 *
 * <p>Side effect: no WebView in this process can use WebRTC once a webxdc app was opened. monocles'
 * calls use native WebRTC, not WebView, and are unaffected.
 */
public final class WebRtcKeeper {

    private static final long READY_TIMEOUT_MS = 15_000;

    // The same FILL500 sequence as WebxdcPage, reporting back whether the 501st was refused.
    private static final String PAGE =
            "<!DOCTYPE html><html><head><script>(function(){'use strict';try{for(var"
                + " i=0;i<500;i++){new RTCPeerConnection();}}catch(e){}var blocked=false;try{var"
                + " p=new RTCPeerConnection();try{p.close();}catch(e){}}catch(e){blocked=true;}"
                + "WebRtcKeeper.filled(blocked);})();</script></head><body></body></html>";

    /**
     * FILL500 per-document backstop (see WebxdcPage): fills the RTCPeerConnection budget and, if a
     * 501st connection still succeeds, removes the WebRTC entry points. Shared by the webxdc pages
     * (inlined first in every served HTML document) and the command WebView (document-start
     * script).
     */
    public static final String BLOCKER_JS =
            "(function(){'use strict';try{for(var i=0;i<500;i++){new"
                + " RTCPeerConnection();}}catch(e){}var blocked=false;try{var p=new"
                + " RTCPeerConnection();try{p.close();}catch(e){}}catch(e){blocked=true;}if(blocked)return;console.warn('WebRTC"
                + " connection limit not enforced, removing WebRTC API');var"
                + " N=['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel',"
                + "'RTCIceCandidate','RTCSessionDescription','RTCRtpSender','RTCRtpReceiver',"
                + "'RTCRtpTransceiver','RTCIceTransport','RTCDtlsTransport','RTCSctpTransport',"
                + "'RTCCertificate','RTCDTMFSender','RTCPeerConnectionIceEvent','RTCDataChannelEvent','RTCTrackEvent','RTCError','RTCErrorEvent'];function"
                + " lock(w){if(!w)return w;for(var i=0;i<N.length;i++){try{"
                + "Object.defineProperty(w,N[i],{value:undefined,writable:false,configurable:false,enumerable:false});}catch(e){}}return"
                + " w;}lock(window);"
                + "[window.HTMLIFrameElement,window.HTMLFrameElement,window.HTMLObjectElement,window.HTMLEmbedElement].forEach(function(C){if(!C)return;var"
                + " P=C.prototype;['contentWindow','contentDocument'].forEach(function(k){var"
                + " d=Object.getOwnPropertyDescriptor(P,k);if(!d||!d.get)return;"
                + "Object.defineProperty(P,k,{configurable:false,enumerable:d.enumerable,get:function(){var"
                + " v=d.get.call(this);if(v){lock(k==='contentWindow'?v:v.defaultView);}return"
                + " v;}});});});})();";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Runnable> pending = new ArrayList<>();

    @SuppressLint(
            "StaticFieldLeak") // application context only, kept for the process lifetime on purpose
    private static WebView keeper;

    private static boolean ready = false;
    // Whether the keeper verified that a 501st RTCPeerConnection is refused (main thread).
    private static boolean blocking = false;

    private WebRtcKeeper() {}

    /**
     * Runs {@code action} on the main thread once the keeper holds the budget (immediately if it
     * already does). If the keeper can't report within {@link #READY_TIMEOUT_MS}, {@code action}
     * runs anyway - the per-document injection in WebxdcPage still applies then. Must be called on
     * the main thread.
     */
    public static void whenReady(final Context context, final Runnable action) {
        if (ready) {
            action.run();
            return;
        }
        pending.add(action);
        if (keeper != null) {
            return;
        }
        try {
            keeper = createKeeper(context.getApplicationContext());
        } catch (final RuntimeException e) {
            // e.g. WebView provider missing/updating: fall back to the per-document blocker
            Log.w(Config.LOGTAG, "could not create WebRTC keeper", e);
            markReady();
            return;
        }
        MAIN.postDelayed(
                () -> {
                    if (!ready) {
                        Log.w(Config.LOGTAG, "WebRTC keeper did not report in time");
                        markReady();
                    }
                },
                READY_TIMEOUT_MS);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private static WebView createKeeper(final Context context) {
        final WebView webView = new WebView(context);
        final WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setBlockNetworkLoads(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        webView.addJavascriptInterface(new Callback(), "WebRtcKeeper");
        // Its own origin, distinct from every webxdc app (https://<uuid>.localhost); nothing
        // but this constant page ever loads here.
        webView.loadDataWithBaseURL(
                "https://webrtc-keeper.localhost/", PAGE, "text/html", "UTF-8", null);
        return webView;
    }

    /**
     * True once the keeper verified that this renderer process refuses further RTCPeerConnections.
     * False while not ready, after a timeout, or if the limit is not enforced. Main thread only.
     */
    public static boolean isBlocking() {
        return blocking;
    }

    private static void markReady() {
        if (ready) return;
        ready = true;
        final List<Runnable> actions = new ArrayList<>(pending);
        pending.clear();
        for (final Runnable action : actions) {
            action.run();
        }
    }

    private static final class Callback {
        @JavascriptInterface
        public void filled(final boolean blocked) {
            if (!blocked) {
                Log.w(
                        Config.LOGTAG,
                        "WebRTC connection limit not enforced by this WebView; "
                                + "relying on the per-document blocker");
            }
            MAIN.post(() -> {
                blocking = blocked;
                markReady();
            });
        }
    }
}
