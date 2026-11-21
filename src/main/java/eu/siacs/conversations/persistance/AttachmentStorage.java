package eu.siacs.conversations.persistance;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * How the backup reads attachments and writes restored ones. The backup itself only ever
 * handles plaintext bytes inside its own encryption; this seam is where encryption at rest
 * (planned) plugs in: export decrypts through it, restore re-encrypts with this device's media
 * key. Device media keys therefore never go into a backup, and no plaintext temp file is
 * needed.
 */
public interface AttachmentStorage {

    /** The plaintext content of an attachment. */
    InputStream openForBackup(File file) throws IOException;

    /** Starts writing a restored attachment; nothing is visible at {@code target} before commit. */
    RestoreWriter beginRestore(File target) throws IOException;

    interface RestoreWriter {
        void write(byte[] content) throws IOException;

        /** Moves the finished file into place. */
        void commit() throws IOException;

        /** Drops everything written so far; safe to call more than once. */
        void abort();
    }

    AttachmentStorage PLAIN = new Plain();

    /**
     * This device's storage: export reads the plaintext of encrypted attachments, restore
     * encrypts into private media with this device's key (device keys never enter a backup).
     */
    static AttachmentStorage forDevice(final android.content.Context context) {
        final android.content.Context app = context.getApplicationContext();
        return new AttachmentStorage() {
            @Override
            public InputStream openForBackup(final File file) throws IOException {
                return MediaCrypto.openInput(app, file);
            }

            @Override
            public RestoreWriter beginRestore(final File target) throws IOException {
                if (!MediaCrypto.shouldEncrypt(app, target)) {
                    return PLAIN.beginRestore(target);
                }
                final MediaCipher.EncryptedFileOutputStream out =
                        MediaCrypto.cipher(app).create(target);
                return new RestoreWriter() {
                    @Override
                    public void write(final byte[] content) throws IOException {
                        out.write(content, 0, content.length);
                    }

                    @Override
                    public void commit() throws IOException {
                        out.commit();
                    }

                    @Override
                    public void abort() {
                        out.abort();
                    }
                };
            }
        };
    }

    /** Attachments as plain files (today). */
    final class Plain implements AttachmentStorage {

        @Override
        public InputStream openForBackup(final File file) throws IOException {
            return new FileInputStream(file);
        }

        @Override
        public RestoreWriter beginRestore(final File target) throws IOException {
            final File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Could not create " + parent);
            }
            final File part = MediaCipher.partFileFor(target);
            final FileOutputStream out = new FileOutputStream(part);
            return new RestoreWriter() {
                private boolean open = true;

                @Override
                public void write(final byte[] content) throws IOException {
                    if (!open) {
                        throw new IOException("restore of " + target + " already finished");
                    }
                    out.write(content);
                }

                @Override
                public void commit() throws IOException {
                    if (!open) {
                        throw new IOException("restore of " + target + " already finished");
                    }
                    open = false;
                    try {
                        out.getFD().sync(); // on disk before it becomes visible by rename
                    } finally {
                        out.close();
                    }
                    // rename replaces an existing target atomically on Android's file systems;
                    // only if that is refused, fall back to delete + rename
                    if (!part.renameTo(target)
                            && !(target.delete() && part.renameTo(target))) {
                        part.delete();
                        throw new IOException("Could not move " + part + " to " + target);
                    }
                }

                @Override
                public void abort() {
                    if (open) {
                        open = false;
                        try {
                            out.close();
                        } catch (final IOException ignored) {
                            // nothing to keep
                        }
                    }
                    if (part.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                    }
                }
            };
        }
    }
}
