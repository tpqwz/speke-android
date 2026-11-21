package de.monocles.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

/**
 * A Signal sticker pack, fetched straight from Signal's CDN and decrypted on the device: the pack
 * key never leaves it. Signal's CDN only learns the pack id (and, without Tor, the IP address).
 *
 * <p>Format: every file is {@code IV(16) || AES-256-CBC/PKCS7 || HMAC-SHA256(IV || ct)(32)} with
 * keys from {@code HKDF-SHA256(packKey, salt = none, info = "Sticker Pack", 64)}: AES key first,
 * MAC key second. The manifest is a protobuf {@code Pack{1 title, 2 author, 3 cover, 4 repeated
 * Sticker{1 id, 2 emoji, 3 contentType}}}.
 */
public final class SignalStickerPack {

    /** Signal's CDN under a publicly trusted certificate (cdn.signal.org uses Signal's own CA). */
    static final String CDN = "https://cdn-ca.signal.org";

    static final Pattern PACK_ID = Pattern.compile("[0-9a-f]{32}");
    static final Pattern PACK_KEY = Pattern.compile("[0-9a-f]{64}");

    private static final byte[] HKDF_INFO = "Sticker Pack".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_LENGTH = 16;
    private static final int MAC_LENGTH = 32;

    private SignalStickerPack() {}

    static String manifestUrl(final String packId) {
        return CDN + "/stickers/" + packId + "/manifest.proto";
    }

    static String stickerUrl(final String packId, final int stickerId) {
        return CDN + "/stickers/" + packId + "/full/" + stickerId;
    }

    /** Decrypts one manifest or sticker file. The MAC is checked before anything is decrypted. */
    static byte[] decrypt(final byte[] blob, final byte[] packKey) throws GeneralSecurityException {
        if (packKey == null || packKey.length != 32) {
            throw new GeneralSecurityException("invalid pack key");
        }
        if (blob == null
                || blob.length < IV_LENGTH + 16 + MAC_LENGTH
                || (blob.length - IV_LENGTH - MAC_LENGTH) % 16 != 0) {
            throw new GeneralSecurityException("invalid ciphertext length");
        }
        final byte[] keys = deriveKeys(packKey);
        final byte[] aesKey = Arrays.copyOfRange(keys, 0, 32);
        final byte[] macKey = Arrays.copyOfRange(keys, 32, 64);
        Arrays.fill(keys, (byte) 0);
        try {
            final int macOffset = blob.length - MAC_LENGTH;
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
            mac.update(blob, 0, macOffset);
            final byte[] expected = mac.doFinal();
            final byte[] actual = Arrays.copyOfRange(blob, macOffset, blob.length);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new GeneralSecurityException("bad mac");
            }
            final Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(blob, 0, IV_LENGTH));
            return cipher.doFinal(blob, IV_LENGTH, macOffset - IV_LENGTH);
        } finally {
            Arrays.fill(aesKey, (byte) 0);
            Arrays.fill(macKey, (byte) 0);
        }
    }

    static byte[] deriveKeys(final byte[] packKey) {
        final HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(packKey, null, HKDF_INFO));
        final byte[] out = new byte[64];
        hkdf.generateBytes(out, 0, out.length);
        return out;
    }

    static byte[] hexToBytes(final String hex) {
        final byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /** File extension from the image's own magic bytes, or null for anything else (e.g. Lottie). */
    static String sniffExtension(final byte[] data) {
        if (data == null) return null;
        if (data.length >= 12
                && data[0] == 'R'
                && data[1] == 'I'
                && data[2] == 'F'
                && data[3] == 'F'
                && data[8] == 'W'
                && data[9] == 'E'
                && data[10] == 'B'
                && data[11] == 'P') {
            return "webp";
        }
        if (data.length >= 8
                && (data[0] & 0xff) == 0x89
                && data[1] == 'P'
                && data[2] == 'N'
                && data[3] == 'G'
                && data[4] == '\r'
                && data[5] == '\n'
                && (data[6] & 0xff) == 0x1a
                && data[7] == '\n') {
            return "png";
        }
        if (data.length >= 6
                && data[0] == 'G'
                && data[1] == 'I'
                && data[2] == 'F'
                && data[3] == '8'
                && (data[4] == '7' || data[4] == '9')
                && data[5] == 'a') {
            return "gif";
        }
        return null;
    }

    static final class Sticker {
        final int id;
        final String emoji;

        Sticker(final int id, final String emoji) {
            this.id = id;
            this.emoji = emoji;
        }
    }

    static final class Manifest {
        final String title;
        final String author;
        final List<Sticker> stickers;

        Manifest(final String title, final String author, final List<Sticker> stickers) {
            this.title = title;
            this.author = author;
            this.stickers = Collections.unmodifiableList(stickers);
        }
    }

    /**
     * Parses a decrypted manifest. The bytes come from whoever made the pack: anything malformed is
     * an {@link IOException}, never a runtime exception.
     */
    static Manifest parseManifest(final byte[] data, final int maxStickers) throws IOException {
        if (data == null || data.length == 0) throw new IOException("empty manifest");
        final Reader r = new Reader(data, 0, data.length);
        String title = null;
        String author = null;
        final List<Sticker> stickers = new ArrayList<>();
        while (r.hasMore()) {
            final long tag = r.varint();
            final int field = (int) (tag >>> 3);
            final int wireType = (int) (tag & 7);
            if (field == 1 && wireType == 2) {
                title = r.string();
            } else if (field == 2 && wireType == 2) {
                author = r.string();
            } else if (field == 4 && wireType == 2) {
                final Reader sub = r.sub();
                final Sticker sticker = parseSticker(sub);
                if (sticker != null && stickers.size() < maxStickers) {
                    stickers.add(sticker);
                }
            } else {
                r.skip(wireType);
            }
        }
        return new Manifest(
                title == null ? null : title.trim(),
                author == null ? null : author.trim(),
                stickers);
    }

    private static Sticker parseSticker(final Reader r) throws IOException {
        Long id = null;
        String emoji = null;
        while (r.hasMore()) {
            final long tag = r.varint();
            final int field = (int) (tag >>> 3);
            final int wireType = (int) (tag & 7);
            if (field == 1 && wireType == 0) {
                id = r.varint();
            } else if (field == 2 && wireType == 2) {
                emoji = r.string();
            } else {
                r.skip(wireType);
            }
        }
        if (id == null || id < 0 || id > Integer.MAX_VALUE) return null;
        return new Sticker(id.intValue(), emoji == null ? "" : emoji.trim());
    }

    private static final class Reader {
        private final byte[] data;
        private int pos;
        private final int end;

        Reader(final byte[] data, final int pos, final int end) {
            this.data = data;
            this.pos = pos;
            this.end = end;
        }

        boolean hasMore() {
            return pos < end;
        }

        long varint() throws IOException {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) throw new IOException("truncated varint");
                final byte b = data[pos++];
                result |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return result;
            }
            throw new IOException("varint too long");
        }

        private int length() throws IOException {
            final long len = varint();
            if (len < 0 || len > end - pos) throw new IOException("bad length");
            return (int) len;
        }

        String string() throws IOException {
            final int len = length();
            final String s = new String(data, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        Reader sub() throws IOException {
            final int len = length();
            final Reader sub = new Reader(data, pos, pos + len);
            pos += len;
            return sub;
        }

        void skip(final int wireType) throws IOException {
            switch (wireType) {
                case 0:
                    varint();
                    break;
                case 1:
                    advance(8);
                    break;
                case 2:
                    advance(length());
                    break;
                case 5:
                    advance(4);
                    break;
                default:
                    throw new IOException("unsupported wire type " + wireType);
            }
        }

        private void advance(final int n) throws IOException {
            if (n > end - pos) throw new IOException("truncated field");
            pos += n;
        }
    }
}
