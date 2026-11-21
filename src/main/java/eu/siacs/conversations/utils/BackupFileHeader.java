package eu.siacs.conversations.utils;

import androidx.annotation.NonNull;
import eu.siacs.conversations.xmpp.Jid;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class BackupFileHeader {

    /** Backups encrypted with a key derived from the account password (Argon2id). */
    public static final int VERSION = 4;

    /**
     * Backups encrypted with a random recovery key: the v4 header plus a key-type byte. Only
     * these use version 5, so password backups stay readable by older app versions.
     */
    public static final int RECOVERY_KEY_VERSION = 5;

    public static final int KEY_TYPE_RECOVERY_KEY = 1;

    private final int fileVersion;
    private final String app;
    private final Jid jid;
    private final long timestamp;
    private final byte[] iv;
    private final byte[] salt;

    @NonNull
    @Override
    public String toString() {
        return "BackupFileHeader{"
                + "version="
                + fileVersion
                + ", app='"
                + app
                + '\''
                + ", jid="
                + jid
                + ", timestamp="
                + timestamp
                + ", iv="
                + CryptoHelper.bytesToHex(iv)
                + ", salt="
                + CryptoHelper.bytesToHex(salt)
                + '}';
    }

    public BackupFileHeader(String app, Jid jid, long timestamp, byte[] iv, byte[] salt) {
        this(VERSION, app, jid, timestamp, iv, salt);
    }

    public BackupFileHeader(
            int fileVersion, String app, Jid jid, long timestamp, byte[] iv, byte[] salt) {
        this.fileVersion = fileVersion;
        this.app = app;
        this.jid = jid;
        this.timestamp = timestamp;
        this.iv = iv;
        this.salt = salt;
    }

    public void write(DataOutputStream dataOutputStream) throws IOException {
        dataOutputStream.writeInt(fileVersion);
        dataOutputStream.writeUTF(app);
        dataOutputStream.writeUTF(jid.asBareJid().toString());
        dataOutputStream.writeLong(timestamp);
        dataOutputStream.write(iv);
        dataOutputStream.write(salt);
        if (fileVersion >= RECOVERY_KEY_VERSION) {
            dataOutputStream.writeByte(KEY_TYPE_RECOVERY_KEY);
        }
    }

    /** Whether the backup is encrypted with a recovery key instead of the account password. */
    public boolean usesRecoveryKey() {
        return fileVersion >= RECOVERY_KEY_VERSION;
    }

    public static BackupFileHeader read(DataInputStream inputStream) throws IOException {
        final int version = inputStream.readInt();
        final String app = inputStream.readUTF();
        final String jid = inputStream.readUTF();
        long timestamp = inputStream.readLong();
        final byte[] iv = new byte[12];
        inputStream.readFully(iv);
        final byte[] salt = new byte[16];
        inputStream.readFully(salt);
        if (version < 2) {
            throw new OutdatedBackupFileVersion();
        }
        if (version > RECOVERY_KEY_VERSION) {
            throw new IllegalArgumentException(
                    "Backup File version was "
                            + version
                            + " but app only supports version "
                            + RECOVERY_KEY_VERSION);
        }
        if (version == RECOVERY_KEY_VERSION) {
            final int keyType = inputStream.readUnsignedByte();
            if (keyType != KEY_TYPE_RECOVERY_KEY) {
                throw new IllegalArgumentException("Unknown backup key type " + keyType);
            }
        }
        return new BackupFileHeader(version, app, Jid.of(jid), timestamp, iv, salt);
    }

    public int getVersion() {
        return fileVersion;
    }

    public byte[] getSalt() {
        return salt;
    }

    public byte[] getIv() {
        return iv;
    }

    public Jid getJid() {
        return jid;
    }

    public String getApp() {
        return app;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public static class OutdatedBackupFileVersion extends RuntimeException {}
}
