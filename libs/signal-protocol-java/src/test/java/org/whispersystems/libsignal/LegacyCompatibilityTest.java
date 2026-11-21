package org.whispersystems.libsignal;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import org.junit.Test;
import org.whispersystems.libsignal.fingerprint.Fingerprint;
import org.whispersystems.libsignal.fingerprint.NumericFingerprintGenerator;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.state.impl.InMemorySignalProtocolStore;

/**
 * The vendored copy only differs from the Maven artifact signal-protocol-java 2.6.2 by its
 * regenerated protobuf code (protoc 25.9 instead of 2.5). These tests pin that this changes neither
 * the schema nor anything already stored or in flight.
 *
 * <p>The fixtures under {@code legacy-2.6.2/} were produced by the original Maven jar on
 * protobuf-java 2.5.0: identities, session records, pre-key records and undelivered messages, as an
 * existing install holds them.
 */
public class LegacyCompatibilityTest {

    private static final SignalProtocolAddress ALICE =
            new SignalProtocolAddress("alice@example.org", 1111);
    private static final SignalProtocolAddress BOB =
            new SignalProtocolAddress("bob@example.org", 2222);
    private static final SignalProtocolAddress CAROL =
            new SignalProtocolAddress("carol@example.org", 3333);

    private static Properties load(String name) throws Exception {
        Properties p = new Properties();
        try (InputStream in =
                LegacyCompatibilityTest.class.getResourceAsStream("/legacy-2.6.2/" + name)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    private static byte[] b64(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null) throw new AssertionError("missing fixture " + key);
        return Base64.getDecoder().decode(v);
    }

    private static InMemorySignalProtocolStore store(Properties f, String who) throws Exception {
        return new InMemorySignalProtocolStore(
                new IdentityKeyPair(b64(f, who + ".identity")),
                Integer.parseInt(f.getProperty(who + ".regid")));
    }

    @Test
    public void unsafeGencodeGuardIsActive() {
        // Set by the module's test task: old (pre-21.7) gencode would then throw instead of
        // only logging, so every other test here doubles as a check that none is left.
        assertEquals(
                "true", System.getProperty("com.google.protobuf.error_on_unsafe_pre22_gencode"));
    }

    @Test
    public void schemaIsIdenticalToTheOldGencode() throws Exception {
        Properties d = load("descriptors.properties");
        FileDescriptor[] now = {
            org.whispersystems.libsignal.protocol.SignalProtos.getDescriptor(),
            org.whispersystems.libsignal.state.StorageProtos.getDescriptor(),
            org.whispersystems.libsignal.fingerprint.FingerprintProtos.getDescriptor(),
        };
        String[] names = {
            "org.whispersystems.libsignal.protocol.SignalProtos",
            "org.whispersystems.libsignal.state.StorageProtos",
            "org.whispersystems.libsignal.fingerprint.FingerprintProtos",
        };
        for (int i = 0; i < now.length; i++) {
            FileDescriptorProto old = FileDescriptorProto.parseFrom(b64(d, names[i]));
            assertEquals(names[i], old, now[i].toProto());
        }
    }

    @Test
    public void storedSessionsAndPendingMessagesKeepWorking() throws Exception {
        Properties f = load("fixtures.properties");

        InMemorySignalProtocolStore alice = store(f, "alice");
        alice.storeSession(BOB, new SessionRecord(b64(f, "alice.session.bob")));

        InMemorySignalProtocolStore bob = store(f, "bob");
        bob.storeSession(ALICE, new SessionRecord(b64(f, "bob.session.alice")));
        bob.storeSignedPreKey(7, new SignedPreKeyRecord(b64(f, "bob.signedprekey.7")));
        bob.storePreKey(42, new PreKeyRecord(b64(f, "bob.prekey.42")));

        InMemorySignalProtocolStore carol = store(f, "carol");
        carol.storeSession(BOB, new SessionRecord(b64(f, "carol.session.bob")));

        SessionCipher a2b = new SessionCipher(alice, BOB);
        SessionCipher b2a = new SessionCipher(bob, ALICE);

        // Messages encrypted by the old code before the upgrade, delivered out of order.
        assertEquals("pending-a-2", text(b2a.decrypt(new SignalMessage(b64(f, "ab.pending.2")))));
        assertEquals("pending-a-1", text(b2a.decrypt(new SignalMessage(b64(f, "ab.pending.1")))));
        assertEquals("pending-b-1", text(a2b.decrypt(new SignalMessage(b64(f, "ba.pending.1")))));

        // The conversation continues with the new code, both ways.
        exchange(a2b, b2a, "after-upgrade");

        // An old PreKey message (Carol's first message) consumes the old pre-key records.
        SessionCipher b2c = new SessionCipher(bob, CAROL);
        SessionCipher c2b = new SessionCipher(carol, BOB);
        assertEquals(
                "hello from carol",
                text(b2c.decrypt(new PreKeySignalMessage(b64(f, "cb.prekey")))));
        assertFalse("one-time pre-key must be consumed", bob.containsPreKey(42));
        // Carol's old record still carries the unacknowledged pre-key until Bob replies.
        assertEquals(CiphertextMessage.PREKEY_TYPE, c2b.encrypt("again".getBytes()).getType());
        assertEquals("reply", text(c2b.decrypt(new SignalMessage(b2c.encrypt("reply".getBytes()).serialize()))));
        assertEquals(CiphertextMessage.WHISPER_TYPE, c2b.encrypt("acked".getBytes()).getType());

        // Records written by the new code read back unchanged.
        for (SessionRecord r :
                new SessionRecord[] {alice.loadSession(BOB), bob.loadSession(ALICE), carol.loadSession(BOB)}) {
            byte[] bytes = r.serialize();
            assertArrayEquals(bytes, new SessionRecord(bytes).serialize());
        }
        assertTrue(bob.containsSignedPreKey(7));
    }

    @Test
    public void fingerprintsAreUnchanged() throws Exception {
        Properties f = load("fixtures.properties");
        IdentityKey a = new IdentityKeyPair(b64(f, "alice.identity")).getPublicKey();
        IdentityKey b = new IdentityKeyPair(b64(f, "bob.identity")).getPublicKey();
        Fingerprint fp =
                new NumericFingerprintGenerator(1024)
                        .createFor("alice@example.org", a, "bob@example.org", b);
        assertEquals(
                f.getProperty("fingerprint.displayable"),
                fp.getDisplayableFingerprint().getDisplayText());
        assertArrayEquals(
                b64(f, "fingerprint.scannable"), fp.getScannableFingerprint().getSerialized());
    }

    private static void exchange(SessionCipher a2b, SessionCipher b2a, String tag) throws Exception {
        for (int i = 0; i < 3; i++) {
            CiphertextMessage m = a2b.encrypt((tag + "-a" + i).getBytes());
            assertEquals(CiphertextMessage.WHISPER_TYPE, m.getType());
            assertEquals(tag + "-a" + i, text(b2a.decrypt(new SignalMessage(m.serialize()))));
            CiphertextMessage r = b2a.encrypt((tag + "-b" + i).getBytes());
            assertEquals(tag + "-b" + i, text(a2b.decrypt(new SignalMessage(r.serialize()))));
        }
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
