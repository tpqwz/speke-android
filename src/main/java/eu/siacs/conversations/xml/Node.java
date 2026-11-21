package eu.siacs.conversations.xml;

import com.google.common.collect.ImmutableMap;
import java.util.Map;

public interface Node {
    public String getContent();

    public String toString(final ImmutableMap<String, String> ns);

    public void appendToBuilder(
            final Map<String, String> ns, final StringBuilder elementOutput, final int skipEnd);
}
