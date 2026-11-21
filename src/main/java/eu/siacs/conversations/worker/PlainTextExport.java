package eu.siacs.conversations.worker;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.documentfile.provider.DocumentFile;
import com.google.common.hash.Hashing;
import eu.siacs.conversations.Config;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * One plain-text export run of one account. Like the .ceb backups, a run never touches older
 * exports: it is written to {@code <account>.<date>.plaintext.partial} in the backup location
 * and only renamed to {@code <account>.<date>.plaintext} once every chat was written. Only then
 * are the account's complete exports beyond the newest {@link #KEEP} deleted, so a failed or
 * stopped run leaves the previous ones as they were.
 */
final class PlainTextExport {

    static final int KEEP = 3;

    /**
     * Held for a whole account run (begin → open… → commit/abort). A one-off and a recurring
     * backup may run at the same time; with the lock, any partial folder that begin() finds is
     * really a leftover of a killed process and never the folder of a run still writing.
     */
    static final Object LOCK = new Object();
    private static final String SUFFIX = ".plaintext";
    private static final String PARTIAL = ".partial";
    /** UTF-8 bytes; with hash and the longest suffix a name stays well below 255 bytes. */
    private static final int MAX_NAME_BYTES = 150;
    private static final int MAX_DUPLICATES = 100;
    private static final String DATE_PATTERN = "\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}";

    private final Context context;
    private final Uri backupLocation;
    private final String account;
    private final String name;
    private File partialDirectory;
    private DocumentFile partialDocument;
    private final Set<String> usedNames = new HashSet<>();

    PlainTextExport(
            final Context context, final Uri backupLocation, final String account, final Date date) {
        this.context = context;
        this.backupLocation = backupLocation;
        this.account = account;
        this.name = folderName(account, date);
    }

    /**
     * SECURITY: chat addresses come from anyone who writes to us. RFC 7622 allows characters
     * like {@code * ? | \ :} and lengths far beyond 255 bytes, which FAT/exFAT SD cards and
     * some providers refuse — the export of that account would then fail on every run. Keeps
     * letters, digits and {@code @ . _ + -}, replaces the rest, caps the length, and adds a hash
     * of the original whenever anything changed so different addresses never share a name.
     */
    static String safeFileName(final String jid) {
        final StringBuilder safe = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < jid.length(); ) {
            final int codePoint = jid.codePointAt(i);
            i += Character.charCount(codePoint);
            final boolean keep =
                    Character.isLetterOrDigit(codePoint) || "@._+-".indexOf(codePoint) >= 0;
            final int length = keep ? utf8Length(codePoint) : 1;
            if (bytes + length > MAX_NAME_BYTES) {
                break; // file systems limit names in bytes, not characters
            }
            bytes += length;
            if (keep) {
                safe.appendCodePoint(codePoint);
            } else {
                safe.append('_');
            }
        }
        if (safe.length() == 0 || safe.charAt(0) == '.') {
            safe.insert(0, '_'); // never empty, ".", ".." or hidden
        }
        if (!safe.toString().equals(jid)) {
            safe.append('-')
                    .append(
                            Hashing.sha256()
                                    .hashString(jid, StandardCharsets.UTF_8)
                                    .toString(),
                            0,
                            8);
        }
        return safe.toString();
    }

    private static int utf8Length(final int codePoint) {
        if (codePoint < 0x80) return 1;
        if (codePoint < 0x800) return 2;
        if (codePoint < 0x10000) return 3;
        return 4;
    }

    /**
     * {@code <base>.txt}, or {@code <base>-2.txt} … if that name was already used in this run
     * (compared case-insensitively, as on FAT/exFAT and Android's shared storage) or exists.
     * Two chats mapping to one name must never abort the export.
     */
    static String uniqueName(
            final String base, final Set<String> used, final Predicate<String> existsOnDisk)
            throws IOException {
        for (int i = 1; i <= MAX_DUPLICATES; ++i) {
            final String candidate = (i == 1 ? base : base + "-" + i) + ".txt";
            final String key = candidate.toLowerCase(Locale.ROOT);
            if (!used.contains(key) && !existsOnDisk.test(candidate)) {
                used.add(key);
                return candidate;
            }
        }
        throw new IOException("too many chats named " + base);
    }

    static String folderName(final String account, final Date date) {
        return safeFileName(account)
                + "."
                + new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(date)
                + SUFFIX;
    }

    /**
     * Exact match, so the exports of {@code kim@example.org} never include those of {@code
     * kim@example.org.uk}.
     */
    static boolean isCompleteFolder(final String name, final String account) {
        return name != null
                && Pattern.matches(
                        Pattern.quote(safeFileName(account))
                                + "\\."
                                + DATE_PATTERN
                                + Pattern.quote(SUFFIX),
                        name);
    }

    static boolean isPartialFolder(final String name, final String account) {
        return name != null
                && Pattern.matches(
                        Pattern.quote(safeFileName(account))
                                + "\\."
                                + DATE_PATTERN
                                + Pattern.quote(SUFFIX + PARTIAL),
                        name);
    }

    /** The complete exports of {@code account} to delete so that the newest {@code keep} stay. */
    static List<String> foldersToPrune(
            final Collection<String> names, final String account, final int keep) {
        final List<String> complete = new ArrayList<>();
        for (final String name : names) {
            if (isCompleteFolder(name, account)) {
                complete.add(name);
            }
        }
        if (complete.size() <= keep) {
            return Collections.emptyList();
        }
        Collections.sort(complete); // the date sorts lexically
        return complete.subList(0, complete.size() - keep);
    }

    private boolean isFileLocation() {
        return "file".equalsIgnoreCase(backupLocation.getScheme());
    }

    private DocumentFile tree() throws IOException {
        final DocumentFile tree = DocumentFile.fromTreeUri(context, backupLocation);
        if (tree == null) {
            throw new IOException("DocumentFile.fromTreeUri returned null for " + backupLocation);
        }
        return tree;
    }

    /**
     * Removes leftovers of interrupted runs and creates this run's partial folder. Callers hold
     * {@link #LOCK} until commit() or abort().
     */
    void begin() throws IOException {
        final String partialName = name + PARTIAL;
        if (isFileLocation()) {
            final File root = new File(backupLocation.getPath());
            if (!root.isDirectory() && !root.mkdirs()) {
                throw new IOException("Could not create " + root.getAbsolutePath());
            }
            final File[] stale = root.listFiles((dir, n) -> isPartialFolder(n, account));
            if (stale != null) {
                for (final File directory : stale) {
                    deleteRecursively(directory);
                }
            }
            final File directory = new File(root, partialName);
            if (directory.exists() || !directory.mkdir()) {
                throw new IOException("Could not create " + directory.getAbsolutePath());
            }
            this.partialDirectory = directory;
        } else {
            final DocumentFile tree = tree();
            for (final DocumentFile file : tree.listFiles()) {
                if (isPartialFolder(file.getName(), account) && !file.delete()) {
                    Log.w(Config.LOGTAG, "could not delete stale export " + file.getUri());
                }
            }
            if (tree.findFile(partialName) != null) {
                throw new IOException(partialName + " already exists in " + backupLocation);
            }
            final DocumentFile directory = tree.createDirectory(partialName);
            if (directory == null || !partialName.equals(directory.getName())) {
                if (directory != null) {
                    directory.delete();
                }
                throw new IOException("Could not create " + partialName + " in " + backupLocation);
            }
            this.partialDocument = directory;
        }
    }

    /** A new file {@code <contact>.txt} in this run's folder. */
    OutputStream open(final String contact) throws IOException {
        if (partialDirectory != null) {
            final File directory = partialDirectory;
            final String fileName =
                    uniqueName(
                            safeFileName(contact), usedNames, n -> new File(directory, n).exists());
            return new FileOutputStream(new File(directory, fileName));
        }
        if (partialDocument == null) {
            throw new IllegalStateException("begin() was not called");
        }
        // the folder is new, so the names of this run are all there is; a provider that still
        // finds a clash adds " (1)" itself, and nothing is ever overwritten
        final String fileName = uniqueName(safeFileName(contact), usedNames, n -> false);
        final DocumentFile file = partialDocument.createFile("text/plain", fileName);
        if (file == null) {
            throw new IOException("Could not create " + fileName + " in " + partialDocument.getUri());
        }
        final OutputStream outputStream =
                context.getContentResolver().openOutputStream(file.getUri());
        if (outputStream == null) {
            throw new IOException("Could not open " + file.getUri());
        }
        return outputStream;
    }

    /** Publishes this run and only then deletes the oldest exports. False if that failed. */
    boolean commit() {
        try {
            if (partialDirectory != null) {
                final File root = partialDirectory.getParentFile();
                final File target = new File(root, name);
                if (target.exists() || !partialDirectory.renameTo(target)) {
                    throw new IOException("Could not rename to " + target);
                }
                partialDirectory = null;
                final String[] names = root.list();
                if (names != null) {
                    for (final String old : foldersToPrune(List.of(names), account, KEEP)) {
                        deleteRecursively(new File(root, old));
                        Log.d(Config.LOGTAG, "deleted old plain text export " + old);
                    }
                }
            } else if (partialDocument != null) {
                final DocumentFile tree = tree();
                if (tree.findFile(name) != null || !partialDocument.renameTo(name)) {
                    throw new IOException("Could not rename " + partialDocument.getUri());
                }
                partialDocument = null;
                final List<String> names = new ArrayList<>();
                final DocumentFile[] files = tree.listFiles();
                for (final DocumentFile file : files) {
                    names.add(file.getName());
                }
                final List<String> prune = foldersToPrune(names, account, KEEP);
                for (final DocumentFile file : files) {
                    if (prune.contains(file.getName()) && file.delete()) {
                        Log.d(Config.LOGTAG, "deleted old plain text export " + file.getName());
                    }
                }
            } else {
                return false;
            }
            return true;
        } catch (final IOException | RuntimeException e) {
            Log.w(Config.LOGTAG, "could not finish plain text export " + name, e);
            abort();
            return false;
        }
    }

    /** Drops this run; older exports are left alone. */
    void abort() {
        if (partialDirectory != null) {
            deleteRecursively(partialDirectory);
            partialDirectory = null;
        }
        if (partialDocument != null) {
            if (!partialDocument.delete()) {
                Log.w(Config.LOGTAG, "could not delete " + partialDocument.getUri());
            }
            partialDocument = null;
        }
    }

    private static void deleteRecursively(final File file) {
        // never follow a symbolic link out of the backup location: only the link is removed
        // (only the last path element is compared, so a symlinked backup path like /sdcard is fine)
        boolean isLink;
        try {
            final File parent = file.getAbsoluteFile().getParentFile();
            isLink =
                    parent == null
                            || !file.getCanonicalFile()
                                    .equals(new File(parent.getCanonicalFile(), file.getName()));
        } catch (final IOException e) {
            isLink = true;
        }
        final File[] children = isLink ? null : file.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            Log.w(Config.LOGTAG, "could not delete " + file);
        }
    }
}
