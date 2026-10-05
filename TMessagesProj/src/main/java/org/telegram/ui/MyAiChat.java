package org.telegram.ui;

import android.app.Activity;
import android.text.TextUtils;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.forkgram.ForkDialogs;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;

// AI in chats, with the OpenAI key from the AI tab. Secret chats are never sent.
// - Message menu "Ask AI": Explain / Summarize / Translate one message.
// - Chat menu "Ask this chat": a question answered from recent history, with [rN] links to the source messages.
//   History is read with the same paced requests as the summaries (AiSummarizer).
public class MyAiChat {

    public static final int OPTION_AI = 1001;
    public static final int MENU_ASK = 9402;
    private static final int ASK_DAYS = 30;

    private static final String[] ACTIONS = {"Explain", "Summarize", "Translate"};

    private static boolean hasKey() {
        return !AiSummarizer.prefs().getString("apiKey", "").isEmpty();
    }

    private static String text(MessageObject message) {
        return message == null || message.messageOwner == null || message.messageOwner.message == null ? "" : message.messageOwner.message.trim();
    }

    // The message whose text to use: in an album only one item carries the caption.
    public static MessageObject textMessage(MessageObject message, MessageObject.GroupedMessages group) {
        if (TextUtils.isEmpty(text(message)) && group != null) {
            MessageObject caption = group.findCaptionMessageObject();
            if (caption != null) {
                return caption;
            }
        }
        return message;
    }

    // fillMessageMenu hook: "Ask AI" above Delete, for messages with text or a caption.
    public static void addMenuItem(MessageObject message, long dialogId, ArrayList<Integer> icons, ArrayList<CharSequence> items, ArrayList<Integer> options) {
        if (DialogObject.isEncryptedDialog(dialogId) || TextUtils.isEmpty(text(message))) {
            return;
        }
        int index = options.indexOf(ChatActivity.OPTION_DELETE);
        if (index < 0) {
            index = options.size();
        }
        items.add(index, "Ask AI");
        options.add(index, OPTION_AI);
        icons.add(index, R.drawable.summary_stars);
    }

    // processSelectedOption hook.
    public static boolean handleOption(ChatActivity chat, int option, MessageObject message) {
        if (option != OPTION_AI || message == null) {
            return false;
        }
        Activity activity = chat.getParentActivity();
        if (activity == null) {
            return true;
        }
        if (!hasKey()) {
            BulletinFactory.of(chat).createErrorBulletin("Set your OpenAI API key in the AI tab first.").show();
            return true;
        }
        String text = text(message);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Ask AI");
        builder.setItems(ACTIONS, (dialog, which) -> {
            String system;
            if (which == 0) {
                system = "Explain this Telegram message clearly and briefly: what it means, its context, any slang, abbreviations or references. Write in the language the message is written in.";
            } else if (which == 1) {
                system = "Summarize this Telegram message in 2 to 5 short bullets starting with \"- \". Write in the language the message is written in.";
            } else {
                String target = text.matches("(?s).*[\\u0590-\\u05FF].*") ? "English" : "Hebrew";
                system = "Translate this Telegram message to " + target + ". Output only the translation.";
            }
            AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
            progress.show();
            AiSummarizer.askOnce(system, text, answer -> {
                progress.dismiss();
                showAnswer(chat, ACTIONS[which], answer, new ArrayList<>());
            }, error -> {
                progress.dismiss();
                showError(chat, error);
            });
        });
        chat.showDialog(builder.create());
        return true;
    }

    // ChatActivity header menu hook.
    public static void addHeaderItem(ActionBarMenuItem headerItem, long dialogId) {
        if (headerItem != null && !DialogObject.isEncryptedDialog(dialogId)) {
            headerItem.lazilyAddSubItem(MENU_ASK, R.drawable.summary_stars, "Ask this chat");
        }
    }

    // ChatActivity onItemClick hook.
    public static boolean onHeaderItem(ChatActivity chat, int id) {
        if (id != MENU_ASK) {
            return false;
        }
        Activity activity = chat.getParentActivity();
        if (activity == null) {
            return true;
        }
        if (!hasKey()) {
            BulletinFactory.of(chat).createErrorBulletin("Set your OpenAI API key in the AI tab first.").show();
            return true;
        }
        ForkDialogs.createFieldAlert(activity, "Ask this chat", "", question -> {
            if (!question.trim().isEmpty()) {
                ask(chat, question.trim());
            }
            return null;
        });
        return true;
    }

    private static void ask(ChatActivity chat, String question) {
        Activity activity = chat.getParentActivity();
        int account = chat.getCurrentAccount();
        ArrayList<Long> chats = new ArrayList<>();
        chats.add(chat.getDialogId());
        int since = ConnectionsManager.getInstance(account).getCurrentTime() - ASK_DAYS * 24 * 3600;
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(true);
        AiSummarizer[] asker = new AiSummarizer[1];
        progress.setOnCancelListener(d -> asker[0].cancel());
        asker[0] = new AiSummarizer(account, chats, since, new AiSummarizer.Callback() {
            @Override
            public void onProgress(String text) {
                progress.setMessage(text);
            }

            @Override
            public void onCollected(int chatCount, int messages, int approxTokens) {
                if (messages == 0) {
                    progress.dismiss();
                    BulletinFactory.of(chat).createErrorBulletin("No messages in the last " + ASK_DAYS + " days.").show();
                    return;
                }
                asker[0].send();
            }

            @Override
            public void onDone(String answer) {
                progress.dismiss();
                showAnswer(chat, question, answer, asker[0].refs);
            }

            @Override
            public void onError(String error) {
                progress.dismiss();
                showError(chat, error);
            }
        });
        asker[0].setQuestion(question);
        progress.show();
        asker[0].start();
    }

    private static void showAnswer(BaseFragment fragment, String title, String answer, ArrayList<AiSummarizer.Ref> refs) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        BottomSheet[] sheet = new BottomSheet[1];
        BottomSheet.Builder builder = new BottomSheet.Builder(activity);
        builder.setTitle(title, true);
        builder.setCustomView(AiSummaryActivity.summaryView(activity, answer, refs, ref -> {
            if (sheet[0] != null) {
                sheet[0].dismiss();
            }
            AiSummaryActivity.openMessage(fragment, ref);
        }));
        sheet[0] = builder.create();
        fragment.showDialog(sheet[0]);
    }

    private static void showError(BaseFragment fragment, String error) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("AI failed");
        builder.setMessage(error);
        builder.setPositiveButton("OK", null);
        fragment.showDialog(builder.create());
    }
}
