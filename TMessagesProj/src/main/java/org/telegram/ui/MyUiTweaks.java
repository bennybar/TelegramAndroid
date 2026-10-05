package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

// Look toggles: Material 3 bottom bar, hidden Profile tab, compact search bar, tinted pinned chats, emoji panel.
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

    // Neutral MD3 surface container: the background a few percent darker (lighter in dark themes), no accent cast.
    private static int surfaceContainer() {
        int background = Theme.getColor(Theme.key_windowBackgroundWhite);
        return ColorUtils.blendARGB(background, Theme.isCurrentThemeDark() ? 0xFFFFFFFF : 0xFF000000, Theme.isCurrentThemeDark() ? 0.07f : 0.04f);
    }

    // MainTabsActivity hook: a full-width tonal bar (MD3 surface container) instead of the floating glass pill.
    public static void applyTabBar(MainTabsLayout tabsView, FrameLayout wrapper, View fadeView) {
        if (!md3TabBar()) {
            return;
        }
        wrapper.setBackground(new ColorDrawable(surfaceContainer())); // wrapper padding already covers the nav bar area
        tabsView.setBackground(null);
        tabsView.setMaxWidth(Integer.MAX_VALUE);
        tabsView.setPadding(0, 0, 0, 0);
        // Icon-only bar: 56dp instead of the 72dp glass bar.
        tabsView.getLayoutParams().height = AndroidUtilities.dp(56);
        fadeView.setBackground(null);
    }

    // GlassTabView.createMainTab hook: icon-only MD3 tab, the icon centered in its 32dp-tall indicator.
    public static void layoutMainTab(View tab, View icon, View label) {
        if (!md3TabBar()) {
            return;
        }
        mainTabs.add(tab);
        icon.setLayoutParams(LayoutHelper.createFrame(24, 24, Gravity.CENTER));
        label.setVisibility(View.GONE);
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
        int color = ColorUtils.blendARGB(surfaceContainer(), accent(), 0.16f);
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

    // DialogsActivity hook: EditText's built-in ~48dp minimum height would push the text off-center at 40dp.
    public static void applyCompactSearch(TextView editText) {
        if (!compactSearch()) {
            return;
        }
        editText.setMinHeight(0);
        editText.setMinimumHeight(0);
        // Measured on device: in the 40dp field the text sat ~4dp above the pill's visual center
        // (the glass pill is inset and shadowed unevenly), so move the text down by that much.
        editText.setTranslationY(AndroidUtilities.dp(4));
    }

    // LocaleController.stringForMessageListDate hook: "Yesterday" in the chat list instead of a time or weekday.
    public static boolean yesterdayLabel() {
        return pref("yesterdayLabel");
    }

    public static String yesterday() {
        String text = org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.Yesterday);
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // DialogCell hook: muted chats drawn faded, so unmuted ones stand out without re-sorting.
    public static boolean dimMuted() {
        return pref("dimMuted");
    }

    public static void saveRow(Canvas canvas, View cell, boolean muted) {
        if (muted && dimMuted()) {
            canvas.saveLayerAlpha(0, 0, cell.getWidth(), cell.getHeight(), 125);
        } else {
            canvas.save();
        }
    }

    public static boolean pinEmojiSearch() {
        return pref("pinEmojiSearch");
    }

    public static boolean hideEmojiTitles() {
        return pref("hideEmojiTitles");
    }

    // EmojiView hook: null collapses the "Emoji & People" / "Recently used" row to 1px. The rows stay, so the
    // category tabs still jump to the right place.
    public static String emojiSectionTitle(String title) {
        return hideEmojiTitles() ? null : title;
    }

    public static final String[] EMOJI_HEIGHT_LABELS = {"100%", "90%", "80%", "70%", "60%"};
    private static final float[] EMOJI_HEIGHTS = {1f, 0.9f, 0.8f, 0.7f, 0.6f};

    public static int emojiHeightIndex() {
        int index = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).getInt("emojiPanelHeight", 0);
        return Math.max(0, Math.min(EMOJI_HEIGHTS.length - 1, index));
    }

    public static void setEmojiHeightIndex(int index) {
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).edit().putInt("emojiPanelHeight", index).apply();
    }

    // ChatActivityEnterView hook: the emoji/GIF/sticker panel as a share of the keyboard's height.
    public static int emojiPanelHeight(int height) {
        return (int) (height * EMOJI_HEIGHTS[emojiHeightIndex()]);
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
        int shift = MyChatListSize.rowShiftPx(); // drawn inside the row-spacing translation; cover the real row
        canvas.drawRect(0, -shift, cell.getMeasuredWidth(), cell.getMeasuredHeight() - shift, pinnedPaint);
    }

    public static boolean showPinnedPill(DialogCell cell) {
        return cell.getIsPinned() && !pinnedTint();
    }
}
