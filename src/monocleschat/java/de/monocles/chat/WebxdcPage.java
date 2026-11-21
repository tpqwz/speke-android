// Based on GPLv3 code from deltachat-android
// https://github.com/deltachat/deltachat-android/blob/master/src/org/thoughtcrime/securesms/WebViewActivity.java
// https://github.com/deltachat/deltachat-android/blob/master/src/org/thoughtcrime/securesms/WebxdcActivity.java
package de.monocles.chat;

import android.content.Context;
import android.content.Intent;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.util.Consumer;
import androidx.databinding.DataBindingUtil;
import com.google.android.material.color.MaterialColors;
import com.google.common.io.ByteStreams;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.WebxdcPageBinding;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;
import io.ipfs.cid.Cid;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONException;
import org.json.JSONObject;
import org.tomlj.Toml;
import org.tomlj.TomlTable;

public class WebxdcPage implements ConversationPage {
    protected XmppConnectionService xmppConnectionService;
    protected WebxdcPageBinding binding = null;
    protected ZipFile zip = null;
    // Set when the page is closed, so a deferred load (WebRtcKeeper) doesn't start it again.
    protected boolean closed = false;
    protected TomlTable manifest = null;
    protected String baseUrl;
    protected Message source;
    protected WebxdcUpdate lastUpdate = null;
    protected WeakReference<XmppActivity> activity;
    protected WeakReference<Consumer<ConversationPage>> remover;

    public WebxdcPage(final XmppActivity activity, File f, Message source) {
        this.xmppConnectionService = activity.xmppConnectionService;
        this.source = source;
        this.activity = new WeakReference(activity);
        try {
            // decrypted for the page's lifetime without a name on disk (see MediaFiles.openZip)
            if (f != null) zip = eu.siacs.conversations.persistance.MediaFiles.openZip(activity, f);
            manifest = readManifest(zip);
        } catch (final IOException e) {
            Log.w(Config.LOGTAG, "WebxdcPage: " + e);
        }

        // ids in the subdomain makes sure, different apps using same files do not share the same
        // cache entry
        // (WebView may use a global cache shared across objects).
        // (a random-id would also work, but would need maintenance and does not add benefits as we
        // regard the file-part interceptRequest() only,
        // also a random-id is not that useful for debugging)
        baseUrl = "https://" + source.getUuid() + ".localhost";
    }

    /** A manifest is a few lines of TOML; anything bigger is not one we read. */
    static final int MAX_MANIFEST_BYTES = 64 * 1024;

    static final int MAX_NAME_LENGTH = 80;

    /**
     * Reads {@code manifest.toml} from an app archive. The archive comes from whoever sent the app,
     * so this never throws and never reads more than {@link #MAX_MANIFEST_BYTES}: an entry can
     * claim any size and unpack to far more than the archive weighs.
     *
     * @return the manifest, or null if there is none or it cannot be used
     */
    static TomlTable readManifest(final ZipFile zip) {
        if (zip == null) return null;
        try {
            final ZipEntry entry = zip.getEntry("manifest.toml");
            if (entry == null || entry.isDirectory() || entry.getSize() > MAX_MANIFEST_BYTES)
                return null;
            final byte[] buffer = new byte[MAX_MANIFEST_BYTES + 1];
            int length = 0;
            try (final InputStream in = zip.getInputStream(entry)) {
                int read;
                while (length < buffer.length
                        && (read = in.read(buffer, length, buffer.length - length)) != -1) {
                    length += read;
                }
            }
            // the declared size is the sender's claim; this is what actually came out
            if (length > MAX_MANIFEST_BYTES) return null;
            return Toml.parse(
                    new String(buffer, 0, length, java.nio.charset.StandardCharsets.UTF_8));
        } catch (final IOException | RuntimeException | StackOverflowError e) {
            // StackOverflowError: a value nested thousands of levels deep
            Log.w(Config.LOGTAG, "WebxdcPage: unusable manifest: " + e);
            return null;
        }
    }

    /**
     * The app's name from its manifest, fit to be shown as a label: only if it really is a string
     * ({@code TomlTable.getString} throws when it is not), without control or text-direction
     * characters, and of bounded length. Null if there is none.
     */
    static String manifestName(final TomlTable manifest) {
        if (manifest == null) return null;
        final Object value;
        try {
            value = manifest.get("name");
        } catch (final RuntimeException e) {
            return null;
        }
        if (!(value instanceof String)) return null;
        final StringBuilder name = new StringBuilder();
        final String raw = (String) value;
        for (int i = 0; i < raw.length() && name.length() < MAX_NAME_LENGTH; ) {
            final int codePoint = raw.codePointAt(i);
            i += Character.charCount(codePoint);
            final int type = Character.getType(codePoint);
            if (type == Character.CONTROL
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SURROGATE
                    || type == Character.UNASSIGNED) {
                continue;
            }
            name.appendCodePoint(codePoint);
        }
        final String cleaned = name.toString().trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * The app's name from its manifest, or null if the archive or the name is missing. Reads just
     * that and closes the archive again - for labels, where no page is needed.
     */
    public static String readName(final File file) {
        if (file == null || !file.canRead()) return null;
        try (final ZipFile archive =
                eu.siacs.conversations.persistance.MediaFiles.openZip(
                        eu.siacs.conversations.Conversations.getContext(), file)) {
            return manifestName(readManifest(archive));
        } catch (final IOException | RuntimeException e) {
            Log.w(Config.LOGTAG, "WebxdcPage: could not read name: " + e);
            return null;
        }
    }

    public WebxdcPage(final XmppActivity activity, Cid cid, Message source) {
        this(activity, activity.xmppConnectionService.getFileForCid(cid), source);
    }

    public Drawable getIcon() {
        return getIcon(288);
    }

    public Drawable getIcon(int dp) {
        if (android.os.Build.VERSION.SDK_INT < 28) return null;
        if (zip == null) return null;
        ZipEntry entry = zip.getEntry("icon.webp");
        if (entry == null) entry = zip.getEntry("icon.png");
        if (entry == null) entry = zip.getEntry("icon.jpg");
        if (entry == null) return null;

        try {
            DisplayMetrics metrics = xmppConnectionService.getResources().getDisplayMetrics();
            ImageDecoder.Source source =
                    ImageDecoder.createSource(
                            ByteBuffer.wrap(ByteStreams.toByteArray(zip.getInputStream(entry))));
            return ImageDecoder.decodeDrawable(
                    source,
                    (decoder, info, src) -> {
                        int w = info.getSize().getWidth();
                        int h = info.getSize().getHeight();
                        Rect r = FileBackend.rectForSize(w, h, (int) (metrics.density * dp));
                        decoder.setTargetSize(r.width(), r.height());
                    });
        } catch (final IOException e) {
            Log.w(Config.LOGTAG, "WebxdcPage.getIcon: " + e);
            return null;
        }
    }

    public String getName() {
        String title = manifestName(manifest);
        return title == null ? "Widget" : title;
    }

    public String getTitle() {
        String title = manifestName(manifest);
        if (lastUpdate != null && lastUpdate.getDocument() != null) {
            if (title == null) {
                title = lastUpdate.getDocument();
            } else {
                title += ": " + lastUpdate.getDocument();
            }
        }
        return title == null ? "Widget" : title;
    }

    public String getNode() {
        return "webxdc\0" + source.getUuid();
    }

    public boolean threadMatches(final Element thread) {
        if (thread == null) return false;
        if (thread.getContent() == null) return false;
        if (source.getThread() == null) return false;
        return thread.getContent().equals(source.getThread().getContent());
    }

    public boolean openUri(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        xmppConnectionService.startActivity(intent);
        return true;
    }

    // SECURITY: webxdc apps must not reach the network. setBlockNetworkLoads() and the CSP
    // cover HTTP(S), but not WebRTC: Chromium does not enforce the CSP `webrtc` directive, and
    // ICE/STUN goes out as direct UDP that no WebView proxy override would capture (Chromium
    // needs the `disable_non_proxied_udp` policy for that, which WebView doesn't expose). A
    // malicious .xdc could otherwise learn the user's IP via its own STUN server.
    //
    // Fix (Delta Chat's audited "FILL500", https://delta.chat/en/2023-05-22-webxdc-security):
    // Chromium allows at most 500 RTCPeerConnections per renderer process, counted in the
    // constructor (before any network activity), and unclosed ones are never collected while
    // their document lives. Creating 500 before any app script runs therefore makes every
    // further RTCPeerConnection fail - in this document, in fresh about:blank/srcdoc frames
    // (which a JS-level deletion could not reach) and in other documents of the process.
    // The connections die with their document, so the budget is held for the whole process by
    // WebRtcKeeper (a never-navigating hidden WebView, filled before any webxdc page loads):
    // otherwise closing one webxdc app would hand the budget to another one still open. This
    // per-document copy runs first in every HTML document we serve as the backstop (it finds
    // the budget exhausted and does nothing else). If a 501st connection still succeeds (a
    // future Chromium without the limit), fall back to removing the WebRTC entry points.
    private static final String WEBRTC_BLOCKER = "<script>" + WebRtcKeeper.BLOCKER_JS + "</script>";

    // Document types other than text/html in which scripts can run.
    private static final java.util.Set<String> SCRIPTABLE_NON_HTML =
            new java.util.HashSet<>(
                    java.util.Arrays.asList(
                            "image/svg+xml",
                            "application/xhtml+xml",
                            "application/xml",
                            "text/xml"));

    /** Inserts {@link #WEBRTC_BLOCKER} as the very first script of an HTML document. */
    static byte[] injectWebRtcBlocker(final byte[] html) {
        final String doc = new String(html, java.nio.charset.StandardCharsets.UTF_8);
        final java.util.regex.Matcher head =
                java.util.regex.Pattern.compile(
                                "<head(\\s[^>]*)?>", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(doc);
        final java.util.regex.Matcher htmlTag =
                java.util.regex.Pattern.compile(
                                "<html(\\s[^>]*)?>", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(doc);
        final java.util.regex.Matcher doctype =
                java.util.regex.Pattern.compile(
                                "^\\s*<!doctype[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(doc);
        final int at;
        if (head.find()) {
            at = head.end();
        } else if (htmlTag.find()) {
            at = htmlTag.end();
        } else if (doctype.find()) {
            at = doctype.end();
        } else {
            at = 0;
        }
        return (doc.substring(0, at) + WEBRTC_BLOCKER + doc.substring(at))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    protected WebResourceResponse interceptRequest(String rawUrl) {
        Log.i(Config.LOGTAG, "interceptRequest: " + rawUrl);
        WebResourceResponse res = null;
        boolean scriptableNonHtml = false;
        try {
            if (zip == null) {
                throw new Exception("no zip found");
            }
            if (rawUrl == null) {
                throw new Exception("no url specified");
            }
            String path = Uri.parse(rawUrl).getPath();
            if (path.equalsIgnoreCase("/webxdc.js")) {
                InputStream targetStream =
                        xmppConnectionService.getResources().openRawResource(R.raw.webxdc);
                res = new WebResourceResponse("text/javascript", "UTF-8", targetStream);
            } else {
                ZipEntry entry = zip.getEntry(path.substring(1));
                if (entry == null) {
                    throw new Exception("\"" + path + "\" not found");
                }
                String mimeType = MimeUtils.guessFromPath(path);
                String encoding = mimeType.startsWith("text/") ? "UTF-8" : null;
                if ("text/html".equals(mimeType)) {
                    final byte[] html = ByteStreams.toByteArray(zip.getInputStream(entry));
                    res =
                            new WebResourceResponse(
                                    mimeType,
                                    encoding,
                                    new ByteArrayInputStream(injectWebRtcBlocker(html)));
                } else {
                    scriptableNonHtml = SCRIPTABLE_NON_HTML.contains(mimeType);
                    res = new WebResourceResponse(mimeType, encoding, zip.getInputStream(entry));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            InputStream targetStream =
                    new ByteArrayInputStream(
                            ("Webxdc Request Error: " + e.getMessage()).getBytes());
            res = new WebResourceResponse("text/plain", "UTF-8", targetStream);
        }

        if (res != null) {
            Map<String, String> headers = new HashMap<>();
            if (scriptableNonHtml) {
                // SVG/XHTML/XML can run scripts when loaded as a document, but don't get the
                // FILL500 injection. Navigating the top level to one would release the 500
                // connections of the previous document, so such documents may run no script.
                headers.put(
                        "Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data: blob:"
                                + " ; font-src 'self' data: ; sandbox");
            } else
                headers.put(
                        "Content-Security-Policy",
                        "default-src 'self'; "
                                + "style-src 'self' 'unsafe-inline' blob: ; "
                                + "font-src 'self' data: blob: ; "
                                + "script-src 'self' 'unsafe-inline' 'unsafe-eval' blob: ; "
                                + "connect-src 'self' data: blob: ; "
                                + "img-src 'self' data: blob: ; "
                                + "media-src 'self' data: blob: ; "
                                + "webrtc 'block' ; ");
            headers.put("X-DNS-Prefetch-Control", "off");
            res.setResponseHeaders(headers);
        }
        return res;
    }

    public View inflateUi(Context context, Consumer<ConversationPage> remover) {
        this.remover = new WeakReference<>(remover);
        if (binding != null) {
            binding.webview.loadUrl("javascript:__webxdcUpdate();");
            return getView();
        }
        binding =
                DataBindingUtil.inflate(
                        LayoutInflater.from(context), R.layout.webxdc_page, null, false);
        binding.webview.setWebViewClient(
                new WebViewClient() {
                    // `shouldOverrideUrlLoading()` is called when the user clicks a URL,
                    // returning `true` causes the WebView to abort loading the URL,
                    // returning `false` causes the WebView to continue loading the URL as usual.
                    // the method is not called for POST request nor for on-page-links.
                    //
                    // nb: from API 24, `shouldOverrideUrlLoading(String)` is deprecated and
                    // `shouldOverrideUrlLoading(WebResourceRequest)` shall be used.
                    // the new one has the same functionality, and the old one still exist,
                    // so, to support all systems, for now, using the old one seems to be the
                    // simplest way.
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, String url) {
                        if (url.startsWith(baseUrl)) return false;

                        if (url != null) {
                            Uri uri = Uri.parse(url);
                            switch (uri.getScheme()) {
                                case "http":
                                case "https":
                                case "mailto":
                                case "xmpp":
                                    return openUri(uri);
                            }
                        }
                        // by returning `true`, we also abort loading other URLs in our WebView;
                        // eg. that might be weird or internal protocols.
                        // if we come over really useful things, we should allow that explicitly.
                        return true;
                    }

                    @Override
                    @SuppressWarnings("deprecation")
                    public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                        WebResourceResponse res = interceptRequest(url);
                        if (res != null) {
                            return res;
                        }
                        return super.shouldInterceptRequest(view, url);
                    }

                    @Override
                    @RequiresApi(21)
                    public WebResourceResponse shouldInterceptRequest(
                            WebView view, WebResourceRequest request) {
                        WebResourceResponse res = interceptRequest(request.getUrl().toString());
                        if (res != null) {
                            return res;
                        }
                        return super.shouldInterceptRequest(view, request);
                    }
                });

        binding.webview.setWebChromeClient(
                new WebChromeClient() {
                    @Override
                    public boolean onShowFileChooser(
                            WebView webView,
                            ValueCallback<Uri[]> filePathCallback,
                            WebChromeClient.FileChooserParams fileChooserParams) {
                        // WebxdcActivity.this.filePathCallback = filePathCallback;
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        intent.putExtra(
                                Intent.EXTRA_ALLOW_MULTIPLE,
                                fileChooserParams.getMode()
                                        == FileChooserParams.MODE_OPEN_MULTIPLE);
                        final XmppActivity activity = WebxdcPage.this.activity.get();
                        if (activity != null)
                            activity.startActivityWithCallback(
                                    Intent.createChooser(intent, "Choose a file"),
                                    filePathCallback);
                        return activity != null;
                    }
                });

        // disable "safe browsing" as this has privacy issues,
        // eg. at least false positives are sent to the "Safe Browsing Lookup API".
        // as all URLs opened in the WebView are local anyway,
        // "safe browsing" will never be able to report issues, so it can be disabled.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.webview.getSettings().setSafeBrowsingEnabled(false);
        }

        WebSettings webSettings = binding.webview.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setAllowFileAccess(false);
        webSettings.setBlockNetworkLoads(true);
        webSettings.setAllowContentAccess(false);
        webSettings.setGeolocationEnabled(false);
        webSettings.setAllowFileAccessFromFileURLs(false);
        webSettings.setAllowUniversalAccessFromFileURLs(false);
        webSettings.setDatabaseEnabled(true);
        webSettings.setDomStorageEnabled(true);
        binding.webview.setNetworkAvailable(
                false); // this does not block network but sets `window.navigator.isOnline` in js
        // land
        binding.webview.addJavascriptInterface(new InternalJSApi(), "InternalJSApi");

        // Only once the WebRTC budget of this renderer process is held for good (WebRtcKeeper).
        WebRtcKeeper.whenReady(
                context,
                () -> {
                    if (!closed && binding != null)
                        binding.webview.loadUrl(baseUrl + "/index.html");
                });

        final var actions =
                source.getStatus() == Message.STATUS_DUMMY
                        ? new String[] {activity.get().getString(R.string.action_close)}
                        : new String[] {
                            activity.get().getString(R.string.add_to_home_screen),
                            activity.get().getString(R.string.action_close)
                        };

        if (source.getStatus() == Message.STATUS_DUMMY) binding.actions.setNumColumns(1);

        binding.actions.setAdapter(
                new ArrayAdapter<String>(context, R.layout.simple_list_item, actions) {
                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        View v = super.getView(position, convertView, parent);
                        TextView tv = (TextView) v.findViewById(android.R.id.text1);
                        tv.setGravity(Gravity.CENTER);
                        tv.setTextColor(ContextCompat.getColor(context, R.color.white));
                        tv.setBackgroundColor(
                                MaterialColors.harmonizeWithPrimary(
                                        activity.get(),
                                        UIHelper.getColorForName(getItem(position))));
                        return v;
                    }
                });
        binding.actions.setOnItemClickListener((parent, v, pos, id) -> {
            if (pos == 0 && actions.length > 1) {
                Intent intent = new Intent(xmppConnectionService, ConversationsActivity.class);
                intent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
                intent.putExtra(
                        ConversationsActivity.EXTRA_CONVERSATION,
                        ((Conversation) source.getConversation()).getUuid());
                intent.putExtra(ConversationsActivity.EXTRA_POST_INIT_ACTION, "webxdc");
                intent.putExtra(ConversationsActivity.EXTRA_DOWNLOAD_UUID, source.getUuid());
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

                ShortcutInfoCompat.Builder builder =
                        new ShortcutInfoCompat.Builder(
                                        xmppConnectionService, "webxdc:" + source.getUuid())
                                .setShortLabel(getTitle())
                                .setIntent(intent);
                Drawable icon = getIcon();
                if (icon != null && icon instanceof BitmapDrawable) {
                    builder =
                            builder.setIcon(
                                    IconCompat.createFromIcon(
                                            Icon.createWithBitmap(
                                                    ((BitmapDrawable) icon).getBitmap())));
                }
                ShortcutManagerCompat.requestPinShortcut(
                        xmppConnectionService, builder.build(), null);
            } else {
                closed = true;
                binding.webview.loadUrl("about:blank");
                remover.accept(WebxdcPage.this);
            }
        });

        return getView();
    }

    public View getView() {
        if (binding == null) return null;
        return binding.getRoot();
    }

    public void refresh() {
        if (source.getStatus() == Message.STATUS_DUMMY) return;
        if (binding == null) return;
        binding.webview.post(() -> binding.webview.loadUrl("javascript:__webxdcUpdate();"));
    }

    public void realtimeData(String base64) {
        if (source.getStatus() == Message.STATUS_DUMMY) return;
        if (binding == null) return;

        binding.webview.post(() ->
                binding.webview.loadUrl(
                        "javascript:__webxdcRealtimeData('"
                                + base64.replace("'", "").replace("\\", "").replace("+", "%2B")
                                + "');"));
    }

    protected Jid selfJid() {
        final Conversation conversation = (Conversation) source.getConversation();
        if (conversation.getMode() == Conversation.MODE_MULTI
                && !conversation.getMucOptions().nonanonymous()) {
            return conversation.getMucOptions().getSelf().getFullJid();
        } else {
            return source.getConversation().getAccount().getJid().asBareJid();
        }
    }

    protected class InternalJSApi {
        @JavascriptInterface
        public String selfAddr() {
            final Conversation conversation = (Conversation) source.getConversation();
            if (conversation.getMode() == Conversation.MODE_MULTI
                    && !conversation.getMucOptions().nonanonymous()) {
                final var occupantId = conversation.getMucOptions().getSelf().getOccupantId();
                if (occupantId != null) return occupantId;
            }
            return "xmpp:" + Uri.encode(selfJid().toString(), "@/+");
        }

        @JavascriptInterface
        public String selfName() {
            final Conversation conversation = (Conversation) source.getConversation();
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                return conversation.getMucOptions().getActualNick();
            } else {
                final String displayName = conversation.getAccount().getDisplayName();
                return displayName == null || "".equals(displayName)
                        ? conversation.getAccount().getUsername()
                        : displayName;
            }
        }

        @JavascriptInterface
        public boolean sendStatusUpdate(String paramS, String descr) {
            if (source.getStatus() == Message.STATUS_DUMMY) return false;

            JSONObject params = new JSONObject();
            try {
                params = new JSONObject(paramS);
            } catch (final JSONException e) {
                Log.w(Config.LOGTAG, "WebxdcPage sendStatusUpdate invalid JSON: " + e);
            }
            String payload = null;
            int encryption;
            if (source.getConversation() instanceof Conversation) {
                encryption = ((Conversation) source.getConversation()).getNextEncryption();
            } else {
                encryption = source.getEncryption();
            }
            Message message = new Message(source.getConversation(), descr, encryption);
            message.addPayload(new Element("store", "urn:xmpp:hints"));
            Element webxdc = new Element("x", "urn:xmpp:webxdc:0");
            message.addPayload(webxdc);
            if (params.has("payload")) {
                payload = JSONObject.wrap(params.opt("payload")).toString();
                webxdc.addChild("json", "urn:xmpp:json:0").setContent(payload);
            }
            if (params.has("document")) {
                webxdc.addChild("document").setContent(params.optString("document", null));
            }
            if (params.has("summary")) {
                webxdc.addChild("summary").setContent(params.optString("summary", null));
            }
            final JSONObject notifyDict = params.optJSONObject("notify");
            if (notifyDict != null && notifyDict.length() > 0) {
                webxdc.addChild("notify", "urn:xmpp:webxdc:0").setContent(notifyDict.toString());
            }
            message.setBody(params.optString("info", null));
            message.setThread(source.getThread());
            if (source.isPrivateMessage()) {
                Message.configurePrivateMessage(message, source.getCounterpart());
            }
            xmppConnectionService.sendMessage(message);
            xmppConnectionService.insertWebxdcUpdate(
                    new WebxdcUpdate(
                            (Conversation) message.getConversation(),
                            message.getUuid(),
                            selfJid(),
                            message.getThread(),
                            params.optString("info", null),
                            params.optString("document", null),
                            params.optString("summary", null),
                            payload));
            binding.webview.post(() -> binding.webview.loadUrl("javascript:__webxdcUpdate();"));
            return true;
        }

        @JavascriptInterface
        public String getStatusUpdates(long lastKnownSerial) {
            if (source.getStatus() == Message.STATUS_DUMMY) return "[]";

            StringBuilder builder = new StringBuilder("[");
            String sep = "";
            for (WebxdcUpdate update :
                    xmppConnectionService.findWebxdcUpdates(source, lastKnownSerial)) {
                lastUpdate = update;
                builder.append(sep);
                builder.append(update.toString());
                sep = ",";
            }
            builder.append("]");
            return builder.toString();
        }

        @JavascriptInterface
        public String sendToChat(String message) {
            try {
                JSONObject jsonObject = new JSONObject(message);

                String text = null;
                String data = null;
                String name = null;
                if (jsonObject.has("base64")) {
                    data = jsonObject.getString("base64");
                }
                if (jsonObject.has("name")) {
                    name = jsonObject.getString("name");
                }
                if (jsonObject.has("text")) {
                    text = jsonObject.getString("text");
                }

                Intent intent = new Intent(xmppConnectionService, ConversationsActivity.class);
                intent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
                intent.putExtra(
                        ConversationsActivity.EXTRA_CONVERSATION,
                        ((Conversation) source.getConversation()).getUuid());
                if (text != null) intent.putExtra(Intent.EXTRA_TEXT, text);
                if (data != null) {
                    var mimeType = name == null ? null : MimeUtils.guessFromPath(name);
                    if (mimeType == null) mimeType = "application/octet-stream";
                    intent.putExtra(
                            Intent.EXTRA_STREAM, Uri.parse("data:" + mimeType + ";base64," + data));
                }
                activity.get()
                        .runOnUiThread(() -> {
                            if (source.getStatus() == Message.STATUS_DUMMY) {
                                closed = true;
                                binding.webview.loadUrl("about:blank");
                                final var remover = WebxdcPage.this.remover.get();
                                if (remover != null) remover.accept(WebxdcPage.this);
                            }
                            activity.get().startActivity(intent);
                        });
                return null;
            } catch (Exception e) {
                e.printStackTrace();
                return e.toString();
            }
        }

        @JavascriptInterface
        public void sendRealtime(byte[] data) {
            if (source.getStatus() == Message.STATUS_DUMMY) return;

            final int encryption;
            if (source.getConversation() instanceof Conversation) {
                encryption = ((Conversation) source.getConversation()).getNextEncryption();
            } else {
                encryption = source.getEncryption();
            }
            Message message = new Message(source.getConversation(), null, encryption);
            message.addPayload(new Element("no-store", "urn:xmpp:hints"));
            Element webxdc = new Element("x", "urn:xmpp:webxdc:0");
            message.addPayload(webxdc);
            webxdc.addChild("data").setContent(Base64.encodeToString(data, Base64.NO_WRAP));
            message.setThread(source.getThread());
            if (source.isPrivateMessage()) {
                Message.configurePrivateMessage(message, source.getCounterpart());
            }
            message.setBody((String) null);
            xmppConnectionService.sendMessage(message);
        }
    }
}
