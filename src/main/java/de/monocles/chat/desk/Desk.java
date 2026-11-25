package de.monocles.chat.desk;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One room, read three ways, and the same messages as a directory.
 *
 * <p>A thread is a post that has a reply. A post with none stays off that list, including your own,
 * and the board still shows it. The first two levels of a thread are open. Deeper replies stay
 * folded until that line is opened. Opening the thread itself shows the whole tree. Search keeps a
 * hit together with the posts above and below it. Changing the shelf leaves an open thread. A
 * thread's directory contains the files on that post and on its replies.
 *
 * <p>The conversation screen reads the open room through {@link Session}. Chat on that shelf is the
 * transcript the list already drew.
 */
public final class Desk {

    public enum View {
        THREADS,
        BOARD,
        STREAM
    }

    public enum Group {
        FLAT,
        TYPE,
        MONTH,
        SENDER,
        EXTENSION,
        THREAD
    }

    public enum Sort {
        NAME,
        DATE,
        SIZE,
        KIND,
        SENDER
    }

    /** Order of the thread list and the board. The stream stays in time order. */
    public enum ShelfSort {
        ACTIVITY,
        REPLIES,
        NEWEST,
        OLDEST
    }

    /** What the shelf says when the list has no rows. */
    public enum Empty {
        NONE,
        SEARCH,
        SHELF,
        THREADS
    }

    /** The line under the shelf switch. */
    public enum Cue {
        HIDDEN,
        STREAM,
        THREAD,
        SHELF
    }

    /** One message, plus the files that were sent with it. */
    public static final class Note {
        public final String id;
        public final String parentId;
        public final String author;
        public final String body;
        public final long at;
        public final boolean fromSelf;
        public final List<Attached> files;

        public Note(
                String id,
                String parentId,
                String author,
                String body,
                long at,
                boolean fromSelf,
                List<Attached> files) {
            this.id = id;
            this.parentId = parentId;
            this.author = author;
            this.body = body;
            this.at = at;
            this.fromSelf = fromSelf;
            this.files = files == null ? List.of() : List.copyOf(files);
        }
    }

    /** A file posted on a note. */
    public static final class Attached {
        public final String name;
        public final String kind;
        public final String sender;
        public final long bytes;
        public final long at;

        public Attached(String name, String kind, String sender, long bytes, long at) {
            this.name = name;
            this.kind = kind;
            this.sender = sender;
            this.bytes = bytes;
            this.at = at;
        }
    }

    public static final class Line {
        public final String id;
        public final int depth;
        public final String text;
        public final int folded;
        public final boolean focused;

        Line(String id, int depth, String text, int folded, boolean focused) {
            this.id = id;
            this.depth = depth;
            this.text = text;
            this.folded = folded;
            this.focused = focused;
        }
    }

    public static final class StreamLine {
        public final String id;
        public final String text;
        public final String quote;
        public final boolean focused;

        StreamLine(String id, String text, String quote, boolean focused) {
            this.id = id;
            this.text = text;
            this.quote = quote;
            this.focused = focused;
        }
    }

    /** One row of the reading that is on screen. A folder row opens the next level. */
    public static final class Row {
        public enum Kind {
            THREAD,
            POST,
            STREAM,
            DAY,
            FILE,
            FOLDER
        }

        public final Kind kind;
        public final String id;
        public final int depth;
        public final String text;
        public final String author;
        public final String when;
        public final String quote;
        /** Replies under this post, including replies to those replies. */
        public final int replies;
        public final int fileCount;
        public final boolean collapsed;
        public final boolean focused;
        public final boolean outgoing;
        public final boolean folder;
        public final String chip;
        public final String chipKind;
        public final String chipDetail;

        public Row(
                Kind kind,
                String id,
                int depth,
                String text,
                String author,
                String when,
                String quote,
                int replies,
                int fileCount,
                boolean collapsed,
                boolean focused,
                boolean outgoing,
                boolean folder,
                String chip,
                String chipKind,
                String chipDetail) {
            this.kind = kind;
            this.id = id;
            this.depth = depth;
            this.text = text;
            this.author = author == null ? "" : author;
            this.when = when == null ? "" : when;
            this.quote = quote;
            this.replies = replies;
            this.fileCount = fileCount;
            this.collapsed = collapsed;
            this.focused = focused;
            this.outgoing = outgoing;
            this.folder = folder;
            this.chip = chip;
            this.chipKind = chipKind;
            this.chipDetail = chipDetail;
        }

        static Row file(String id, String text, boolean folder) {
            return new Row(
                    folder ? Kind.FOLDER : Kind.FILE,
                    id,
                    0,
                    text,
                    "",
                    "",
                    null,
                    0,
                    0,
                    false,
                    false,
                    false,
                    folder,
                    null,
                    null,
                    null);
        }

        static Row day(String id, String label) {
            return new Row(
                    Kind.DAY, id, 0, label, "", "", null, 0, 0, false, false, false, false, null, null, null);
        }
    }

    private Desk() {}

    /** Desk, the sample room. Ada, Jules, and you. */
    public static List<Note> sample() {
        long t0 = 1_775_088_000L;
        return List.of(
                new Note(
                        "m1",
                        null,
                        "Ada",
                        "Second frame from the end of the pier.\nWind was up, so the horizon is soft.",
                        t0,
                        false,
                        List.of(
                                new Attached("shot2.jpg", "image", "Ada", 1200, t0),
                                new Attached("shot10.jpg", "image", "Ada", 1400, t0))),
                new Note(
                        "m2",
                        "m1",
                        "Jules",
                        "Then we crop the rope.",
                        t0 + 60,
                        false,
                        List.of(new Attached("rope-note.txt", "text", "Jules", 80, t0 + 60))),
                new Note(
                        "m2b",
                        "m2",
                        "Ada",
                        "Then climb slowly.",
                        t0 + 120,
                        false,
                        List.of()),
                new Note(
                        "m3",
                        null,
                        "You",
                        "Hold the bisque at 200.",
                        t0 + 180,
                        true,
                        List.of()),
                new Note(
                        "m4",
                        null,
                        "Ada",
                        "The glaze fridge is labeled.",
                        t0 + 240,
                        false,
                        List.of(new Attached("glaze.pdf", "document", "Ada", 3000, t0 + 240))));
    }

    public static List<String> threadTitles(List<Note> notes) {
        Index index = new Index(notes);
        List<String> titles = new ArrayList<>();
        for (int card : index.cards()) {
            titles.add(index.title(card));
        }
        return titles;
    }

    public static List<Line> board(List<Note> notes, String focusId) {
        Index index = new Index(notes);
        List<Line> lines = new ArrayList<>();
        for (int root : index.rootsSorted(ShelfSort.ACTIVITY)) {
            walk(index, root, 0, focusId, false, Map.of(), lines);
        }
        return lines;
    }

    public static List<Line> thread(List<Note> notes, String rootId, String focusId) {
        Index index = new Index(notes);
        int root = index.require(rootId);
        if (index.rootOf(root) != root || !index.isCard(root)) {
            throw new IllegalArgumentException("That message is not a thread yet.");
        }
        List<Line> lines = new ArrayList<>();
        walk(index, root, 0, focusId, true, Map.of(), lines);
        return lines;
    }

    public static List<StreamLine> stream(List<Note> notes, String focusId) {
        Index index = new Index(notes);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < notes.size(); i++) {
            order.add(i);
        }
        order.sort(index::byTime);
        List<StreamLine> lines = new ArrayList<>();
        for (int i : order) {
            Note note = notes.get(i);
            String quote = null;
            if (index.parent[i] >= 0) {
                int parent = index.parent[i];
                quote = notes.get(parent).author + ": " + firstLine(notes.get(parent).body);
            }
            lines.add(
                    new StreamLine(
                            note.id,
                            note.author + " — " + firstLine(note.body),
                            quote,
                            note.id.equals(focusId)));
        }
        return lines;
    }

    /**
     * Names in a flat directory, or folder labels when {@code folder} is null and {@code group} is
     * not flat. {@code threadId} null is the whole room.
     */
    public static List<String> files(
            List<Note> notes, String threadId, Group group, String folder) {
        return files(notes, threadId, group, folder, Sort.NAME);
    }

    public static List<String> files(
            List<Note> notes, String threadId, Group group, String folder, Sort sort) {
        Index index = new Index(notes);
        List<FileRef> refs = index.filesIn(threadId);
        if (group == Group.FLAT || folder != null) {
            if (folder != null) {
                refs.removeIf(ref -> !index.groupKey(ref, group).equals(folder));
                if (refs.isEmpty()) {
                    throw new IllegalArgumentException("That folder is not in this arrangement.");
                }
            }
            refs.sort((a, b) -> compareFiles(a, b, sort));
            List<String> names = new ArrayList<>();
            for (FileRef ref : refs) {
                names.add(ref.file.name);
            }
            return names;
        }
        List<String> labels = new ArrayList<>();
        for (FileRef ref : refs) {
            String key = index.groupKey(ref, group);
            if (!labels.contains(key)) {
                labels.add(key);
            }
        }
        labels.sort(Desk::compareNatural);
        return labels;
    }

    public static String monthKey(long at) {
        return DateTimeFormatter.ofPattern("yyyy-MM")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochSecond(at));
    }

    static String formatWhen(long at) {
        return DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.UK)
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochSecond(at));
    }

    /** {@code today}, {@code yesterday}, or a UTC date such as {@code 02 Apr 2026}. */
    static String dayLabel(long at, long now) {
        LocalDate day = Instant.ofEpochSecond(at).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate today = Instant.ofEpochSecond(now).atZone(ZoneOffset.UTC).toLocalDate();
        if (day.equals(today)) {
            return "today";
        }
        if (day.equals(today.minusDays(1))) {
            return "yesterday";
        }
        return DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.UK)
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochSecond(at));
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.UK, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.UK, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static int compareFiles(FileRef a, FileRef b, Sort sort) {
        int primary =
                switch (sort) {
                    case NAME -> 0;
                    case DATE -> Long.compare(a.file.at, b.file.at);
                    case SIZE -> Long.compare(a.file.bytes, b.file.bytes);
                    case KIND -> a.file.kind.compareTo(b.file.kind);
                    case SENDER -> a.file.sender.compareTo(b.file.sender);
                };
        if (primary != 0) {
            return primary;
        }
        return compareNatural(a.file.name, b.file.name);
    }

    public static int compareNatural(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (isDigit(ca) && isDigit(cb)) {
                int ia = takeDigits(a, i);
                int jb = takeDigits(b, j);
                int order = compareDigits(a.substring(i, ia), b.substring(j, jb));
                if (order != 0) {
                    return order;
                }
                i = ia;
                j = jb;
            } else {
                char la = Character.toLowerCase(ca);
                char lb = Character.toLowerCase(cb);
                if (la != lb) {
                    return Character.compare(la, lb);
                }
                i++;
                j++;
            }
        }
        return Integer.compare(a.length(), b.length());
    }

    /** The open room. Shelf changes close a thread. Back walks the places you left. */
    public static final class Session {
        private List<Note> notes;
        private View view = View.THREADS;
        private String threadId;
        private String focusId;
        private boolean files;
        private String folder;
        private Group group = Group.FLAT;
        private Sort sort = Sort.NAME;
        private ShelfSort shelfSort = ShelfSort.ACTIVITY;
        private String query = "";
        private final HashMap<String, Boolean> opened = new HashMap<>();
        private final ArrayDeque<Mark> back = new ArrayDeque<>();

        public Session(List<Note> notes) {
            this.notes = notes;
        }

        /** Keep the place. Drop a thread or a focus the new notes no longer contain. */
        public void replace(List<Note> next) {
            notes = next == null ? List.of() : next;
            Index index = new Index(notes);
            if (threadId != null && !stillThread(index, threadId)) {
                threadId = null;
            }
            if (focusId != null && !index.byId.containsKey(focusId)) {
                focusId = null;
            }
            if (folder != null) {
                boolean found = false;
                for (FileRef ref : index.filesIn(threadId)) {
                    if (index.groupKey(ref, group).equals(folder)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    folder = null;
                }
            }
        }

        public static Session sample() {
            return new Session(Desk.sample());
        }

        public View view() {
            return view;
        }

        public String threadId() {
            return threadId;
        }

        public String focusId() {
            return focusId;
        }

        public boolean filesOpen() {
            return files;
        }

        public Group group() {
            return group;
        }

        public Sort sort() {
            return sort;
        }

        public ShelfSort shelfSort() {
            return shelfSort;
        }

        public String query() {
            return query;
        }

        public boolean hasBack() {
            return !back.isEmpty();
        }

        public void setView(View next) {
            go(next, null);
        }

        public void openThread(String id) {
            Index index = new Index(notes);
            int i = index.require(id);
            if (index.rootOf(i) != i || !index.isCard(i)) {
                throw new IllegalArgumentException("That message is not a thread yet.");
            }
            go(view, id);
        }

        /** Jump to this message in one of the three readings. */
        public void showIn(String id, View next) {
            Index index = new Index(notes);
            int i = index.require(id);
            int root = index.rootOf(i);
            if (next == View.STREAM) {
                go(View.STREAM, null);
                focusId = notes.get(i).id;
                return;
            }
            if (next == View.BOARD) {
                revealAncestors(index, i);
                go(View.BOARD, null);
                focusId = notes.get(i).id;
                return;
            }
            if (!index.isCard(root)) {
                throw new IllegalArgumentException("That message is not a thread yet.");
            }
            go(View.THREADS, notes.get(root).id);
            focusId = notes.get(i).id;
        }

        /**
         * The stream's double-tap. The board opens on this message, and each post above it is
         * unfolded.
         */
        public void openOnBoard(String id) {
            showIn(id, View.BOARD);
        }

        public void openFiles() {
            folder = null;
            if (files) {
                return;
            }
            back.add(new Mark(view, threadId, false));
            files = true;
            focusId = null;
        }

        public void openFolder(String name) {
            if (group == Group.FLAT) {
                throw new IllegalArgumentException("That folder is not in this arrangement.");
            }
            List<String> labels = Desk.files(notes, threadId, group, null);
            if (!labels.contains(name)) {
                throw new IllegalArgumentException("That folder is not in this arrangement.");
            }
            folder = name;
        }

        public void setGroup(Group next) {
            group = next;
            folder = null;
        }

        public void setSort(Sort next) {
            sort = next;
        }

        public void setShelfSort(ShelfSort next) {
            if (next != null) {
                shelfSort = next;
            }
        }

        /** Narrow the shelf. An open thread closes, because the search takes its place. */
        public void setQuery(String next) {
            String cleaned = next == null ? "" : next;
            if (cleaned.equals(query)) {
                return;
            }
            query = cleaned;
            threadId = null;
            focusId = null;
        }

        public void search(String next) {
            setQuery(next);
        }

        public void focus(String id) {
            new Index(notes).require(id);
            focusId = id;
        }

        /**
         * Open the thread this row names. A search opens it on the latest matching line. A post
         * with no replies opens in the stream.
         */
        public void openListed(String id) {
            Index index = new Index(notes);
            int i = index.require(id);
            int root = index.rootOf(i);
            int target = root;
            if (!query.trim().isEmpty()) {
                target = bestMatch(index, root, query.trim().toLowerCase(Locale.ROOT).split("\\s+"));
            }
            if (index.isCard(root)) {
                View stay = view == View.STREAM ? View.BOARD : view;
                go(stay, notes.get(root).id);
            } else {
                go(View.STREAM, null);
            }
            focusId = notes.get(target).id;
        }

        /** Record the fold the other way. Open means the map value is true. */
        public void toggleFold(String id, boolean collapsedNow) {
            opened.put(id, collapsedNow);
        }

        /** The directory that holds this message's file. A thread's files include its replies. */
        public void showFile(String id) {
            Index index = new Index(notes);
            int root = index.rootOf(index.require(id));
            String nextThread = index.isCard(root) ? notes.get(root).id : null;
            if (!files) {
                back.add(new Mark(view, threadId, false));
            }
            threadId = nextThread;
            files = true;
            folder = null;
            focusId = null;
        }

        public Empty emptyKind() {
            if (files) {
                return fileRows().isEmpty() ? Empty.SHELF : Empty.NONE;
            }
            if (threadId != null) {
                return Empty.NONE;
            }
            List<Note> shown = visible();
            if (shown.isEmpty()) {
                return query.trim().isEmpty() ? Empty.SHELF : Empty.SEARCH;
            }
            if (view == View.THREADS && query.trim().isEmpty()) {
                Index index = new Index(shown);
                for (int root : index.roots) {
                    if (index.isCard(root)) {
                        return Empty.NONE;
                    }
                }
                return Empty.THREADS;
            }
            return Empty.NONE;
        }

        public Cue cue() {
            if (files) {
                return Cue.HIDDEN;
            }
            if (threadId != null) {
                return Cue.THREAD;
            }
            if (view == View.STREAM) {
                return Cue.STREAM;
            }
            return Cue.SHELF;
        }

        public List<Row> rows() {
            if (files) {
                return fileRows();
            }
            Index index = new Index(visible());
            if (threadId != null) {
                int root = index.require(threadId);
                List<Row> rows = new ArrayList<>();
                walkPosts(index, root, 0, true, rows);
                return rows;
            }
            return switch (view) {
                case THREADS -> threadRows(index);
                case BOARD -> postRows(index, false);
                case STREAM -> streamRows(index);
            };
        }

        public boolean back() {
            Mark previous = back.pollLast();
            if (previous == null) {
                return false;
            }
            view = previous.view;
            threadId = previous.threadId;
            files = previous.files;
            folder = null;
            focusId = null;
            return true;
        }

        private void go(View nextView, String nextThread) {
            if (!files && nextView == view && same(nextThread, threadId)) {
                return;
            }
            back.add(new Mark(view, threadId, files));
            view = nextView;
            threadId = nextThread;
            files = false;
            folder = null;
            focusId = null;
        }

        private boolean stillThread(Index index, String id) {
            Integer i = index.byId.get(id);
            return i != null && index.rootOf(i) == i && index.isCard(i);
        }

        private List<Note> visible() {
            if (threadId != null || query.trim().isEmpty()) {
                return notes;
            }
            return matching(notes, query);
        }

        private void revealAncestors(Index index, int i) {
            int cursor = i;
            int guard = 0;
            while (index.parent[cursor] >= 0 && guard <= index.parent.length) {
                opened.put(notes.get(index.parent[cursor]).id, true);
                cursor = index.parent[cursor];
                guard++;
            }
        }

        private int bestMatch(Index index, int root, String[] words) {
            int found = bestIn(index, root, words);
            return found < 0 ? root : found;
        }

        private int bestIn(Index index, int i, String[] words) {
            int best = hits(index.notes.get(i), words) ? i : -1;
            for (int child : index.kids.get(i)) {
                int found = bestIn(index, child, words);
                if (found < 0) {
                    continue;
                }
                if (best < 0 || index.notes.get(found).at >= index.notes.get(best).at) {
                    best = found;
                }
            }
            return best;
        }

        private List<Row> threadRows(Index index) {
            List<Row> rows = new ArrayList<>();
            boolean searching = !query.trim().isEmpty();
            for (int root : index.rootsSorted(shelfSort)) {
                int replies = index.descendants(root);
                if (replies == 0 && !searching) {
                    continue;
                }
                Note note = index.notes.get(root);
                rows.add(
                        postRow(
                                Row.Kind.THREAD,
                                index,
                                root,
                                0,
                                index.title(root),
                                formatWhen(index.activity(root)),
                                null,
                                replies,
                                index.filesUnder(root),
                                false,
                                note.id.equals(focusId)));
            }
            return rows;
        }

        private List<Row> postRows(Index index, boolean force) {
            List<Row> rows = new ArrayList<>();
            for (int root : index.rootsSorted(shelfSort)) {
                walkPosts(index, root, 0, force, rows);
            }
            return rows;
        }

        private void walkPosts(Index index, int i, int depth, boolean force, List<Row> out) {
            Note note = index.notes.get(i);
            boolean kids = !index.kids.get(i).isEmpty();
            boolean collapsed = collapsed(note.id, depth, force, kids);
            int replies = index.descendants(i);
            out.add(
                    postRow(
                            Row.Kind.POST,
                            index,
                            i,
                            depth,
                            note.body == null ? "" : note.body,
                            formatWhen(note.at),
                            null,
                            replies,
                            note.files.size(),
                            collapsed,
                            note.id.equals(focusId)));
            if (!collapsed) {
                for (int child : index.kids.get(i)) {
                    walkPosts(index, child, depth + 1, force, out);
                }
            }
        }

        private boolean collapsed(String id, int depth, boolean force, boolean kids) {
            if (force || !kids) {
                return false;
            }
            if (opened.containsKey(id)) {
                return !Boolean.TRUE.equals(opened.get(id));
            }
            return depth >= 2;
        }

        private Row postRow(
                Row.Kind kind,
                Index index,
                int i,
                int depth,
                String text,
                String when,
                String quote,
                int replies,
                int fileCount,
                boolean collapsed,
                boolean focused) {
            Note note = index.notes.get(i);
            String chip = null;
            String chipKind = null;
            String chipDetail = null;
            if (!note.files.isEmpty()) {
                Attached file = note.files.get(0);
                chip = file.name;
                chipKind = file.kind;
                chipDetail = file.bytes > 0 ? formatBytes(file.bytes) : "";
                if (note.files.size() > 1) {
                    String more = "+" + (note.files.size() - 1);
                    chipDetail = chipDetail.isEmpty() ? more : chipDetail + " · " + more;
                }
            }
            return new Row(
                    kind,
                    note.id,
                    depth,
                    text,
                    note.author,
                    when,
                    quote,
                    replies,
                    fileCount,
                    collapsed,
                    focused,
                    note.fromSelf,
                    false,
                    chip,
                    chipKind,
                    chipDetail);
        }

        private List<Row> streamRows(Index index) {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < index.notes.size(); i++) {
                order.add(i);
            }
            order.sort(index::byTime);
            long now = System.currentTimeMillis() / 1000L;
            List<Row> rows = new ArrayList<>();
            String lastDay = null;
            for (int i : order) {
                Note note = index.notes.get(i);
                String day = dayLabel(note.at, now);
                if (!day.equals(lastDay)) {
                    rows.add(Row.day("day-" + note.id, day));
                    lastDay = day;
                }
                String quote = null;
                if (index.parent[i] >= 0) {
                    Note parent = index.notes.get(index.parent[i]);
                    quote = parent.author + ": " + firstLine(parent.body);
                }
                rows.add(
                        new Row(
                                Row.Kind.STREAM,
                                note.id,
                                0,
                                note.body == null ? "" : note.body,
                                note.author,
                                formatWhen(note.at),
                                quote,
                                0,
                                note.files.size(),
                                false,
                                note.id.equals(focusId),
                                note.fromSelf,
                                false,
                                null,
                                null,
                                null));
            }
            return rows;
        }

        private List<Row> fileRows() {
            Index index = new Index(notes);
            List<FileRef> refs = index.filesIn(threadId);
            if (group != Group.FLAT && folder == null) {
                List<String> labels = new ArrayList<>();
                for (FileRef ref : refs) {
                    String key = index.groupKey(ref, group);
                    if (!labels.contains(key)) {
                        labels.add(key);
                    }
                }
                labels.sort(Desk::compareNatural);
                List<Row> rows = new ArrayList<>();
                for (String label : labels) {
                    rows.add(Row.file(label, label, true));
                }
                return rows;
            }
            if (folder != null) {
                refs.removeIf(ref -> !index.groupKey(ref, group).equals(folder));
            }
            refs.sort((a, b) -> compareFiles(a, b, sort));
            List<Row> rows = new ArrayList<>();
            for (FileRef ref : refs) {
                rows.add(Row.file(notes.get(ref.note).id, ref.file.name, false));
            }
            return rows;
        }

        private static boolean same(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }

        private static final class Mark {
            final View view;
            final String threadId;
            final boolean files;

            Mark(View view, String threadId, boolean files) {
                this.view = view;
                this.threadId = threadId;
                this.files = files;
            }
        }
    }

    private static void walk(
            Index index,
            int i,
            int depth,
            String focusId,
            boolean force,
            Map<String, Boolean> opened,
            List<Line> out) {
        Note note = index.notes.get(i);
        boolean kids = !index.kids.get(i).isEmpty();
        boolean collapsed;
        if (force || !kids) {
            collapsed = false;
        } else if (opened != null && opened.containsKey(note.id)) {
            collapsed = !Boolean.TRUE.equals(opened.get(note.id));
        } else {
            collapsed = depth >= 2;
        }
        String text =
                depth == 0 ? index.title(i) : note.author + " — " + firstLine(note.body);
        int folded = collapsed ? index.descendants(i) : 0;
        out.add(new Line(note.id, depth, text, folded, note.id.equals(focusId)));
        if (!collapsed) {
            for (int child : index.kids.get(i)) {
                walk(index, child, depth + 1, focusId, force, opened, out);
            }
        }
    }

    /** A hit, the posts it answers, and the replies under it. */
    static List<Note> matching(List<Note> notes, String query) {
        String trimmed = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || notes.isEmpty()) {
            return notes;
        }
        String[] words = trimmed.split("\\s+");
        Map<String, Note> byId = new HashMap<>();
        Map<String, List<String>> kids = new HashMap<>();
        for (Note note : notes) {
            byId.put(note.id, note);
            kids.put(note.id, new ArrayList<>());
        }
        for (Note note : notes) {
            if (note.parentId != null && byId.containsKey(note.parentId) && !note.parentId.equals(note.id)) {
                kids.get(note.parentId).add(note.id);
            }
        }
        Set<String> keep = new HashSet<>();
        for (Note note : notes) {
            if (!hits(note, words)) {
                continue;
            }
            String cursor = note.id;
            Set<String> guard = new HashSet<>();
            while (cursor != null && guard.add(cursor)) {
                keep.add(cursor);
                Note current = byId.get(cursor);
                String parent = current == null ? null : current.parentId;
                cursor = parent != null && byId.containsKey(parent) ? parent : null;
            }
            keepDescendants(note.id, kids, keep);
        }
        List<Note> out = new ArrayList<>();
        for (Note note : notes) {
            if (keep.contains(note.id)) {
                out.add(note);
            }
        }
        return out;
    }

    private static void keepDescendants(String id, Map<String, List<String>> kids, Set<String> keep) {
        for (String child : kids.getOrDefault(id, List.of())) {
            if (keep.add(child)) {
                keepDescendants(child, kids, keep);
            }
        }
    }

    static boolean hits(Note note, String[] words) {
        StringBuilder text = new StringBuilder();
        if (note.body != null) {
            text.append(note.body);
        }
        text.append(' ');
        if (note.author != null) {
            text.append(note.author);
        }
        for (Attached file : note.files) {
            text.append(' ').append(file.name);
        }
        String haystack = text.toString().toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (!haystack.contains(word)) {
                return false;
            }
        }
        return true;
    }

    static String firstLine(String body) {
        if (body == null) {
            return "";
        }
        for (String line : body.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int takeDigits(String text, int start) {
        int end = start;
        while (end < text.length() && isDigit(text.charAt(end))) {
            end++;
        }
        return end;
    }

    private static int compareDigits(String a, String b) {
        String asig = significant(a);
        String bsig = significant(b);
        if (asig.length() != bsig.length()) {
            return Integer.compare(asig.length(), bsig.length());
        }
        return asig.compareTo(bsig);
    }

    private static String significant(String digits) {
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }
        String skipped = digits.substring(i);
        return skipped.isEmpty() ? "0" : skipped;
    }

    private static final class FileRef {
        final int note;
        final Attached file;

        FileRef(int note, Attached file) {
            this.note = note;
            this.file = file;
        }
    }

    private static final class Index {
        final List<Note> notes;
        final HashMap<String, Integer> byId = new HashMap<>();
        final int[] parent;
        final List<List<Integer>> kids = new ArrayList<>();
        final List<Integer> roots = new ArrayList<>();

        Index(List<Note> notes) {
            this.notes = notes;
            for (int i = 0; i < notes.size(); i++) {
                byId.put(notes.get(i).id, i);
                kids.add(new ArrayList<>());
            }
            parent = new int[notes.size()];
            for (int i = 0; i < notes.size(); i++) {
                parent[i] = -1;
            }
            for (int i = 0; i < notes.size(); i++) {
                String parentId = notes.get(i).parentId;
                Integer linked = parentId == null ? null : byId.get(parentId);
                if (linked != null && linked != i && !cycle(linked, i)) {
                    parent[i] = linked;
                    kids.get(linked).add(i);
                } else {
                    roots.add(i);
                }
            }
            for (List<Integer> row : kids) {
                row.sort(this::byTime);
            }
            roots.sort(this::byTime);
        }

        int require(String id) {
            Integer i = byId.get(id);
            if (i == null) {
                throw new IllegalArgumentException("No message with that id.");
            }
            return i;
        }

        int rootOf(int i) {
            int guard = 0;
            while (parent[i] >= 0 && guard <= parent.length) {
                i = parent[i];
                guard++;
            }
            return i;
        }

        /** A root is a thread once something answers it. */
        boolean isCard(int i) {
            return subtreeCount(i) > 1;
        }

        List<Integer> cards() {
            List<Integer> cards = new ArrayList<>();
            for (int root : rootsSorted(ShelfSort.ACTIVITY)) {
                if (isCard(root)) {
                    cards.add(root);
                }
            }
            return cards;
        }

        List<Integer> rootsSorted(ShelfSort sort) {
            List<Integer> ordered = new ArrayList<>(roots);
            ordered.sort((a, b) -> compareShelf(a, b, sort == null ? ShelfSort.ACTIVITY : sort));
            return ordered;
        }

        int descendants(int i) {
            return subtreeCount(i) - 1;
        }

        int filesUnder(int i) {
            int count = notes.get(i).files.size();
            for (int child : kids.get(i)) {
                count += filesUnder(child);
            }
            return count;
        }

        private int compareShelf(int a, int b, ShelfSort sort) {
            if (sort == ShelfSort.REPLIES) {
                int byReplies = Integer.compare(descendants(b), descendants(a));
                if (byReplies != 0) {
                    return byReplies;
                }
            } else if (sort == ShelfSort.NEWEST) {
                int byAt = Long.compare(notes.get(b).at, notes.get(a).at);
                if (byAt != 0) {
                    return byAt;
                }
                return notes.get(a).id.compareTo(notes.get(b).id);
            } else if (sort == ShelfSort.OLDEST) {
                int byAt = Long.compare(notes.get(a).at, notes.get(b).at);
                if (byAt != 0) {
                    return byAt;
                }
                return notes.get(a).id.compareTo(notes.get(b).id);
            }
            int byActivity = Long.compare(activity(b), activity(a));
            if (byActivity != 0) {
                return byActivity;
            }
            return notes.get(a).id.compareTo(notes.get(b).id);
        }

        int byTime(int a, int b) {
            int byAt = Long.compare(notes.get(a).at, notes.get(b).at);
            if (byAt != 0) {
                return byAt;
            }
            return notes.get(a).id.compareTo(notes.get(b).id);
        }

        String title(int i) {
            String line = firstLine(notes.get(i).body);
            if (!line.isEmpty()) {
                return line;
            }
            if (!notes.get(i).files.isEmpty()) {
                return notes.get(i).files.get(0).name;
            }
            return notes.get(i).id;
        }

        List<FileRef> filesIn(String threadId) {
            Integer root = null;
            if (threadId != null) {
                root = rootOf(require(threadId));
            }
            List<FileRef> refs = new ArrayList<>();
            for (int i = 0; i < notes.size(); i++) {
                if (root != null && rootOf(i) != root) {
                    continue;
                }
                for (Attached file : notes.get(i).files) {
                    refs.add(new FileRef(i, file));
                }
            }
            return refs;
        }

        String groupKey(FileRef ref, Group group) {
            Attached file = ref.file;
            return switch (group) {
                case FLAT -> "";
                case TYPE -> file.kind;
                case MONTH -> monthKey(file.at);
                case SENDER -> file.sender;
                case EXTENSION -> extension(file.name);
                case THREAD -> title(rootOf(ref.note));
            };
        }

        private int subtreeCount(int i) {
            int count = 1;
            for (int child : kids.get(i)) {
                count += subtreeCount(child);
            }
            return count;
        }

        private long activity(int i) {
            long at = notes.get(i).at;
            for (int child : kids.get(i)) {
                at = Math.max(at, activity(child));
            }
            return at;
        }

        private boolean cycle(int cursor, int child) {
            int guard = 0;
            while (guard <= parent.length) {
                if (cursor == child) {
                    return true;
                }
                if (parent[cursor] < 0) {
                    return false;
                }
                cursor = parent[cursor];
                guard++;
            }
            return true;
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "none";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
