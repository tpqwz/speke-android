package eu.siacs.conversations.utils;

import org.junit.Assert;
import org.junit.Test;

public class BackupKeysTest {

    private static byte[] hex(final String hex) {
        final byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; ++i) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    @Test
    public void formatAndParseRoundTrip() {
        final byte[] key = BackupKeys.generate();
        final String formatted = BackupKeys.format(key);
        Assert.assertEquals(64 + 15, formatted.length());
        Assert.assertArrayEquals(key, BackupKeys.parse(formatted));
        Assert.assertArrayEquals(key, BackupKeys.parse(formatted.toUpperCase().replace(' ', '-')));
        Assert.assertArrayEquals(key, BackupKeys.parse(formatted.replace(" ", "")));
    }

    @Test
    public void invalidKeysAreRejected() {
        Assert.assertNull(BackupKeys.parse(null));
        Assert.assertNull(BackupKeys.parse(""));
        Assert.assertNull(BackupKeys.parse("abcd"));
        Assert.assertNull(BackupKeys.parse("g".repeat(64)));
        Assert.assertNull(BackupKeys.parse("a".repeat(66)));
    }

    @Test
    public void hkdfMatchesRfc5869TestCase1() {
        final byte[] okm =
                BackupKeys.hkdfSha256(
                        hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
                        hex("000102030405060708090a0b0c"),
                        hex("f0f1f2f3f4f5f6f7f8f9"),
                        42);
        Assert.assertArrayEquals(
                hex(
                        "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
                okm);
    }

    @Test
    public void fileKeysDependOnSaltAndAccount() {
        final byte[] key = BackupKeys.generate();
        final byte[] salt = new byte[16];
        final byte[] a = BackupKeys.fileKey(key, salt, "kim@example.org");
        Assert.assertEquals(32, a.length);
        Assert.assertArrayEquals(a, BackupKeys.fileKey(key, salt, "kim@example.org"));
        Assert.assertFalse(
                java.util.Arrays.equals(a, BackupKeys.fileKey(key, salt, "alex@example.org")));
        final byte[] otherSalt = new byte[16];
        otherSalt[0] = 1;
        Assert.assertFalse(
                java.util.Arrays.equals(a, BackupKeys.fileKey(key, otherSalt, "kim@example.org")));
    }
}
