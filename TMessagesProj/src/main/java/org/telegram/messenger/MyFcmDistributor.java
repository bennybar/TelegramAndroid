package org.telegram.messenger;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.unifiedpush.android.embedded_fcm_distributor.EmbeddedDistributorReceiver;
import org.unifiedpush.android.embedded_fcm_distributor.Gateway;

// Built-in UnifiedPush distributor that delivers through Google Play Services (FCM), no ntfy needed.
// Play Services hands out a WebPush endpoint; FCM only accepts VAPID-signed pushes, which Telegram doesn't send,
// so Telegram is given the user's own relay (gateway/cloudflare-worker) that signs and forwards to FCM.
// The relay URL and its VAPID public key are a per-device setting, never built into the APK; the receiver stays
// disabled (and invisible as a distributor) until both are set.
public class MyFcmDistributor extends EmbeddedDistributorReceiver {

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mypush", Context.MODE_PRIVATE);
    }

    public static String relayUrl() {
        return prefs().getString("relayUrl", "");
    }

    public static String vapidKey() {
        return prefs().getString("vapidKey", "");
    }

    // Wall-clock time of the last push that reached the app, to tell "nothing is arriving" from a quiet day.
    public static void markPushReceived() {
        prefs().edit().putLong("lastPush", System.currentTimeMillis()).apply();
    }

    public static String lastPushText() {
        long last = prefs().getLong("lastPush", 0);
        return last == 0 ? "No push received yet." : "Last push received " + android.text.format.DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS) + ".";
    }

    public static boolean isConfigured() {
        return !relayUrl().isEmpty() && !vapidKey().isEmpty();
    }

    public static void setConfig(String relayUrl, String vapidKey) {
        String url = relayUrl.trim();
        if (!url.isEmpty() && !url.endsWith("/")) {
            url += "/";
        }
        prefs().edit().putString("relayUrl", url).putString("vapidKey", vapidKey.trim()).commit();
        Context context = ApplicationLoader.applicationContext;
        context.getPackageManager().setComponentEnabledSetting(
            new ComponentName(context, MyFcmDistributor.class),
            isConfigured() ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP);
    }

    @Override
    public Gateway getGateway() {
        return new Gateway() {
            @Override
            public String getVapid() {
                return vapidKey();
            }

            @Override
            public String getEndpoint(String token) {
                return relayUrl() + "fcm/" + token;
            }
        };
    }
}
