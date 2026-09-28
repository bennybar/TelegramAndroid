package org.telegram.messenger;

import android.content.Context;
import android.graphics.Typeface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

// "Use Google Sans": bundled Google Sans (assets/fonts/googlesans_*.ttf, subset to Latin + Hebrew + punctuation,
// SIL Open Font License) for bold and, where Android allows it, regular text.
public class MyFonts {

    public static boolean googleSans() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).getBoolean("googleSans", false);
    }

    public static void setGoogleSans(boolean value) {
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).edit().putBoolean("googleSans", value).commit();
    }

    // App start hook, before any text is drawn.
    public static void apply(Context context) {
        if (!context.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).getBoolean("googleSans", false)) {
            return;
        }
        Typeface regular;
        try {
            regular = Typeface.createFromAsset(context.getAssets(), "fonts/googlesans_regular.ttf");
            AndroidUtilities.mediumTypeface = Typeface.createFromAsset(context.getAssets(), "fonts/googlesans_medium.ttf");
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        // Most Telegram text sets no typeface and draws with the system default, so swap the default.
        // These are non-SDK hooks; if Android refuses them, regular text simply keeps the device font.
        try {
            Method setDefault = Typeface.class.getDeclaredMethod("setDefault", Typeface.class);
            setDefault.setAccessible(true);
            setDefault.invoke(null, regular);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        for (String name : new String[]{"DEFAULT", "SANS_SERIF"}) {
            try {
                Field field = Typeface.class.getDeclaredField(name);
                field.setAccessible(true);
                field.set(null, regular);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        try {
            Field map = Typeface.class.getDeclaredField("sSystemFontMap");
            map.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Typeface> fonts = (Map<String, Typeface>) map.get(null);
            if (fonts != null) {
                fonts.put("sans-serif", regular);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
