package de.monocles.chat;

import android.content.Context;
import android.util.Log;
import eu.siacs.conversations.BuildConfig;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Config {

    public static final String LOGTAG = BuildConfig.APP_NAME;

    public static final String PROVIDER_URL = "https://data.xmpp.net/providers/v2/providers-A.json";

    public static class DOMAIN {

        // Servers pinned to the top of the provider picker and marked "recommended".
        // Deliberately empty: free accounts are only suggested from the XMPP
        // Providers A list, without favouring any server.
        public static final List<String> DOMAINS = Collections.emptyList();

        // don't use these servers in provider list
        public static final List<String> BLACKLISTED_DOMAINS = Arrays.asList();

        private static final SecureRandom RANDOM = new SecureRandom();

        /**
         * A random server from the XMPP Providers A list for a free account: the list already
         * loaded by ProviderService, or else the copy bundled with the app
         * (res/raw/providers_a.json). Null only if neither can be read — the user then picks a
         * server themselves.
         */
        public static String getRandomServer(final Context context) {
            List<String> providers = ProviderService.getProviders();
            if (providers.isEmpty() && context != null) {
                providers = ProviderService.readBundled(context);
            }
            if (providers.isEmpty()) {
                Log.d(LOGTAG, "MagicCreate: no provider list available");
                return null;
            }
            final String domain = providers.get(RANDOM.nextInt(providers.size()));
            Log.d(LOGTAG, "MagicCreate account on domain: " + domain);
            return domain;
        }
    }
}
