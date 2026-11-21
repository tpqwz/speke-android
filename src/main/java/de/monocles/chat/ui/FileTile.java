package de.monocles.chat.ui;

import androidx.annotation.Nullable;
import com.google.common.base.Strings;
import eu.siacs.conversations.utils.MimeUtils;
import java.util.Locale;
import java.util.Set;

/**
 * What the paper-sheet tile of a shared file shows when there is no preview: its extension, on a
 * colour that tells the kind of file. Kept free of Android classes so it can be tested on the JVM.
 */
public final class FileTile {

    /** Kinds of file the tile is coloured by. */
    public enum Family {
        /** Documents and text: PDF, office and OpenDocument files, plain text. */
        DOCUMENT,
        /** Containers: archives and WebXDC apps. */
        ARCHIVE,
        /** Everything else: audio, video, images, unknown types. */
        OTHER
    }

    /** Longest extension shown in the tile; anything longer gets the generic type icon. */
    static final int MAX_LABEL_LENGTH = 4;

    private static final Set<String> DOCUMENT_EXTENSIONS =
            Set.of(
                    "pdf", "doc", "docx", "odt", "ods", "odp", "xls", "xlsx", "ppt", "pptx", "rtf",
                    "txt", "md", "csv", "epub");
    private static final Set<String> ARCHIVE_EXTENSIONS =
            Set.of("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "xdc", "apk");

    private FileTile() {}

    /**
     * The extension to print on the tile, upper case: from the file name, else from the media type.
     * Null when there is none, or when it is longer than {@link #MAX_LABEL_LENGTH} or not plain
     * letters and digits (the tile then shows the type icon).
     */
    @Nullable
    public static String extensionLabel(
            @Nullable final String fileName, @Nullable final String mime) {
        String extension = extensionOf(fileName);
        if (extension == null && !Strings.isNullOrEmpty(mime)) {
            extension = MimeUtils.guessExtensionFromMimeType(mime);
        }
        if (Strings.isNullOrEmpty(extension)
                || extension.length() > MAX_LABEL_LENGTH
                || !extension.chars().allMatch(Character::isLetterOrDigit)) {
            return null;
        }
        return extension.toUpperCase(Locale.ROOT);
    }

    /** The kind of file, by media type first and by extension where the type is generic. */
    public static Family family(@Nullable final String mime, @Nullable final String fileName) {
        final String type =
                mime == null ? "" : mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (type.equals("application/pdf")
                || type.startsWith("text/")
                || type.equals("application/msword")
                || type.equals("application/rtf")
                || type.equals("application/epub+zip")
                || type.startsWith("application/vnd.oasis.opendocument")
                || type.startsWith("application/vnd.openxmlformats-officedocument")
                || type.startsWith("application/vnd.ms-")) {
            return Family.DOCUMENT;
        }
        if (type.equals("application/zip")
                || type.equals("application/webxdc+zip")
                || type.equals("application/x-7z-compressed")
                || type.equals("application/vnd.rar")
                || type.equals("application/x-rar-compressed")
                || type.equals("application/x-tar")
                || type.equals("application/gzip")
                || type.equals("application/vnd.android.package-archive")) {
            return Family.ARCHIVE;
        }
        if (!type.isEmpty() && !type.equals("application/octet-stream")) {
            return Family.OTHER;
        }
        final String extension = extensionOf(fileName);
        if (extension != null && DOCUMENT_EXTENSIONS.contains(extension)) {
            return Family.DOCUMENT;
        }
        if (extension != null && ARCHIVE_EXTENSIONS.contains(extension)) {
            return Family.ARCHIVE;
        }
        return Family.OTHER;
    }

    @Nullable
    private static String extensionOf(@Nullable final String fileName) {
        if (Strings.isNullOrEmpty(fileName)) {
            return null;
        }
        final int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
