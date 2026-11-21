package eu.siacs.conversations.ui.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.CheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.parser.MessageParser;
import eu.siacs.conversations.services.XmppConnectionService;
import java.util.Arrays;

/**
 * The one place a user changes a conversation's disappearing-message timer: the switch in the
 * contact and room details, the chat menu and the "active" banner all end up in {@link #apply}.
 *
 * <p>Note to self has no details screen of its own (its header opens the account), so the menu and
 * the banner are the only way to change a timer another of our devices set there.
 */
public final class EphemeralTimerPicker {

    private EphemeralTimerPicker() {}

    /** Whether we may set the timer here at all (same rule as for an incoming change). */
    public static boolean isAvailable(final Conversation conversation) {
        return conversation != null
                && MessageParser.mayChangeEphemeralTimer(
                        conversation.getMode() == Conversation.MODE_MULTI,
                        conversation.isPrivateAndNonAnonymous());
    }

    /** Off plus the usual durations, the current value preselected. */
    public static void show(
            final Activity activity,
            final XmppConnectionService service,
            final Conversation conversation) {
        if (activity == null || service == null || !isAvailable(conversation)) {
            return;
        }
        final int current = conversation.getEphemeralTimer();
        // "Off", then the usual durations and a timer set elsewhere that is none of them
        final int[] durations = durationsWith(activity, current);
        final String[] names = durationNames(activity, durations);
        final int[] values = new int[durations.length + 1];
        final String[] entries = new String[values.length];
        entries[0] = activity.getString(R.string.ephemeral_messages_off);
        System.arraycopy(durations, 0, values, 1, durations.length);
        System.arraycopy(names, 0, entries, 1, names.length);
        int checked = 0;
        for (int i = 1; current > 0 && i < values.length; i++) {
            if (values[i] == current) {
                checked = i;
                break;
            }
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.ephemeral_messages)
                .setSingleChoiceItems(
                        entries,
                        checked,
                        (dialog, which) -> {
                            dialog.dismiss();
                            if (which == 0) {
                                apply(service, conversation, 0);
                            } else {
                                confirmEnable(
                                        activity,
                                        () -> apply(service, conversation, values[which]),
                                        null);
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * The usual durations, plus {@code current} when it is on and none of them (set by another
     * client): shown as itself, so no list ever claims a different setting than the one in force,
     * and every listed duration stays a choice that differs from it.
     */
    public static int[] durationsWith(final Context context, final int current) {
        final int[] durations =
                context.getResources().getIntArray(R.array.ephemeral_duration_values);
        if (current <= 0) {
            return durations;
        }
        for (final int duration : durations) {
            if (duration == current) {
                return durations;
            }
        }
        final int[] values = Arrays.copyOf(durations, durations.length + 1);
        values[durations.length] = current;
        return values;
    }

    /** Labels for {@code values}, as {@link #describe} reads them. */
    public static String[] durationNames(final Context context, final int[] values) {
        final String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = describe(context, values[i]);
        }
        return names;
    }

    /**
     * How a timer reads in the banner and the picker: the listed name for one of the usual
     * durations, otherwise the exact value (a timer set elsewhere is never rounded to another).
     */
    public static String describe(final Context context, final int seconds) {
        final Resources res = context.getResources();
        final int[] durations = res.getIntArray(R.array.ephemeral_duration_values);
        final String[] names = res.getStringArray(R.array.ephemeral_durations);
        for (int i = 0; i < durations.length && i < names.length; i++) {
            if (durations[i] == seconds) {
                return names[i];
            }
        }
        return exactDuration(context, seconds);
    }

    /** {@code seconds} in the largest unit that divides it exactly ("2 hours", "90 seconds"). */
    private static String exactDuration(final Context context, final int seconds) {
        final Resources res = context.getResources();
        if (seconds % 86400 == 0) {
            return res.getQuantityString(R.plurals.days, seconds / 86400, seconds / 86400);
        } else if (seconds % 3600 == 0) {
            return res.getQuantityString(R.plurals.hours, seconds / 3600, seconds / 3600);
        } else if (seconds % 60 == 0) {
            return res.getQuantityString(R.plurals.minutes, seconds / 60, seconds / 60);
        }
        return res.getQuantityString(R.plurals.seconds, seconds, seconds);
    }

    /**
     * Shows the "not guaranteed to disappear elsewhere" warning unless the user turned it off, then
     * runs {@code onConfirm}; {@code onCancel} (may be null) when the warning is dismissed.
     */
    public static void confirmEnable(
            final Activity activity, final Runnable onConfirm, final Runnable onCancel) {
        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(activity);
        if (sp.getBoolean(AppSettings.HIDE_EPHEMERAL_WARNING, false)) {
            onConfirm.run();
            return;
        }
        final View dialogView =
                activity.getLayoutInflater().inflate(R.layout.dialog_ephemeral_warning, null);
        final CheckBox dontShowAgain = dialogView.findViewById(R.id.dont_show_again);
        new MaterialAlertDialogBuilder(activity)
                .setView(dialogView)
                .setPositiveButton(
                        R.string.ok,
                        (dialog, which) -> {
                            if (dontShowAgain.isChecked()) {
                                sp.edit()
                                        .putBoolean(AppSettings.HIDE_EPHEMERAL_WARNING, true)
                                        .apply();
                            }
                            onConfirm.run();
                        })
                .setNegativeButton(
                        R.string.cancel,
                        (dialog, which) -> {
                            if (onCancel != null) onCancel.run();
                        })
                .setOnCancelListener(dialog -> {
                    if (onCancel != null) onCancel.run();
                })
                .show();
    }

    /**
     * Sets the timer (0 = off) and always announces it, even when our value did not change: our
     * other devices or the contact may hold a different one (a change they missed), and the user's
     * explicit choice is what brings them back in line. In a PQ OMEMO2 chat the announcement is
     * encrypted ({@code sendEphemeralControl}), so repeating it reveals nothing.
     */
    public static void apply(
            final XmppConnectionService service,
            final Conversation conversation,
            final int seconds) {
        if (!isAvailable(conversation)) {
            return;
        }
        final int timer = Math.max(0, seconds);
        conversation.setEphemeralTimer(timer);
        conversation.setEphemeralBy(null);
        service.databaseBackend.updateConversation(conversation);
        if (timer > 0) {
            service.sendEphemeralImplicitNegotiation(conversation, timer);
        } else {
            service.sendEphemeralIWantOut(conversation);
        }
        service.updateConversationUi();
    }
}
