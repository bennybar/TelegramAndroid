package org.telegram.ui;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Locale;

// The Shopping page of the AI tab: one chosen chat's last 1-5 days are read with the Digest's paced history
// requests, the model picks out what someone asked to buy, and the items are merged into a checklist kept on this
// phone (new items added, ones already listed skipped, checkmarks kept). Each item links to its message.
// The scan runs in the background (MyWorkService) with a progress notification; when it ends away from this page,
// a notification opens the list.
public class MyShopping {

    private static final int[] DAYS = {1, 2, 3, 4, 5};
    private static final int MAX_CHARS = 100_000;
    private static final String CHANNEL_DONE = "my_shop_done";
    private static final String ACTION_OPEN = "org.telegram.ui.MyShopping.OPEN";
    private static final int NOTIFICATION_ID = 0x7E7E00;

    private static final String PROMPT =
        "You find shopping requests in a Telegram chat: things someone asks to buy, adds to a grocery or shopping " +
        "list, or says are missing or running out at home (for example \"צריך חלב\", \"תקנה לחם\", \"נגמר השמפו\"). " +
        "The messages come one per line: a reference like [r12], the day and time, the sender (\"Me\" is the user), " +
        "then the text.\n" +
        "Return JSON only, in this shape: {\"items\":[{\"name\":\"...\",\"qty\":\"...\",\"ref\":\"r12\"}]}\n" +
        "- name: the product, short, in the language it was written in (usually Hebrew), without the quantity.\n" +
        "- qty: the quantity, size or brand if given (\"2\", \"1 ק\\\"ג\", \"גדול\"), otherwise \"\".\n" +
        "- ref: the reference of the message that asked for it, copied from the input.\n" +
        "One entry per product: merge repeated requests and keep the latest details. Leave out items the chat says " +
        "were already bought, are already at home, or were cancelled (\"קניתי\", \"כבר יש\", \"עזוב\"), and " +
        "anything that isn't a purchase (plans, links, ads, general talk). If there is nothing, return {\"items\":[]}.";

    // ---- The list, kept in the AI prefs (not part of settings backups) ----

    static class Item {
        String name;
        String qty;
        boolean checked;
        long dialogId;
        int messageId;
    }

    static ArrayList<Item> load() {
        ArrayList<Item> items = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(AiSummarizer.prefs().getString("shopList", "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                Item item = new Item();
                item.name = o.getString("name");
                item.qty = o.optString("qty", "");
                item.checked = o.optBoolean("checked");
                item.dialogId = o.optLong("did");
                item.messageId = o.optInt("mid");
                items.add(item);
            }
        } catch (Exception ignore) {}
        return items;
    }

    static void save(ArrayList<Item> items) {
        try {
            JSONArray list = new JSONArray();
            for (Item item : items) {
                list.put(new JSONObject().put("name", item.name).put("qty", item.qty).put("checked", item.checked)
                    .put("did", item.dialogId).put("mid", item.messageId));
            }
            AiSummarizer.prefs().edit().putString("shopList", list.toString()).apply();
        } catch (Exception ignore) {}
    }

    private static String normalized(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static long chat() {
        return AiSummarizer.prefs().getLong("shopChat", 0);
    }

    private static int days() {
        return AiSummarizer.prefs().getInt("shopDays", 2);
    }

    // ---- Scan state, shared by the page and the background run ----

    private static boolean scanning;
    private static String scanStage = "";
    private static Page page; // the page on screen, if any

    public static boolean isScanning() {
        return scanning;
    }

    private static void changed() {
        if (page != null) {
            page.rebuild();
        }
    }

    // ---- The page ----

    // Built by AiSummaryActivity for its Shopping tab: the scan settings, then the checklist.
    public static class Page extends ScrollView {

        private final BaseFragment fragment;
        private final LinearLayout content;
        private final Runnable onScanStateChanged;

        public Page(BaseFragment fragment, Runnable onScanStateChanged) {
            super(fragment.getContext());
            this.fragment = fragment;
            this.onScanStateChanged = onScanStateChanged;
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

        // On screen right now: attached, visible and not scrolled away by the tab pager.
        boolean isOnScreen() {
            return isShown() && !ApplicationLoader.mainInterfacePaused && getGlobalVisibleRect(new Rect());
        }

        void rebuild() {
            Context context = getContext();
            content.removeAllViews();
            content.addView(createSettingsCard(context));
            content.addView(hint(context, "Reads one chat and lists what someone asked to buy. Secret chats are never sent."));
            ArrayList<Item> items = load();
            int open = 0;
            for (Item item : items) {
                if (!item.checked) {
                    open++;
                }
            }
            if (items.isEmpty()) {
                content.addView(hint(context, "The list is empty. Scan to fill it."));
            } else {
                LinearLayout list = card(context);
                list.addView(header(context, "TO BUY · " + open, null, null));
                for (Item item : items) {
                    if (!item.checked) {
                        list.addView(row(context, items, item));
                    }
                }
                if (open == 0) {
                    list.addView(hint(context, "Everything's checked off."));
                }
                if (open < items.size()) {
                    list.addView(header(context, "CHECKED · " + (items.size() - open), "Clear", () -> {
                        for (int i = items.size() - 1; i >= 0; i--) {
                            if (items.get(i).checked) {
                                items.remove(i);
                            }
                        }
                        save(items);
                        rebuild();
                    }));
                    for (Item item : items) {
                        if (item.checked) {
                            list.addView(row(context, items, item));
                        }
                    }
                }
                content.addView(list);
                content.addView(hint(context, "Tap an item to check it off. Long-press to open the message it came from."));
            }
            if (!items.isEmpty() || AiSummarizer.prefs().getLong("shopLastScan", 0) != 0) {
                content.addView(startOver(context, items.size()));
            }
            onScanStateChanged.run();
        }

        // Clears the list and the scan history, then (optionally) scans the chosen chat again from scratch.
        private View startOver(Context context, int count) {
            TextView button = new TextView(context);
            button.setText("Start over");
            button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            button.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
            button.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(4), AndroidUtilities.dp(21), AndroidUtilities.dp(16));
            button.setOnClickListener(v -> {
                if (scanning) {
                    return;
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setTitle("Start over?");
                builder.setMessage((count > 0 ? "Removes all " + count + " items and the scan history." : "Removes the scan history.") +
                    (chat() != 0 ? " Scan " + AiSummarizer.chatTitle(fragment.getCurrentAccount(), chat()) + " again now?" : ""));
                builder.setPositiveButton(chat() != 0 ? "Clear & scan" : "Clear", (d, w) -> {
                    clearAll();
                    if (chat() != 0) {
                        scan(fragment);
                    }
                });
                if (chat() != 0) {
                    builder.setNeutralButton("Just clear", (d, w) -> clearAll());
                }
                builder.setNegativeButton("Cancel", null);
                fragment.showDialog(builder.create());
            });
            return button;
        }

        private void clearAll() {
            save(new ArrayList<>());
            AiSummarizer.prefs().edit().remove("shopLastScan").remove("shopLastResult").apply();
            rebuild();
        }

        private LinearLayout card(Context context) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return card;
        }

        // Chat, days and the scan's status.
        private View createSettingsCard(Context context) {
            LinearLayout card = card(context);
            card.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(16), AndroidUtilities.dp(21), AndroidUtilities.dp(16));
            card.addView(label(context, "SCAN"));

            long did = chat();
            TextView chatView = new TextView(context);
            chatView.setText(did == 0 ? "Choose a chat ›" : "From " + AiSummarizer.chatTitle(fragment.getCurrentAccount(), did) + " ›");
            chatView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            chatView.setTypeface(AndroidUtilities.bold());
            chatView.setTextColor(Theme.getColor(did == 0 ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteBlackText));
            chatView.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(12));
            chatView.setOnClickListener(v -> pickChat(fragment));
            card.addView(chatView);

            // "Last  1 2 3 4 5  days", the chosen one filled.
            LinearLayout daysRow = new LinearLayout(context);
            daysRow.setOrientation(LinearLayout.HORIZONTAL);
            daysRow.setGravity(Gravity.CENTER_VERTICAL);
            daysRow.addView(gray(context, "Last"), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 10, 0));
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
            for (int d : DAYS) {
                TextView chip = new TextView(context);
                chip.setText(String.valueOf(d));
                chip.setGravity(Gravity.CENTER);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                chip.setTypeface(AndroidUtilities.bold());
                chip.setIncludeFontPadding(false);
                boolean selected = d == days();
                chip.setTextColor(selected ? Theme.getColor(Theme.key_featuredStickers_buttonText) : accent);
                chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(18), selected ? accent : Theme.multAlpha(accent, 0.1f), Theme.multAlpha(accent, 0.25f)));
                chip.setOnClickListener(v -> {
                    AiSummarizer.prefs().edit().putInt("shopDays", d).apply();
                    rebuild();
                });
                daysRow.addView(chip, LayoutHelper.createLinear(36, 36, 0, 0, 6, 0));
            }
            daysRow.addView(gray(context, days() == 1 ? "day" : "days"), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 0, 0, 0));
            card.addView(daysRow);

            String status;
            if (scanning) {
                status = scanStage;
            } else {
                long last = AiSummarizer.prefs().getLong("shopLastScan", 0);
                status = last == 0 ? "Not scanned yet." : "Last scan " + AiSummaryActivity.whenText(last) + " · " + AiSummarizer.prefs().getString("shopLastResult", "");
            }
            TextView statusView = gray(context, status);
            statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            statusView.setPadding(0, AndroidUtilities.dp(14), 0, 0);
            card.addView(statusView);
            return card;
        }

        private View header(Context context, String text, String action, Runnable onAction) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(14), AndroidUtilities.dp(21), AndroidUtilities.dp(4));
            row.addView(label(context, text), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            if (action != null) {
                TextView button = new TextView(context);
                button.setText(action);
                button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                button.setTypeface(AndroidUtilities.bold());
                button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
                button.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));
                button.setOnClickListener(v -> onAction.run());
                row.addView(button);
            }
            return row;
        }

        // An item: round checkbox, name and quantity, right to left for Hebrew; checked items struck through.
        private View row(Context context, ArrayList<Item> items, Item item) {
            boolean rtl = (item.name + item.qty).matches("(?s).*[\\u0590-\\u05FF].*");
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            row.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(12), AndroidUtilities.dp(21), AndroidUtilities.dp(12));

            CheckBox2 check = new CheckBox2(context, 22);
            check.setColor(Theme.key_checkbox, Theme.key_radioBackground, Theme.key_checkboxCheck);
            check.setDrawUnchecked(true);
            check.setDrawBackgroundAsArc(10);
            check.setChecked(item.checked, false);
            check.setClickable(false);
            row.addView(check, LayoutHelper.createLinear(24, 24));

            TextView name = new TextView(context);
            name.setText(item.qty.isEmpty() ? item.name : item.name + "  ·  " + item.qty);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            name.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
            name.setTextColor(Theme.getColor(item.checked ? Theme.key_windowBackgroundWhiteGrayText : Theme.key_windowBackgroundWhiteBlackText));
            if (item.checked) {
                name.setPaintFlags(name.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            }
            row.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 16, 0, 0, 0));

            row.setOnClickListener(v -> {
                item.checked = !item.checked;
                save(items);
                rebuild();
            });
            row.setOnLongClickListener(v -> {
                if (item.dialogId == 0 || item.messageId == 0) {
                    BulletinFactory.of(fragment).createErrorBulletin("No source message for this item.").show();
                } else {
                    AiSummaryActivity.openMessage(fragment, new AiSummarizer.Ref(item.dialogId, item.messageId));
                }
                return true;
            });
            return row;
        }

        private TextView label(Context context, String text) {
            TextView label = new TextView(context);
            label.setText(text);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            label.setLetterSpacing(0.04f);
            label.setTypeface(AndroidUtilities.bold());
            label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            return label;
        }

        private TextView gray(Context context, String text) {
            TextView view = new TextView(context);
            view.setText(text);
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            return view;
        }

        private TextView hint(Context context, String text) {
            TextView view = gray(context, text);
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            view.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(10), AndroidUtilities.dp(21), AndroidUtilities.dp(14));
            return view;
        }
    }

    private static void pickChat(BaseFragment fragment) {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("checkCanWrite", false);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((f, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            long did = dids.get(0).dialogId;
            if (DialogObject.isEncryptedDialog(did)) {
                BulletinFactory.of(f).createErrorBulletin("Secret chats can't be sent to the AI.").show();
                return false;
            }
            AiSummarizer.prefs().edit().putLong("shopChat", did).apply();
            f.finishFragment();
            changed();
            return true;
        });
        fragment.presentFragment(picker);
    }

    // ---- Scanning, in the background ----

    // The Shopping tab's bottom button.
    public static void scan(BaseFragment fragment) {
        if (scanning) {
            return;
        }
        if (AiSummarizer.prefs().getString("apiKey", "").isEmpty()) {
            BulletinFactory.of(fragment).createErrorBulletin("Set your OpenAI API key first.").show();
            return;
        }
        long did = chat();
        if (did == 0) {
            pickChat(fragment);
            return;
        }
        int account = UserConfig.selectedAccount;
        int days = days();
        String chatTitle = AiSummarizer.chatTitle(account, did);
        ArrayList<Long> chats = new ArrayList<>();
        chats.add(did);
        int since = ConnectionsManager.getInstance(account).getCurrentTime() - days * 24 * 3600;
        scanning = true;
        stage(chatTitle, "Reading messages…", 0);
        AiSummarizer[] reader = new AiSummarizer[1];
        reader[0] = new AiSummarizer(account, chats, since, new AiSummarizer.Callback() {
            @Override
            public void onProgress(String text) {
                stage(chatTitle, "Reading messages…", 0);
            }

            @Override
            public void onCollected(int chatCount, int messages, int approxTokens) {
                if (messages == 0) {
                    finish(chatTitle, null, "No messages in the last " + (days == 1 ? "day" : days + " days") + ".");
                    return;
                }
                stage(chatTitle, "Finding shopping items in " + messages + " messages…", 60);
                AiSummarizer.askOnce(PROMPT, reader[0].transcript(MAX_CHARS), answer -> {
                    ArrayList<Item> added = merge(answer, reader[0].refs);
                    if (added == null) {
                        finish(chatTitle, null, "Couldn't read the AI's answer. Try again.");
                    } else {
                        finish(chatTitle, added, null);
                    }
                }, error -> finish(chatTitle, null, error));
            }

            @Override
            public void onDone(String summary) {
            }

            @Override
            public void onError(String error) {
                finish(chatTitle, null, error);
            }
        });
        reader[0].start();
    }

    private static void stage(String chatTitle, String text, int percent) {
        scanStage = text;
        MyWorkService.put("shop", "Shopping list · " + chatTitle, text, percent);
        changed();
    }

    // The end of a scan: a bulletin on the Shopping page if it's on screen, otherwise a notification.
    private static void finish(String chatTitle, ArrayList<Item> added, String error) {
        scanning = false;
        MyWorkService.remove("shop");
        String result = error != null ? "failed" : added.isEmpty() ? "nothing new" : added.size() + (added.size() == 1 ? " new item" : " new items");
        AiSummarizer.prefs().edit().putLong("shopLastScan", System.currentTimeMillis()).putString("shopLastResult", result).apply();
        Page shown = page;
        changed();
        String message = error != null ? "❌ " + error : added.isEmpty() ? "No new shopping items found." : "Added " + added.size() + (added.size() == 1 ? " item." : " items.");
        if (shown != null && shown.isOnScreen()) {
            if (error != null) {
                BulletinFactory.of(shown.fragment).createErrorBulletin(message).show();
            } else {
                BulletinFactory.of(shown.fragment).createSimpleBulletin(R.raw.done, message).show();
            }
            return;
        }
        Context context = ApplicationLoader.applicationContext;
        NotificationManager manager = MyWorkService.notificationManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, "Shopping list", NotificationManager.IMPORTANCE_HIGH));
        }
        String text = message;
        if (added != null && !added.isEmpty()) {
            ArrayList<String> names = new ArrayList<>();
            for (Item item : added) {
                names.add(item.name);
            }
            text = TextUtils.join(", ", names);
        }
        String title = error != null ? "Shopping scan failed" : added.isEmpty() ? "Shopping list: nothing new" : "Shopping list: " + added.size() + (added.size() == 1 ? " new item" : " new items");
        Intent open = new Intent(context, LaunchActivity.class).setAction(ACTION_OPEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(MyIcons.notificationIcon())
            .setContentTitle(title + " · " + chatTitle)
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    // LaunchActivity hook: the notification opens the Shopping page.
    public static boolean handleIntent(LaunchActivity activity, Intent intent) {
        if (intent == null || !ACTION_OPEN.equals(intent.getAction()) || AndroidUtilities.needShowPasscode(false)) {
            return false;
        }
        Bundle args = new Bundle();
        args.putInt("page", AiSummaryActivity.PAGE_SHOPPING);
        AndroidUtilities.runOnUIThread(() -> activity.presentFragment(new AiSummaryActivity(args)), 300);
        return true;
    }

    // Adds the new items to the saved list (names already on it are skipped, checkmarks kept); null if unreadable.
    private static ArrayList<Item> merge(String answer, ArrayList<AiSummarizer.Ref> refs) {
        JSONArray found;
        try {
            int start = answer.indexOf('{');
            int end = answer.lastIndexOf('}');
            found = new JSONObject(answer.substring(start, end + 1)).getJSONArray("items");
        } catch (Exception e) {
            return null;
        }
        ArrayList<Item> items = load();
        java.util.HashSet<String> known = new java.util.HashSet<>();
        for (Item item : items) {
            known.add(normalized(item.name));
        }
        ArrayList<Item> added = new ArrayList<>();
        for (int i = 0; i < found.length(); i++) {
            JSONObject o = found.optJSONObject(i);
            String name = o == null ? "" : o.optString("name", "").trim();
            if (name.isEmpty() || !known.add(normalized(name))) {
                continue;
            }
            Item item = new Item();
            item.name = name;
            item.qty = o.optString("qty", "").trim();
            String ref = o.optString("ref", "").replaceAll("[^0-9]", "");
            int index = ref.isEmpty() ? -1 : Integer.parseInt(ref) - 1;
            if (index >= 0 && index < refs.size()) {
                item.dialogId = refs.get(index).dialogId;
                item.messageId = refs.get(index).messageId;
            }
            items.add(item);
            added.add(item);
        }
        save(items);
        return added;
    }
}
