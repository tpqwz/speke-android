package eu.siacs.conversations.ui.fragment.settings;

import static android.app.Activity.RESULT_OK;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Longs;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.ui.activity.SettingsActivity;
import eu.siacs.conversations.utils.BackupKeys;
import eu.siacs.conversations.utils.SettingsBackup;
import eu.siacs.conversations.worker.ExportBackupWorker;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import me.drakeet.support.toast.ToastCompat;

public class BackupSettingsFragment extends XmppPreferenceFragment {

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US);
    public static final String CREATE_ONE_OFF_BACKUP = "create_one_off_backup";
    private static final String RECURRING_BACKUP = "recurring_backup";
    private static final String EXPORT_PLAIN_TEXT_LOGS = "export_plain_text_logs";
    private static final String BACKUP_RECOVERY_KEY = "backup_recovery_key";
    public static final int REQUEST_EXPORT_SETTINGS = 0xbf8701;
    public static final int REQUEST_IMPORT_SETTINGS = 0xbf8703;

    private final ActivityResultLauncher<String> requestStorageForBackupLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    isGranted -> {
                        if (isGranted) {
                            startOneOffBackup();
                        } else {
                            Toast.makeText(
                                            requireActivity(),
                                            getString(
                                                    R.string.no_storage_permission,
                                                    getString(R.string.app_name)),
                                            Toast.LENGTH_LONG)
                                    .show();
                        }
                    });

    private byte[] keyToShowAfterAuthentication = null;

    private final ActivityResultLauncher<Intent> confirmCredentialLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        final byte[] key = keyToShowAfterAuthentication;
                        keyToShowAfterAuthentication = null;
                        if (key != null && result.getResultCode() == RESULT_OK) {
                            showRecoveryKeyDialog(key, false);
                        }
                    });

    private final ActivityResultLauncher<Uri> pickBackupLocationLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocumentTree(),
                    uri -> {
                        if (uri == null) {
                            Log.d(Config.LOGTAG, "no backup location selected");
                            return;
                        }
                        submitBackupLocationPreference(uri);
                    });

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_backup, rootKey);
        final var createOneOffBackup = findPreference(CREATE_ONE_OFF_BACKUP);
        // final var export = findPreference("export");
        final ListPreference recurringBackup = findPreference(RECURRING_BACKUP);
        final var backupLocation = findPreference(AppSettings.BACKUP_LOCATION);
        if (createOneOffBackup == null || recurringBackup == null || backupLocation == null) {
            throw new IllegalStateException(
                    "The preference resource file is missing some preferences");
        }
        final var appSettings = new AppSettings(requireContext());
        backupLocation.setSummary(
                getString(
                        R.string.pref_create_backup_summary,
                        appSettings.getBackupLocationAsPath()));
        backupLocation.setOnPreferenceClickListener(this::onBackupLocationPreferenceClicked);
        createOneOffBackup.setOnPreferenceClickListener(this::onBackupPreferenceClicked);
        // export.setOnPreferenceClickListener(this::onExportClicked);
        setValues(
                recurringBackup,
                R.array.recurring_backup_values,
                value -> timeframeValueToName(requireContext(), value));

        final Preference recoveryKey = findPreference(BACKUP_RECOVERY_KEY);
        if (recoveryKey != null) {
            updateRecoveryKeySummary(recoveryKey);
            recoveryKey.setOnPreferenceClickListener(this::onRecoveryKeyClicked);
        }

        final SwitchPreferenceCompat exportPlainText = findPreference(EXPORT_PLAIN_TEXT_LOGS);
        if (exportPlainText != null) {
            exportPlainText.setOnPreferenceChangeListener(this::onExportPlainTextChanged);
        }

        final var importSettingsPreference = findPreference("import_settings");
        if (importSettingsPreference != null) {
            importSettingsPreference.setOnPreferenceClickListener(preference -> {
                if (requireSettingsActivity().hasStoragePermission(REQUEST_IMPORT_SETTINGS)) {
                    openSettingsPicker();
                }
                return true;
            });
        }

        final var exportSettingsPreference = findPreference("export_settings");
        if (exportSettingsPreference != null) {
            exportSettingsPreference.setOnPreferenceClickListener(preference -> {
                if (requireSettingsActivity().hasStoragePermission(REQUEST_EXPORT_SETTINGS)) {
                    exportSettings();
                }
                return true;
            });
        }
    }

    /**
     * Turning the plain-text export on writes every chat, OMEMO ones included, unencrypted into
     * the backup location, so it needs an explicit confirmation. Turning it off never does.
     */
    private boolean onExportPlainTextChanged(final Preference preference, final Object newValue) {
        if (!Boolean.TRUE.equals(newValue)) {
            return true;
        }
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.pref_export_plain_text_logs_warning_title)
                .setMessage(R.string.pref_export_plain_text_logs_warning)
                .setPositiveButton(
                        R.string.enable,
                        (dialog, which) -> {
                            // setChecked persists the value without calling this listener again
                            if (preference instanceof SwitchPreferenceCompat switchPreference) {
                                switchPreference.setChecked(true);
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
        return false;
    }

    private DatabaseBackend database() {
        return DatabaseBackend.getInstance(requireContext());
    }

    private void updateRecoveryKeySummary(final Preference preference) {
        preference.setSummary(
                database().getBackupRecoveryKey() != null
                        ? R.string.backup_recovery_key_summary_on
                        : R.string.backup_recovery_key_summary_off);
    }

    private void updateRecoveryKeySummary() {
        final Preference preference = findPreference(BACKUP_RECOVERY_KEY);
        if (preference != null) {
            updateRecoveryKeySummary(preference);
        }
    }

    /**
     * Like Signal's recovery key: a random 256-bit key instead of the account password. It is
     * shown once and has to be confirmed, so nobody ends up with backups they can't open.
     */
    private boolean onRecoveryKeyClicked(final Preference preference) {
        final byte[] current = database().getBackupRecoveryKey();
        if (current == null) {
            new MaterialAlertDialogBuilder(requireActivity())
                    .setTitle(R.string.backup_recovery_key)
                    .setMessage(R.string.backup_recovery_key_explanation)
                    .setPositiveButton(
                            R.string.backup_recovery_key_create,
                            (d, w) -> showRecoveryKeyDialog(BackupKeys.generate(), true))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        }
        final CharSequence[] items = {
            getString(R.string.backup_recovery_key_show),
            getString(R.string.backup_recovery_key_replace),
            getString(R.string.backup_recovery_key_disable)
        };
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.backup_recovery_key)
                .setItems(
                        items,
                        (d, which) -> {
                            switch (which) {
                                case 0 -> showRecoveryKeyAfterAuthentication(current);
                                case 1 ->
                                        new MaterialAlertDialogBuilder(requireActivity())
                                                .setTitle(R.string.backup_recovery_key_replace)
                                                .setMessage(
                                                        R.string
                                                                .backup_recovery_key_replace_warning)
                                                .setPositiveButton(
                                                        R.string.backup_recovery_key_create,
                                                        (d2, w2) ->
                                                                showRecoveryKeyDialog(
                                                                        BackupKeys.generate(),
                                                                        true))
                                                .setNegativeButton(R.string.cancel, null)
                                                .show();
                                default ->
                                        new MaterialAlertDialogBuilder(requireActivity())
                                                .setTitle(R.string.backup_recovery_key_disable)
                                                .setMessage(
                                                        R.string
                                                                .backup_recovery_key_disable_warning)
                                                .setPositiveButton(
                                                        R.string.ok,
                                                        (d2, w2) -> {
                                                            database().setBackupRecoveryKey(null);
                                                            updateRecoveryKeySummary();
                                                        })
                                                .setNegativeButton(R.string.cancel, null)
                                                .show();
                            }
                        })
                .show();
        return true;
    }

    /** The stored key is only shown after the device credential, if the device has one. */
    private void showRecoveryKeyAfterAuthentication(final byte[] key) {
        final var keyguard =
                requireContext().getSystemService(android.app.KeyguardManager.class);
        final Intent intent =
                keyguard == null || !keyguard.isDeviceSecure()
                        ? null
                        : keyguard.createConfirmDeviceCredentialIntent(
                                getString(R.string.backup_recovery_key),
                                getString(R.string.backup_recovery_key_confirm_identity));
        if (intent == null) {
            showRecoveryKeyDialog(key, false);
            return;
        }
        keyToShowAfterAuthentication = key;
        confirmCredentialLauncher.launch(intent);
    }

    /**
     * Shows {@code key}; for a new key ({@code confirm}) it is only saved once the user typed
     * its last 8 characters, i.e. has actually written it down.
     */
    private void showRecoveryKeyDialog(final byte[] key, final boolean confirm) {
        final var context = requireContext();
        final String formatted = BackupKeys.format(key);
        final int padding = (int) (20 * context.getResources().getDisplayMetrics().density);
        final android.widget.LinearLayout layout = new android.widget.LinearLayout(context);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(padding, padding / 2, padding, 0);
        final android.widget.TextView keyView = new android.widget.TextView(context);
        keyView.setText(formatted);
        keyView.setTextIsSelectable(true);
        keyView.setTypeface(android.graphics.Typeface.MONOSPACE);
        keyView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        layout.addView(keyView);
        final com.google.android.material.textfield.TextInputLayout confirmLayout =
                new com.google.android.material.textfield.TextInputLayout(context);
        final com.google.android.material.textfield.TextInputEditText confirmInput =
                new com.google.android.material.textfield.TextInputEditText(
                        confirmLayout.getContext());
        if (confirm) {
            final android.widget.TextView message = new android.widget.TextView(context);
            message.setText(R.string.backup_recovery_key_new_message);
            message.setPadding(0, padding / 2, 0, 0);
            layout.addView(message);
            confirmInput.setInputType(
                    android.text.InputType.TYPE_CLASS_TEXT
                            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            confirmLayout.setHint(R.string.backup_recovery_key_confirm_hint);
            confirmLayout.addView(confirmInput);
            layout.addView(confirmLayout);
        }
        final MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(requireActivity())
                        .setTitle(R.string.backup_recovery_key)
                        .setView(layout)
                        .setNeutralButton(R.string.copy_to_clipboard, null)
                        .setPositiveButton(confirm ? R.string.save : R.string.ok, null)
                        .setCancelable(!confirm);
        if (confirm) {
            builder.setNegativeButton(R.string.cancel, null);
        }
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(
                d -> {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                            .setOnClickListener(v -> copyRecoveryKey(formatted));
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                            .setOnClickListener(
                                    v -> {
                                        if (confirm) {
                                            final String typed =
                                                    String.valueOf(confirmInput.getText())
                                                            .replace(" ", "")
                                                            .toLowerCase(Locale.ROOT);
                                            final String hex = formatted.replace(" ", "");
                                            if (!hex.substring(hex.length() - 8).equals(typed)) {
                                                confirmLayout.setError(
                                                        getString(
                                                                R.string
                                                                        .backup_recovery_key_confirm_mismatch));
                                                return;
                                            }
                                            database().setBackupRecoveryKey(key);
                                            updateRecoveryKeySummary();
                                        }
                                        dialog.dismiss();
                                    });
                });
        // the key must not end up in screenshots or the recent-apps preview
        if (dialog.getWindow() != null) {
            dialog.getWindow()
                    .addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        dialog.show();
    }

    private void copyRecoveryKey(final String formatted) {
        final var clipboard =
                requireContext().getSystemService(android.content.ClipboardManager.class);
        if (clipboard == null) {
            return;
        }
        final android.content.ClipData clip =
                android.content.ClipData.newPlainText(
                        getString(R.string.backup_recovery_key), formatted);
        // keep it out of clipboard previews and history (Android 13+)
        final android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        clipboard.setPrimaryClip(clip);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(requireContext(), R.string.backup_recovery_key_copied, Toast.LENGTH_SHORT)
                    .show();
        }
    }

    public void openSettingsPicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        startActivityForResult(
                Intent.createChooser(intent, getString(R.string.select_settings_file)),
                REQUEST_IMPORT_SETTINGS);
    }

    private boolean onBackupLocationPreferenceClicked(final Preference preference) {
        this.pickBackupLocationLauncher.launch(null);
        return false;
    }

    private void submitBackupLocationPreference(final Uri uri) {
        final var contentResolver = requireContext().getContentResolver();
        contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        final var appSettings = new AppSettings(requireContext());
        appSettings.setBackupLocation(uri);
        final var preference = findPreference(AppSettings.BACKUP_LOCATION);
        if (preference == null) {
            return;
        }
        preference.setSummary(
                getString(R.string.pref_create_backup_summary, AppSettings.asPath(uri)));
    }

    @Override
    protected void onSharedPreferenceChanged(@NonNull String key) {
        super.onSharedPreferenceChanged(key);
        if (RECURRING_BACKUP.equals(key)) {
            final var sharedPreferences = getPreferenceManager().getSharedPreferences();
            if (sharedPreferences != null) {
                applyRecurringBackup(sharedPreferences);
            }
        }
    }

    /** Schedules (or cancels) the recurring backup for the interval stored in the preferences. */
    private void applyRecurringBackup(final SharedPreferences sharedPreferences) {
        final Long recurringBackupInterval =
                Longs.tryParse(Strings.nullToEmpty(sharedPreferences.getString(RECURRING_BACKUP, null)));
        if (recurringBackupInterval == null) {
            return;
        }
        Log.d(Config.LOGTAG, "recurring backup interval changed to: " + recurringBackupInterval);
        final var workManager = WorkManager.getInstance(requireContext());
        if (recurringBackupInterval <= 0) {
            workManager.cancelUniqueWork(RECURRING_BACKUP);
        } else {
            final Constraints constraints =
                    new Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .setRequiresStorageNotLow(true)
                            .build();

            final PeriodicWorkRequest periodicWorkRequest =
                    new PeriodicWorkRequest.Builder(
                                    ExportBackupWorker.class,
                                    recurringBackupInterval,
                                    TimeUnit.SECONDS)
                            .setConstraints(constraints)
                            .setInputData(
                                    new Data.Builder().putBoolean("recurring_backup", true).build())
                            .build();
            workManager.enqueueUniquePeriodicWork(
                    RECURRING_BACKUP, ExistingPeriodicWorkPolicy.UPDATE, periodicWorkRequest);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.backup);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_IMPORT_SETTINGS) {
            final Uri uri = data == null ? null : data.getData();
            if (resultCode == RESULT_OK && uri != null) {
                importSettings(uri, requireSettingsActivity());
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (requestCode == REQUEST_EXPORT_SETTINGS) {
                    exportSettings();
                }
                if (requestCode == REQUEST_IMPORT_SETTINGS) {
                    ToastCompat.makeText(
                                    requireActivity(),
                                    "permissions for open setting spicker granted",
                                    ToastCompat.LENGTH_SHORT)
                            .show();
                    openSettingsPicker();
                }
            } else {
                ToastCompat.makeText(
                                requireActivity(),
                                R.string.no_storage_permission,
                                ToastCompat.LENGTH_SHORT)
                        .show();
            }
        }
    }

    private boolean onBackupPreferenceClicked(final Preference preference) {
        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.disable_all_accounts)
                .setMessage(R.string.disable_all_accounts_question)
                .setPositiveButton(
                        R.string.yes,
                        (dialog, whichButton) -> {
                            for (final var account : requireService().getAccounts()) {
                                account.setOption(Account.OPTION_DISABLED, true);
                                if (!requireService().updateAccount(account)) {
                                    Toast.makeText(
                                                    requireActivity(),
                                                    R.string.unable_to_update_account,
                                                    Toast.LENGTH_SHORT)
                                            .show();
                                }
                            }
                            aboutToStartOneOffBackup();
                        })
                .setNegativeButton(R.string.no, (dialog, whichButton) -> aboutToStartOneOffBackup())
                .show();
        return true;
    }

    private void aboutToStartOneOffBackup() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                            requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestStorageForBackupLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            } else {
                startOneOffBackup();
            }
        } else {
            startOneOffBackup();
        }
    }

    private void startOneOffBackup() {
        final OneTimeWorkRequest exportBackupWorkRequest =
                new OneTimeWorkRequest.Builder(ExportBackupWorker.class)
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build();
        WorkManager.getInstance(requireContext())
                .enqueueUniqueWork(
                        CREATE_ONE_OFF_BACKUP, ExistingWorkPolicy.KEEP, exportBackupWorkRequest);
        final MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(requireActivity());
        builder.setMessage(R.string.backup_started_message);
        builder.setPositiveButton(R.string.ok, null);
        builder.create().show();
    }

    /**
     * SECURITY: the file is untrusted. It is read as bounded, strict JSON (never deserialized),
     * and only allowlisted settings with valid values are applied, on top of the current ones —
     * see {@link SettingsBackup}. Old settings.dat files are rejected.
     */
    private void importSettings(final Uri uri, final SettingsActivity settingsActivity) {
        int messageResId = R.string.error_import_settings;
        try (final InputStream is = settingsActivity.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new IOException("could not open " + uri);
            }
            final byte[] content =
                    ByteStreams.toByteArray(
                            ByteStreams.limit(is, SettingsBackup.MAX_FILE_SIZE + 1L));
            if (content.length > SettingsBackup.MAX_FILE_SIZE) {
                throw new SettingsBackup.UnsupportedSettingsFileException("file too large");
            }
            final Map<String, Object> settings =
                    SettingsBackup.parse(
                            new InputStreamReader(
                                    new ByteArrayInputStream(content), StandardCharsets.UTF_8),
                            this::allowedChoices);
            final SharedPreferences preferences =
                    PreferenceManager.getDefaultSharedPreferences(settingsActivity);
            final SharedPreferences.Editor editor = preferences.edit();
            for (final Map.Entry<String, Object> entry : settings.entrySet()) {
                final Object value = entry.getValue();
                if (value instanceof Boolean b) {
                    editor.putBoolean(entry.getKey(), b);
                } else if (value instanceof Integer i) {
                    editor.putInt(entry.getKey(), i);
                } else if (value instanceof String string) {
                    editor.putString(entry.getKey(), string);
                }
            }
            if (!editor.commit()) {
                throw new IOException("could not save imported settings");
            }
            Log.d(Config.LOGTAG, "imported " + settings.size() + " settings");
            if (settings.containsKey(RECURRING_BACKUP)) {
                applyRecurringBackup(preferences);
            }
            messageResId = R.string.success_import_settings;
        } catch (final SettingsBackup.UnsupportedSettingsFileException e) {
            Log.w(Config.LOGTAG, "unsupported settings file", e);
            messageResId = R.string.error_import_settings_unsupported;
        } catch (final IOException | RuntimeException e) {
            Log.w(Config.LOGTAG, "could not import settings", e);
        }
        ToastCompat.makeText(settingsActivity, messageResId, ToastCompat.LENGTH_LONG).show();
        if (messageResId == R.string.success_import_settings) {
            settingsActivity.recreate(); // theme and other appearance settings
        }
    }

    /** The entry values of each CHOICE setting in {@link SettingsBackup#ALLOWED}. */
    @Nullable
    private Set<String> allowedChoices(final String key) {
        final var resources = getResources();
        final int stringArray =
                switch (key) {
                    case "picture_compression" -> R.array.picture_compression_values;
                    case "video_compression" -> R.array.video_compression_values;
                    case "cache_deletion_time" -> R.array.cache_deletion_values;
                    case "voice_message_codec" -> R.array.voice_codec_values;
                    case "theme" -> R.array.themes_values;
                    case "avatar_shape" -> R.array.avatars_shape_values;
                    case "quick_action" -> R.array.quick_action_values;
                    case "grace_period_length" -> R.array.grace_periods_values;
                    default -> 0;
                };
        if (stringArray != 0) {
            return ImmutableSet.copyOf(resources.getStringArray(stringArray));
        }
        // values set in code via XmppPreferenceFragment.setValues (String.valueOf of the ints)
        final int intArray =
                switch (key) {
                    case "auto_accept_file_size" -> R.array.file_size_values;
                    case "update_track_longer_than" -> R.array.track_duration_values;
                    case RECURRING_BACKUP -> R.array.recurring_backup_values;
                    default -> 0;
                };
        if (intArray == 0) {
            return null;
        }
        final ImmutableSet.Builder<String> values = ImmutableSet.builder();
        for (final int value : resources.getIntArray(intArray)) {
            values.add(String.valueOf(value));
        }
        return values.build();
    }

    private void exportSettings() {
        boolean success = false;
        final var context = requireSettingsActivity();
        final var appSettings = new AppSettings(context);
        final String fileName = DATE_FORMAT.format(new Date()) + "_settings.json";
        // Same folder as the .ceb backups: a plain path or, for a picked folder, its SAF tree.
        // Only the allowlisted settings are written (see SettingsBackup).
        try (final OutputStream outputStream =
                        openSettingsFile(appSettings.getBackupLocation(), fileName);
                final Writer writer =
                        new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
            final SharedPreferences pref =
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
            SettingsBackup.write(pref.getAll(), writer);
            success = true;
        } catch (final IOException | RuntimeException e) {
            Log.w(Config.LOGTAG, "could not export settings", e);
        }
        if (success) {
            new Thread(() -> runOnUiThread(() -> requireActivity().recreate())).start();
            ToastCompat.makeText(
                            requireActivity(),
                            R.string.success_export_settings,
                            ToastCompat.LENGTH_SHORT)
                    .show();
        } else {
            ToastCompat.makeText(
                            requireActivity(),
                            R.string.error_export_settings,
                            ToastCompat.LENGTH_SHORT)
                    .show();
        }
    }

    /**
     * Creates {@code fileName} inside the backup location. An existing file is never replaced:
     * if writing failed, an earlier export would be lost.
     */
    private OutputStream openSettingsFile(final Uri backupLocation, final String fileName)
            throws IOException {
        final var context = requireContext();
        if ("file".equalsIgnoreCase(backupLocation.getScheme())) {
            final File directory = new File(backupLocation.getPath());
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Could not create " + directory.getAbsolutePath());
            }
            final File file = new File(directory, fileName);
            if (!file.createNewFile()) {
                throw new IOException(file + " already exists");
            }
            return new FileOutputStream(file);
        }
        final DocumentFile tree = DocumentFile.fromTreeUri(context, backupLocation);
        if (tree == null) {
            throw new IOException("DocumentFile.fromTreeUri returned null for " + backupLocation);
        }
        if (tree.findFile(fileName) != null) {
            throw new IOException(fileName + " already exists in " + backupLocation);
        }
        final DocumentFile file = tree.createFile("application/json", fileName);
        if (file == null) {
            throw new IOException("Could not create " + fileName + " in " + backupLocation);
        }
        if (!fileName.equals(file.getName())) {
            file.delete();
            throw new IOException("Provider renamed " + fileName + " to " + file.getName());
        }
        final OutputStream outputStream =
                context.getContentResolver().openOutputStream(file.getUri());
        if (outputStream == null) {
            throw new IOException("Could not open " + file.getUri());
        }
        return outputStream;
    }

    public SettingsActivity requireSettingsActivity() {
        final var activity = requireActivity();
        if (activity instanceof SettingsActivity settingsActivity) {
            return settingsActivity;
        }
        throw new IllegalStateException(
                String.format(
                        "%s is not %s",
                        activity.getClass().getName(), SettingsActivity.class.getName()));
    }

    private boolean onExportClicked(final Preference preference) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                            requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestStorageForBackupLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            } else {
                startExport();
            }
        } else {
            startExport();
        }
        return true;
    }

    private void startExport() {
        final OneTimeWorkRequest exportBackupWorkRequest =
                new OneTimeWorkRequest.Builder(ExportBackupWorker.class)
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build();
        WorkManager.getInstance(requireContext())
                .enqueueUniqueWork(
                        CREATE_ONE_OFF_BACKUP, ExistingWorkPolicy.KEEP, exportBackupWorkRequest);
        final MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(requireActivity());
        builder.setMessage(R.string.backup_started_message);
        builder.setPositiveButton(R.string.ok, null);
        builder.create().show();
    }
}
