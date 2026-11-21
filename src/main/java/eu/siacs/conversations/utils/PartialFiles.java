package eu.siacs.conversations.utils;

import android.util.Log;
import com.google.common.io.Files;
import eu.siacs.conversations.Config;
import java.io.File;
import java.io.IOException;

/**
 * Incoming files are written to a partial file in the cache (see
 * {@code FileBackend.getPartialFile}) and only moved to their real place once complete.
 */
public final class PartialFiles {

    private PartialFiles() {}

    /**
     * Moves a completed partial file to its final location, replacing an existing target. The
     * cache is internal storage and the target may be on shared storage, where a rename does not
     * work; the copy then goes to a temporary name next to the target first, so the target is
     * never left half written.
     */
    public static void move(final File partial, final File target) throws IOException {
        final android.content.Context context = eu.siacs.conversations.Conversations.getContext();
        if (context != null
                && eu.siacs.conversations.persistance.MediaCrypto.shouldEncrypt(context, target)
                && !eu.siacs.conversations.persistance.MediaCrypto.isEncrypted(partial)) {
            // private media is encrypted at rest; the plain partial is removed afterwards
            eu.siacs.conversations.persistance.MediaCrypto.moveIntoStorage(
                    context, partial, target);
            return;
        }
        final File directory = target.getParentFile();
        if (directory != null && directory.mkdirs()) {
            Log.d(Config.LOGTAG, "created directory " + directory.getAbsolutePath());
        }
        if (partial.renameTo(target)) {
            return;
        }
        final File tmp = new File(directory, "." + target.getName() + ".part");
        try {
            Files.copy(partial, tmp);
            if (!tmp.renameTo(target)) {
                throw new IOException("unable to rename " + tmp + " to " + target);
            }
        } catch (final IOException e) {
            if (tmp.exists() && !tmp.delete()) {
                Log.w(Config.LOGTAG, "unable to delete " + tmp.getAbsolutePath());
            }
            throw e;
        }
        if (!partial.delete()) {
            Log.w(Config.LOGTAG, "unable to delete " + partial.getAbsolutePath());
        }
    }
}
