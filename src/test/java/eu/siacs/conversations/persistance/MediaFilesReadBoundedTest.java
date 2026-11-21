package eu.siacs.conversations.persistance;

import static org.junit.Assert.assertArrayEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.Test;

public class MediaFilesReadBoundedTest {

    private static ByteArrayInputStream stream(final int size) {
        final byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) i;
        }
        return new ByteArrayInputStream(data);
    }

    @Test
    public void readsExactlyTheRecordedLength() throws IOException {
        final byte[] expected = stream(300_000).readAllBytes();
        assertArrayEquals(expected, MediaFiles.readBounded(stream(300_000), 300_000, 1 << 20));
    }

    @Test(expected = IOException.class)
    public void refusesAboveTheLimit() throws IOException {
        MediaFiles.readBounded(stream(10), 10, 9);
    }

    @Test(expected = IOException.class)
    public void refusesALongerStream() throws IOException {
        MediaFiles.readBounded(stream(11), 10, 100);
    }

    @Test(expected = IOException.class)
    public void refusesAShorterStream() throws IOException {
        MediaFiles.readBounded(stream(9), 10, 100);
    }

    @Test(expected = IOException.class)
    public void refusesANegativeLength() throws IOException {
        MediaFiles.readBounded(stream(0), -1, 100);
    }
}
