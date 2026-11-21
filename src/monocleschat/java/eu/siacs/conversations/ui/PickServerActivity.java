package eu.siacs.conversations.ui;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.MenuItem;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityPickServerBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.ui.util.BackHandler;
import java.util.List;

public class PickServerActivity extends XmppActivity {

    @Override
    protected void refreshUiReal() {}

    @Override
    protected void onBackendConnected() {}

    @Override
    public void onStart() {
        super.onStart();
        /* final int theme = findTheme();
        if (this.mTheme != theme) {
            recreate();
        }*/
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean handleBack() {
        startActivity(new Intent(this, WelcomeActivity.class));
        return false;
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null) {
            setIntent(intent);
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        if (getResources().getBoolean(R.bool.portrait_only)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        super.onCreate(savedInstanceState);
        BackHandler.register(this, this::handleBack);
        ActivityPickServerBinding binding =
                DataBindingUtil.setContentView(this, R.layout.activity_pick_server);
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        // "Use monocles" opens the monocles account choice: a paid monocles.net
        // account, or a free account on a public provider (MagicCreateActivity,
        // reached from there with the invite URI passed on).
        binding.useCim.setOnClickListener(v -> {
            final Intent intent = new Intent(this, de.monocles.chat.RegisterMonoclesActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            addInviteUri(intent);
            startActivity(intent);
        });
        binding.useOwnProvider.setOnClickListener(v -> {
            List<Account> accounts = xmppConnectionService.getAccounts();
            Intent intent = new Intent(this, EditAccountActivity.class);
            intent.putExtra(EditAccountActivity.EXTRA_FORCE_REGISTER, true);
            if (accounts.size() == 1) {
                intent.putExtra("jid", accounts.get(0).getJid().asBareJid().toString());
                intent.putExtra("init", true);
            } else if (accounts.size() >= 1) {
                intent = new Intent(this, ManageAccountActivity.class);
            }
            addInviteUri(intent);
            startActivity(intent);
        });
    }

    public void addInviteUri(Intent intent) {
        StartConversationActivity.addInviteUri(intent, getIntent());
    }

    public static void launch(AppCompatActivity activity) {
        Intent intent = new Intent(activity, PickServerActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0);
    }
}
