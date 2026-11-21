package im.conversations.android.xmpp.model.bind2;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;
import java.util.Collection;
import java.util.Collections;

@XmlElement
public class Bind extends Extension {

    public Bind() {
        super(Bind.class);
    }

    public Inline getInline() {
        return this.getExtension(Inline.class);
    }

    public Collection<Feature> getInlineFeatures() {
        final var inline = getInline();
        return inline == null ? Collections.emptyList() : inline.getExtensions(Feature.class);
    }

    public void setTag(final String tag) {
        this.addExtension(new Tag(tag));
    }
}
