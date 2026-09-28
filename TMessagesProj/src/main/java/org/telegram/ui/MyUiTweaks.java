package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

// Look toggles: Material 3 bottom bar, hidden Profile tab, compact search bar, tinted pinned chats.
public class MyUiTweaks {

    private static boolean pref(String key) {
        // Read straight from the prefs file: some of these are needed before MessagesController exists.
        return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).getBoolean(key, false);
    }

    public static void toggle(String key) {
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).edit().putBoolean(key, !pref(key)).apply();
    }

    public static boolean md3TabBar() {
        return pref("md3TabBar");
    }

    public static boolean hideProfileTab() {
        return pref("hideProfileTab");
    }

    public static boolean compactSearch() {
        return pref("compactSearch");
    }

    public static boolean pinnedTint() {
        return pref("pinnedTint");
    }

    // ---- Material 3 bottom bar ----

    private static final Set<View> mainTabs = Collections.newSetFromMap(new WeakHashMap<>());
    private static final RectF indicator = new RectF();
    private static Paint indicatorPaint;

    private static int accent() {
        return Theme.getColor(Theme.key_glass_tabSelected);
    }

    private static int surfaceContainer() {
        return ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhite), accent(), 0.06f);
    }

    // MainTabsActivity hook: a full-width tonal bar (MD3 surface container) instead of the floating glass pill.
    public static void applyTabBar(MainTabsLayout tabsView, FrameLayout wrapper, View fadeView) {
        if (!md3TabBar()) {
            return;
        }
        wrapper.setBackground(new ColorDrawable(surfaceContainer())); // wrapper padding already covers the nav bar area
        tabsView.setBackground(null);
        tabsView.setMaxWidth(Integer.MAX_VALUE);
        tabsView.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));
        fadeView.setBackground(null);
    }

    // GlassTabView.createMainTab hook: MD3 layout, icon inside a 32dp-tall indicator and the label 4dp below it.
    public static void layoutMainTab(View tab, View icon, View label) {
        if (!md3TabBar()) {
            return;
        }
        mainTabs.add(tab);
        icon.setLayoutParams(LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 10, 0, 0));
        label.setLayoutParams(LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 41, 0, 0));
    }

    // GlassTabView.dispatchDraw hook: MD3 active indicator (64x32 pill behind the icon, growing in on selection)
    // instead of the full-tab glass highlight. Returns true when it handled the drawing.
    public static boolean drawMainTabIndicator(View tab, Canvas canvas, float selectedFactor, View icon) {
        if (!mainTabs.contains(tab)) {
            return false;
        }
        if (indicatorPaint == null) {
            indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        int color = ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhite), accent(), 0.22f);
        indicatorPaint.setColor(color);
        indicatorPaint.setAlpha((int) (Math.min(1f, selectedFactor * 1.5f) * 255));
        float cx = icon.getLeft() + icon.getWidth() / 2f;
        float cy = icon.getTop() + icon.getHeight() / 2f;
        float halfWidth = AndroidUtilities.dp(32) * (0.5f + 0.5f * selectedFactor);
        float halfHeight = AndroidUtilities.dp(16);
        indicator.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight);
        canvas.drawRoundRect(indicator, halfHeight, halfHeight, indicatorPaint);
        return true;
    }

    // ---- Compact search bar ----

    public static int searchFieldHeight() {
        return compactSearch() ? 40 : 48;
    }

    // ---- Tinted pinned chats ----

    private static Paint pinnedPaint;

    // DialogCell onDraw hook: a faint accent tint behind pinned rows (their 📌 time pill is dropped instead).
    public static void drawPinnedBackground(DialogCell cell, Canvas canvas) {
        if (!pinnedTint() || !cell.getIsPinned()) {
            return;
        }
        if (pinnedPaint == null) {
            pinnedPaint = new Paint();
        }
        pinnedPaint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_chats_unreadCounter), 0.07f));
        canvas.drawRect(0, 0, cell.getMeasuredWidth(), cell.getMeasuredHeight(), pinnedPaint);
    }

    public static boolean showPinnedPill(DialogCell cell) {
        return cell.getIsPinned() && !pinnedTint();
    }
}
