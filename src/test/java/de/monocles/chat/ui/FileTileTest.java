package de.monocles.chat.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The paper-sheet tile of a shared file: which extension it prints and how it is coloured. */
public class FileTileTest {

    @Test
    public void extensionComesFromTheNameThenTheType() {
        assertEquals("PDF", FileTile.extensionLabel("Quarterly report.PDF", "application/pdf"));
        assertEquals("TXT", FileTile.extensionLabel(null, "text/plain"));
        assertEquals("DOCX", FileTile.extensionLabel("letter.docx", null));
        // An upload name without extension (PQ OMEMO2) falls back to the described type.
        assertEquals("PDF", FileTile.extensionLabel("0123456789abcdef", "application/pdf"));
    }

    @Test
    public void unusableExtensionsGetTheIcon() {
        assertNull(FileTile.extensionLabel("x.verylongext", null));
        assertNull(FileTile.extensionLabel("weird.p-f", null));
        assertNull(FileTile.extensionLabel(".hidden", null));
        assertNull(FileTile.extensionLabel("trailing.", null));
        assertNull(FileTile.extensionLabel(null, null));
        assertNull(FileTile.extensionLabel("", ""));
    }

    @Test
    public void filesAreGroupedIntoFamilies() {
        assertEquals(FileTile.Family.DOCUMENT, FileTile.family("application/pdf", "a.pdf"));
        assertEquals(
                FileTile.Family.DOCUMENT, FileTile.family("text/plain; charset=utf-8", "a.txt"));
        assertEquals(
                FileTile.Family.DOCUMENT,
                FileTile.family(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "a.docx"));
        assertEquals(
                FileTile.Family.DOCUMENT,
                FileTile.family("application/vnd.oasis.opendocument.text", "a.odt"));
        assertEquals(FileTile.Family.ARCHIVE, FileTile.family("application/zip", "a.zip"));
        assertEquals(
                FileTile.Family.ARCHIVE, FileTile.family("application/webxdc+zip", "chess.xdc"));
        assertEquals(FileTile.Family.OTHER, FileTile.family("audio/mpeg", "a.mp3"));
        assertEquals(FileTile.Family.OTHER, FileTile.family("image/png", "a.png"));
    }

    @Test
    public void aGenericTypeIsDecidedByTheExtension() {
        assertEquals(
                FileTile.Family.DOCUMENT, FileTile.family("application/octet-stream", "notes.md"));
        assertEquals(FileTile.Family.ARCHIVE, FileTile.family(null, "backup.7z"));
        assertEquals(FileTile.Family.OTHER, FileTile.family(null, "blob"));
    }
}
