package org.telegram.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;

import java.util.ArrayList;
import java.util.Date;

// iMessage-style time headers: a small "14:32" pill above a message sent more than an hour after the one before it
// (day headers already cover the first message of a day). Drawn as a chat list item decoration, so Telegram's
// message list and its date items stay untouched. Albums are skipped (their cells are laid out as one block).
public class MyTimeGaps extends RecyclerView.ItemDecoration {

    private static final int GAP_SECONDS = 3600;
    private static final int HEIGHT_DP = 30;

    private final ChatActivity chat;
    private final RectF rect = new RectF();

    public MyTimeGaps(ChatActivity chat) {
        this.chat = chat;
    }

    public static boolean enabled() {
        return MessagesController.getGlobalMainSettings().getBoolean("timeGapHeaders", false);
    }

    public static void toggle() {
        MessagesController.getGlobalMainSettings().edit().putBoolean("timeGapHeaders", !enabled()).apply();
    }

    // The message, if a time header goes above this cell.
    private MessageObject headerFor(View view) {
        if (!enabled() || !(view instanceof ChatMessageCell)) {
            return null;
        }
        MessageObject message = ((ChatMessageCell) view).getMessageObject();
        if (message == null || message.getGroupId() != 0) {
            return null;
        }
        ArrayList<MessageObject> messages = chat.messages; // newest first
        int index = messages.indexOf(message);
        if (index < 0 || index + 1 >= messages.size()) {
            return null;
        }
        MessageObject older = messages.get(index + 1);
        if (older.type == MessageObject.TYPE_DATE || older.messageOwner == null || message.messageOwner == null) {
            return null;
        }
        return message.messageOwner.date - older.messageOwner.date >= GAP_SECONDS ? message : null;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
        if (headerFor(view) != null) {
            outRect.top = AndroidUtilities.dp(HEIGHT_DP);
        }
    }

    @Override
    public void onDrawOver(Canvas canvas, RecyclerView parent, RecyclerView.State state) {
        if (!enabled()) {
            return;
        }
        Paint textPaint = Theme.chat_actionTextPaint;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            MessageObject message = headerFor(child);
            if (message == null) {
                continue;
            }
            String text = LocaleController.getInstance().getFormatterDay().format(new Date(message.messageOwner.date * 1000L));
            float oldSize = textPaint.getTextSize();
            textPaint.setTextSize(AndroidUtilities.dp(12));
            float width = textPaint.measureText(text) + AndroidUtilities.dp(16);
            float centerY = child.getY() - AndroidUtilities.dp(HEIGHT_DP) / 2f;
            rect.set((parent.getWidth() - width) / 2f, centerY - AndroidUtilities.dp(10), (parent.getWidth() + width) / 2f, centerY + AndroidUtilities.dp(10));
            int oldAlpha = Theme.chat_actionBackgroundPaint.getAlpha();
            Theme.chat_actionBackgroundPaint.setAlpha((int) (oldAlpha * child.getAlpha()));
            canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, Theme.chat_actionBackgroundPaint);
            Theme.chat_actionBackgroundPaint.setAlpha(oldAlpha);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            canvas.drawText(text, rect.left + AndroidUtilities.dp(8), rect.centerY() - (fm.ascent + fm.descent) / 2, textPaint);
            textPaint.setTextSize(oldSize);
        }
    }
}
