package de.monocles.chat.desk;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * One room, read three ways, and the same messages as a directory.
 *
 * <p>A post is a thread once it has a reply. Your own new post stays on the thread list before
 * anyone replies. A lone message from someone else is not a thread. Threads, Board, and Stream are
 * the same notes. Changing the shelf leaves an open thread. A thread's directory contains the
 * files on that post and on its replies.
 *
 * <p>The conversation screen still draws its own list. This is the reading that list should use.
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
        for (int card : index.cards()) {
            walk(index, card, 0, focusId, lines);
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
        walk(index, root, 0, focusId, lines);
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
        Index index = new Index(notes);
        List<FileRef> refs = index.filesIn(threadId);
        if (group == Group.FLAT || folder != null) {
            if (folder != null) {
                refs.removeIf(ref -> !index.groupKey(ref, group).equals(folder));
                if (refs.isEmpty()) {
                    throw new IllegalArgumentException("That folder is not in this arrangement.");
                }
            }
            refs.sort(Comparator.comparing((FileRef ref) -> ref.file.name, Desk::compareNatural));
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
        private final List<Note> notes;
        private View view = View.THREADS;
        private String threadId;
        private String focusId;
        private final ArrayDeque<Mark> back = new ArrayDeque<>();

        public Session(List<Note> notes) {
            this.notes = notes;
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

        public boolean back() {
            Mark previous = back.pollLast();
            if (previous == null) {
                return false;
            }
            view = previous.view;
            threadId = previous.threadId;
            focusId = null;
            return true;
        }

        /** Earliest hit. A reply opens its thread. A lone message opens the stream. */
        public void search(String query) {
            Index index = new Index(notes);
            int hit = index.firstHit(query);
            if (hit < 0) {
                throw new IllegalArgumentException("No message with that id.");
            }
            int root = index.rootOf(hit);
            focusId = null;
            if (index.isCard(root)) {
                go(view, notes.get(root).id);
            } else {
                go(View.STREAM, null);
            }
            focusId = notes.get(hit).id;
        }

        private void go(View nextView, String nextThread) {
            if (nextView == view && same(nextThread, threadId)) {
                return;
            }
            back.add(new Mark(view, threadId));
            view = nextView;
            threadId = nextThread;
            focusId = null;
        }

        private static boolean same(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }

        private static final class Mark {
            final View view;
            final String threadId;

            Mark(View view, String threadId) {
                this.view = view;
                this.threadId = threadId;
            }
        }
    }

    private static void walk(Index index, int i, int depth, String focusId, List<Line> out) {
        Note note = index.notes.get(i);
        String text =
                depth == 0
                        ? index.title(i)
                        : note.author + " — " + firstLine(note.body);
        boolean showKids = depth < 1 || index.isStrictAncestor(i, focusId);
        int folded = !showKids ? index.kids.get(i).size() : 0;
        out.add(new Line(note.id, depth, text, folded, note.id.equals(focusId)));
        if (showKids) {
            for (int child : index.kids.get(i)) {
                walk(index, child, depth + 1, focusId, out);
            }
        }
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

        boolean isCard(int i) {
            return subtreeCount(i) > 1 || notes.get(i).fromSelf;
        }

        List<Integer> cards() {
            List<Integer> cards = new ArrayList<>();
            for (int root : roots) {
                if (isCard(root)) {
                    cards.add(root);
                }
            }
            cards.sort(
                    (a, b) -> {
                        int byActivity = Long.compare(activity(b), activity(a));
                        if (byActivity != 0) {
                            return byActivity;
                        }
                        return notes.get(a).id.compareTo(notes.get(b).id);
                    });
            return cards;
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

        boolean isStrictAncestor(int i, String focusId) {
            if (focusId == null) {
                return false;
            }
            Integer focus = byId.get(focusId);
            if (focus == null) {
                return false;
            }
            int cursor = focus;
            int guard = 0;
            while (parent[cursor] >= 0 && guard <= parent.length) {
                if (parent[cursor] == i) {
                    return true;
                }
                cursor = parent[cursor];
                guard++;
            }
            return false;
        }

        int firstHit(String query) {
            String[] words = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
            if (query.trim().isEmpty()) {
                return -1;
            }
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < notes.size(); i++) {
                order.add(i);
            }
            order.sort(this::byTime);
            for (int i : order) {
                if (matches(i, words)) {
                    return i;
                }
            }
            return -1;
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

        private boolean matches(int i, String[] words) {
            Note note = notes.get(i);
            StringBuilder text = new StringBuilder(note.body.toLowerCase(Locale.ROOT));
            text.append(' ').append(note.author.toLowerCase(Locale.ROOT));
            for (Attached file : note.files) {
                text.append(' ').append(file.name.toLowerCase(Locale.ROOT));
            }
            String haystack = text.toString();
            for (String word : words) {
                if (!haystack.contains(word)) {
                    return false;
                }
            }
            return true;
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
