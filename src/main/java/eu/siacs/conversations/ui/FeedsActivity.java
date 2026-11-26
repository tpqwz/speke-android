package eu.siacs.conversations.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import de.monocles.chat.feeds.FeedList;
import de.monocles.chat.feeds.FeedXml;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.service.AudioMiniPlayer;
import eu.siacs.conversations.ui.service.BackgroundAudioController;
import eu.siacs.conversations.ui.util.BackHandler;
import eu.siacs.conversations.ui.util.FloatingBars;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * RSS and Podcasts over one list of feed addresses. RSS shows each item's link. Podcasts shows
 * items with an audio enclosure. Sharing sends that address into a chat. An episode plays on the
 * in-app player.
 */
public class FeedsActivity extends XmppActivity {

    public static final String EXTRA_PODCASTS = "podcasts";

    private static final String FILE_NAME = "feeds.txt";
    private static final int MAX_FILE_BYTES = 256 * 1024;

    public static Intent intent(Context context, boolean podcasts, boolean showNavBar) {
        Intent intent = new Intent(context, FeedsActivity.class);
        intent.putExtra(EXTRA_PODCASTS, podcasts);
        intent.putExtra("show_nav_bar", showNavBar);
        return intent;
    }

    private boolean podcasts;
    private volatile int generation;
    private BottomNavigationView bottomNavigation;
    private TextView empty;
    private AudioMiniPlayer audioMiniPlayer;
    private final List<Row> shown = new ArrayList<>();
    private ArrayAdapter<Row> adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        podcasts = getIntent().getBooleanExtra(EXTRA_PODCASTS, false);
        BackHandler.register(this, this::handleBack);
        setContentView(R.layout.activity_feeds);
        View miniPlayerRoot = findViewById(R.id.audio_miniplayer);
        if (miniPlayerRoot != null) {
            audioMiniPlayer = new AudioMiniPlayer(this, miniPlayerRoot);
        }
        Activities.setSystemBarsTransparent(this, findViewById(android.R.id.content));
        FloatingBars.behindSystemBars(this);
        View appBar = findViewById(R.id.app_bar);
        bottomNavigation = findViewById(R.id.bottom_navigation);
        FloatingBars.belowStatusBar(appBar);
        FloatingBars.aboveNavigationBar(bottomNavigation);
        setSupportActionBar(findViewById(R.id.toolbar));
        configureActionBar(getSupportActionBar());
        setTitle(podcasts ? R.string.podcasts : R.string.rss);

        ListView list = findViewById(R.id.feeds_list);
        empty = findViewById(R.id.feeds_empty);
        FloatingBars.inset(list, appBar, bottomNavigation);
        adapter =
                new ArrayAdapter<>(
                        this, android.R.layout.simple_list_item_2, android.R.id.text1, shown) {
                    @NonNull
                    @Override
                    public View getView(
                            int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                        View view = super.getView(position, convertView, parent);
                        Row row = getItem(position);
                        TextView title = view.findViewById(android.R.id.text1);
                        TextView subtitle = view.findViewById(android.R.id.text2);
                        if (row == null) {
                            return view;
                        }
                        title.setText(row.title);
                        subtitle.setText(row.when.isEmpty() ? row.feedUrl : row.when);
                        title.setMaxLines(2);
                        subtitle.setMaxLines(1);
                        return view;
                    }
                };
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener(
                (parent, view, position, id) -> {
                    Row row = adapter.getItem(position);
                    if (row != null) {
                        onRow(row);
                    }
                });
        list.setOnItemLongClickListener(
                (parent, view, position, id) -> {
                    Row row = adapter.getItem(position);
                    if (row != null) {
                        confirmRemove(row.feedUrl);
                    }
                    return true;
                });

        bottomNavigation.getMenu().clear();
        bottomNavigation.inflateMenu(
                podcasts
                        ? R.menu.bottom_navigation_menu_podcasts
                        : R.menu.bottom_navigation_menu_rss);
        bottomNavigation.setOnItemSelectedListener(
                item -> {
                    int navId = item.getItemId();
                    if (navId == R.id.chats) {
                        startActivity(new Intent(this, ConversationsActivity.class));
                        overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                        return true;
                    } else if (navId == R.id.calls) {
                        Intent calls = new Intent(this, CallsActivity.class);
                        calls.putExtra("show_nav_bar", true);
                        startActivity(calls);
                        overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                        return true;
                    } else if (navId == R.id.rss) {
                        if (!podcasts) {
                            return true;
                        }
                        startActivity(intent(this, false, true));
                        overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                        return true;
                    } else if (navId == R.id.podcasts) {
                        if (podcasts) {
                            return true;
                        }
                        startActivity(intent(this, true, true));
                        overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
                        return true;
                    }
                    return false;
                });
    }

    @Override
    public void onPause() {
        if (audioMiniPlayer != null) {
            audioMiniPlayer.onPause();
        }
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (audioMiniPlayer != null) {
            audioMiniPlayer.onResume();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        bottomNavigation.setSelectedItemId(podcasts ? R.id.podcasts : R.id.rss);
        if (getBooleanPreference("show_nav_bar", R.bool.show_nav_bar)
                && getIntent().getBooleanExtra("show_nav_bar", false)) {
            bottomNavigation.setVisibility(View.VISIBLE);
        } else {
            bottomNavigation.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_feeds, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_add_feed) {
            askForFeed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onBackendConnected() {
        refreshUiReal();
        reload();
    }

    @Override
    protected void refreshUiReal() {
        if (xmppConnectionService == null) {
            return;
        }
        int unreadCount = xmppConnectionService.unreadCount();
        var chatsBadge = bottomNavigation.getOrCreateBadge(R.id.chats);
        chatsBadge.setNumber(unreadCount);
        chatsBadge.setVisible(unreadCount > 0);
        chatsBadge.setHorizontalOffset(20);
        boolean missed = xmppConnectionService.getNotificationService().hasNewMissedCalls();
        bottomNavigation.getOrCreateBadge(R.id.calls).setVisible(missed);
        ActionBar actionBar = getSupportActionBar();
        boolean showNavBar = bottomNavigation.getVisibility() == View.VISIBLE;
        if (actionBar != null) {
            actionBar.setHomeButtonEnabled(!showNavBar);
            actionBar.setDisplayHomeAsUpEnabled(!showNavBar);
        }
    }

    private boolean handleBack() {
        if (bottomNavigation.getVisibility() == View.VISIBLE) {
            Intent intent = new Intent(this, ConversationsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
            overridePendingTransition(R.animator.fade_in, R.animator.fade_out);
        }
        return false;
    }

    private void askForFeed() {
        EditText input = new EditText(this);
        input.setHint(R.string.add_feed_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_feed)
                .setView(input)
                .setPositiveButton(R.string.add, (dialog, which) -> addFeed(input.getText().toString()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void addFeed(String raw) {
        String url = FeedList.canonical(raw);
        if (url == null) {
            replaceToast(getString(R.string.feed_rejected));
            return;
        }
        List<String> urls = FeedList.read(readFeeds());
        if (urls.contains(url)) {
            reload();
            return;
        }
        if (urls.size() >= FeedList.MAX_FEEDS) {
            replaceToast(getString(R.string.feed_list_full));
            return;
        }
        urls.add(url);
        if (store(urls)) {
            reload();
        }
    }

    private void confirmRemove(String feedUrl) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.remove_feed)
                .setMessage(feedUrl)
                .setPositiveButton(
                        R.string.delete,
                        (dialog, which) -> {
                            List<String> urls = FeedList.read(readFeeds());
                            urls.remove(feedUrl);
                            if (store(urls)) {
                                reload();
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void onRow(Row row) {
        if (podcasts) {
            new AlertDialog.Builder(this)
                    .setTitle(row.title)
                    .setItems(
                            new CharSequence[] {
                                getString(R.string.share_to_chat), getString(R.string.play_episode)
                            },
                            (dialog, which) -> {
                                if (which == 0) {
                                    share(row.audio);
                                } else {
                                    play(row.audio, row.title);
                                }
                            })
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(row.title)
                    .setItems(
                            new CharSequence[] {
                                getString(R.string.share_to_chat), getString(R.string.open_link)
                            },
                            (dialog, which) -> {
                                if (which == 0) {
                                    share(row.link);
                                } else {
                                    openLink(row.link);
                                }
                            })
                    .show();
        }
    }

    private void share(String url) {
        String safe = FeedList.canonical(url);
        if (safe == null || xmppConnectionService == null) {
            replaceToast(getString(safe == null ? R.string.feed_rejected : R.string.feeds_offline));
            return;
        }
        List<Conversation> conversations = new ArrayList<>(xmppConnectionService.getConversations());
        if (conversations.isEmpty()) {
            replaceToast(getString(R.string.no_chats));
            return;
        }
        String[] names = new String[conversations.size()];
        for (int i = 0; i < conversations.size(); i++) {
            CharSequence name = conversations.get(i).getName();
            names[i] = name == null || name.length() == 0 ? safe : name.toString();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_chat)
                .setItems(
                        names,
                        (dialog, which) -> {
                            Conversation conversation = conversations.get(which);
                            if (xmppConnectionService == null) {
                                return;
                            }
                            xmppConnectionService.sendMessage(
                                    new Message(
                                            conversation, safe, conversation.getNextEncryption()));
                            replaceToast(getString(R.string.feed_shared));
                        })
                .show();
    }

    private void openLink(String url) {
        String safe = FeedList.canonical(url);
        if (safe == null) {
            replaceToast(getString(R.string.feed_rejected));
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(safe)));
        } catch (ActivityNotFoundException e) {
            replaceToast(getString(R.string.feed_rejected));
        }
    }

    private void play(String url, String title) {
        String safe = FeedList.canonical(url);
        if (safe == null) {
            replaceToast(getString(R.string.feed_rejected));
            return;
        }
        String shownTitle = title == null || title.isEmpty() ? safe : title;
        BackgroundAudioController.getInstance(this).playRemote(Uri.parse(safe), shownTitle);
        replaceToast(getString(R.string.feed_playing));
    }

    private void reload() {
        final int gen = ++generation;
        List<String> urls = FeedList.read(readFeeds());
        if (urls.isEmpty()) {
            show(List.of(), R.string.feeds_empty);
            return;
        }
        if (xmppConnectionService == null) {
            show(List.of(), R.string.feeds_offline);
            return;
        }
        Account account = null;
        for (Account candidate : xmppConnectionService.getAccounts()) {
            if (candidate.isOnlineAndConnected()) {
                account = candidate;
                break;
            }
        }
        if (account == null) {
            show(List.of(), R.string.feeds_offline);
            return;
        }
        empty.setText(R.string.feeds_loading);
        final XmppConnectionService service = xmppConnectionService;
        final Account online = account;
        final boolean wantAudio = podcasts;
        new Thread(
                        () -> {
                            List<Row> rows = new ArrayList<>();
                            for (String url : urls) {
                                if (gen != generation) {
                                    return;
                                }
                                try {
                                    rows.addAll(fetch(service, online, url, wantAudio));
                                } catch (Exception e) {
                                    Log.d(Config.LOGTAG, "skipped a feed", e);
                                }
                            }
                            runOnUiThread(
                                    () -> {
                                        if (gen != generation || isFinishing()) {
                                            return;
                                        }
                                        show(rows, R.string.feeds_none);
                                    });
                        },
                        "feeds")
                .start();
    }

    private void show(List<Row> rows, int emptyText) {
        shown.clear();
        shown.addAll(rows);
        adapter.notifyDataSetChanged();
        empty.setText(emptyText);
    }

    private List<Row> fetch(
            XmppConnectionService service, Account account, String feedUrl, boolean wantAudio)
            throws IOException {
        HttpUrl httpUrl = HttpUrl.parse(feedUrl);
        if (httpUrl == null
                || !HttpConnectionManager.linkPreviewUrlAllowed(httpUrl)
                || HttpConnectionManager.isLocalTarget(httpUrl.host())) {
            return List.of();
        }
        OkHttpClient base = service.getHttpConnectionManager().buildHttpClient(httpUrl, account, false);
        boolean proxied =
                service.useTorToConnect()
                        || service.useI2PToConnect()
                        || account.isOnion()
                        || account.isI2P();
        OkHttpClient client = HttpConnectionManager.linkPreviewClient(base, proxied);
        Request request =
                new Request.Builder()
                        .url(httpUrl)
                        .header("User-Agent", "LaNoxe")
                        .header(
                                "Accept",
                                "application/rss+xml, application/atom+xml, application/xml, text/xml")
                        .build();
        try (Response response = HttpConnectionManager.executeLinkPreviewRequest(client, request)) {
            if (!response.isSuccessful() || response.body() == null) {
                return List.of();
            }
            byte[] xml = readCapped(response.body().byteStream());
            FeedXml.Feed feed = FeedXml.parse(xml, response.request().url().toString());
            List<Row> rows = new ArrayList<>();
            for (FeedXml.Item item : feed.items) {
                String link = FeedList.canonical(item.link);
                String audio = FeedList.canonical(item.audio);
                if (wantAudio) {
                    if (audio == null) {
                        continue;
                    }
                } else if (link == null) {
                    continue;
                }
                String title = item.title == null || item.title.isEmpty() ? (wantAudio ? audio : link) : item.title;
                rows.add(new Row(feedUrl, title, link, audio, item.when == null ? "" : item.when));
            }
            return rows;
        }
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (total + n > FeedXml.MAX_BYTES) {
                throw new IOException("feed is larger than 1 MB");
            }
            out.write(buf, 0, n);
            total += n;
        }
        return out.toByteArray();
    }

    private String readFeeds() {
        File file = new File(getFilesDir(), FILE_NAME);
        if (!file.isFile()) {
            return "";
        }
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(buf)) >= 0 && total < MAX_FILE_BYTES) {
                int take = Math.min(n, MAX_FILE_BYTES - total);
                out.write(buf, 0, take);
                total += take;
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.d(Config.LOGTAG, "could not read feeds", e);
            return "";
        }
    }

    private boolean store(List<String> urls) {
        File dir = getFilesDir();
        File dest = new File(dir, FILE_NAME);
        File tmp = new File(dir, FILE_NAME + ".tmp");
        byte[] bytes = FeedList.write(urls).getBytes(StandardCharsets.UTF_8);
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            if (dest.exists() && !dest.delete()) {
                Log.d(Config.LOGTAG, "could not replace the feed list");
            }
            if (!tmp.renameTo(dest)) {
                try (FileOutputStream out = new FileOutputStream(dest)) {
                    out.write(bytes);
                }
                tmp.delete();
            }
            return true;
        } catch (IOException e) {
            Log.d(Config.LOGTAG, "could not save feeds", e);
            replaceToast(getString(R.string.feed_not_saved));
            return false;
        }
    }

    private static final class Row {
        final String feedUrl;
        final String title;
        final String link;
        final String audio;
        final String when;

        Row(String feedUrl, String title, String link, String audio, String when) {
            this.feedUrl = feedUrl;
            this.title = title;
            this.link = link;
            this.audio = audio;
            this.when = when;
        }

        @NonNull
        @Override
        public String toString() {
            return title;
        }
    }
}
