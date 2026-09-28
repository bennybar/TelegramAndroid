package org.telegram.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.Layout;
import android.text.TextPaint;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;

// iMessage-style chat list: blue unread dot on the left instead of count badges, time followed by a chevron.
// Hooked into DialogCell; the cell is marked by its shifted avatarStart.
public class MyIMessageStyle {

    private static final int STYLED_AVATAR_START = 23;      // stock 11: leaves room for the dot on the left
    private static final int STYLED_PADDING_START = 84;     // stock 72
    private static final int CHEVRON_SPACE_DP = 14;

    private static Paint dotPaint;
    private static Paint chevronPaint;
    private static final Path chevron = new Path();

    public static boolean enabled() {
        return MessagesController.getGlobalMainSettings().getBoolean("iMessageChatList", false);
    }

    public static void setEnabled(boolean value) {
        MessagesController.getGlobalMainSettings().edit().putBoolean("iMessageChatList", value).apply();
        if (value) {
            SharedConfig.setUseThreeLinesLayout(true); // name + two-line grey preview
        }
    }

    // DialogCell constructor hook. Only the main chats list (cells with a DialogsActivity) gets the style.
    public static void applyToCell(DialogCell cell, DialogsActivity fragment) {
        if (fragment != null && enabled() && !LocaleController.isRTL) {
            cell.avatarStart = STYLED_AVATAR_START;
            cell.messagePaddingStart = STYLED_PADDING_START;
        }
    }

    public static boolean isStyled(DialogCell cell) {
        return cell.avatarStart == STYLED_AVATAR_START;
    }

    // How far to move the time left so the chevron fits after it.
    public static int timeShift(DialogCell cell) {
        return isStyled(cell) ? AndroidUtilities.dp(CHEVRON_SPACE_DP) : 0;
    }

    // Drawn right after the time; the canvas is already translated to the time's origin.
    public static void drawChevron(DialogCell cell, Canvas canvas, Layout timeLayout, TextPaint timePaint) {
        if (!isStyled(cell)) {
            return;
        }
        if (chevronPaint == null) {
            chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            chevronPaint.setStyle(Paint.Style.STROKE);
            chevronPaint.setStrokeWidth(AndroidUtilities.dp(1.6f));
            chevronPaint.setStrokeCap(Paint.Cap.ROUND);
            chevronPaint.setStrokeJoin(Paint.Join.ROUND);
        }
        chevronPaint.setColor(timePaint.getColor());
        float x = timeLayout.getWidth() + AndroidUtilities.dp(6);
        float y = timeLayout.getHeight() / 2f;
        float h = AndroidUtilities.dp(4);
        chevron.reset();
        chevron.moveTo(x, y - h);
        chevron.lineTo(x + h * 0.8f, y);
        chevron.lineTo(x, y + h);
        canvas.drawPath(chevron, chevronPaint);
    }

    // Replaces the count badge: a dot left of the avatar, blue for unread, grey when muted.
    public static void drawUnreadDot(DialogCell cell, Canvas canvas, boolean muted) {
        if (dotPaint == null) {
            dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        dotPaint.setColor(Theme.getColor(muted ? Theme.key_chats_unreadCounterMuted : Theme.key_chats_unreadCounter));
        canvas.drawCircle(AndroidUtilities.dp(11.5f), cell.avatarImage.getCenterY(), AndroidUtilities.dp(5), dotPaint);
    }
}
