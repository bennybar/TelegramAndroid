package org.telegram.ui;

import android.util.TypedValue;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatUnreadCell;

import java.util.ArrayList;
import java.util.WeakHashMap;

// The "Unread messages" divider, made quieter: a small "3 new messages" pill styled like Telegram's date headers
// instead of the full-width blue bar. It goes away about 2 seconds after you reach the bottom of the chat (by then
// everything below it has been seen); Telegram keeps it until the chat is reopened.
public class MyUnread {

    private static final long DELAY_MS = 2000;
    private static final WeakHashMap<ChatActivity, Runnable> pending = new WeakHashMap<>();

    // ChatActivity bind hook for the divider cell.
    public static void bindCell(ChatUnreadCell cell, ArrayList<MessageObject> messages, MessageObject divider, Theme.ResourcesProvider resourcesProvider) {
        // Messages are newest first, so everything before the divider is new.
        int index = divider == null ? -1 : messages.indexOf(divider);
        int count = 0;
        for (int i = 0; i < index; i++) {
            if (messages.get(i).type != MessageObject.TYPE_DATE) {
                count++;
            }
        }
        cell.getBackgroundLayout().setVisibility(View.INVISIBLE);
        cell.getImageView().setVisibility(View.GONE);
        cell.setText(count > 0 ? LocaleController.formatPluralString("NewMessages", count) : LocaleController.getString(org.telegram.messenger.R.string.UnreadMessages));
        cell.getTextView().setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        cell.getTextView().setTextColor(Theme.getColor(Theme.key_chat_serviceText, resourcesProvider));
        cell.getTextView().setIncludeFontPadding(false);
        cell.getTextView().setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(4), AndroidUtilities.dp(10), AndroidUtilities.dp(4));
        cell.getTextView().setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), Theme.getColor(Theme.key_chat_serviceBackground, resourcesProvider)));
    }

    // ChatActivity.updateMessagesVisiblePart hook (runs on every scroll and layout).
    public static void update(ChatActivity chat, boolean hasDivider, boolean atBottom, Runnable removeDivider) {
        Runnable scheduled = pending.get(chat);
        if (!hasDivider || !atBottom) {
            if (scheduled != null) {
                AndroidUtilities.cancelRunOnUIThread(scheduled);
                pending.remove(chat);
            }
            return;
        }
        if (scheduled == null) {
            Runnable run = () -> {
                pending.remove(chat);
                removeDivider.run();
            };
            pending.put(chat, run);
            AndroidUtilities.runOnUIThread(run, DELAY_MS);
        }
    }
}
