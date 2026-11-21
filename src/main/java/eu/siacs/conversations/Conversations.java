package eu.siacs.conversations;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;
import eu.siacs.conversations.services.EmojiInitializationService;
import eu.siacs.conversations.utils.ExceptionHelper;
import p32929.easypasscodelock.Activities.LockscreenActivity;
import p32929.easypasscodelock.Utils.EasyLock;
import p32929.easypasscodelock.Utils.EasylockSP;

public class Conversations extends Application {

    @SuppressLint("StaticFieldLeak")
    private static Context CONTEXT;

    public static Context getContext() {
        return Conversations.CONTEXT;
    }

    @SuppressLint("StaticFieldLeak")
    private static Conversations INSTANCE;

    @Override
    public void onCreate() {
        EasylockSP.init(getApplicationContext());
        super.onCreate();
        INSTANCE = this;
        CONTEXT = this.getApplicationContext();
        EmojiInitializationService.execute(getApplicationContext());
        ExceptionHelper.init(getApplicationContext());
        applyThemeSettings();
        EasyLock.setBackgroundColor(getColor(R.color.black26));
        EasyLock.forgotPassword(v ->
                Toast.makeText(
                                getApplicationContext(),
                                R.string.app_lock_forgot_password,
                                Toast.LENGTH_LONG)
                        .show());
        registerAppLockLifecycle();
    }

    private int startedActivities = 0;

    // Molly-style auto-lock (startup password only): when the app went to the background
    private long backgroundSince = 0;
    private final android.os.Handler autoLockHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autoLock = () -> lockSession(false);

    // all app activities that exist, for finishing them on lock
    private final java.util.Set<Activity> createdActivities =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Locks the app now (startup password only): wipes keys and ends the process. */
    public static void lockNow() {
        final Conversations instance = INSTANCE;
        if (instance != null) {
            instance.lockSession(true);
        }
    }

    private void lockSession(final boolean relaunch) {
        eu.siacs.conversations.services.AppSessionLock.lock(
                this, new java.util.ArrayList<>(createdActivities), relaunch);
    }

    private boolean appLocked = false;

    private boolean lockOnNextForeground = true;

    private final java.util.Map<Activity, View> appLockCovers = new java.util.WeakHashMap<>();

    // The PIN prompt currently on screen (check mode only), if any.
    private java.lang.ref.WeakReference<Activity> lockPrompt = null;

    // App activities (not the PIN prompt) that are currently started.
    private final java.util.Set<Activity> startedAppActivities =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private static boolean isCheckPrompt(final Activity activity) {
        if (!(activity instanceof LockscreenActivity)) {
            return false;
        }
        final android.content.Intent intent = activity.getIntent();
        final String status = intent == null ? null : intent.getStringExtra("passStatus");
        return status == null || "check".equals(status);
    }

    private boolean isLockPromptAlive() {
        final Activity prompt = lockPrompt == null ? null : lockPrompt.get();
        return prompt != null && !prompt.isFinishing() && !prompt.isDestroyed();
    }

    private void registerAppLockLifecycle() {
        registerActivityLifecycleCallbacks(
                new ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityCreated(
                            final Activity activity, final Bundle savedInstanceState) {
                        createdActivities.add(activity);
                        if (new AppSettings(activity).isAppLockActive()) {
                            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                        }
                        if (isCheckPrompt(activity)) {
                            lockPrompt = new java.lang.ref.WeakReference<>(activity);
                        }
                    }

                    @Override
                    public void onActivityStarted(final Activity activity) {
                        final boolean comingToForeground = startedActivities == 0;
                        if (comingToForeground) {
                            autoLockHandler.removeCallbacks(autoLock);
                            // the timer may not have fired (e.g. the process was frozen)
                            if (eu.siacs.conversations.services.AppSessionLock.isDue(
                                    backgroundSince,
                                    android.os.SystemClock.elapsedRealtime(),
                                    eu.siacs.conversations.services.AppSessionLock
                                            .autoLockDelayMillis(activity))) {
                                backgroundSince = 0;
                                lockSession(true);
                                return;
                            }
                            backgroundSince = 0;
                        }
                        startedActivities++;
                        if (activity instanceof LockscreenActivity) {
                            return;
                        }
                        startedAppActivities.add(activity);
                        if (comingToForeground && lockOnNextForeground) {
                            lockOnNextForeground = false;
                            // Decide based on whether a passcode is actually set (read here, from
                            // an
                            // activity context — it returns null if read during
                            // Application.onCreate).
                            if (new AppSettings(activity).isAppLockActive()) {
                                appLocked = true;
                                // Cover before the first frame so content never flashes.
                                coverActivity(activity);
                                EasyLock.checkPassword(activity);
                            } else {
                                appLocked = false;
                            }
                        } else if (appLocked) {
                            // An activity appeared while locked — cover it too.
                            coverActivity(activity);
                            // The PIN prompt may be gone although we are still locked (the user
                            // backed out of it and reopened the app before its onStop arrived, so
                            // this did not count as coming to the foreground). Never leave the bare
                            // cover on screen: bring the prompt back.
                            if (!isLockPromptAlive()) {
                                EasyLock.checkPassword(activity);
                            }
                        }
                    }

                    @Override
                    public void onActivityStopped(final Activity activity) {
                        startedAppActivities.remove(activity);
                        if (startedActivities > 0) {
                            startedActivities--;
                        }
                        if (startedActivities == 0 && !activity.isChangingConfigurations()) {
                            lockOnNextForeground = true;
                            final long delay =
                                    eu.siacs.conversations.services.AppSessionLock
                                            .autoLockDelayMillis(activity);
                            if (delay > 0) {
                                backgroundSince = android.os.SystemClock.elapsedRealtime();
                                autoLockHandler.postDelayed(autoLock, delay);
                            }
                        }
                    }

                    @Override
                    public void onActivityResumed(final Activity activity) {}

                    @Override
                    public void onActivityPaused(final Activity activity) {}

                    @Override
                    public void onActivitySaveInstanceState(
                            final Activity activity, final Bundle outState) {}

                    @Override
                    public void onActivityDestroyed(final Activity activity) {
                        createdActivities.remove(activity);
                        appLockCovers.remove(activity);
                        startedAppActivities.remove(activity);
                        if (lockPrompt != null && lockPrompt.get() == activity) {
                            lockPrompt = null;
                            // The prompt was removed without unlocking (e.g. cleared by a
                            // singleTask re-launch of the activity below it). If an app activity
                            // is still showing, put the prompt back on top of its cover.
                            if (appLocked && !activity.isChangingConfigurations()) {
                                for (final Activity started :
                                        new java.util.ArrayList<>(startedAppActivities)) {
                                    if (!started.isFinishing() && !started.isDestroyed()) {
                                        EasyLock.checkPassword(started);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                });
    }

    public static void notifyAppUnlocked() {
        final Conversations instance = INSTANCE;
        if (instance != null) {
            instance.handleUnlocked();
        }
    }

    private void handleUnlocked() {
        appLocked = false;
        for (final View cover : new java.util.ArrayList<>(appLockCovers.values())) {
            removeCover(cover);
        }
        appLockCovers.clear();
    }

    private void coverActivity(final Activity activity) {
        if (appLockCovers.containsKey(activity) || activity.getWindow() == null) {
            return;
        }
        final View cover = new View(activity);
        cover.setClickable(true);
        cover.setFocusable(true);
        cover.setBackgroundColor(resolveOpaqueBackground(activity));
        activity.addContentView(
                cover,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        appLockCovers.put(activity, cover);
    }

    private void removeCover(final View cover) {
        if (cover != null && cover.getParent() instanceof ViewGroup) {
            ((ViewGroup) cover.getParent()).removeView(cover);
        }
    }

    private int resolveOpaqueBackground(final Activity activity) {
        final TypedValue typedValue = new TypedValue();
        if (activity.getTheme()
                        .resolveAttribute(
                                com.google.android.material.R.attr.colorSurface, typedValue, true)
                && typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return typedValue.data;
        }
        return 0xFF000000;
    }

    public void applyThemeSettings() {
        final var sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (sharedPreferences == null) {
            return;
        }
        applyThemeSettings(sharedPreferences);
    }

    private void applyThemeSettings(final SharedPreferences sharedPreferences) {
        AppCompatDelegate.setDefaultNightMode(getDesiredNightMode(this, sharedPreferences));
        var dynamicColorsOptions =
                new DynamicColorsOptions.Builder()
                        .setPrecondition((activity, t) -> isDynamicColorsDesired(activity))
                        .build();
        DynamicColors.applyToActivitiesIfAvailable(this, dynamicColorsOptions);
    }

    public static int getDesiredNightMode(final Context context) {
        final var sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (sharedPreferences == null) {
            return AppCompatDelegate.getDefaultNightMode();
        }
        return getDesiredNightMode(context, sharedPreferences);
    }

    public static boolean isDynamicColorsDesired(final Context context) {
        final var preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (isCustomColorsDesired(context)) return false;
        return preferences.getBoolean(AppSettings.DYNAMIC_COLORS, false);
    }

    public static boolean isCustomColorsDesired(final Context context) {
        final var sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        final String theme =
                sharedPreferences.getString(AppSettings.THEME, context.getString(R.string.theme));
        return "custom".equals(theme);
    }

    private static int getDesiredNightMode(
            final Context context, final SharedPreferences sharedPreferences) {
        var theme =
                sharedPreferences.getString(AppSettings.THEME, context.getString(R.string.theme));

        // Migrate old themes to equivalent custom
        if ("oledblack".equals(theme)) {
            theme = "custom";
            final var p = PreferenceManager.getDefaultSharedPreferences(context);
            p.edit()
                    .putString(AppSettings.THEME, "custom")
                    .putBoolean("custom_theme_automatic", false)
                    .putBoolean("custom_theme_dark", true)
                    .putBoolean("custom_theme_color_match", true)
                    .putInt("custom_dark_theme_primary", context.getColor(R.color.white))
                    .putInt(
                            "custom_dark_theme_primary_dark",
                            context.getColor(android.R.color.black))
                    .putInt("custom_dark_theme_accent", context.getColor(R.color.yeller))
                    .putInt(
                            "custom_dark_theme_background_primary",
                            context.getColor(android.R.color.black))
                    .commit();
        }

        // Migrate old themes to equivalent custom
        if ("obsidian".equals(theme)) {
            theme = "custom";
            final var p = PreferenceManager.getDefaultSharedPreferences(context);
            p.edit()
                    .putString(AppSettings.THEME, "custom")
                    .putBoolean("custom_theme_automatic", false)
                    .putBoolean("custom_theme_dark", true)
                    .putInt("custom_dark_theme_primary", context.getColor(R.color.black_blue))
                    .putInt("custom_dark_theme_primary_dark", context.getColor(R.color.black_blue))
                    .putInt("custom_dark_theme_accent", context.getColor(R.color.yeller))
                    .putInt(
                            "custom_dark_theme_background_primary",
                            context.getColor(R.color.blacker_blue))
                    .commit();
        }

        if ("custom".equals(theme)) {
            if (sharedPreferences.getBoolean("custom_theme_automatic", false))
                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            return sharedPreferences.getBoolean("custom_theme_dark", false)
                    ? AppCompatDelegate.MODE_NIGHT_YES
                    : AppCompatDelegate.MODE_NIGHT_NO;
        }

        return getDesiredNightMode(theme);
    }

    public static int getDesiredNightMode(final String theme) {
        if ("automatic".equals(theme)) {
            return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        } else if ("light".equals(theme)) {
            return AppCompatDelegate.MODE_NIGHT_NO;
        } else {
            return AppCompatDelegate.MODE_NIGHT_YES;
        }
    }
}
