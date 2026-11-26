package de.monocles.chat.desk;

import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xmpp.Jid;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The open conversation, as the notes {@link Desk} reads. */
public final class DeskNotes {

    private DeskNotes() {}

    public static List<Desk.Note> from(List<Message> messages) {
        List<Desk.Note> notes = new ArrayList<>();
        if (messages == null) {
            return notes;
        }
        for (Message message : messages) {
            if (message == null || message.getUuid() == null) {
                continue;
            }
            if (message.getType() == Message.TYPE_STATUS) {
                continue;
            }
            if ("LOAD_MORE".equals(message.getBody())) {
                continue;
            }
            String author = author(message);
            boolean fromSelf = message.getStatus() != Message.STATUS_RECEIVED;
            long at = seconds(message.getTimeSent());
            List<Desk.Attached> files = files(message, author, at);
            Message reply = message.getInReplyTo();
            String parentId = reply == null ? null : reply.getUuid();
            notes.add(
                    new Desk.Note(
                            message.getUuid(),
                            parentId,
                            author,
                            text(message),
                            at,
                            fromSelf,
                            files));
        }
        return notes;
    }

    private static List<Desk.Attached> files(Message message, String author, long at) {
        List<Desk.Attached> files = new ArrayList<>();
        addFile(files, message, author, at);
        for (Message extra : message.getAttachments()) {
            addFile(files, extra, author, at);
        }
        return files;
    }

    private static void addFile(List<Desk.Attached> files, Message message, String author, long at) {
        String name = fileName(message.getRelativeFilePath());
        if (name == null) {
            return;
        }
        Message.FileParams params = message.getFileParams();
        long bytes = params == null ? 0 : params.getSize();
        files.add(new Desk.Attached(name, kind(name), author, bytes, at));
    }

    /** The words that were written. A reply's quoted parent is a fallback, not part of the note. */
    private static String text(Message message) {
        String body = message.getBody(true);
        if (body == null) {
            return "";
        }
        return body.trim();
    }

    private static String author(Message message) {
        if (message.getStatus() != Message.STATUS_RECEIVED) {
            return "You";
        }
        Jid jid = message.getTrueCounterpart();
        if (jid == null) {
            jid = message.getCounterpart();
        }
        if (jid == null) {
            return "";
        }
        String local = jid.getLocal();
        if (local == null || local.isEmpty()) {
            return jid.toString();
        }
        return local;
    }

    static long seconds(long timeSent) {
        if (timeSent > 10_000_000_000L) {
            return timeSent / 1000L;
        }
        return timeSent;
    }

    private static String fileName(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isEmpty() ? null : name;
    }

    private static String kind(String name) {
        int dot = name.lastIndexOf('.');
        String ext =
                dot <= 0 || dot == name.length() - 1
                        ? ""
                        : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "jpg", "jpeg", "png", "gif", "webp", "heic" -> "image";
            case "txt", "md", "csv" -> "text";
            case "mp4", "webm", "mkv", "mov" -> "video";
            case "mp3", "m4a", "ogg", "opus" -> "audio";
            case "" -> "document";
            default -> "document";
        };
    }
}
