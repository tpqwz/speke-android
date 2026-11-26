package de.monocles.chat.desk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import de.monocles.chat.desk.Desk.Attached;
import de.monocles.chat.desk.Desk.Line;
import de.monocles.chat.desk.Desk.Note;
import de.monocles.chat.desk.Desk.Row;
import de.monocles.chat.desk.Desk.Session;
import de.monocles.chat.desk.Desk.Sort;
import de.monocles.chat.desk.Desk.StreamLine;
import de.monocles.chat.desk.Desk.View;
import java.util.List;
import org.junit.Test;

public class DeskTest {

    @Test
    public void aPostKeepsItsFirstHttpsAddress() {
        assertEquals("https://example.com/a", Desk.linkIn("see https://example.com/a."));
        assertEquals("example.com", Desk.linkHost("https://www.example.com/a"));
        assertEquals("example.com", Desk.linkHost("https://example.com:443/a"));
        assertNull(Desk.linkIn("http://example.com/a"));
        assertNull(Desk.linkIn("no address"));
        List<Note> notes =
                List.of(
                        new Note("root", null, "Ada", "https://feeds.example.com/a", 10, false, List.of()),
                        new Note("reply", "root", "Jules", "Read it", 11, false, List.of()));
        Row row = new Session(notes).rows().get(0);
        assertEquals("https://feeds.example.com/a", row.link);
        assertEquals("feeds.example.com", Desk.linkHost(row.link));
    }

    @Test
    public void april2026() {
        assertEquals("2026-04", Desk.monthKey(1_775_088_000L));
    }

    @Test
    public void naturalNamesPutShot2BeforeShot10() {
        assertTrue(Desk.compareNatural("shot2.jpg", "shot10.jpg") < 0);
        assertTrue(Desk.compareNatural("shot10.jpg", "shot2.jpg") > 0);
    }

    @Test
    public void aReplyMakesAThreadAndALoneNoteDoesNot() {
        assertEquals(
                List.of("Second frame from the end of the pier."),
                Desk.threadTitles(Desk.sample()));
    }

    @Test
    public void yourUnansweredPostStaysInTheStream() {
        List<Note> notes =
                List.of(
                        new Note("orphan", "missing", "Ada", "Orphan", 1, false, List.of()),
                        new Note("mine", null, "Ada", "Mine", 2, true, List.of()));
        assertEquals(List.of(), Desk.threadTitles(notes));
        Session session = new Session(notes);
        session.setView(View.BOARD);
        assertEquals(
                List.of("mine", "orphan"),
                session.rows().stream().map(row -> row.id).toList());
    }

    @Test
    public void titleIsTheFirstLine() {
        assertEquals(
                "Second frame from the end of the pier.",
                Desk.thread(Desk.sample(), "m1", null).get(0).text);
    }

    @Test
    public void openingAThreadShowsTheWholeTree() {
        List<Line> open = Desk.thread(Desk.sample(), "m1", null);
        assertTrue(open.stream().anyMatch(line -> line.text.contains("climb")));
        assertTrue(open.stream().noneMatch(line -> line.folded > 0));
    }

    @Test
    public void aReplyUnderTheSecondLevelStaysFoldedUntilOpened() {
        List<Note> notes =
                List.of(
                        new Note("a", null, "Ada", "Root", 1, false, List.of()),
                        new Note("b", "a", "Ada", "Child", 2, false, List.of()),
                        new Note("c", "b", "Ada", "Grand", 3, false, List.of()),
                        new Note("d", "c", "Ada", "Great", 4, false, List.of()));
        Session session = new Session(notes);
        session.setView(View.BOARD);
        assertTrue(session.rows().stream().anyMatch(row -> row.text.equals("Grand")));
        assertTrue(session.rows().stream().noneMatch(row -> row.text.equals("Great")));
        Row grand =
                session.rows().stream()
                        .filter(row -> row.text.equals("Grand"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(grand.collapsed);
        assertEquals(1, grand.replies);
        session.toggleFold(grand.id, true);
        assertTrue(session.rows().stream().anyMatch(row -> row.text.equals("Great")));
        session.openThread("a");
        assertTrue(session.rows().stream().anyMatch(row -> row.text.equals("Great")));
        assertTrue(session.rows().stream().noneMatch(row -> row.collapsed));
    }

    @Test
    public void switchingViewClosesTheThreadAndBackReturns() {
        Session session = Session.sample();
        session.openThread("m1");
        session.setView(View.BOARD);
        assertEquals(View.BOARD, session.view());
        assertNull(session.threadId());
        assertTrue(session.rows().stream().anyMatch(row -> row.text.equals("Hold the bisque at 200.")));
        assertTrue(session.rows().stream().anyMatch(row -> row.text.contains("climb")));
        assertTrue(session.back());
        assertEquals("m1", session.threadId());
    }

    @Test
    public void aLoneMessageStaysOnTheListForChat() {
        Session session = Session.sample();
        assertThrows(IllegalArgumentException.class, () -> session.openThread("m4"));
        session.search("glaze");
        assertEquals(View.THREADS, session.view());
        assertEquals(List.of("m4"), session.rows().stream().map(row -> row.id).toList());
        assertEquals(0, session.rows().get(0).replies);
        assertFalse(session.openListed("m4"));
        assertEquals(View.THREADS, session.view());
        assertNull(session.threadId());
        assertEquals("m4", session.focusId());
        assertFalse(session.hasBack());
    }

    @Test
    public void threadsChipClosesTheOpenThreadWithoutStacking() {
        Session session = Session.sample();
        session.openThread("m1");
        session.showList();
        assertNull(session.threadId());
        assertEquals(View.THREADS, session.view());
        assertFalse(session.hasBack());
        assertFalse(session.back());
    }

    @Test
    public void searchForRopeKeepsThePierThreadAndOpensOnThatLine() {
        Session session = Session.sample();
        session.search("climb");
        assertNull(session.threadId());
        assertEquals(List.of("m1"), session.rows().stream().map(row -> row.id).toList());
        session.openListed("m1");
        assertEquals("m1", session.threadId());
        assertEquals("m2b", session.focusId());
        assertTrue(
                session.rows().stream()
                        .anyMatch(row -> row.focused && row.text.contains("climb")));
    }

    @Test
    public void threadFilesStayOnTheThreadAndSortNaturally() {
        assertEquals(
                List.of("rope-note.txt", "shot2.jpg", "shot10.jpg"),
                Desk.files(Desk.sample(), "m1", Desk.Group.FLAT, null));
        assertEquals(
                List.of("jpg", "pdf", "txt"),
                Desk.files(Desk.sample(), null, Desk.Group.EXTENSION, null));
        assertEquals(
                List.of("shot2.jpg", "shot10.jpg"),
                Desk.files(Desk.sample(), null, Desk.Group.EXTENSION, "jpg"));
        assertEquals(
                List.of("2026-04"), Desk.files(Desk.sample(), null, Desk.Group.MONTH, null));
    }

    @Test
    public void theShelfRowsCarryTheThreadIds() {
        List<Row> rows = Session.sample().rows();
        assertEquals(1, rows.size());
        assertEquals("m1", rows.get(0).id);
        assertEquals("Second frame from the end of the pier.", rows.get(0).text);
        assertEquals("Ada", rows.get(0).author);
        assertEquals("02 Apr 2026 00:02", rows.get(0).when);
        assertEquals(2, rows.get(0).replies);
        assertEquals(3, rows.get(0).fileCount);
    }

    @Test
    public void theBoardListsEveryPostNewestActivityFirst() {
        Session session = Session.sample();
        session.setView(View.BOARD);
        assertEquals(
                List.of("m4", "m3", "m1"),
                session.rows().stream().filter(row -> row.depth == 0).map(row -> row.id).toList());
        session.setShelfSort(Desk.ShelfSort.REPLIES);
        assertEquals(
                List.of("m1", "m4", "m3"),
                session.rows().stream().filter(row -> row.depth == 0).map(row -> row.id).toList());
        session.setShelfSort(Desk.ShelfSort.OLDEST);
        assertEquals(
                List.of("m1", "m3", "m4"),
                session.rows().stream().filter(row -> row.depth == 0).map(row -> row.id).toList());
        Row pier =
                session.rows().stream().filter(row -> "m1".equals(row.id)).findFirst().orElseThrow();
        assertEquals("shot2.jpg", pier.chip);
        assertEquals("1.2 KB · +1", pier.chipDetail);
    }

    @Test
    public void showInOpensTheOtherReadings() {
        Session session = Session.sample();
        session.showIn("m2", View.BOARD);
        assertEquals(View.BOARD, session.view());
        assertNull(session.threadId());
        assertEquals("m2", session.focusId());
        session.showIn("m2", View.THREADS);
        assertEquals("m1", session.threadId());
        assertEquals("m2", session.focusId());
        assertThrows(IllegalArgumentException.class, () -> session.showIn("m4", View.THREADS));
        session.showIn("m4", View.BOARD);
        assertEquals(View.BOARD, session.view());
        assertEquals("m4", session.focusId());
        session.showIn("m4", View.STREAM);
        assertEquals(View.STREAM, session.view());
        assertEquals("m4", session.focusId());
    }

    @Test
    public void filesCloseOnTheShelfAndBackReturns() {
        Session session = Session.sample();
        session.openThread("m1");
        session.openFiles();
        assertEquals(
                List.of("rope-note.txt", "shot2.jpg", "shot10.jpg"),
                session.rows().stream().map(row -> row.text).toList());
        Row shot =
                session.rows().stream()
                        .filter(row -> "shot2.jpg".equals(row.text))
                        .findFirst()
                        .orElseThrow();
        assertEquals("Ada", shot.author);
        assertEquals("1.2 KB", shot.chip);
        assertEquals("JPG image", shot.chipKind);
        assertEquals("", Desk.formatSize(0));
        assertEquals("80 B", Desk.formatSize(80));
        session.setGroup(Desk.Group.EXTENSION);
        Row jpg =
                session.rows().stream().filter(row -> "jpg".equals(row.id)).findFirst().orElseThrow();
        assertEquals(".jpg", jpg.text);
        assertEquals(2, jpg.fileCount);
        assertTrue(jpg.folder);
        session.openFolder("jpg");
        assertEquals(List.of("shot2.jpg", "shot10.jpg"), session.rows().stream().map(row -> row.text).toList());
        assertTrue(session.back());
        assertTrue(session.filesOpen());
        assertTrue(session.rows().stream().anyMatch(row -> row.folder));
        assertTrue(session.back());
        assertEquals("m1", session.threadId());
        assertFalse(session.filesOpen());
    }

    @Test
    public void fileSearchStaysInTheThreadAndKeepsTheDirectory() {
        Session session = Session.sample();
        session.openThread("m1");
        session.openFiles();
        session.setQuery("shot");
        assertTrue(session.filesOpen());
        assertEquals("m1", session.threadId());
        assertEquals(
                List.of("shot2.jpg", "shot10.jpg"),
                session.rows().stream().map(row -> row.text).toList());
    }

    @Test
    public void sizeOrderPutsTheSmallerFileFirst() {
        List<Note> notes =
                List.of(
                        new Note(
                                "a",
                                null,
                                "Ada",
                                "A",
                                1,
                                true,
                                List.of(
                                        new Attached("big.pdf", "document", "Ada", 50, 1),
                                        new Attached("small.txt", "text", "Ada", 5, 1))));
        assertEquals(
                List.of("small.txt", "big.pdf"),
                Desk.files(notes, null, Desk.Group.FLAT, null, Sort.SIZE));
    }

    @Test
    public void aNewNoteListKeepsTheOpenThread() {
        Session session = Session.sample();
        session.openThread("m1");
        session.replace(Desk.sample());
        assertEquals("m1", session.threadId());
        session.replace(List.of());
        assertNull(session.threadId());
    }

    @Test
    public void millisBecomeSeconds() {
        assertEquals(1_775_088_000L, DeskNotes.seconds(1_775_088_000_000L));
        assertEquals(1_775_088_000L, DeskNotes.seconds(1_775_088_000L));
    }

    @Test
    public void streamQuotesTheParent() {
        StreamLine rope =
                Desk.stream(Desk.sample(), null).stream()
                        .filter(line -> "m2".equals(line.id))
                        .findFirst()
                        .orElseThrow();
        assertEquals("Jules — Then we crop the rope.", rope.text);
        assertEquals("Ada: Second frame from the end of the pier.", rope.quote);
    }
}
