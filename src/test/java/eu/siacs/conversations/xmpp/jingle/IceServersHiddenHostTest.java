package eu.siacs.conversations.xmpp.jingle;

import im.conversations.android.xmpp.model.disco.external.Service;
import im.conversations.android.xmpp.model.disco.external.Services;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;

/** XEP-0215 relays on .onion/.i2p hosts are never handed to libwebrtc (it would resolve them). */
public class IceServersHiddenHostTest {

    private static Service service(final String type, final String host) {
        final Service service = new Service();
        service.setAttribute("type", type);
        service.setAttribute("host", host);
        service.setAttribute("port", "3478");
        service.setAttribute("transport", "udp");
        return service;
    }

    @Test
    public void hiddenServiceRelaysAreDropped() {
        final Services services = new Services();
        services.addExtension(service("stun", "stun.example.org"));
        services.addExtension(service("stun", "relay.abcdef.onion"));
        services.addExtension(service("stun", "STUN.EXAMPLE.I2P."));
        final List<String> urls =
                services.getIceServers().stream()
                        .flatMap(s -> s.urls.stream())
                        .collect(Collectors.toList());
        Assert.assertEquals(List.of("stun:stun.example.org:3478"), urls);
    }
}
