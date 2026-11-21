package eu.siacs.conversations.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OAuthAuthorization;

/**
 * Receives {@code <applicationId>://oauth-redirect} from the browser at the end of an OAuth login
 * and hands it to the account screen.
 *
 * <p>This activity is exported, so any app or web page can start it. It therefore only acts on a
 * redirect whose {@code state} this process handed to a browser and has not redeemed yet;
 * everything else is dropped without opening any other screen.
 */
public class OAuthRedirectActivity extends AppCompatActivity {

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Intent intent = getIntent();
        final Uri data = intent == null ? null : intent.getData();
        final Jid account;
        if (OAuthAuthorization.isExpectedRedirect(data)) {
            account = OAuthAuthorization.accountFromState(data.getQueryParameter("state"));
        } else {
            account = null;
        }
        if (account == null) {
            Log.d(Config.LOGTAG, "ignoring unexpected oauth redirect");
            Toast.makeText(this, R.string.could_not_process_oauth_redirect, Toast.LENGTH_LONG)
                    .show();
            finish();
            return;
        }
        final Intent editAccountIntent = new Intent(this, EditAccountActivity.class);
        editAccountIntent.putExtra("jid", account.asBareJid().toString());
        editAccountIntent.setData(data);
        editAccountIntent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(editAccountIntent);
        finish();
    }
}
