package eu.siacs.conversations.utils;

import static android.app.Activity.RESULT_OK;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.persistance.MediaCrypto;
import eu.siacs.conversations.persistance.MediaFiles;
import eu.siacs.conversations.ui.fragment.settings.InterfaceSettingsFragment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ChatBackgroundHelper {
    public static final int REQUEST_IMPORT_BACKGROUND = 0xbf8704;

    public static void onActivityResult(
            Activity activity,
            int requestCode,
            int resultCode,
            Intent data,
            @Nullable String conversationUUID) {
        if (requestCode == REQUEST_IMPORT_BACKGROUND) {
            if (resultCode == RESULT_OK) {
                Uri bguri = data.getData();
                onPickFile(activity, bguri, conversationUUID);
            }
        }
    }

    public static void onRequestPermissionsResult(
            InterfaceSettingsFragment settingsFragment,
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && requestCode == REQUEST_IMPORT_BACKGROUND) {
            openBGPicker(settingsFragment);
        }
    }

    public static void onRequestPermissionsResult(
            Fragment fragment,
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && requestCode == REQUEST_IMPORT_BACKGROUND) {
            openBGPicker(fragment);
        }
    }

    public static File getBgFile(Activity activity, @Nullable String conversationUUID) {
        if (conversationUUID == null) {
            return new File(
                    activity.getFilesDir()
                            + File.separator
                            + "backgrounds"
                            + File.separator
                            + "bg.jpg");
        } else {
            return new File(
                    activity.getFilesDir()
                            + File.separator
                            + "backgrounds"
                            + File.separator
                            + "bg_"
                            + conversationUUID
                            + ".jpg");
        }
    }

    @Nullable
    public static Uri getBgUri(Activity activity, String conversationUUID) {
        File chatBgfileUri =
                new File(
                        activity.getFilesDir()
                                + File.separator
                                + "backgrounds"
                                + File.separator
                                + "bg_"
                                + conversationUUID
                                + ".jpg");
        File bgfileUri =
                new File(
                        activity.getFilesDir()
                                + File.separator
                                + "backgrounds"
                                + File.separator
                                + "bg.jpg");
        if (chatBgfileUri.exists()) {
            return Uri.fromFile(chatBgfileUri);
        } else if (bgfileUri.exists()) {
            return Uri.fromFile(bgfileUri);
        } else {
            return null;
        }
    }

    public static void openBGPicker(InterfaceSettingsFragment settingsFragment) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        settingsFragment.startActivityForResult(
                Intent.createChooser(intent, "Select image"), REQUEST_IMPORT_BACKGROUND);
    }

    public static void openBGPicker(Fragment fragment) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        fragment.startActivityForResult(
                Intent.createChooser(intent, "Select image"), REQUEST_IMPORT_BACKGROUND);
    }

    private static void onPickFile(Activity activity, Uri uri, @Nullable String conversationUUID) {
        if (uri == null) {
            return;
        }
        try {
            final File bgfile = getBgFile(activity, conversationUUID);
            final File bgfolder = bgfile.getParentFile();
            if (bgfolder != null && !bgfolder.exists()) {
                bgfolder.mkdirs();
            }
            compressImage(activity, bgfile, uri, 0);
            Toast.makeText(activity, R.string.custom_background_set, Toast.LENGTH_LONG).show();
        } catch (final IOException exception) {
            Toast.makeText(activity, R.string.create_background_failed, Toast.LENGTH_LONG).show();
            Log.d(Config.LOGTAG, "Could not create background" + exception);
        }
    }

    /**
     * Decodes, scales and rotates the picked picture and compresses it in memory; only the final
     * JPEG is written, encrypted at rest. The source may itself be encrypted (the editor's
     * output), so it is read through {@link MediaFiles#openInput}.
     */
    private static void compressImage(Activity activity, File f, Uri image, int sampleSize)
            throws IOException {
        final int IMAGE_QUALITY = 65;
        final int ImageSize = (int) (0.08 * 1024 * 1024);
        try {
            final Bitmap originalBitmap;
            final BitmapFactory.Options options = new BitmapFactory.Options();
            final int inSampleSize = (int) Math.pow(2, sampleSize);
            Log.d(Config.LOGTAG, "reading bitmap with sample size " + inSampleSize);
            options.inSampleSize = inSampleSize;
            try (final InputStream is = MediaFiles.openInput(activity, image)) {
                originalBitmap = BitmapFactory.decodeStream(is, null, options);
            }
            if (originalBitmap == null) {
                throw new IOException("Source file was not an image");
            }
            if (!"image/jpeg".equals(options.outMimeType) && hasAlpha(originalBitmap)) {
                originalBitmap.recycle();
                throw new IOException("Source file had alpha channel");
            }
            Bitmap scaledBitmap = resize(originalBitmap, 1920);
            final int rotation = getRotation(activity, image);
            scaledBitmap = rotate(scaledBitmap, rotation);
            byte[] compressed;
            int quality = IMAGE_QUALITY;
            while (true) {
                final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
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
            MediaCrypto.store(activity, new ByteArrayInputStream(compressed), f);
        } catch (final FileNotFoundException e) {
            throw new IOException(String.valueOf(R.string.error_file_not_found));
        } catch (final IOException e) {
            throw new IOException(String.valueOf(R.string.error_io_exception));
        } catch (SecurityException e) {
            throw new IOException(
                    String.valueOf(R.string.error_security_exception_during_image_copy));
        } catch (final OutOfMemoryError e) {
            ++sampleSize;
            if (sampleSize <= 3) {
                compressImage(activity, f, image, sampleSize);
            } else {
                throw new IOException(String.valueOf(R.string.error_out_of_memory));
            }
        }
    }

    private static int getRotation(Activity activity, final Uri image) {
        try (final InputStream is = MediaFiles.openInput(activity, image)) {
            return getRotation(is);
        } catch (final Exception e) {
            return 0;
        }
    }

    private static int getRotation(final InputStream inputStream) throws IOException {
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

    private static void close(final Closeable stream) {
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
