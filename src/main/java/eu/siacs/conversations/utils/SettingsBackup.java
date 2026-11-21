package eu.siacs.conversations.utils;

import com.google.common.collect.ImmutableMap;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The settings file of Settings → Backup → Export/Import settings.
 *
 * <p>SECURITY: the file is unencrypted and an imported one may be crafted, so this is a strict
 * allowlist of plain appearance/behaviour settings with a type (and for lists the allowed values)
 * each. Never add security settings (preferences_security.xml), privacy or network toggles,
 * device-bound values (backup location, ringtones, push) or internal keys (database key format,
 * startup password, app lock PIN, ids) — a settings file must not be able to read or weaken them.
 * The old settings.dat (Java serialization of all preferences) is deliberately not readable any
 * more.
 */
public final class SettingsBackup {

    public static final String FORMAT = "monocles-chat-settings";
    public static final int VERSION = 1;
    public static final int MAX_FILE_SIZE = 64 * 1024;
    public static final int MAX_TEXT_LENGTH = 64; // AppSettings.CUSTOM_RESOURCE_NAME_MAX_LENGTH

    public enum Type {
        BOOLEAN,
        /** A ColorPreference, stored as int. */
        COLOR,
        /** A ListPreference value, stored as String; must be one of its entry values. */
        CHOICE,
        /** Free text, at most {@link #MAX_TEXT_LENGTH} characters, no control characters. */
        TEXT
    }

    public static final Map<String, Type> ALLOWED = buildAllowlist();

    private static Map<String, Type> buildAllowlist() {
        final ImmutableMap.Builder<String, Type> builder = ImmutableMap.builder();
        for (final String key :
                new String[] {
                    // attachments
                    "auto_send_recording",
                    "auto_accept_unmetered",
                    "nomedia",
                    "delete_unused_files",
                    "internal_meda_viewer",
                    "skip_image_editor_screen",
                    // connection (appearance only)
                    "show_connection_options",
                    "low_quality_opus",
                    // interface
                    "dynamic_colors",
                    "custom_theme_automatic",
                    "custom_theme_dark",
                    "custom_theme_color_match",
                    "unicolored_chatbg",
                    "show_contact_status",
                    "always_full_timestamps",
                    "show_own_accounts",
                    "pinned_status_message",
                    "colored_muc_names",
                    "show_nav_bar",
                    "show_nav_drawer",
                    "large_font",
                    "show_dynamic_tags",
                    "plain_text_links",
                    "set_text_collapsable",
                    "custom_tab",
                    "swipe_to_archive",
                    "show_thread_feature",
                    "showtextformatting",
                    "scroll_to_bottom",
                    "start_searching",
                    "follow_thread_in_channel",
                    "jump_to_commands_tab",
                    "message_autocomplete",
                    "compose_rich_text",
                    "show_muc_pm",
                    "display_enter_key",
                    "enter_is_send",
                    "use_green_background",
                    "align_start",
                    "show_avatars",
                    // notifications
                    "notification_headsup",
                    "vibrate_on_notification",
                    "led",
                    "dialler_integration_incoming",
                    "call_integration",
                    "enable_foreground_service",
                    // privacy screen, behaviour only
                    "allow_message_correction",
                    "manually_change_presence",
                    "away_when_screen_off",
                    "dnd_on_silent_mode",
                    "treat_vibrate_as_silent",
                    "share_separate_uploads"
                }) {
            builder.put(key, Type.BOOLEAN);
        }
        for (final String key :
                new String[] {
                    "custom_theme_primary",
                    "custom_theme_primary_dark",
                    "custom_theme_accent",
                    "custom_theme_background_primary",
                    "custom_dark_theme_primary",
                    "custom_dark_theme_primary_dark",
                    "custom_dark_theme_accent",
                    "custom_dark_theme_background_primary"
                }) {
            builder.put(key, Type.COLOR);
        }
        for (final String key :
                new String[] {
                    "picture_compression",
                    "video_compression",
                    "auto_accept_file_size",
                    "cache_deletion_time",
                    "voice_message_codec",
                    "theme",
                    "avatar_shape",
                    "quick_action",
                    "grace_period_length",
                    "update_track_longer_than",
                    "recurring_backup"
                }) {
            builder.put(key, Type.CHOICE);
        }
        builder.put("custom_resource_name", Type.TEXT);
        return builder.build();
    }

    /** The file is not a settings file of this format (e.g. an old settings.dat). */
    public static class UnsupportedSettingsFileException extends IOException {
        public UnsupportedSettingsFileException(final String message) {
            super(message);
        }
    }

    private SettingsBackup() {}

    /** Writes the allowlisted entries of {@code preferences} whose value has the expected type. */
    public static void write(final Map<String, ?> preferences, final Writer out)
            throws IOException {
        final JsonWriter writer = new JsonWriter(out);
        writer.setIndent("  ");
        writer.beginObject();
        writer.name("format").value(FORMAT);
        writer.name("version").value(VERSION);
        writer.name("settings");
        writer.beginObject();
        for (final Map.Entry<String, Type> allowed : ALLOWED.entrySet()) {
            final Object value = preferences.get(allowed.getKey());
            switch (allowed.getValue()) {
                case BOOLEAN -> {
                    if (value instanceof Boolean b) {
                        writer.name(allowed.getKey()).value(b);
                    }
                }
                case COLOR -> {
                    if (value instanceof Integer i) {
                        writer.name(allowed.getKey()).value(i);
                    }
                }
                case CHOICE, TEXT -> {
                    if (value instanceof String s) {
                        writer.name(allowed.getKey()).value(s);
                    }
                }
            }
        }
        writer.endObject();
        writer.endObject();
        writer.flush();
    }

    /**
     * Reads a settings file. Only allowlisted keys with a valid value are returned (Boolean,
     * Integer or String, matching {@link #ALLOWED}); everything else is skipped. The caller must
     * bound the input to {@link #MAX_FILE_SIZE}.
     *
     * @param allowedChoices the entry values of a {@link Type#CHOICE} key
     */
    public static Map<String, Object> parse(
            final Reader in, final Function<String, Set<String>> allowedChoices)
            throws IOException {
        // a new JsonReader is not lenient: comments, unquoted names etc. are rejected
        final JsonReader reader = new JsonReader(in);
        String format = null;
        Integer version = null;
        Map<String, Object> settings = null;
        try {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new UnsupportedSettingsFileException("not a JSON object");
            }
            reader.beginObject();
            while (reader.hasNext()) {
                final String name = reader.nextName();
                switch (name) {
                    case "format" -> format = nextStringOrNull(reader);
                    case "version" -> version = nextIntOrNull(reader);
                    case "settings" -> {
                        if (settings != null) {
                            throw new UnsupportedSettingsFileException("duplicate settings");
                        }
                        settings = readSettings(reader, allowedChoices);
                    }
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new UnsupportedSettingsFileException("trailing data");
            }
        } catch (final IllegalStateException | NumberFormatException e) {
            // JsonReader signals structural problems this way, e.g. a binary settings.dat
            throw new UnsupportedSettingsFileException("malformed: " + e.getMessage());
        } catch (final com.google.gson.stream.MalformedJsonException
                | java.io.EOFException e) {
            throw new UnsupportedSettingsFileException("malformed: " + e.getMessage());
        }
        if (!FORMAT.equals(format)) {
            throw new UnsupportedSettingsFileException("wrong format " + format);
        }
        if (version == null || version != VERSION) {
            throw new UnsupportedSettingsFileException("unsupported version " + version);
        }
        if (settings == null) {
            throw new UnsupportedSettingsFileException("no settings");
        }
        return Collections.unmodifiableMap(settings);
    }

    private static Map<String, Object> readSettings(
            final JsonReader reader, final Function<String, Set<String>> allowedChoices)
            throws IOException {
        final Map<String, Object> settings = new LinkedHashMap<>();
        reader.beginObject();
        while (reader.hasNext()) {
            final String key = reader.nextName();
            final Type type = ALLOWED.get(key);
            final JsonToken token = reader.peek();
            Object value = null;
            if (type == Type.BOOLEAN && token == JsonToken.BOOLEAN) {
                value = reader.nextBoolean();
            } else if (type == Type.COLOR && token == JsonToken.NUMBER) {
                final long number = reader.nextLong();
                if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
                    value = (int) number;
                }
            } else if (type == Type.CHOICE && token == JsonToken.STRING) {
                final String choice = reader.nextString();
                final Set<String> choices = allowedChoices.apply(key);
                if (choices != null && choices.contains(choice)) {
                    value = choice;
                }
            } else if (type == Type.TEXT && token == JsonToken.STRING) {
                final String text = reader.nextString();
                if (isValidText(text)) {
                    value = text;
                }
            } else {
                reader.skipValue();
            }
            if (value != null) {
                settings.put(key, value);
            }
        }
        reader.endObject();
        return settings;
    }

    static boolean isValidText(final String text) {
        if (text.length() > MAX_TEXT_LENGTH) {
            return false;
        }
        for (int i = 0; i < text.length(); ++i) {
            if (Character.isISOControl(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String nextStringOrNull(final JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.STRING) {
            return reader.nextString();
        }
        reader.skipValue();
        return null;
    }

    private static Integer nextIntOrNull(final JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NUMBER) {
            return reader.nextInt();
        }
        reader.skipValue();
        return null;
    }
}
