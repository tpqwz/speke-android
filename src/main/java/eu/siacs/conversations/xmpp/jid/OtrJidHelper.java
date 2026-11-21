package eu.siacs.conversations.xmpp.jid;

import eu.siacs.conversations.xmpp.Jid;
import net.java.otr4j.session.SessionID;

public final class OtrJidHelper {

    public static Jid fromSessionID(final SessionID id) throws IllegalArgumentException {
        if (id.getUserID().isEmpty()) {
            return Jid.of(id.getAccountID());
        } else {
            return Jid.of(id.getAccountID() + "/" + id.getUserID());
        }
    }
}
