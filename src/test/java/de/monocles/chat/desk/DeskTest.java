package de.monocles.chat.desk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import de.monocles.chat.desk.Desk.Line;
import de.monocles.chat.desk.Desk.Note;
import de.monocles.chat.desk.Desk.Session;
import de.monocles.chat.desk.Desk.StreamLine;
import de.monocles.chat.desk.Desk.View;
import java.util.List;
import org.junit.Test;

public class DeskTest {

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
                List.of("Hold the bisque at 200.", "Second frame from the end of the pier."),
                Desk.threadTitles(Desk.sample()));
    }

    @Test
    public void yourUnansweredPostStaysAndAnOrphanIsItsOwnRoot() {
        List<Note> notes =
                List.of(
                        new Note("orphan", "missing", "Ada", "Orphan", 1, false, List.of()),
                        new Note("mine", null, "Ada", "Mine", 2, true, List.of()));
        assertEquals(List.of("Mine"), Desk.threadTitles(notes));
    }

    @Test
    public void titleIsTheFirstLine() {
        assertEquals(
                "Second frame from the end of the pier.",
                Desk.thread(Desk.sample(), "m1", null).get(0).text);
    }

    @Test
    public void theSecondLevelFoldsUntilTheLineIsOpened() {
        List<Line> folded = Desk.thread(Desk.sample(), "m1", null);
        assertEquals(1, folded.get(1).folded);
        assertFalse(folded.stream().anyMatch(line -> line.text.contains("climb")));
        List<Line> open = Desk.thread(Desk.sample(), "m1", "m2b");
        assertTrue(open.stream().anyMatch(line -> line.focused && line.text.contains("climb")));
        assertTrue(open.stream().noneMatch(line -> line.folded > 0));
    }

    @Test
    public void switchingViewClosesTheThreadAndBackReturns() {
        Session session = Session.sample();
        session.openThread("m1");
        session.setView(View.BOARD);
        assertEquals(View.BOARD, session.view());
        assertNull(session.threadId());
        assertTrue(session.back());
        assertEquals("m1", session.threadId());
    }

    @Test
    public void aLoneMessageOpensInTheStream() {
        Session session = Session.sample();
        assertThrows(IllegalArgumentException.class, () -> session.openThread("m4"));
        session.search("glaze");
        assertEquals(View.STREAM, session.view());
        assertNull(session.threadId());
        assertEquals("m4", session.focusId());
    }

    @Test
    public void searchForRopeOpensThePierThreadOnThatLine() {
        Session session = Session.sample();
        session.search("rope");
        assertEquals("m1", session.threadId());
        assertEquals("m2", session.focusId());
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
