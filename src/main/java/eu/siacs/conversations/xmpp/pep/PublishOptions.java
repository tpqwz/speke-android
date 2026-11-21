package eu.siacs.conversations.xmpp.pep;

import android.os.Bundle;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.xmpp.model.stanza.Iq;

public class PublishOptions {

    private PublishOptions() {}

    public static Bundle openAccess() {
        final Bundle options = new Bundle();
        options.putString("pubsub#access_model", "open");
        return options;
    }

    public static Bundle presenceAccess() {
        final Bundle options = new Bundle();
        options.putString("pubsub#access_model", "presence");
        return options;
    }

    /**
     * Stories: without max_items the server auto-creates the node with its PEP default (often a
     * single item), so every new story silently replaced the previous one.
     */
    public static Bundle storiesAccess() {
        final Bundle options = new Bundle();
        options.putString("pubsub#access_model", "presence");
        options.putString("pubsub#max_items", "120");
        return options;
    }

    public static Bundle persistentWhitelistAccess() {
        final Bundle options = new Bundle();
        options.putString("pubsub#persist_items", "true");
        options.putString("pubsub#access_model", "whitelist");
        return options;
    }

    public static Bundle persistentWhitelistAccessMaxItems() {
        final Bundle options = new Bundle();
        options.putString("pubsub#persist_items", "true");
        options.putString("pubsub#access_model", "whitelist");
        options.putString("pubsub#send_last_published_item", "never");
        options.putString("pubsub#max_items", "max");
        options.putString("pubsub#notify_delete", "true");
        options.putString(
                "pubsub#notify_retract", "true"); // one could also set notify=true on the retract

        return options;
    }

    public static boolean preconditionNotMet(Iq response) {
        final Element error =
                response.getType() == Iq.Type.ERROR ? response.findChild("error") : null;
        return error != null && error.hasChild("precondition-not-met", Namespace.PUBSUB_ERROR);
    }
}
