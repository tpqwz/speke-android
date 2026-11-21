package eu.siacs.conversations.entities;

import eu.siacs.conversations.xmpp.Jid;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Test;

/** Recognising the same message again when its body can't be compared. */
public class MessageSimilarTest {

    private static final Jid PEER = Jid.of("peer@example.com");

    private static Message message(
            final String uuid,
            final String remoteMsgId,
            final Jid counterpart,
            final String body,
            final int encryption,
            final int status) {
        return new Message(
                null,
                uuid,
                "conversation",
                counterpart,
                null,
                body,
                System.currentTimeMillis(),
                encryption,
                status,
                Message.TYPE_TEXT,
                false,
                remoteMsgId,
                null,
                null,
                null,
                true,
                null,
                false,
                null,
                null,
                false,
                false,
                null,
                null,
                null,
                System.currentTimeMillis(),
                null,
                null,
                null,
                null,
                0,
                0);
    }

    @Test
    public void undecryptableArchiveCopyOfOwnMessageIsADuplicate() {
        final String id = UUID.randomUUID().toString();
        final Message restored =
                message(id, id, PEER, "hello", Message.ENCRYPTION_AXOLOTL, Message.STATUS_SEND);
        for (final int encryption :
                new int[] {
                    Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE,
                    Message.ENCRYPTION_AXOLOTL_OMEMO2_NOT_FOR_THIS_DEVICE
                }) {
            final Message copy =
                    message(
                            UUID.randomUUID().toString(),
                            id,
                            PEER,
                            "",
                            encryption,
                            Message.STATUS_SEND);
            Assert.assertTrue(restored.similar(copy));
        }
    }

    @Test
    public void otherIdOrCounterpartIsNotADuplicate() {
        final String id = UUID.randomUUID().toString();
        final Message restored =
                message(id, id, PEER, "hello", Message.ENCRYPTION_AXOLOTL, Message.STATUS_SEND);
        final Message otherId =
                message(
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        PEER,
                        "",
                        Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE,
                        Message.STATUS_SEND);
        final Message otherPeer =
                message(
                        UUID.randomUUID().toString(),
                        id,
                        Jid.of("other@example.com"),
                        "",
                        Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE,
                        Message.STATUS_SEND);
        Assert.assertFalse(restored.similar(otherId));
        Assert.assertFalse(restored.similar(otherPeer));
    }

    @Test
    public void decryptableCopyStillNeedsTheSameBody() {
        final String id = UUID.randomUUID().toString();
        final Message restored =
                message(id, id, PEER, "hello", Message.ENCRYPTION_AXOLOTL, Message.STATUS_SEND);
        final Message changed =
                message(
                        UUID.randomUUID().toString(),
                        id,
                        PEER,
                        "something else",
                        Message.ENCRYPTION_AXOLOTL,
                        Message.STATUS_SEND);
        Assert.assertFalse(restored.similar(changed));
    }
}
