package eu.siacs.conversations.utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;

/** The SOCKS5 reply is written by whoever answers on the proxy port; its lengths are untrusted. */
public class SocksReplyParsingTest {

    @Test
    public void domainNameIsRead() throws Exception {
        final byte[] reply = {3, 'a', '.', 'b'};
        Assert.assertArrayEquals(
                new byte[] {'a', '.', 'b'},
                SocksSocketFactory.readDestination((byte) 0x03, new ByteArrayInputStream(reply)));
    }

    @Test(expected = IOException.class)
    public void zeroLengthDomainNameIsRejected() throws Exception {
        SocksSocketFactory.readDestination((byte) 0x03, new ByteArrayInputStream(new byte[] {0}));
    }

    /** End of stream is -1, which used to become {@code new byte[-1]}. */
    @Test(expected = IOException.class)
    public void endOfStreamInsteadOfLengthIsRejected() throws Exception {
        SocksSocketFactory.readDestination((byte) 0x03, new ByteArrayInputStream(new byte[0]));
    }
}
