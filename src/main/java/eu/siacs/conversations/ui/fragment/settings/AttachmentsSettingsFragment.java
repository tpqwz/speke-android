package eu.siacs.conversations.ui.fragment.settings;

import static android.app.Activity.RESULT_OK;

import android.content.ContentResolver;
import android.content.Context;
import androidx.annotation.NonNull;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;
import androidx.preference.ListPreference;
import com.google.common.io.ByteStreams;
import de.monocles.chat.DownloadDefaultStickers;
import de.monocles.chat.StickersMigration;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.persistance.MediaCrypto;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.UIHelper;
import java.io.Closeable;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class AttachmentsSettingsFragment extends XmppPreferenceFragment {

    public static final int REQUEST_IMPORT_STICKERS = 0xbf8705;
    public static final int REQUEST_IMPORT_GIFS = 0xbf8706;

    // Stickers and GIFs live in app-private storage and imports go through the system picker:
    // none of these actions needs a storage permission.

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_attachments, rootKey);

        /*
        final var p = PreferenceManager.getDefaultSharedPreferences(requireActivity());
        final var stickerDir = findPreference("sticker_directory");
        if (Build.VERSION.SDK_INT >= 29) {
            stickerDir.setSummary(p.getString("sticker_directory", "Documents/LaNoxe/Stickers"));
            stickerDir.setOnPreferenceClickListener((pref) -> {
                final var intent = ((StorageManager) requireActivity().getSystemService(Context.STORAGE_SERVICE)).getPrimaryStorageVolume().createOpenDocumentTreeIntent();
                startActivityForResult(Intent.createChooser(intent, "Choose sticker location"), 0);
                return true;
            });
        } else {
            stickerDir.setVisible(false);
        }
        */

        final var importOwnStickers = findPreference("import_own_stickers");
        if (importOwnStickers != null) {
            importOwnStickers.setOnPreferenceClickListener(preference -> {
                importStickers();
                return true;
            });
        }

        final var importOwnGifs = findPreference("import_own_gifs");
        if (importOwnGifs != null) {
            importOwnGifs.setOnPreferenceClickListener(preference -> {
                importGifs();
                return true;
            });
        }

        final var downloadDefaultStickers = findPreference("download_default_stickers");
        if (downloadDefaultStickers != null) {
            downloadDefaultStickers.setOnPreferenceClickListener(pref -> {
                downloadStickers();
                return true;
            });
        }

        final var clearBlockedMedia = findPreference("clear_blocked_media");
        if (clearBlockedMedia != null) {
            clearBlockedMedia.setOnPreferenceClickListener(pref -> {
                requireService().clearBlockedMedia();
                runOnUiThread(() ->
                        Toast.makeText(
                                        requireActivity(),
                                        "Blocked media will be displayed again",
                                        Toast.LENGTH_LONG)
                                .show());
                return true;
            });
        }

        final ListPreference autoAcceptFileSize = findPreference("auto_accept_file_size");
        if (autoAcceptFileSize == null) {
            throw new IllegalStateException("The preference resource file is missing preferences");
        }
        setValues(
                autoAcceptFileSize,
                R.array.file_size_values,
                value -> {
                    if (value <= 0) {
                        return getString(R.string.never);
                    } else {
                        return UIHelper.filesizeToString(value);
                    }
                });
    }

    private void importStickers() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(
                "image/*"); // allows any image file type. Change * to specific extension to limit
        // it
        // **These following line is the important one!
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(
                Intent.createChooser(intent, "Select images"),
                REQUEST_IMPORT_STICKERS); // REQUEST_IMPORT_STICKERS is simply a global int used to
        // check the calling intent in onActivityResult
    }

    private void importGifs() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(
                "image/gif"); // allows any image file type. Change * to specific extension to limit
        // it
        // **These following line is the important one!
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(
                Intent.createChooser(intent, "Select GIFs"),
                REQUEST_IMPORT_GIFS); // REQUEST_IMPORT_STICKERS is simply a global int used to
        // check the calling intent in onActivityResult
    }

    protected void downloadStickers() {
        final var intent = new Intent(requireActivity(), DownloadDefaultStickers.class);
        intent.putExtra("tor", requireService().useTorToConnect());
        intent.putExtra("i2p", requireService().useI2PToConnect());
        ContextCompat.startForegroundService(requireActivity(), intent);
        runOnUiThread(() ->
                Toast.makeText(
                                requireActivity(),
                                R.string.sticker_download_started,
                                Toast.LENGTH_LONG)
                        .show());
    }

    @Override
    protected void onSharedPreferenceChanged(@NonNull final String key) {
        super.onSharedPreferenceChanged(key);
        if (eu.siacs.conversations.persistance.MediaCrypto.PREFERENCE.equals(key)
                || "default_store_media_securely".equals(key)) {
            // switched on: encrypt the files stored so far
            eu.siacs.conversations.worker.MediaEncryptionWorker.schedule(requireContext());
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.pref_attachments);
    }

    /** Imports run one after another, off the main thread (up to 25 MiB plus a bitmap decode). */
    private static final Executor IMPORT_EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (data == null || resultCode != RESULT_OK) return;
        if (requestCode != REQUEST_IMPORT_STICKERS && requestCode != REQUEST_IMPORT_GIFS) return;
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                final Uri uri = data.getClipData().getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        final boolean gifs = requestCode == REQUEST_IMPORT_GIFS;
        // Everything the background work needs is taken now: the fragment may be gone by then.
        final Context context = requireContext().getApplicationContext();
        final ContentResolver resolver = context.getContentResolver();
        final File stickersDir = StickersMigration.getStickersDir(context);
        final XmppConnectionService service = requireXmppActivity().xmppConnectionService;
        IMPORT_EXECUTOR.execute(() -> {
            int imported = 0;
            for (final Uri uri : uris) {
                try {
                    importOne(context, resolver, stickersDir, uri, gifs);
                    imported++;
                } catch (final IOException | RuntimeException e) {
                    Log.d(Config.LOGTAG, "could not import sticker or GIF: " + e);
                }
            }
            if (imported > 0) {
                XmppConnectionService.stickersChanged();
                if (service != null) service.rescanStickers();
            }
            final String message;
            if (imported == uris.size()) {
                message =
                        context.getString(gifs ? R.string.gif_imported : R.string.sticker_imported);
            } else if (imported == 0) {
                message =
                        context.getString(
                                gifs ? R.string.import_gif_failed : R.string.import_sticker_failed);
            } else {
                message = context.getString(R.string.import_partially, imported, uris.size());
            }
            new Handler(Looper.getMainLooper())
                    .post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
        });
    }

    /**
     * Imports one picked file. A GIF picked via "Import own stickers" goes to the GIFs folder: the
     * sticker picker does not list GIFs, the GIF picker does.
     */
    private static void importOne(
            final Context context,
            final ContentResolver resolver,
            final File stickersDir,
            final Uri uri,
            final boolean gifs)
            throws IOException {
        // the name comes from the picked file's provider: never trust it as a path
        final String name = importName(getFileName(resolver, uri), gifs ? ".gif" : ".png");
        final boolean gif = gifs || name.endsWith(".gif");
        final File folder = new File(stickersDir, gif ? "GIFs" : "Custom");
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("unable to create " + folder.getName());
        }
        final File target = new File(folder, name);
        if (!DownloadDefaultStickers.isInside(stickersDir, target)) {
            throw new IOException("refusing import outside of the sticker directory");
        }
        final boolean compress = !gif && !name.endsWith(".webp") && !name.endsWith(".svg");
        copyInto(context, resolver, uri, target, compress);
    }

    /**
     * A safe file name for an import. The provider's display name can be anything any app offering
     * files chooses (e.g. "../../shared_prefs/x.xml"): it is reduced to one safe path component.
     * The extension is kept apart, so shortening a long name cannot drop it (the pickers list files
     * by extension).
     */
    private static String importName(final String raw, final String fallbackExtension) {
        String base = raw;
        String extension = fallbackExtension;
        if (raw != null) {
            final int dot = raw.lastIndexOf('.');
            if (dot > 0
                    && raw.length() - dot <= 6
                    && raw.substring(dot + 1).matches("[A-Za-z0-9]+")) {
                base = raw.substring(0, dot);
                extension = raw.substring(dot).toLowerCase(java.util.Locale.ROOT);
            }
        }
        String name = DownloadDefaultStickers.safePathComponent(base);
        if (name == null) name = "sticker_" + System.currentTimeMillis();
        return name + extension;
    }

    /** Most bytes one imported sticker or GIF may have: a provider can stream without end. */
    private static final long MAX_IMPORT_BYTES = 25L * 1024 * 1024;

    /**
     * Copies the picked content into {@code target}. A provider may hand out no stream or revoke
     * access (SecurityException): either is an import failure, never a crash. The content is read
     * into memory (size-capped), compressed there if asked to, and only then stored: encrypted at
     * rest and atomically, so a failed import neither leaves a partial or plaintext file for the
     * picker to list nor destroys an earlier sticker of the same name.
     */
    private static void copyInto(
            final Context context,
            final ContentResolver resolver,
            final Uri uri,
            final File target,
            final boolean compress)
            throws IOException {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("provider returned no data");
            if (!compress) {
                // GIFs and other kept-as-is files are streamed, never held in memory; going
                // over the limit aborts the store (nothing written, an older sticker kept)
                MediaCrypto.store(context, new LimitedInputStream(in, MAX_IMPORT_BYTES), target);
                return;
            }
            final byte[] data = ByteStreams.toByteArray(ByteStreams.limit(in, MAX_IMPORT_BYTES + 1));
            if (data.length > MAX_IMPORT_BYTES) {
                throw new IOException("file too large");
            }
            // An animated PNG would be flattened to its first frame below: convert it to an
            // animated WebP instead, which Android (and the recipient's client) can play
            final byte[] webp = eu.siacs.conversations.utils.ApngConverter.toAnimatedWebp(data);
            if (webp != null) {
                final String name = target.getName();
                final int dot = name.lastIndexOf('.');
                final File webpTarget =
                        new File(
                                target.getParentFile(),
                                (dot > 0 ? name.substring(0, dot) : name) + ".webp");
                MediaCrypto.store(context, new ByteArrayInputStream(webp), webpTarget);
                return;
            }
            // from the copied (size-capped) bytes, never a second read from the provider
            final byte[] compressed = compressImageToSticker(data, 0);
            MediaCrypto.store(context, new ByteArrayInputStream(compressed), target);
        } catch (final RuntimeException e) {
            throw new IOException(e);
        }
    }

    /** Fails with an IOException once more than {@code limit} bytes were read. */
    private static final class LimitedInputStream extends java.io.FilterInputStream {
        private final long limit;
        private long count = 0;

        private LimitedInputStream(final InputStream in, final long limit) {
            super(in);
            this.limit = limit;
        }

        private int counted(final int n) throws IOException {
            if (n > 0) {
                count += n;
                if (count > limit) {
                    throw new IOException("file too large");
                }
            }
            return n;
        }

        @Override
        public int read() throws IOException {
            final int b = super.read();
            if (b >= 0) {
                counted(1);
            }
            return b;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            return counted(super.read(b, off, len));
        }

        @Override
        public long skip(final long n) throws IOException {
            return counted((int) Math.min(Integer.MAX_VALUE, super.skip(n)));
        }
    }

    private static String getFileName(final ContentResolver resolver, final Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    final int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (column >= 0) result = cursor.getString(column);
                }
            } catch (final RuntimeException e) {
                Log.d(Config.LOGTAG, "unable to query display name: " + e);
            }
        }
        if (result == null) {
            result = uri.getPath();
            if (result == null) return null;
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    /**
     * Re-encodes the image as a small JPEG, in memory. Images with transparency are returned as
     * they are: JPEG would turn it black.
     */
    private static byte[] compressImageToSticker(final byte[] image, int sampleSize)
            throws IOException {
        final int IMAGE_QUALITY = 65;
        final int ImageSize = (int) (0.04 * 1024 * 1024);
        try {
            final BitmapFactory.Options options = new BitmapFactory.Options();
            final int inSampleSize = (int) Math.pow(2, sampleSize);
            Log.d(Config.LOGTAG, "reading bitmap with sample size " + inSampleSize);
            options.inSampleSize = inSampleSize;
            final Bitmap originalBitmap =
                    BitmapFactory.decodeByteArray(image, 0, image.length, options);
            if (originalBitmap == null) {
                throw new IOException("Source file was not an image");
            }
            if (!"image/jpeg".equals(options.outMimeType) && hasAlpha(originalBitmap)) {
                originalBitmap.recycle();
                return image; // keep the transparent original as copied
            }
            Bitmap scaledBitmap = resize(originalBitmap, 480);
            final int rotation = getRotation(new ByteArrayInputStream(image));
            scaledBitmap = rotate(scaledBitmap, rotation);
            byte[] compressed;
            int quality = IMAGE_QUALITY;
            while (true) {
                final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                if (!scaledBitmap.compress(Config.IMAGE_FORMAT, quality, buffer)) {
                    throw new IOException(String.valueOf(R.string.error_compressing_image));
                }
                compressed = buffer.toByteArray();
                if (compressed.length <= ImageSize || quality <= 50) {
                    break;
                }
                quality -= 5;
            }
            scaledBitmap.recycle();
            return compressed;
        } catch (final OutOfMemoryError e) {
            ++sampleSize;
            if (sampleSize <= 3) {
                return compressImageToSticker(image, sampleSize);
            }
            throw new IOException(String.valueOf(R.string.error_out_of_memory));
        }
    }

    private static int getRotation(final File f) {
        try (final InputStream inputStream = new FileInputStream(f)) {
            return getRotation(inputStream);
        } catch (Exception e) {
            return 0;
        }
    }

    public static int getRotation(final InputStream inputStream) throws IOException {
        final ExifInterface exif = new ExifInterface(inputStream);
        final int orientation =
                exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_180:
                return 180;
            case ExifInterface.ORIENTATION_ROTATE_90:
                return 90;
            case ExifInterface.ORIENTATION_ROTATE_270:
                return 270;
            default:
                return 0;
        }
    }

    private static Bitmap rotate(final Bitmap bitmap, final int degree) {
        if (degree == 0) {
            return bitmap;
        }
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();
        final Matrix matrix = new Matrix();
        matrix.postRotate(degree);
        final Bitmap result = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true);
        if (!bitmap.isRecycled()) {
            bitmap.recycle();
        }
        return result;
    }

    public static void close(final Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception e) {
                Log.d(Config.LOGTAG, "unable to close stream", e);
            }
        }
    }

    private static void cleanup(final File file) {
        try {
            file.delete();
        } catch (Exception e) {

        }
    }

    private static Bitmap resize(final Bitmap originalBitmap, int size) throws IOException {
        int w = originalBitmap.getWidth();
        int h = originalBitmap.getHeight();
        if (w <= 0 || h <= 0) {
            throw new IOException("Decoded bitmap reported bounds smaller 0");
        } else if (Math.max(w, h) > size) {
            int scalledW;
            int scalledH;
            if (w <= h) {
                scalledW = Math.max((int) (w / ((double) h / size)), 1);
                scalledH = size;
            } else {
                scalledW = size;
                scalledH = Math.max((int) (h / ((double) w / size)), 1);
            }
            final Bitmap result =
                    Bitmap.createScaledBitmap(originalBitmap, scalledW, scalledH, true);
            if (!originalBitmap.isRecycled()) {
                originalBitmap.recycle();
            }
            return result;
        } else {
            return originalBitmap;
        }
    }

    private static boolean hasAlpha(final Bitmap bitmap) {
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();
        final int yStep = Math.max(1, w / 100);
        final int xStep = Math.max(1, h / 100);
        for (int x = 0; x < w; x += xStep) {
            for (int y = 0; y < h; y += yStep) {
                if (Color.alpha(bitmap.getPixel(x, y)) < 255) {
                    return true;
                }
            }
        }
        return false;
    }
}
