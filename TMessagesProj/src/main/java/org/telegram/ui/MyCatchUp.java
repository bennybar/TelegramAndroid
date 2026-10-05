package org.telegram.ui;

import android.app.Activity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Cells.ChatUnreadCell;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;

// Right-to-left swipe on a chat: AI summary of its unread messages (or the last 24 hours) in a bottom sheet.
public class MyCatchUp {

    public static boolean marksRead() {
        return AiSummarizer.prefs().getBoolean("catchUpMarksRead", true);
    }

    public static void setMarksRead(boolean value) {
        AiSummarizer.prefs().edit().putBoolean("catchUpMarksRead", value).apply();
    }

    // Chats list swipe: unread messages per the dialog's read state, or the last 24 hours.
    public static void run(BaseFragment fragment, long dialogId, Runnable markRead) {
        int account = fragment.getCurrentAccount();
        TLRPC.Dialog dialog = MessagesController.getInstance(account).dialogs_dict.get(dialogId);
        boolean unread = dialog != null && dialog.unread_count > 0;
        int since = unread ? 0 : ConnectionsManager.getInstance(account).getCurrentTime() - 24 * 3600;
        String title = AiSummarizer.chatTitle(account, dialogId) + " · " + (unread ? dialog.unread_count + " unread" : "last 24 hours");
        start(fragment, dialogId, since, unread ? dialog.read_inbox_max_id : 0, title, markRead);
    }

    // ChatActivity hook: make the "Unread messages" divider offer a summary of everything below it.
    public static void bindUnreadCell(ChatActivity chat, ChatUnreadCell cell, long dialogId, ArrayList<MessageObject> messages, MessageObject divider) {
        // Center the label on the blue bar: the bar starts 7dp down a 40dp cell (center 20.5dp) while the text is
        // centered in the cell 1dp higher, and font padding pushed it further off.
        cell.getTextView().setIncludeFontPadding(false);
        cell.getTextView().setTranslationY(AndroidUtilities.dp(1));
        if (DialogObject.isEncryptedDialog(dialogId) || AiSummarizer.prefs().getString("apiKey", "").isEmpty()) {
            cell.setOnClickListener(null);
            cell.setClickable(false);
            return;
        }
        cell.setText(LocaleController.getString(R.string.UnreadMessages) + "  ·  ✦ Summarize");
        cell.setOnClickListener(v -> {
            // The divider sits just above the first unread message; the list is newest first.
            int index = messages.indexOf(divider);
            int lastReadId = index >= 0 && index + 1 < messages.size() ? messages.get(index + 1).getId() : 0;
            String title = AiSummarizer.chatTitle(chat.getCurrentAccount(), dialogId) + " · unread";
            start(chat, dialogId, 0, lastReadId, title, null);
        });
    }

    private static void start(BaseFragment fragment, long dialogId, int since, int minMessageId, String title, Runnable markRead) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        if (AiSummarizer.prefs().getString("apiKey", "").isEmpty()) {
            BulletinFactory.of(fragment).createErrorBulletin("Set your OpenAI API key in the AI tab first.").show();
            return;
        }
        int account = fragment.getCurrentAccount();

        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(true);
        ArrayList<Long> chats = new ArrayList<>();
        chats.add(dialogId);
        AiSummarizer[] summarizer = new AiSummarizer[1];
        progress.setOnCancelListener(d -> summarizer[0].cancel());

        summarizer[0] = new AiSummarizer(account, chats, since, new AiSummarizer.Callback() {
            @Override
            public void onProgress(String text) {
                progress.setMessage(text);
            }

            @Override
            public void onCollected(int chatCount, int messages, int approxTokens) {
                if (messages == 0) {
                    progress.dismiss();
                    BulletinFactory.of(fragment).createErrorBulletin("Nothing to summarize.").show();
                    return;
                }
                summarizer[0].send();
            }

            @Override
            public void onDone(String summary) {
                progress.dismiss();
                BottomSheet[] sheet = new BottomSheet[1];
                BottomSheet.Builder builder = new BottomSheet.Builder(activity);
                builder.setTitle(title, true);
                builder.setCustomView(AiSummaryActivity.summaryView(activity, summary, summarizer[0].refs, ref -> {
                    if (sheet[0] != null) {
                        sheet[0].dismiss();
                    }
                    AiSummaryActivity.openMessage(fragment, ref);
                }));
                sheet[0] = builder.create();
                fragment.showDialog(sheet[0]);
                if (markRead != null && marksRead()) {
                    markRead.run();
                }
            }

            @Override
            public void onError(String error) {
                progress.dismiss();
                AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                builder.setTitle("Catch-up failed");
                builder.setMessage(error);
                builder.setPositiveButton("OK", null);
                fragment.showDialog(builder.create());
            }
        });
        summarizer[0].setMinMessageId(minMessageId);
        progress.show();
        summarizer[0].start();
    }
}
