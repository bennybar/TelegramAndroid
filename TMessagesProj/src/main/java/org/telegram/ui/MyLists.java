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
import android.provider.CalendarContract;
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
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.LayoutHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

// AI-tab checklists made from one chosen chat each: Shopping, Tasks, Dates and Kids. A scan reads the chat's last
// 1-5 days with the Digest's paced history requests, the model picks out the list's kind of item, and new items are
// merged into the list kept on this phone (ones already listed skipped, checkmarks kept); each links to its message,
// and dated ones can go to the calendar. Scans run in the background (MyWorkService) with a progress notification;
// one that ends away from its page posts a notification that opens it.
public class MyLists {

    private static final int[] DAYS = {1, 2, 3, 4, 5};
    private static final int MAX_CHARS = 100_000;
    private static final String CHANNEL_DONE = "my_lists_done";
    private static final String ACTION_OPEN = "org.telegram.ui.MyLists.OPEN";
    private static final int NOTIFICATION_ID = 0x7E7E00;

    private static final String FORMAT =
        "The messages come one per line: a reference like [r12], the weekday, date and time it was sent, the sender " +
        "(\"Me\" is the user), then the text. The first line says today's date.\n" +
        "Return JSON only, in this shape: {\"items\":[{\"name\":\"...\",\"detail\":\"...\",\"when\":\"...\",\"ref\":\"r12\"}]}\n" +
        "- ref: the reference of the message it came from, copied from the input.\n" +
        "- when: \"YYYY-MM-DD HH:MM\", or \"YYYY-MM-DD\" when there's no time, or \"\" when there's no date. Resolve " +
        "relative dates (\"מחר\", \"ביום שלישי\") from the date the message was sent.\n" +
        "Write name and detail in the language of the chat (usually Hebrew), short. One entry per thing: merge " +
        "repeats and keep the latest details. If there is nothing, return {\"items\":[]}.\n";

    // One list: its look, its prompt, and where it keeps its data.
    static class Kind {
        final String key;
        final String emoji;
        final String title;
        final String scanLabel;
        final String openLabel;
        final String doneLabel;
        final String noun;
        final boolean dated;
        final String prompt;

        Kind(String key, String emoji, String title, String scanLabel, String openLabel, String doneLabel, String noun, boolean dated, String prompt) {
            this.key = key;
            this.emoji = emoji;
            this.title = title;
            this.scanLabel = scanLabel;
            this.openLabel = openLabel;
            this.doneLabel = doneLabel;
            this.noun = noun;
            this.dated = dated;
            this.prompt = prompt;
        }

        String pref(String name) {
            return key + name; // "shopList", "taskChat", ...
        }

        int index() {
            for (int i = 0; i < KINDS.length; i++) {
                if (KINDS[i] == this) {
                    return i;
                }
            }
            return 0;
        }
    }

    static final Kind[] KINDS = {
        new Kind("shop", "🛒", "Shopping list", "Scan for items", "TO BUY", "CHECKED", "item", false,
            "You find shopping requests in a Telegram chat: things someone asks to buy, adds to a grocery or shopping " +
            "list, or says are missing or running out at home (for example \"צריך חלב\", \"תקנה לחם\", \"נגמר השמפו\").\n" +
            "- name: the product, without the quantity. detail: the quantity, size or brand if given (\"2\", " +
            "\"1 ק\\\"ג\", \"גדול\"), otherwise \"\". when: \"\".\n" +
            "Leave out items the chat says were already bought, are already at home, or were cancelled (\"קניתי\", " +
            "\"כבר יש\", \"עזוב\"), and anything that isn't a purchase.\n"),
        new Kind("task", "✅", "Tasks", "Scan for tasks", "TO DO", "DONE", "task", false,
            "You find tasks for the user (\"Me\") in a Telegram chat: things someone asks Me to do, bring, send, check, " +
            "pay, call or remember (\"תוכל לאסוף את...\", \"תשלח לי...\", \"אל תשכח...\"), and things Me promised to do " +
            "(\"אני אבדוק\", \"אשלח לך מחר\").\n" +
            "- name: the task as a short action (\"לאסוף את נועה מהחוג\"). detail: who asked, plus the deadline in words " +
            "if there is one (\"מאשתי · עד מחר\"). when: the deadline if there is one, otherwise \"\".\n" +
            "Leave out tasks that were done or cancelled later in the chat (\"עשיתי\", \"סגרתי\", \"כבר לא צריך\"), " +
            "requests aimed at other people, and general talk.\n"),
        new Kind("date", "📅", "Dates", "Scan for dates", "UPCOMING", "DONE", "date", true,
            "You find events, appointments and deadlines with a date or time in a Telegram chat: meetings, doctor and " +
            "other appointments, parties, trips, payments due, deliveries (\"תור לרופא ב-12.11\", \"ביום שלישי ב-17:00 " +
            "אסיפת הורים\").\n" +
            "- name: the event, short. detail: the place or anything to remember, otherwise \"\". when: its date and time.\n" +
            "Leave out events that were cancelled, ones that already happened before today, and anything without a " +
            "date.\n"),
        new Kind("kids", "🎒", "Kids", "Scan for kids' stuff", "TO HANDLE", "DONE", "item", true,
            "You read a school, class or kids' activity group in a Telegram chat and find what a parent has to act on: " +
            "things to bring or prepare, payments, forms to sign, deadlines, trips and events, and schedule changes " +
            "(cancelled class, early finish, different place).\n" +
            "- name: what to do or know, short (\"להביא תלבושת לבנה\", \"לשלם 50 ₪ לטיול\", \"אין חוג ביום רביעי\"). " +
            "detail: who or which class it's about and any amount, otherwise \"\". when: the date it's needed or " +
            "happens, otherwise \"\".\n" +
            "Leave out greetings, thank-yous, photos, chatter, and things that already happened before today.\n"),
    };

    static Kind kind(String key) {
        for (Kind kind : KINDS) {
            if (kind.key.equals(key)) {
                return kind;
            }
        }
        return KINDS[0];
    }

    // ---- The lists, kept in the AI prefs (not part of settings backups) ----

    static class Item {
        String name;
        String detail;
        long when; // ms, 0 when undated
        boolean allDay;
        boolean checked;
        long dialogId;
        int messageId;
    }

    static ArrayList<Item> load(Kind kind) {
        ArrayList<Item> items = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(AiSummarizer.prefs().getString(kind.pref("List"), "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                Item item = new Item();
                item.name = o.getString("name");
                item.detail = o.optString("detail", o.optString("qty", ""));
                item.when = o.optLong("when");
                item.allDay = o.optBoolean("allDay");
                item.checked = o.optBoolean("checked");
                item.dialogId = o.optLong("did");
                item.messageId = o.optInt("mid");
                items.add(item);
            }
        } catch (Exception ignore) {}
        return items;
    }

    static void save(Kind kind, ArrayList<Item> items) {
        try {
            JSONArray list = new JSONArray();
            for (Item item : items) {
                list.put(new JSONObject().put("name", item.name).put("detail", item.detail).put("when", item.when)
                    .put("allDay", item.allDay).put("checked", item.checked).put("did", item.dialogId).put("mid", item.messageId));
            }
            AiSummarizer.prefs().edit().putString(kind.pref("List"), list.toString()).apply();
        } catch (Exception ignore) {}
    }

    private static String identity(Kind kind, Item item) {
        String name = item.name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return kind.dated && item.when != 0 ? name + "@" + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(item.when)) : name;
    }

    private static long chat(Kind kind) {
        return AiSummarizer.prefs().getLong(kind.pref("Chat"), 0);
    }

    private static int days(Kind kind) {
        return AiSummarizer.prefs().getInt(kind.pref("Days"), 2);
    }

    // "Tue 20/10 17:00", or "Tue 20/10" for a whole day.
    private static String whenLabel(Item item) {
        return new SimpleDateFormat(item.allDay ? "EEE dd/MM" : "EEE dd/MM HH:mm", Locale.US).format(new Date(item.when));
    }

    // ---- Scan state, shared by the pages and the background runs ----

    private static final HashMap<String, String> scanning = new HashMap<>(); // kind key -> current step
    private static final HashMap<String, Page> pages = new HashMap<>(); // the pages that exist, by kind key

    public static boolean isScanning(int kindIndex) {
        return scanning.containsKey(KINDS[kindIndex].key);
    }

    public static String scanLabel(int kindIndex) {
        Kind kind = KINDS[kindIndex];
        return kind.emoji + "  " + kind.scanLabel;
    }

    private static void changed(Kind kind) {
        Page page = pages.get(kind.key);
        if (page != null) {
            page.rebuild();
        }
    }

    // ---- The page ----

    public static Page createPage(BaseFragment fragment, int kindIndex, Runnable onScanStateChanged) {
        return new Page(fragment, KINDS[kindIndex], onScanStateChanged);
    }

    // One list's page in the AI tab: the scan settings, then the checklist.
    public static class Page extends ScrollView {

        private final BaseFragment fragment;
        private final Kind kind;
        private final LinearLayout content;
        private final Runnable onScanStateChanged;

        Page(BaseFragment fragment, Kind kind, Runnable onScanStateChanged) {
            super(fragment.getContext());
            this.fragment = fragment;
            this.kind = kind;
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
            pages.put(kind.key, this);
            rebuild();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (pages.get(kind.key) == this) {
                pages.remove(kind.key);
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
            content.addView(hint(context, "Reads one chat and lists what it finds. Secret chats are never sent."));
            ArrayList<Item> items = load(kind);
            ArrayList<Item> open = new ArrayList<>();
            ArrayList<Item> done = new ArrayList<>();
            for (Item item : items) {
                (item.checked ? done : open).add(item);
            }
            if (kind.dated) {
                // Soonest first; undated ones after.
                java.util.Collections.sort(open, (a, b) -> Long.compare(a.when == 0 ? Long.MAX_VALUE : a.when, b.when == 0 ? Long.MAX_VALUE : b.when));
            }
            if (items.isEmpty()) {
                content.addView(hint(context, "The list is empty. Scan to fill it."));
            } else {
                LinearLayout list = new LinearLayout(context);
                list.setOrientation(LinearLayout.VERTICAL);
                list.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                list.addView(header(context, kind.openLabel + " · " + open.size(), null, null));
                for (Item item : open) {
                    list.addView(row(context, items, item));
                }
                if (open.isEmpty()) {
                    list.addView(hint(context, "Everything's checked off."));
                }
                if (!done.isEmpty()) {
                    list.addView(header(context, kind.doneLabel + " · " + done.size(), "Clear", () -> {
                        items.removeAll(done);
                        save(kind, items);
                        rebuild();
                    }));
                    for (Item item : done) {
                        list.addView(row(context, items, item));
                    }
                }
                content.addView(list);
                content.addView(hint(context, "Tap to check off. Long-press for the message" + (kind.dated ? ", the calendar" : "") + " or to delete."));
            }
            if (!items.isEmpty() || AiSummarizer.prefs().getLong(kind.pref("LastScan"), 0) != 0) {
                content.addView(startOver(context, items.size()));
            }
            onScanStateChanged.run();
        }

        // Chat, days and the scan's status.
        private View createSettingsCard(Context context) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            card.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(16), AndroidUtilities.dp(21), AndroidUtilities.dp(16));
            card.addView(label(context, "SCAN"));

            long did = chat(kind);
            TextView chatView = new TextView(context);
            chatView.setText(did == 0 ? "Choose a chat ›" : "From " + AiSummarizer.chatTitle(fragment.getCurrentAccount(), did) + " ›");
            chatView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            chatView.setTypeface(AndroidUtilities.bold());
            chatView.setTextColor(Theme.getColor(did == 0 ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteBlackText));
            chatView.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(12));
            chatView.setOnClickListener(v -> pickChat(fragment, kind));
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
                boolean selected = d == days(kind);
                chip.setTextColor(selected ? Theme.getColor(Theme.key_featuredStickers_buttonText) : accent);
                chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(18), selected ? accent : Theme.multAlpha(accent, 0.1f), Theme.multAlpha(accent, 0.25f)));
                chip.setOnClickListener(v -> {
                    AiSummarizer.prefs().edit().putInt(kind.pref("Days"), d).apply();
                    rebuild();
                });
                daysRow.addView(chip, LayoutHelper.createLinear(36, 36, 0, 0, 6, 0));
            }
            daysRow.addView(gray(context, days(kind) == 1 ? "day" : "days"), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 0, 0, 0));
            card.addView(daysRow);

            String status = scanning.get(kind.key);
            if (status == null) {
                long last = AiSummarizer.prefs().getLong(kind.pref("LastScan"), 0);
                status = last == 0 ? "Not scanned yet." : "Last scan " + AiSummaryActivity.whenText(last) + " · " + AiSummarizer.prefs().getString(kind.pref("LastResult"), "");
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

        // An item: round checkbox, the name, and a gray line with its date and detail; right to left for Hebrew,
        // checked items struck through.
        private View row(Context context, ArrayList<Item> items, Item item) {
            boolean rtl = (item.name + item.detail).matches("(?s).*[\\u0590-\\u05FF].*");
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            row.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(11), AndroidUtilities.dp(21), AndroidUtilities.dp(11));

            CheckBox2 check = new CheckBox2(context, 22);
            check.setColor(Theme.key_checkbox, Theme.key_radioBackground, Theme.key_checkboxCheck);
            check.setDrawUnchecked(true);
            check.setDrawBackgroundAsArc(10);
            check.setChecked(item.checked, false);
            check.setClickable(false);
            row.addView(check, LayoutHelper.createLinear(24, 24));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(context);
            name.setText(item.name);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            name.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
            name.setTextColor(Theme.getColor(item.checked ? Theme.key_windowBackgroundWhiteGrayText : Theme.key_windowBackgroundWhiteBlackText));
            if (item.checked) {
                name.setPaintFlags(name.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            }
            texts.addView(name);
            String sub = item.when != 0 ? whenLabel(item) + (item.detail.isEmpty() ? "" : " · " + item.detail) : item.detail;
            if (!sub.isEmpty()) {
                TextView subView = gray(context, sub);
                subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                subView.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
                texts.addView(subView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            }
            row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 16, 0, 0, 0));

            row.setOnClickListener(v -> {
                item.checked = !item.checked;
                save(kind, items);
                rebuild();
            });
            row.setOnLongClickListener(v -> {
                ArrayList<CharSequence> labels = new ArrayList<>();
                ArrayList<Runnable> actions = new ArrayList<>();
                if (item.dialogId != 0 && item.messageId != 0) {
                    labels.add("Open the message");
                    actions.add(() -> AiSummaryActivity.openMessage(fragment, new AiSummarizer.Ref(item.dialogId, item.messageId)));
                }
                if (item.when != 0) {
                    labels.add("Add to calendar");
                    actions.add(() -> addToCalendar(item));
                }
                labels.add("Delete");
                actions.add(() -> {
                    items.remove(item);
                    save(kind, items);
                    rebuild();
                });
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setTitle(item.name);
                builder.setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run());
                fragment.showDialog(builder.create());
                return true;
            });
            return row;
        }

        // Android's own "new event" screen, prefilled; nothing is linked or synced.
        private void addToCalendar(Item item) {
            Intent intent = new Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, item.name)
                .putExtra(CalendarContract.Events.DESCRIPTION, item.detail)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, item.when)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, item.when + (item.allDay ? 24 : 1) * 3600_000L)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, item.allDay);
            try {
                fragment.getParentActivity().startActivity(intent);
            } catch (Exception e) {
                BulletinFactory.of(fragment).createErrorBulletin("No calendar app found.").show();
            }
        }

        // Clears the list and the scan history, then (optionally) scans the chosen chat again from scratch.
        private View startOver(Context context, int count) {
            TextView button = new TextView(context);
            button.setText("Start over");
            button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            button.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
            button.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(4), AndroidUtilities.dp(21), AndroidUtilities.dp(16));
            button.setOnClickListener(v -> {
                if (scanning.containsKey(kind.key)) {
                    return;
                }
                long did = chat(kind);
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setTitle("Start over?");
                builder.setMessage((count > 0 ? "Removes all " + count + " " + kind.noun + "s and the scan history." : "Removes the scan history.") +
                    (did != 0 ? " Scan " + AiSummarizer.chatTitle(fragment.getCurrentAccount(), did) + " again now?" : ""));
                builder.setPositiveButton(did != 0 ? "Clear & scan" : "Clear", (d, w) -> {
                    clearAll();
                    if (did != 0) {
                        scan(fragment, kind.index());
                    }
                });
                if (did != 0) {
                    builder.setNeutralButton("Just clear", (d, w) -> clearAll());
                }
                builder.setNegativeButton("Cancel", null);
                fragment.showDialog(builder.create());
            });
            return button;
        }

        private void clearAll() {
            save(kind, new ArrayList<>());
            AiSummarizer.prefs().edit().remove(kind.pref("LastScan")).remove(kind.pref("LastResult")).apply();
            rebuild();
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

    private static void pickChat(BaseFragment fragment, Kind kind) {
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
            AiSummarizer.prefs().edit().putLong(kind.pref("Chat"), did).apply();
            f.finishFragment();
            changed(kind);
            return true;
        });
        fragment.presentFragment(picker);
    }

    // ---- Scanning, in the background ----

    // A list page's bottom button.
    public static void scan(BaseFragment fragment, int kindIndex) {
        Kind kind = KINDS[kindIndex];
        if (scanning.containsKey(kind.key)) {
            return;
        }
        if (AiSummarizer.prefs().getString("apiKey", "").isEmpty()) {
            BulletinFactory.of(fragment).createErrorBulletin("Set your OpenAI API key first.").show();
            return;
        }
        long did = chat(kind);
        if (did == 0) {
            pickChat(fragment, kind);
            return;
        }
        int account = UserConfig.selectedAccount;
        int days = days(kind);
        String chatTitle = AiSummarizer.chatTitle(account, did);
        ArrayList<Long> chats = new ArrayList<>();
        chats.add(did);
        int since = ConnectionsManager.getInstance(account).getCurrentTime() - days * 24 * 3600;
        stage(kind, chatTitle, "Reading messages…", 0);
        AiSummarizer[] reader = new AiSummarizer[1];
        reader[0] = new AiSummarizer(account, chats, since, new AiSummarizer.Callback() {
            @Override
            public void onProgress(String text) {
            }

            @Override
            public void onCollected(int chatCount, int messages, int approxTokens) {
                if (messages == 0) {
                    finish(kind, chatTitle, null, "No messages in the last " + (days == 1 ? "day" : days + " days") + ".");
                    return;
                }
                stage(kind, chatTitle, "Looking through " + messages + " messages…", 60);
                String today = "Today is " + new SimpleDateFormat("EEEE dd/MM/yyyy", Locale.US).format(new Date()) + ".\n";
                AiSummarizer.askOnce(kind.prompt + FORMAT, today + reader[0].transcript(MAX_CHARS), answer -> {
                    ArrayList<Item> added = merge(kind, answer, reader[0].refs);
                    if (added == null) {
                        finish(kind, chatTitle, null, "Couldn't read the AI's answer. Try again.");
                    } else {
                        finish(kind, chatTitle, added, null);
                    }
                }, error -> finish(kind, chatTitle, null, error));
            }

            @Override
            public void onDone(String summary) {
            }

            @Override
            public void onError(String error) {
                finish(kind, chatTitle, null, error);
            }
        });
        reader[0].start();
    }

    private static void stage(Kind kind, String chatTitle, String text, int percent) {
        scanning.put(kind.key, text);
        MyWorkService.put("list_" + kind.key, kind.title + " · " + chatTitle, text, percent);
        changed(kind);
    }

    // The end of a scan: a bulletin on its page if that's on screen, otherwise a notification.
    private static void finish(Kind kind, String chatTitle, ArrayList<Item> added, String error) {
        scanning.remove(kind.key);
        MyWorkService.remove("list_" + kind.key);
        String plural = kind.noun + "s";
        String result = error != null ? "failed" : added.isEmpty() ? "nothing new" : added.size() + " new " + (added.size() == 1 ? kind.noun : plural);
        AiSummarizer.prefs().edit().putLong(kind.pref("LastScan"), System.currentTimeMillis()).putString(kind.pref("LastResult"), result).apply();
        Page shown = pages.get(kind.key);
        changed(kind);
        String message = error != null ? "❌ " + error : added.isEmpty() ? "Nothing new found." : "Added " + added.size() + " " + (added.size() == 1 ? kind.noun : plural) + ".";
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
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, "Lists from chats", NotificationManager.IMPORTANCE_HIGH));
        }
        String text = message;
        if (added != null && !added.isEmpty()) {
            ArrayList<String> names = new ArrayList<>();
            for (Item item : added) {
                names.add(item.name);
            }
            text = TextUtils.join(", ", names);
        }
        String title = kind.emoji + " " + kind.title + ": " + (error != null ? "scan failed" : result);
        int index = kind.index();
        Intent open = new Intent(context, LaunchActivity.class).setAction(ACTION_OPEN).putExtra("kind", index).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(MyIcons.notificationIcon())
            .setContentTitle(title + " · " + chatTitle)
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID + index, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        manager.notify(NOTIFICATION_ID + index, builder.build());
    }

    // LaunchActivity hook: the notification opens the list's page.
    public static boolean handleIntent(LaunchActivity activity, Intent intent) {
        if (intent == null || !ACTION_OPEN.equals(intent.getAction()) || AndroidUtilities.needShowPasscode(false)) {
            return false;
        }
        Bundle args = new Bundle();
        args.putInt("page", AiSummaryActivity.PAGE_LISTS + intent.getIntExtra("kind", 0));
        AndroidUtilities.runOnUIThread(() -> activity.presentFragment(new AiSummaryActivity(args)), 300);
        return true;
    }

    // Adds the new items to the saved list (ones already on it are skipped, checkmarks kept); null if unreadable.
    private static ArrayList<Item> merge(Kind kind, String answer, ArrayList<AiSummarizer.Ref> refs) {
        JSONArray found;
        try {
            int start = answer.indexOf('{');
            int end = answer.lastIndexOf('}');
            found = new JSONObject(answer.substring(start, end + 1)).getJSONArray("items");
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
        ArrayList<Item> items = load(kind);
        HashSet<String> known = new HashSet<>();
        for (Item item : items) {
            known.add(identity(kind, item));
        }
        ArrayList<Item> added = new ArrayList<>();
        for (int i = 0; i < found.length(); i++) {
            JSONObject o = found.optJSONObject(i);
            String name = o == null ? "" : o.optString("name", "").trim();
            if (name.isEmpty()) {
                continue;
            }
            Item item = new Item();
            item.name = name;
            item.detail = o.optString("detail", "").trim();
            parseWhen(item, o.optString("when", "").trim());
            if (!known.add(identity(kind, item))) {
                continue;
            }
            String ref = o.optString("ref", "").replaceAll("[^0-9]", "");
            int index = ref.isEmpty() ? -1 : Integer.parseInt(ref) - 1;
            if (index >= 0 && index < refs.size()) {
                item.dialogId = refs.get(index).dialogId;
                item.messageId = refs.get(index).messageId;
            }
            items.add(item);
            added.add(item);
        }
        save(kind, items);
        return added;
    }

    // "YYYY-MM-DD HH:MM" or "YYYY-MM-DD", in this phone's time zone.
    private static void parseWhen(Item item, String when) {
        if (when.isEmpty()) {
            return;
        }
        try {
            boolean hasTime = when.length() > 10;
            Date date = new SimpleDateFormat(hasTime ? "yyyy-MM-dd HH:mm" : "yyyy-MM-dd", Locale.US).parse(when);
            if (date != null) {
                item.when = date.getTime();
                item.allDay = !hasTime;
            }
        } catch (Exception ignore) {}
    }
}
