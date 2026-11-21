package eu.siacs.conversations.ui.util;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.OnBackPressedDispatcher;
import androidx.annotation.NonNull;
import java.util.function.BooleanSupplier;

/**
 * Custom back handling that works with predictive back.
 *
 * <p>The manifest enables {@code enableOnBackInvokedCallback} (and targetSdk 36+ makes predictive
 * back the default), so on API 33+ the system never calls {@code Activity.onBackPressed()}
 * overrides. Back logic must instead go through the {@link OnBackPressedDispatcher}.
 */
public final class BackHandler {

    private BackHandler() {}

    /**
     * Registers {@code consume} for back events of {@code activity}. If it returns {@code true} the
     * event is handled; if it returns {@code false} the default behaviour runs, exactly like the
     * old {@code super.onBackPressed()} (including the fragment back stack). Call from {@code
     * onCreate} after {@code super.onCreate}.
     */
    public static void register(
            @NonNull final ComponentActivity activity, @NonNull final BooleanSupplier consume) {
        final OnBackPressedDispatcher dispatcher = activity.getOnBackPressedDispatcher();
        dispatcher.addCallback(
                activity,
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (consume.getAsBoolean()) {
                            return;
                        }
                        setEnabled(false);
                        try {
                            dispatcher.onBackPressed();
                        } finally {
                            setEnabled(true);
                        }
                    }
                });
    }
}
