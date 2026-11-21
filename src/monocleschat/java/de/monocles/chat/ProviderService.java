package de.monocles.chat;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import eu.siacs.conversations.R;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.XmppActivity;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ProviderService extends AsyncTask<XmppActivity, Object, Boolean> {
    public static List<String> providers = new ArrayList<>();
    XmppConnectionService xmppConnectionService;
    private boolean mUseTor;
    private boolean mUseI2P;

    public ProviderService() {}

    public static List<String> getProviders() {
        final HashSet<String> provider = new HashSet<>();
        if (!providers.isEmpty()) {
            provider.addAll(providers);
        }
        return new ArrayList<>(provider);
    }

    @Override
    protected Boolean doInBackground(XmppActivity... activity) {
        StringBuilder jsonString = new StringBuilder();
        boolean isError = false;
        mUseTor = xmppConnectionService != null && xmppConnectionService.useTorToConnect();
        mUseI2P = xmppConnectionService != null && xmppConnectionService.useI2PToConnect();
        if (!mUseTor && mUseI2P) {
            // Do not update providers if all connections tunneled to I2P (via settings checkbox)
            // without Tor
            return true;
        }

        try {
            if (XmppActivity.staticXmppConnectionService != null
                    && XmppActivity.staticXmppConnectionService.getBooleanPreference(
                            "load_providers_list_external", R.bool.load_providers_list_external)
                    && XmppActivity.staticXmppConnectionService.hasInternetConnection()) {
                Log.d(
                        Config.LOGTAG,
                        "ProviderService: Updating provider list from " + Config.PROVIDER_URL);
                final InputStream is =
                        HttpConnectionManager.open(Config.PROVIDER_URL, mUseTor, mUseI2P);
                final BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonString.append(line);
                }
                is.close();
                reader.close();
            } else {
                Log.d(Config.LOGTAG, "ProviderService: Updating provider list from " + "local");
                final InputStream is =
                        XmppActivity.staticXmppConnectionService
                                .getResources()
                                .openRawResource(R.raw.providers_a);
                final BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonString.append(line);
                }
                is.close();
                reader.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
            isError = true;
        }

        try {
            getProviderFromJSON(jsonString.toString());
        } catch (Exception e) {
            e.printStackTrace();
            isError = true;
        }
        if (isError) {
            Log.d(Config.LOGTAG, "ProviderService: Updating provider list failed");
        }
        return !isError;
    }

    private void getProviderFromJSON(String json) {
        final List<String> parsed = parseProviders(json);
        if (!parsed.isEmpty()) {
            // replace, don't append: servers dropped from the A list must disappear
            providers = parsed;
        }
    }

    private static List<String> parseProviders(final String json) {
        final List<String> result = new ArrayList<>();
        final List<ProviderHelper> providersList =
                new Gson().fromJson(json, new TypeToken<List<ProviderHelper>>() {}.getType());
        if (providersList == null) {
            return result;
        }
        for (final ProviderHelper helper : providersList) {
            final String provider = helper == null ? null : helper.get_jid();
            if (provider != null
                    && !provider.isEmpty()
                    && !Config.DOMAIN.BLACKLISTED_DOMAINS.contains(provider)
                    && !result.contains(provider)) {
                Log.d(Config.LOGTAG, "ProviderService: Updating provider list. Adding " + provider);
                result.add(provider);
            }
        }
        return result;
    }

    /** The A list bundled with the app (res/raw/providers_a.json), read synchronously. */
    public static List<String> readBundled(final Context context) {
        final StringBuilder json = new StringBuilder();
        try (final BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                context.getResources().openRawResource(R.raw.providers_a)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
            return parseProviders(json.toString());
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "ProviderService: could not read the bundled provider list", e);
            return new ArrayList<>();
        }
    }
}
