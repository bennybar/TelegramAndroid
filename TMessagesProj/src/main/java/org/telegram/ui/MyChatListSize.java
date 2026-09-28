package org.telegram.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;

// Chat list text size: scales name/preview/time fonts and grows the row height to match.
// Text positions inside the row stay stock; up to 115% the scaled text still fits between them.
public class MyChatListSize {

    public static final String[] LABELS = {"90%", "100%", "105%", "110%", "115%"};
    private static final float[] SCALES = {0.9f, 1f, 1.05f, 1.1f, 1.15f};
    public static final String[] AVATAR_LABELS = {"80%", "90%", "100%", "110%", "120%"};
    private static final float[] AVATAR_SCALES = {0.8f, 0.9f, 1f, 1.1f, 1.2f};

    public static boolean unreadDot() {
        return MessagesController.getGlobalMainSettings().getBoolean("chatListUnreadDot", false);
    }

    public static boolean dividers() {
        return MessagesController.getGlobalMainSettings().getBoolean("chatListDividers", false);
    }

    public static void toggle(String key) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(key, !MessagesController.getGlobalMainSettings().getBoolean(key, false)).apply();
    }

    private static Paint dotPaint;

    // Replaces the count badge: a dot outside the photo, blue for unread, grey when muted.
    public static void drawUnreadDot(DialogCell cell, Canvas canvas, boolean muted) {
        if (dotPaint == null) {
            dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        dotPaint.setColor(Theme.getColor(muted ? Theme.key_chats_unreadCounterMuted : Theme.key_chats_unreadCounter));
        float x = LocaleController.isRTL ? cell.getMeasuredWidth() - AndroidUtilities.dp(11.5f) : AndroidUtilities.dp(11.5f);
        canvas.drawCircle(x, cell.avatarImage.getCenterY(), AndroidUtilities.dp(5), dotPaint);
    }

    public static int avatarIndex() {
        int index = MessagesController.getGlobalMainSettings().getInt("chatListAvatarSize", 2);
        return Math.max(0, Math.min(AVATAR_SCALES.length - 1, index));
    }

    public static void setAvatarIndex(int index) {
        MessagesController.getGlobalMainSettings().edit().putInt("chatListAvatarSize", index).apply();
    }

    private static float avatarScale() {
        return AVATAR_SCALES[avatarIndex()];
    }

    // buildLayout hook, right after the stock avatar rect is set: resize around its center, keeping the
    // outer edge (left, or right in RTL) where it was. The text column was moved by applyToCell to match.
    public static void scaleAvatar(RectF rect) {
        float s = avatarScale();
        if (s == 1f) {
            return;
        }
        float size = rect.width() * s;
        float centerY = rect.centerY();
        if (LocaleController.isRTL) {
            rect.left = rect.right - size;
        } else {
            rect.right = rect.left + size;
        }
        if (s > 1f) {
            rect.bottom = rect.top + size; // grow down; applyToCell made the row taller by the same amount
        } else {
            rect.top = centerY - size / 2f;
            rect.bottom = centerY + size / 2f;
        }
    }

    public static int index() {
        int index = MessagesController.getGlobalMainSettings().getInt("chatListTextSize", 1);
        return Math.max(0, Math.min(SCALES.length - 1, index));
    }

    public static void setIndex(int index) {
        MessagesController.getGlobalMainSettings().edit().putInt("chatListTextSize", index).apply();
    }

    private static float scale() {
        return SCALES[index()];
    }

    // DialogCell constructor hook: taller (or shorter) rows for the scaled text.
    public static void applyToCell(DialogCell cell) {
        float extra = scale() - 1f;
        if (extra != 0) {
            cell.heightDefault = Math.round(cell.heightDefault + extra * 36);    // name + one preview line
            cell.heightThreeLines = Math.round(cell.heightThreeLines + extra * 44); // name + two preview lines
        }
        if (unreadDot()) {
            // Room for the dot outside the photo.
            cell.avatarStart += 12;
            cell.messagePaddingStart += 12;
        }
        float avatar = avatarScale();
        if (avatar != 1f) {
            // Text starts after the avatar: move it by the avatar's growth (56dp two-line avatar as reference).
            cell.messagePaddingStart = Math.round(cell.messagePaddingStart + 56 * (avatar - 1f));
            // Keep the stock gap above and below the avatar (11dp two-line, 9dp three-line).
            cell.heightDefault = Math.max(cell.heightDefault, Math.round(56 * avatar) + 22);
            cell.heightThreeLines = Math.max(cell.heightThreeLines, Math.round(52 * avatar) + 18);
        }
    }

    // DialogCell buildLayout hook, right after the stock sizes are set.
    public static void scalePaints() {
        float s = scale();
        if (s == 1f) {
            return;
        }
        Theme.dialogs_namePaint[0].setTextSize(AndroidUtilities.dp(17 * s));
        Theme.dialogs_nameEncryptedPaint[0].setTextSize(AndroidUtilities.dp(17 * s));
        Theme.dialogs_messagePaint[0].setTextSize(AndroidUtilities.dp(16 * s));
        Theme.dialogs_messagePrintingPaint[0].setTextSize(AndroidUtilities.dp(16 * s));
        Theme.dialogs_namePaint[1].setTextSize(AndroidUtilities.dp(16 * s));
        Theme.dialogs_nameEncryptedPaint[1].setTextSize(AndroidUtilities.dp(16 * s));
        Theme.dialogs_messagePaint[1].setTextSize(AndroidUtilities.dp(15 * s));
        Theme.dialogs_messagePrintingPaint[1].setTextSize(AndroidUtilities.dp(15 * s));
        Theme.dialogs_messageNamePaint.setTextSize(AndroidUtilities.dp(14 * s));
        Theme.dialogs_timePaint.setTextSize(AndroidUtilities.dp(12 * s));
        Theme.dialogs_timePaintBold.setTextSize(AndroidUtilities.dp(12 * s));
        Theme.dialogs_timePaintBoldAccent.setTextSize(AndroidUtilities.dp(12 * s));
    }
}
