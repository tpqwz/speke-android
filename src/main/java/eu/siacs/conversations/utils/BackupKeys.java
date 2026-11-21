package eu.siacs.conversations.utils;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The optional backup recovery key (like Signal's): 32 random bytes the user writes down
 * instead of relying on the account password. With 256 bits of entropy it needs no slow
 * password KDF; each backup file's AES key is derived from it with HKDF-SHA256, bound to the
 * file's random salt and its account.
 */
public final class BackupKeys {

    public static final int KEY_LENGTH = 32;
    private static final String INFO = "monocles-backup-v5";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private BackupKeys() {}

    public static byte[] generate() {
        final byte[] key = new byte[KEY_LENGTH];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /** 64 hex characters in groups of four, e.g. {@code 3f2a 9c01 …}. */
    public static String format(final byte[] key) {
        if (key == null || key.length != KEY_LENGTH) {
            throw new IllegalArgumentException("not a recovery key");
        }
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < key.length; ++i) {
            if (i > 0 && i % 2 == 0) {
                out.append(' ');
            }
            out.append(HEX[(key[i] >> 4) & 0x0f]).append(HEX[key[i] & 0x0f]);
        }
        return out.toString();
    }

    /**
     * Parses what the user typed: spaces, dashes and case are ignored. Returns null unless it is
     * exactly 64 hex characters.
     */
    public static byte[] parse(final String input) {
        if (input == null) {
            return null;
        }
        final StringBuilder hex = new StringBuilder();
        for (int i = 0; i < input.length(); ++i) {
            final char c = input.charAt(i);
            if (c == ' ' || c == '-' || c == '\n' || c == '\t' || c == '\r') {
                continue;
            }
            if (Character.digit(c, 16) < 0) {
                return null;
            }
            hex.append(Character.toLowerCase(c));
        }
        if (hex.length() != KEY_LENGTH * 2) {
            return null;
        }
        final byte[] key = new byte[KEY_LENGTH];
        for (int i = 0; i < KEY_LENGTH; ++i) {
            key[i] =
                    (byte)
                            ((Character.digit(hex.charAt(2 * i), 16) << 4)
                                    | Character.digit(hex.charAt(2 * i + 1), 16));
        }
        return key;
    }

    /** The AES-256 key of one backup file. */
    public static byte[] fileKey(final byte[] recoveryKey, final byte[] salt, final String account) {
        if (recoveryKey == null || recoveryKey.length != KEY_LENGTH) {
            throw new IllegalArgumentException("not a recovery key");
        }
        final byte[] info =
                (INFO + "\u0000" + account).getBytes(StandardCharsets.UTF_8);
        return hkdfSha256(recoveryKey, salt, info, 32);
    }

    /** HKDF (RFC 5869) with HMAC-SHA256. */
    static byte[] hkdfSha256(
            final byte[] ikm, final byte[] salt, final byte[] info, final int length) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            final byte[] effectiveSalt =
                    salt == null || salt.length == 0 ? new byte[32] : salt;
            mac.init(new SecretKeySpec(effectiveSalt, "HmacSHA256"));
            final byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            final byte[] out = new byte[length];
            byte[] block = new byte[0];
            int offset = 0;
            for (int counter = 1; offset < length; ++counter) {
                mac.update(block);
                mac.update(info);
                mac.update((byte) counter);
                block = mac.doFinal();
                final int n = Math.min(block.length, length - offset);
                System.arraycopy(block, 0, out, offset, n);
                offset += n;
            }
            Arrays.fill(prk, (byte) 0);
            return out;
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
