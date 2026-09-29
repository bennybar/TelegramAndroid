package org.telegram.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;

import java.util.ArrayList;
import java.util.WeakHashMap;

// iMessage touches drawn in the chat list, below the newest message:
// a "Read / Delivered" label under your last message, and an animated typing bubble while the other side types.
// ChatActivity reserves the space through extraBottomPadding() and calls drawBottomExtras() after drawing the list.
public class MyChatExtras {

    private static final int LABEL_BAND_DP = 20;
    private static final int TYPING_BAND_DP = 40;

    private static final WeakHashMap<ChatActivity, Boolean> typing = new WeakHashMap<>();
    private static final RectF rect = new RectF();
    private static Paint dotPaint;
    private static Paint bubblePaint;

    public static boolean readLabel() {
        return MessagesController.getGlobalMainSettings().getBoolean("readLabel", false);
    }

    public static boolean typingBubble() {
        return MessagesController.getGlobalMainSettings().getBoolean("typingBubble", false);
    }

    public static void toggle(String key) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(key, !MessagesController.getGlobalMainSettings().getBoolean(key, false)).apply();
    }

    // UPDATE_MASK_USER_PRINT hook.
    public static void updateTyping(ChatActivity chat, boolean isTyping) {
        typing.put(chat, isTyping && typingBubble());
    }

    private static boolean isTyping(ChatActivity chat) {
        Boolean value = typing.get(chat);
        return value != null && value;
    }

    // checkUi_chatListViewPaddings hook: room below the newest message (animated by Telegram when it changes).
    public static int extraBottomPadding(ChatActivity chat) {
        int dp = 0;
        if (readLabel()) {
            dp = LABEL_BAND_DP;
        }
        if (isTyping(chat)) {
            dp = Math.max(dp, TYPING_BAND_DP);
        }
        return AndroidUtilities.dp(dp);
    }

    // Chat list dispatchDraw hook.
    public static void drawBottomExtras(ChatActivity chat, ViewGroup list, Canvas canvas, ArrayList<MessageObject> messages) {
        if ((!readLabel() && !isTyping(chat)) || messages == null || messages.isEmpty()) {
            return;
        }
        MessageObject newest = messages.get(0);
        ChatMessageCell newestCell = null;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof ChatMessageCell && ((ChatMessageCell) child).getMessageObject() != null
                    && ((ChatMessageCell) child).getMessageObject().getId() == newest.getId()) {
                newestCell = (ChatMessageCell) child;
                break;
            }
        }
        if (newestCell == null) {
            return; // scrolled away from the bottom
        }
        float top = newestCell.getY() + newestCell.getHeight() + AndroidUtilities.dp(2);
        if (readLabel() && newest.isOut() && !newest.isSendError()) {
            String text = newest.isSending() ? "Sending…" : newest.isUnread() ? "Delivered" : "Read";
            Paint textPaint = Theme.chat_actionTextPaint;
            float oldSize = textPaint.getTextSize();
            textPaint.setTextSize(AndroidUtilities.dp(11));
            float width = textPaint.measureText(text) + AndroidUtilities.dp(14);
            float right = list.getWidth() - AndroidUtilities.dp(10);
            rect.set(right - width, top, right, top + AndroidUtilities.dp(17));
            canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, Theme.chat_actionBackgroundPaint);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            canvas.drawText(text, rect.left + AndroidUtilities.dp(7), rect.centerY() - (fm.ascent + fm.descent) / 2, textPaint);
            textPaint.setTextSize(oldSize);
        }
        if (isTyping(chat)) {
            if (bubblePaint == null) {
                bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            }
            bubblePaint.setColor(Theme.getColor(Theme.key_chat_inBubble));
            float left = AndroidUtilities.dp(10);
            rect.set(left, top + AndroidUtilities.dp(2), left + AndroidUtilities.dp(58), top + AndroidUtilities.dp(34));
            canvas.drawRoundRect(rect, AndroidUtilities.dp(16), AndroidUtilities.dp(16), bubblePaint);
            // Three dots pulsing in turn, like iMessage.
            long now = SystemClock.uptimeMillis();
            int dotColor = Theme.getColor(Theme.key_chat_inTimeText);
            for (int i = 0; i < 3; i++) {
                float phase = ((now - i * 160L) % 1200L) / 1200f;
                float pulse = phase < 0.5f ? phase * 2f : (1f - phase) * 2f;
                dotPaint.setColor(dotColor);
                dotPaint.setAlpha((int) (90 + 165 * pulse));
                float cx = rect.left + AndroidUtilities.dp(16) + i * AndroidUtilities.dp(13);
                canvas.drawCircle(cx, rect.centerY(), AndroidUtilities.dp(3.5f + 0.8f * pulse), dotPaint);
            }
            list.postInvalidateOnAnimation();
        }
    }
}
