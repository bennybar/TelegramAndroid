package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;

// "Messages-style icon": a speech-bubble launcher icon, green or Telegram blue (aliases MyBubbleIcon /
// MyBubbleBlueIcon in the app manifest). Switching disables Telegram's own icon aliases, and vice versa
// (LauncherIconController hooks).
public class MyIcons {

    private static ComponentName alias(Context context, boolean blue) {
        return new ComponentName(context.getPackageName(), blue ? "org.telegram.messenger.MyBubbleBlueIcon" : "org.telegram.messenger.MyBubbleIcon");
    }

    private static boolean isOn(Context context, boolean blue) {
        return context.getPackageManager().getComponentEnabledSetting(alias(context, blue)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    public static boolean bubbleEnabled() {
        Context context = ApplicationLoader.applicationContext;
        return isOn(context, false) || isOn(context, true);
    }

    public static boolean blue() {
        return MessagesController.getGlobalMainSettings().getBoolean("bubbleIconBlue", false);
    }

    public static void setBlue(boolean value) {
        MessagesController.getGlobalMainSettings().edit().putBoolean("bubbleIconBlue", value).apply();
        if (bubbleEnabled()) {
            setBubbleEnabled(true); // swap to the other color right away
        }
    }

    public static void setBubbleEnabled(boolean enabled) {
        Context context = ApplicationLoader.applicationContext;
        if (enabled) {
            PackageManager pm = context.getPackageManager();
            for (LauncherIconController.LauncherIcon icon : LauncherIconController.LauncherIcon.values()) {
                pm.setComponentEnabledSetting(icon.getComponentName(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
            boolean blue = blue();
            pm.setComponentEnabledSetting(alias(context, blue), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(alias(context, !blue), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        } else {
            LauncherIconController.setIcon(LauncherIconController.LauncherIcon.DEFAULT);
        }
    }

    // LauncherIconController.setIcon hook: picking one of Telegram's icons turns the bubble icons off.
    public static void disableBubble() {
        Context context = ApplicationLoader.applicationContext;
        PackageManager pm = context.getPackageManager();
        pm.setComponentEnabledSetting(alias(context, false), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        pm.setComponentEnabledSetting(alias(context, true), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }
}
