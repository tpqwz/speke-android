package de.monocles.chat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Assert;
import org.junit.Test;

/** Signal sticker packs are decrypted on the device; manifests are untrusted wire content. */
public class SignalStickerPackTest {

    /** The public "Bandit the Cat" pack, as served by cdn-ca.signal.org. */
    private static final String BANDIT_KEY =
            "5a6dff3948c28efb9b7aaf93ecc375c69fc316e78077ed26867a14d10a0f6a12";

    private static final String BANDIT_MANIFEST =
            "374f22e2e174380c9ca93d1cfac2d1e5a9d34777d5c600efe0a18af875e974440cefb9c497b8ef54"
                + "ce1d84a059c9ca5e1bb3b1efb78a5995bce30c5acc7ecdec814c2f75400a6e4b5480b1a3de520f88"
                + "8d72ef25df0f4f549c10379c1e1cd9c72ac7724434079837a019bed4e67f7c8be698f9851c35d208"
                + "badb95df84be4354280caf84937483c8e25c76b0dc18d58a4569de9b0c30836ecc59862c789ad004"
                + "3c9ab619bda75eb2dcfc2042885096ff861a58147dc75b72846bba2b6c2d790241159732e67ea79e"
                + "0de2cf17491e8369e1a1a94d45a88cd36f7da29bd904571ab065fe082a58581490e493eed49e61ed"
                + "06eb475886a4253649495fd44b5387ed33a84c3b88e0ca6223eb6658ac39d84279837c6948262df3"
                + "82b41aab229215937a52c6cdd6c098e8f2c346582294e68c1fa8f4c3c568e20e4baa421eec476c9c"
                + "f7593247ad800ee29524c9683f263973";

    private static byte[] key0to31() {
        final byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) k[i] = (byte) i;
        return k;
    }

    @Test
    public void hkdfMatchesKnownAnswer() {
        Assert.assertEquals(
                "64f46fd204462c27c05dd6f5db863edd9f286af100e5e9704efdd36ce89583ff"
                        + "c483e5dfa58b86e9a7a2245b775677f5f6864b94f2302dcec6636f713de6de65",
                toHex(SignalStickerPack.deriveKeys(key0to31())));
    }

    @Test
    public void realManifestDecryptsAndParses() throws Exception {
        final byte[] plain =
                SignalStickerPack.decrypt(
                        SignalStickerPack.hexToBytes(BANDIT_MANIFEST),
                        SignalStickerPack.hexToBytes(BANDIT_KEY));
        final SignalStickerPack.Manifest m = SignalStickerPack.parseManifest(plain, 500);
        Assert.assertEquals("Bandit the Cat", m.title);
        Assert.assertEquals("Agnes Lee", m.author);
        Assert.assertFalse(m.stickers.isEmpty());
        Assert.assertEquals(0, m.stickers.get(0).id);
        Assert.assertEquals("👍", m.stickers.get(0).emoji);
    }

    @Test
    public void roundTripAndTampering() throws Exception {
        final byte[] key = key0to31();
        final byte[] plain = "hello sticker".getBytes(StandardCharsets.UTF_8);
        final byte[] blob = encrypt(plain, key);
        Assert.assertArrayEquals(plain, SignalStickerPack.decrypt(blob, key));
        for (final int index : new int[] {0, 20, blob.length - 1}) {
            final byte[] bad = blob.clone();
            bad[index] ^= 1;
            assertRejected(bad, key);
        }
        final byte[] otherKey = key0to31();
        otherKey[0] = 42;
        assertRejected(blob, otherKey);
        assertRejected(Arrays.copyOf(blob, blob.length - 1), key);
        assertRejected(new byte[63], key);
        assertRejected(null, key);
        assertRejected(blob, new byte[16]);
    }

    @Test
    public void manifestWithUnknownFieldsAndCap() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, 1, "Title".getBytes(StandardCharsets.UTF_8));
        field(out, 2, " Author ".getBytes(StandardCharsets.UTF_8));
        field(out, 3, sticker(9, "c"));
        out.write(new byte[] {(byte) 0x48, 0x05}); // field 9, varint: unknown, skipped
        field(out, 4, sticker(1, "a"));
        field(out, 4, sticker(300, "b"));
        field(out, 4, sticker(3, "c"));
        final SignalStickerPack.Manifest m = SignalStickerPack.parseManifest(out.toByteArray(), 2);
        Assert.assertEquals("Title", m.title);
        Assert.assertEquals("Author", m.author);
        Assert.assertEquals(2, m.stickers.size());
        Assert.assertEquals(1, m.stickers.get(0).id);
        Assert.assertEquals(300, m.stickers.get(1).id);
        Assert.assertEquals("b", m.stickers.get(1).emoji);
    }

    @Test
    public void malformedManifestsAreIoExceptions() {
        assertMalformed(new byte[0]);
        assertMalformed(null);
        assertMalformed(new byte[] {0x0a, 0x05, 'a'}); // length past the end
        assertMalformed(new byte[] {0x0a}); // truncated
        assertMalformed(new byte[] {0x0b}); // group wire type
        final byte[] longVarint = new byte[12];
        Arrays.fill(longVarint, (byte) 0xff);
        longVarint[0] = 0x08;
        assertMalformed(longVarint);
        assertMalformed(
                new byte[] {0x0a, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x0f});
    }

    @Test
    public void sniffing() {
        Assert.assertEquals(
                "webp",
                SignalStickerPack.sniffExtension(
                        "RIFF\0\0\0\0WEBPVP8X".getBytes(StandardCharsets.ISO_8859_1)));
        Assert.assertEquals(
                "png",
                SignalStickerPack.sniffExtension(
                        new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0}));
        Assert.assertEquals(
                "gif",
                SignalStickerPack.sniffExtension(
                        "GIF89a...".getBytes(StandardCharsets.ISO_8859_1)));
        Assert.assertNull(
                SignalStickerPack.sniffExtension(
                        "{\"v\":\"5.5\"}".getBytes(StandardCharsets.ISO_8859_1)));
        Assert.assertNull(SignalStickerPack.sniffExtension(new byte[3]));
        Assert.assertNull(SignalStickerPack.sniffExtension(null));
    }

    private static void assertRejected(final byte[] blob, final byte[] key) {
        try {
            SignalStickerPack.decrypt(blob, key);
            Assert.fail("accepted a bad blob");
        } catch (final GeneralSecurityException expected) {
        }
    }

    private static void assertMalformed(final byte[] data) {
        try {
            SignalStickerPack.parseManifest(data, 500);
            Assert.fail("accepted a malformed manifest");
        } catch (final IOException expected) {
        }
    }

    private static byte[] encrypt(final byte[] plain, final byte[] packKey) throws Exception {
        final byte[] keys = SignalStickerPack.deriveKeys(packKey);
        final byte[] iv = new byte[16];
        Arrays.fill(iv, (byte) 7);
        final Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(Arrays.copyOfRange(keys, 0, 32), "AES"),
                new IvParameterSpec(iv));
        final byte[] ct = c.doFinal(plain);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(iv);
        out.write(ct);
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Arrays.copyOfRange(keys, 32, 64), "HmacSHA256"));
        out.write(mac.doFinal(out.toByteArray()));
        return out.toByteArray();
    }

    private static byte[] sticker(final int id, final String emoji) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x08);
        varint(out, id);
        field(out, 2, emoji.getBytes(StandardCharsets.UTF_8));
        field(out, 3, "image/webp".getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static void field(final ByteArrayOutputStream out, final int number, final byte[] value)
            throws IOException {
        out.write((number << 3) | 2);
        varint(out, value.length);
        out.write(value);
    }

    private static void varint(final ByteArrayOutputStream out, long v) {
        while ((v & ~0x7fL) != 0) {
            out.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static String toHex(final byte[] b) {
        final StringBuilder sb = new StringBuilder();
        for (final byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
