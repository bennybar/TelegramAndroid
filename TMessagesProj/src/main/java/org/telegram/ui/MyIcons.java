package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;

// "Messages-style icon": a green speech-bubble launcher icon (alias org.telegram.messenger.MyBubbleIcon in the
// app manifest). Switching disables Telegram's own icon aliases, and vice versa (LauncherIconController hooks).
public class MyIcons {

    private static ComponentName bubbleAlias(Context context) {
        return new ComponentName(context.getPackageName(), "org.telegram.messenger.MyBubbleIcon");
    }

    public static boolean bubbleEnabled() {
        Context context = ApplicationLoader.applicationContext;
        return context.getPackageManager().getComponentEnabledSetting(bubbleAlias(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    public static void setBubbleEnabled(boolean enabled) {
        Context context = ApplicationLoader.applicationContext;
        if (enabled) {
            PackageManager pm = context.getPackageManager();
            for (LauncherIconController.LauncherIcon icon : LauncherIconController.LauncherIcon.values()) {
                pm.setComponentEnabledSetting(icon.getComponentName(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
            pm.setComponentEnabledSetting(bubbleAlias(context), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        } else {
            LauncherIconController.setIcon(LauncherIconController.LauncherIcon.DEFAULT);
        }
    }

    // LauncherIconController.setIcon hook: picking one of Telegram's icons turns the bubble icon off.
    public static void disableBubble() {
        Context context = ApplicationLoader.applicationContext;
        context.getPackageManager().setComponentEnabledSetting(bubbleAlias(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }
}
