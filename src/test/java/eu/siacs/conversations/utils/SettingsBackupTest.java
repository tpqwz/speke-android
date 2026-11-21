package eu.siacs.conversations.utils;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/** The settings file is untrusted: only allowlisted, valid settings may get in or out. */
public class SettingsBackupTest {

    private static Set<String> choices(final String key) {
        return switch (key) {
            case "theme" -> ImmutableSet.of("automatic", "light", "dark");
            case "recurring_backup" -> ImmutableSet.of("0", "86400");
            default -> null;
        };
    }

    private static Map<String, Object> parse(final String json) throws IOException {
        return SettingsBackup.parse(new StringReader(json), SettingsBackupTest::choices);
    }

    private static String file(final String settings) {
        return "{\"format\":\"monocles-chat-settings\",\"version\":1,\"settings\":{"
                + settings
                + "}}";
    }

    @Test
    public void roundTripKeepsAllowedSettings() throws IOException {
        final Map<String, Object> preferences = new HashMap<>();
        preferences.put("large_font", true);
        preferences.put("custom_theme_primary", 0xff336699);
        preferences.put("theme", "dark");
        preferences.put("recurring_backup", "86400");
        preferences.put("custom_resource_name", "phone");
        final StringWriter out = new StringWriter();
        SettingsBackup.write(preferences, out);
        Assert.assertEquals(preferences, parse(out.toString()));
    }

    @Test
    public void internalSecurityAndDeviceSettingsAreNeverWritten() throws IOException {
        final Map<String, Object> preferences = new HashMap<>();
        for (final String key :
                new String[] {
                    "db_kdf_version",
                    "app_lock_pin",
                    "backup_location",
                    "notification_ringtone",
                    "up_push_server",
                    "dns_server_dot"
                }) {
            preferences.put(key, "secret");
        }
        for (final String key :
                new String[] {
                    "require_password_on_startup",
                    "enforce_dane",
                    "require_tls_v1_3",
                    "btbv",
                    "allow_unencrypted",
                    "export_plain_text_logs",
                    "send_crash_reports",
                    "use_tor"
                }) {
            preferences.put(key, true);
        }
        final StringWriter out = new StringWriter();
        SettingsBackup.write(preferences, out);
        for (final String key : preferences.keySet()) {
            Assert.assertFalse(key, out.toString().contains("\"" + key + "\""));
        }
    }

    @Test
    public void internalSecurityAndDeviceSettingsAreNeverImported() throws IOException {
        final Map<String, Object> imported =
                parse(
                        file(
                                "\"db_kdf_version\":\"auto\","
                                        + "\"require_password_on_startup\":false,"
                                        + "\"app_lock_pin\":\"1234\","
                                        + "\"enforce_dane\":false,"
                                        + "\"trust_system_ca_store\":true,"
                                        + "\"backup_location\":\"content://evil\","
                                        + "\"export_plain_text_logs\":true,"
                                        + "\"large_font\":true"));
        Assert.assertEquals(ImmutableMap.of("large_font", true), imported);
    }

    @Test
    public void wrongTypesAndValuesAreSkipped() throws IOException {
        final Map<String, Object> imported =
                parse(
                        file(
                                "\"large_font\":\"true\","
                                        + "\"custom_theme_primary\":\"red\","
                                        + "\"custom_theme_accent\":99999999999,"
                                        + "\"theme\":\"evil\","
                                        + "\"recurring_backup\":\"1\","
                                        + "\"custom_resource_name\":\"0123456789012345678901234567890123456789012345678901234567890123456789\","
                                        + "\"show_avatars\":{\"nested\":true}"));
        Assert.assertTrue(imported.isEmpty());
        Assert.assertTrue(
                parse(file("\"custom_resource_name\":\"a\\nb\"")).isEmpty());
    }

    @Test(expected = SettingsBackup.UnsupportedSettingsFileException.class)
    public void wrongFormatIsRejected() throws IOException {
        parse("{\"format\":\"something-else\",\"version\":1,\"settings\":{}}");
    }

    @Test(expected = SettingsBackup.UnsupportedSettingsFileException.class)
    public void missingFormatIsRejected() throws IOException {
        parse("{\"settings\":{\"large_font\":true}}");
    }

    @Test(expected = SettingsBackup.UnsupportedSettingsFileException.class)
    public void otherVersionIsRejected() throws IOException {
        parse("{\"format\":\"monocles-chat-settings\",\"version\":2,\"settings\":{}}");
    }

    @Test(expected = SettingsBackup.UnsupportedSettingsFileException.class)
    public void oldJavaSerializedSettingsDatIsRejected() throws IOException {
        // stream header of java.io.ObjectOutputStream followed by a HashMap class descriptor
        final byte[] dat = {
            (byte) 0xac, (byte) 0xed, 0x00, 0x05, 0x73, 0x72, 0x00, 0x11, 'j', 'a', 'v', 'a', '.',
            'u', 't', 'i', 'l', '.', 'H', 'a', 's', 'h', 'M', 'a', 'p'
        };
        SettingsBackup.parse(
                new InputStreamReader(new ByteArrayInputStream(dat), StandardCharsets.UTF_8),
                SettingsBackupTest::choices);
    }

    @Test(expected = SettingsBackup.UnsupportedSettingsFileException.class)
    public void malformedJsonIsRejected() throws IOException {
        parse("{\"format\":\"monocles-chat-settings\",\"version\":1,\"settings\":{\"large_font\":");
    }

    @Test(expected = SettingsBackup.UnsupportedSettingsFileException.class)
    public void trailingDataIsRejected() throws IOException {
        parse(file("") + "{}");
    }
}
