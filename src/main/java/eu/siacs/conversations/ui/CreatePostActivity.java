package eu.siacs.conversations.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import com.bumptech.glide.Glide;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityCreatePostBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import java.util.ArrayList;
import java.util.List;

public class CreatePostActivity extends XmppActivity {

    private ActivityCreatePostBinding binding;
    private String inReplyToId;
    private String inReplyToNode;
    private String postId;
    private Uri attachmentUri;
    private String attachmentType;
    private Uri mCameraUri;
    private String accountUuid;
    // A remote attachment (editing a post) whose preview waits for the backend, see
    // showAttachmentPreview().
    private String pendingAttachmentPreviewUrl;
    private String postTitle;
    private String postContent;
    private String linkUrl;

    private List<Account> onlineAccounts = new ArrayList<>();

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    isGranted -> {
                        if (isGranted) {
                            openCamera();
                        } else {
                            Toast.makeText(this, R.string.no_camera_permission, Toast.LENGTH_SHORT)
                                    .show();
                        }
                    });

    private final ActivityResultLauncher<Intent> takePictureLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            attachmentUri = mCameraUri;
                            attachmentType = "image/jpeg";
                            showImagePreview(attachmentUri);
                            binding.attachmentPreview.setVisibility(View.VISIBLE);
                            binding.attachmentVideoView.setVisibility(View.GONE);
                        }
                    });

    private final ActivityResultLauncher<Intent> takeVideoLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            attachmentUri = mCameraUri;
                            attachmentType = "video/mp4";
                            binding.attachmentVideoView.setVideoURI(attachmentUri);
                            binding.attachmentVideoView.setOnPreparedListener(mp -> {
                                mp.setLooping(true);
                                binding.attachmentVideoView.start();
                            });
                            binding.attachmentVideoView.setVisibility(View.VISIBLE);
                            binding.attachmentPreview.setVisibility(View.GONE);
                        }
                    });

    private final ActivityResultLauncher<String> attachFileLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri != null) {
                            this.attachmentUri = uri;
                            this.attachmentType = getContentResolver().getType(attachmentUri);
                            if (this.attachmentType != null
                                    && this.attachmentType.startsWith("image/")) {
                                showImagePreview(attachmentUri);
                                binding.attachmentPreview.setVisibility(View.VISIBLE);
                                binding.attachmentVideoView.setVisibility(View.GONE);
                            } else if (this.attachmentType != null
                                    && this.attachmentType.startsWith("video/")) {
                                binding.attachmentVideoView.setVideoURI(attachmentUri);
                                binding.attachmentVideoView.setOnPreparedListener(mp -> {
                                    mp.setLooping(true);
                                    binding.attachmentVideoView.start();
                                });
                                binding.attachmentVideoView.setVisibility(View.VISIBLE);
                                binding.attachmentPreview.setVisibility(View.GONE);
                            }
                        }
                    });

    private void showImagePreview(final Uri uri) {
        Glide.with(this).load(uri).into(binding.attachmentPreview);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_create_post);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        inReplyToId = getIntent().getStringExtra("in_reply_to_id");
        inReplyToNode = getIntent().getStringExtra("in_reply_to_node");
        postId = getIntent().getStringExtra("post_id");
        postTitle = getIntent().getStringExtra("post_title");
        postContent = getIntent().getStringExtra("post_content");
        accountUuid = getIntent().getStringExtra("account");
        this.linkUrl = getIntent().getStringExtra("link_url");
        if (inReplyToNode != null) {
            setTitle(R.string.add_a_comment);
            binding.postTitleEditText.setVisibility(View.GONE);
            binding.postContentEditText.setHint(R.string.comment);
            binding.attachFileButton.setVisibility(View.GONE);
            binding.attachImageButton.setVisibility(View.GONE);
            binding.attachVideoButton.setVisibility(View.GONE);
            binding.postLinkEditText.setVisibility(View.GONE);
            binding.postPreview.setVisibility(View.VISIBLE);
            binding.postPreviewTitle.setText(postTitle);
            binding.postPreviewContent.setText(postContent);
        } else {
            binding.postTitleEditText.setVisibility(View.VISIBLE);
            binding.postContentEditText.setHint(R.string.post_content);
        }
        if (postId != null) {
            binding.postTitleEditText.setText(getIntent().getStringExtra("title"));
            binding.postContentEditText.setText(getIntent().getStringExtra("content"));
            binding.postLinkEditText.setText(getIntent().getStringExtra("link_url"));
            if (accountUuid != null) {
                binding.accountSpinner.setVisibility(View.GONE);
            }
            final String attachmentUrl = getIntent().getStringExtra("attachment_url");
            this.attachmentType = getIntent().getStringExtra("attachment_type");
            if (attachmentUrl != null && this.attachmentType != null) {
                this.attachmentUri = Uri.parse(attachmentUrl);
                // The account's routing (a .onion/.i2p account forces Tor/I2P) is only known
                // once the backend is bound: until then nothing remote is fetched.
                this.pendingAttachmentPreviewUrl = attachmentUrl;
                if (xmppConnectionService != null) showAttachmentPreview();
            }
        }

        binding.publishButton.setOnClickListener(v -> publishPost());
        binding.attachFileButton.setOnClickListener(v -> attachFileLauncher.launch("*/*"));
        binding.attachImageButton.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });
        binding.attachVideoButton.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                openVideoCamera();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });
    }

    private void openCamera() {
        if (xmppConnectionService == null) {
            Toast.makeText(this, R.string.not_connected_try_again, Toast.LENGTH_SHORT).show();
            return;
        }
        mCameraUri = xmppConnectionService.getFileBackend().getTakePhotoUri();
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, mCameraUri);
        takePictureLauncher.launch(takePictureIntent);
    }

    private void openVideoCamera() {
        if (xmppConnectionService == null) {
            Toast.makeText(this, R.string.not_connected_try_again, Toast.LENGTH_SHORT).show();
            return;
        }
        mCameraUri = xmppConnectionService.getFileBackend().getTakeVideoUri();
        Intent takeVideoIntent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        takeVideoIntent.putExtra(MediaStore.EXTRA_OUTPUT, mCameraUri);
        takeVideoLauncher.launch(takeVideoIntent);
    }

    @Override
    protected void refreshUiReal() {}

    /** True if fetching {@code uri} must go through Tor/I2P (see HttpConnectionManager.route). */
    private void showAttachmentPreview() {
        final String attachmentUrl = this.pendingAttachmentPreviewUrl;
        if (attachmentUrl == null || this.attachmentType == null) return;
        this.pendingAttachmentPreviewUrl = null;
        final Account account =
                accountUuid == null || xmppConnectionService == null
                        ? null
                        : xmppConnectionService.findAccountByUuid(accountUuid);
        if (this.attachmentType.startsWith("image/")) {
            binding.attachmentPreview.setVisibility(View.VISIBLE);
            Glide.with(this)
                    .load(eu.siacs.conversations.http.RoutedUrl.of(attachmentUrl, account))
                    .into(binding.attachmentPreview);
        } else if (this.attachmentType.startsWith("video/")
                && !needsProxy(Uri.parse(attachmentUrl))) {
            // VideoView/MediaPlayer streams outside the app's Tor/I2P routing, so a
            // remote video is only previewed when it may be fetched directly.
            binding.attachmentVideoView.setVisibility(View.VISIBLE);
            binding.attachmentVideoView.setVideoURI(Uri.parse(attachmentUrl));
            binding.attachmentVideoView.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                binding.attachmentVideoView.start();
            });
        }
    }

    private boolean needsProxy(final Uri uri) {
        final String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) return false;
        final var preferences =
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        boolean tor = preferences.getBoolean("use_tor", getResources().getBoolean(R.bool.use_tor));
        boolean i2p = preferences.getBoolean("use_i2p", getResources().getBoolean(R.bool.use_i2p));
        if (accountUuid != null && xmppConnectionService != null) {
            final Account account = xmppConnectionService.findAccountByUuid(accountUuid);
            if (account != null) {
                tor |= account.isOnion();
                i2p |= account.isI2P();
            }
        }
        return eu.siacs.conversations.http.HttpConnectionManager.route(uri.getHost(), tor, i2p)
                != eu.siacs.conversations.http.HttpConnectionManager.Route.DIRECT;
    }

    @Override
    public void onBackendConnected() {
        showAttachmentPreview();
        if (xmppConnectionService != null && accountUuid == null) {
            onlineAccounts.clear();
            List<String> accountJids = new ArrayList<>();
            for (Account account : xmppConnectionService.getAccounts()) {
                if (account.isOnlineAndConnected()) {
                    onlineAccounts.add(account);
                    accountJids.add(account.getJid().asBareJid().toString());
                }
            }
            ArrayAdapter<String> adapter =
                    new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, accountJids);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            binding.accountSpinner.setAdapter(adapter);

            int persistedPosition = getPersistedItem();
            if (!onlineAccounts.isEmpty() && persistedPosition < onlineAccounts.size()) {
                binding.accountSpinner.setSelection(persistedPosition);
            }

            binding.accountSpinner.setOnItemSelectedListener(
                    new AdapterView.OnItemSelectedListener() {
                        @Override
                        public void onItemSelected(
                                AdapterView<?> parentView, View view, int position, long itemId) {
                            setPersistedItem(position);
                        }

                        @Override
                        public void onNothingSelected(AdapterView<?> arg0) {
                            // Do nothing
                        }
                    });
        }
    }

    private void publishPost() {
        String title = binding.postTitleEditText.getText().toString();
        String content = binding.postContentEditText.getText().toString();
        String linkUrl = binding.postLinkEditText.getText().toString();

        if (title.isEmpty() && content.isEmpty() && attachmentUri == null && linkUrl.isEmpty()) {
            Toast.makeText(
                            this,
                            R.string.title_or_content_or_attachment_required,
                            Toast.LENGTH_SHORT)
                    .show();
            return;
        }

        if (xmppConnectionService != null) {
            Account selectedAccount;
            if (accountUuid != null) {
                selectedAccount = xmppConnectionService.findAccountByUuid(accountUuid);
                if (selectedAccount == null) {
                    Toast.makeText(this, getString(R.string.account_not_found), Toast.LENGTH_SHORT)
                            .show();
                    return;
                }
            } else {
                if (binding.accountSpinner.getSelectedItemPosition() < 0
                        || binding.accountSpinner.getSelectedItemPosition()
                                >= onlineAccounts.size()) {
                    Toast.makeText(this, R.string.no_active_account, Toast.LENGTH_SHORT).show();
                    return;
                }
                selectedAccount =
                        onlineAccounts.get(binding.accountSpinner.getSelectedItemPosition());
            }

            if (!selectedAccount.isOnlineAndConnected()) {
                Toast.makeText(this, R.string.account_not_connected, Toast.LENGTH_SHORT).show();
                return;
            }

            binding.publishButton.setEnabled(false);

            if (inReplyToNode != null) {
                xmppConnectionService.publishComment(
                        selectedAccount,
                        inReplyToNode,
                        content,
                        new XmppConnectionService.OnPostPublished() {
                            @Override
                            public void onPostPublished() {
                                runOnUiThread(() -> {
                                    Toast.makeText(
                                                    CreatePostActivity.this,
                                                    R.string.comment_published,
                                                    Toast.LENGTH_SHORT)
                                            .show();
                                    setResult(RESULT_OK);
                                    finish();
                                });
                            }

                            @Override
                            public void onPostPublishFailed() {
                                runOnUiThread(() -> {
                                    Toast.makeText(
                                                    CreatePostActivity.this,
                                                    R.string.error_publish_comment,
                                                    Toast.LENGTH_SHORT)
                                            .show();
                                    binding.publishButton.setEnabled(true);
                                });
                            }
                        });
            } else if (attachmentUri != null) {
                final String mimeType =
                        this.attachmentType != null
                                ? this.attachmentType
                                : getContentResolver().getType(attachmentUri);
                final String scheme = attachmentUri.getScheme();
                if (scheme != null && (scheme.equals("http") || scheme.equals("https"))) {
                    publish(
                            selectedAccount,
                            title,
                            content,
                            attachmentUri.toString(),
                            mimeType,
                            linkUrl);
                } else {
                    Toast.makeText(this, R.string.uploading_attachment, Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                    xmppConnectionService.uploadFileForUrl(
                            selectedAccount,
                            attachmentUri,
                            mimeType,
                            new XmppConnectionService.OnMediaUploaded() {
                                @Override
                                public void onMediaUploaded(String url, String uploadedMimeType) {
                                    publish(
                                            selectedAccount,
                                            title,
                                            content,
                                            url,
                                            uploadedMimeType,
                                            linkUrl);
                                }

                                @Override
                                public void onMediaUploadFailed(int errorResId) {
                                    runOnUiThread(() -> {
                                        Toast.makeText(
                                                        CreatePostActivity.this,
                                                        errorResId,
                                                        Toast.LENGTH_SHORT)
                                                .show();
                                        binding.publishButton.setEnabled(true);
                                    });
                                }
                            });
                }
            } else {
                publish(selectedAccount, title, content, null, null, linkUrl);
            }
        }
    }

    private void publish(
            Account account,
            String title,
            String content,
            String attachmentUrl,
            String attachmentType,
            String linkUrl) {
        // An edit keeps the original publication time (XEP-0277); 0 for a new post.
        final long published = postId != null ? getIntent().getLongExtra("published", 0) : 0;
        xmppConnectionService.publishPost(
                account,
                title,
                content,
                attachmentUrl,
                attachmentType,
                postId,
                linkUrl,
                published,
                new XmppConnectionService.OnPostPublished() {
                    @Override
                    public void onPostPublished() {
                        runOnUiThread(() -> {
                            Toast.makeText(
                                            CreatePostActivity.this,
                                            R.string.post_published,
                                            Toast.LENGTH_SHORT)
                                    .show();
                            setResult(RESULT_OK);
                            finish();
                        });
                    }

                    @Override
                    public void onPostPublishFailed() {
                        runOnUiThread(() -> {
                            Toast.makeText(
                                            CreatePostActivity.this,
                                            R.string.error_publish_post,
                                            Toast.LENGTH_SHORT)
                                    .show();
                            binding.publishButton.setEnabled(true);
                        });
                    }
                });
    }

    private int getPersistedItem() {
        return PreferenceManager.getDefaultSharedPreferences(this)
                .getInt(makePersistedItemKeyName(), 0);
    }

    protected void setPersistedItem(int position) {
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putInt(makePersistedItemKeyName(), position)
                .apply();
    }

    private String makePersistedItemKeyName() {
        return "create_post_selected_account_position";
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
