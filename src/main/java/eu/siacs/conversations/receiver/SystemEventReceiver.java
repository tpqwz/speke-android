package eu.siacs.conversations.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.Compatibility;
import java.util.Set;

public class SystemEventReceiver extends BroadcastReceiver {

    public static final String SETTING_ENABLED_ACCOUNTS = "enabled_accounts";
    public static final String EXTRA_NEEDS_FOREGROUND_SERVICE = "needs_foreground_service";

    // Only the system broadcasts from the manifest and our own alarms may start the service
    // through here. Everything else XmppConnectionService understands (account provisioning,
    // notification actions, ...) must never be reachable via this receiver.
    private static final Set<String> ALLOWED_ACTIONS =
            ImmutableSet.of(
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    Intent.ACTION_SHUTDOWN,
                    ConnectivityManager.CONNECTIVITY_ACTION,
                    AudioManager.RINGER_MODE_CHANGED_ACTION,
                    XmppConnectionService.ACTION_PING,
                    XmppConnectionService.ACTION_IDLE_PING,
                    XmppConnectionService.ACTION_POST_CONNECTIVITY_CHANGE,
                    XmppConnectionService.ACTION_EXPIRE_MESSAGES);

    @Override
    public void onReceive(final Context context, final Intent originalIntent) {
        final String action = originalIntent.getAction();
        if (Strings.isNullOrEmpty(action) || !ALLOWED_ACTIONS.contains(action)) {
            Log.d(Config.LOGTAG, "EventReceiver ignored action " + action);
            return;
        }
        if (!hasEnabledAccounts(context)) {
            Log.d(
                    Config.LOGTAG,
                    "EventReceiver ignored action " + action + " (no enabled accounts)");
            return;
        }
        final Intent intentForService = new Intent(context, XmppConnectionService.class);
        intentForService.setAction(action);
        final Bundle extras = originalIntent.getExtras();
        if (extras != null && !extras.isEmpty()) {
            intentForService.putExtras(extras);
        }
        Compatibility.startService(context, intentForService);
    }

    public static boolean hasEnabledAccounts(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(SETTING_ENABLED_ACCOUNTS, true);
    }
}
