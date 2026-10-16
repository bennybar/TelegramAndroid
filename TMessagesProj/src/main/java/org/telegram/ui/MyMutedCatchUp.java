package org.telegram.ui;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.HashMap;

// The Catch-up page of the AI tab: muted groups and channels with unread messages. Tap one, or "Catch up on all"
// (the 10 most recently active), for a summary of their unread messages (newest 200 per chat) with links back; it
// runs in the background (MyWorkService) and opens in the Digest's result screen, whose Mark as read covers exactly
// what was summarized. Ending away from this page posts a notification that opens the result.
public class MyMutedCatchUp {

    private static final int MAX_CHATS = 10;
    private static final int MAX_PER_CHAT = 200;
    private static final String CHANNEL_DONE = "my_catchup_done";
    private static final String ACTION_OPEN = "org.telegram.ui.MyMutedCatchUp.OPEN";
    private static final int NOTIFICATION_ID = 0x7E7D00;
    private static final String RESULT_KEY = "lastCatchUp";

    private static String stage; // the running catch-up's step, null when idle
    private static Page page;

    public static boolean isRunning() {
        return stage != null;
    }

    // Muted groups and channels with unread messages, most recently active first.
    static ArrayList<TLRPC.Dialog> mutedUnread(int account) {
        MessagesController controller = MessagesController.getInstance(account);
        ArrayList<TLRPC.Dialog> out = new ArrayList<>();
        for (TLRPC.Dialog dialog : controller.getAllDialogs()) {
            if (dialog.id < 0 && dialog.unread_count > 0 && controller.isDialogMuted(dialog.id, 0)) {
                out.add(dialog);
            }
        }
        java.util.Collections.sort(out, (a, b) -> Integer.compare(b.last_message_date, a.last_message_date));
        return out;
    }

    public static String buttonLabel() {
        int count = Math.min(MAX_CHATS, mutedUnread(UserConfig.selectedAccount).size());
        return count == 0 ? "⏪  Nothing to catch up on" : "⏪  Catch up on " + (count == 1 ? "1 chat" : "all " + count + " chats");
    }

    // ---- The page ----

    public static class Page extends ScrollView {

        private final BaseFragment fragment;
        private final LinearLayout content;
        private final Runnable onStateChanged;

        public Page(BaseFragment fragment, Runnable onStateChanged) {
            super(fragment.getContext());
            this.fragment = fragment;
            this.onStateChanged = onStateChanged;
            content = new LinearLayout(getContext());
            content.setOrientation(LinearLayout.VERTICAL);
            addView(content);
            setClipToPadding(false);
            rebuild();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            page = this;
            rebuild();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (page == this) {
                page = null;
            }
        }

        boolean isOnScreen() {
            return isShown() && !ApplicationLoader.mainInterfacePaused && getGlobalVisibleRect(new Rect());
        }

        public void rebuild() {
            Context context = getContext();
            content.removeAllViews();
            ArrayList<TLRPC.Dialog> chats = mutedUnread(fragment.getCurrentAccount());
            TextView intro = hint(context, stage != null ? stage :
                "Muted groups and channels with unread messages. Tap one, or catch up on the " + MAX_CHATS + " most recent below. Up to " + MAX_PER_CHAT + " unread messages per chat are read.");
            content.addView(intro);
            if (chats.isEmpty()) {
                content.addView(hint(context, "No muted chats with unread messages."));
            } else {
                LinearLayout list = new LinearLayout(context);
                list.setOrientation(LinearLayout.VERTICAL);
                list.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                for (TLRPC.Dialog dialog : chats) {
                    list.addView(row(context, dialog));
                }
                content.addView(list);
            }
            AiSummaryActivity.Digest last = AiSummaryActivity.Digest.load(RESULT_KEY);
            if (last != null) {
                TextView open = new TextView(context);
                open.setText("Open the last catch-up (" + AiSummaryActivity.whenText(last.time) + ") ›");
                open.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                open.setTypeface(AndroidUtilities.bold());
                open.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
                open.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(14), AndroidUtilities.dp(21), AndroidUtilities.dp(14));
                open.setOnClickListener(v -> fragment.presentFragment(new AiSummaryActivity.ResultActivity(last)));
                content.addView(open);
            }
            onStateChanged.run();
        }

        private View row(Context context, TLRPC.Dialog dialog) {
            FrameLayout row = new FrameLayout(context);
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            MessagesController controller = MessagesController.getInstance(fragment.getCurrentAccount());
            TLObject peer = controller.getChat(-dialog.id);
            BackupImageView avatar = new BackupImageView(context);
            avatar.setRoundRadius(AndroidUtilities.dp(18));
            if (peer != null) {
                AvatarDrawable avatarDrawable = new AvatarDrawable();
                avatarDrawable.setInfo(fragment.getCurrentAccount(), peer);
                avatar.setForUserOrChat(peer, avatarDrawable);
            }
            row.addView(avatar, LayoutHelper.createFrame(36, 36, Gravity.LEFT | Gravity.CENTER_VERTICAL, 18, 8, 0, 8));

            TextView name = new TextView(context);
            name.setText(AiSummarizer.chatTitle(fragment.getCurrentAccount(), dialog.id));
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 68, 8, 90, 0));

            TextView sub = new TextView(context);
            sub.setText(dialog.unread_count + " unread");
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            sub.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            row.addView(sub, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 68, 29, 90, 0));

            TextView action = new TextView(context);
            action.setText("Catch up ›");
            action.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            action.setTypeface(AndroidUtilities.bold());
            action.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            row.addView(action, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 18, 0));
            row.setMinimumHeight(AndroidUtilities.dp(52));
            ArrayList<TLRPC.Dialog> one = new ArrayList<>();
            one.add(dialog);
            row.setOnClickListener(v -> run(fragment, one));
            return row;
        }

        private TextView hint(Context context, String text) {
            TextView view = new TextView(context);
            view.setText(text);
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            view.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(12), AndroidUtilities.dp(21), AndroidUtilities.dp(12));
            return view;
        }
    }

    // ---- Running, in the background ----

    // The page's bottom button: the most recently active muted chats with unread messages.
    public static void runAll(BaseFragment fragment) {
        ArrayList<TLRPC.Dialog> chats = mutedUnread(fragment.getCurrentAccount());
        if (chats.isEmpty()) {
            BulletinFactory.of(fragment).createSimpleBulletin(org.telegram.messenger.R.raw.done, "No muted chats with unread messages.").show();
            return;
        }
        run(fragment, new ArrayList<>(chats.subList(0, Math.min(MAX_CHATS, chats.size()))));
    }

    private static void run(BaseFragment fragment, ArrayList<TLRPC.Dialog> dialogs) {
        if (stage != null) {
            return;
        }
        if (AiSummarizer.prefs().getString("apiKey", "").isEmpty()) {
            BulletinFactory.of(fragment).createErrorBulletin("Set your OpenAI API key first.").show();
            return;
        }
        int account = fragment.getCurrentAccount();
        ArrayList<Long> chats = new ArrayList<>();
        HashMap<Long, Integer> readUpTo = new HashMap<>();
        for (TLRPC.Dialog dialog : dialogs) {
            chats.add(dialog.id);
            readUpTo.put(dialog.id, dialog.read_inbox_max_id);
        }
        String title = chats.size() == 1 ? AiSummarizer.chatTitle(account, chats.get(0)) : chats.size() + " muted chats";
        setStage(title, "Reading unread messages…", 0);
        AiSummarizer[] summarizer = new AiSummarizer[1];
        summarizer[0] = new AiSummarizer(account, chats, 0, new AiSummarizer.Callback() {
            @Override
            public void onProgress(String text) {
                setStage(title, text, text.startsWith("Reading") ? 20 : 70);
            }

            @Override
            public void onCollected(int chatCount, int messages, int approxTokens) {
                if (messages == 0) {
                    finish(title, null, "Nothing unread to summarize.");
                    return;
                }
                setStage(title, "Summarizing " + messages + " messages…", 60);
                summarizer[0].send();
            }

            @Override
            public void onDone(String summary) {
                AiSummaryActivity.Digest digest = new AiSummaryActivity.Digest();
                digest.label = "Catch-up · " + title;
                digest.time = System.currentTimeMillis();
                digest.text = summary;
                digest.truncated = summarizer[0].truncated;
                digest.refs.addAll(summarizer[0].refs);
                digest.posts = summarizer[0].refs.size();
                digest.save(RESULT_KEY);
                finish(title, digest, null);
            }

            @Override
            public void onError(String error) {
                finish(title, null, error);
            }
        });
        summarizer[0].setMinMessageIds(readUpTo);
        summarizer[0].setMaxPerChat(MAX_PER_CHAT);
        summarizer[0].start();
    }

    private static void setStage(String title, String text, int percent) {
        stage = text;
        MyWorkService.put("catchup", "Catch-up · " + title, text, percent);
        if (page != null) {
            page.rebuild();
        }
    }

    // The end: the result opens over the page if it's on screen, otherwise a notification opens it.
    private static void finish(String title, AiSummaryActivity.Digest digest, String error) {
        stage = null;
        MyWorkService.remove("catchup");
        Page shown = page;
        if (shown != null) {
            shown.rebuild();
        }
        if (shown != null && shown.isOnScreen()) {
            if (digest != null) {
                shown.fragment.presentFragment(new AiSummaryActivity.ResultActivity(digest));
            } else {
                BulletinFactory.of(shown.fragment).createErrorBulletin("❌ " + error).show();
            }
            return;
        }
        Context context = ApplicationLoader.applicationContext;
        NotificationManager manager = MyWorkService.notificationManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, "Catch-up", NotificationManager.IMPORTANCE_HIGH));
        }
        String text = digest != null ? AiSummaryActivity.plainText(digest.text).replaceAll("\\s+", " ").trim() : error;
        if (text.length() > 200) {
            text = text.substring(0, 200) + "…";
        }
        Intent open = new Intent(context, LaunchActivity.class).setAction(ACTION_OPEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(MyIcons.notificationIcon())
            .setContentTitle((digest != null ? "⏪ Catch-up ready · " : "⏪ Catch-up failed · ") + title)
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true);
        if (digest != null) {
            builder.setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    // LaunchActivity hook: the notification opens the catch-up result.
    public static boolean handleIntent(LaunchActivity activity, Intent intent) {
        if (intent == null || !ACTION_OPEN.equals(intent.getAction()) || AndroidUtilities.needShowPasscode(false)) {
            return false;
        }
        AiSummaryActivity.Digest digest = AiSummaryActivity.Digest.load(RESULT_KEY);
        if (digest != null) {
            AndroidUtilities.runOnUIThread(() -> activity.presentFragment(new AiSummaryActivity.ResultActivity(digest)), 300);
        }
        return true;
    }
}
