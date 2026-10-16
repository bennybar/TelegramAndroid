package org.telegram.ui;

import android.content.Context;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.Locale;

// Shopping list, a card in the AI (Digest) tab: one chosen chat's last 1-5 days are read with the Digest's paced
// history requests, the model picks out what someone asked to buy, and the items are merged into a checklist kept
// on this phone (new items added, ones already listed skipped, checkmarks kept). Each item links to its message.
public class MyShopping {

    private static final int[] DAYS = {1, 2, 3, 4, 5};
    private static final int MAX_CHARS = 100_000;

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

    // ---- The card in the AI tab ----

    public static View createCard(BaseFragment fragment, Runnable refresh) {
        Context context = fragment.getContext();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        card.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(16), AndroidUtilities.dp(21), AndroidUtilities.dp(16));

        TextView label = new TextView(context);
        label.setText("SHOPPING LIST");
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        label.setLetterSpacing(0.04f);
        label.setTypeface(AndroidUtilities.bold());
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        card.addView(label);

        long did = chat();
        TextView chatView = new TextView(context);
        chatView.setText(did == 0 ? "Choose a chat ›" : "From " + AiSummarizer.chatTitle(fragment.getCurrentAccount(), did) + " ›");
        chatView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        chatView.setTypeface(AndroidUtilities.bold());
        chatView.setTextColor(Theme.getColor(did == 0 ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteBlackText));
        chatView.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(10));
        chatView.setOnClickListener(v -> pickChat(fragment, refresh));
        card.addView(chatView);

        // Days: "Last  1 2 3 4 5  days", the chosen one filled.
        LinearLayout daysRow = new LinearLayout(context);
        daysRow.setOrientation(LinearLayout.HORIZONTAL);
        daysRow.setGravity(Gravity.CENTER_VERTICAL);
        daysRow.addView(smallText(context, "Last"), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 10, 0));
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
            chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(17), selected ? accent : Theme.multAlpha(accent, 0.1f), Theme.multAlpha(accent, 0.25f)));
            chip.setOnClickListener(v -> {
                AiSummarizer.prefs().edit().putInt("shopDays", d).apply();
                refresh.run();
            });
            daysRow.addView(chip, LayoutHelper.createLinear(34, 34, 0, 0, 6, 0));
        }
        daysRow.addView(smallText(context, days() == 1 ? "day" : "days"), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 0, 0, 0));
        card.addView(daysRow);

        ArrayList<Item> items = load();
        int checked = 0;
        for (Item item : items) {
            if (item.checked) {
                checked++;
            }
        }
        TextView status = smallText(context, items.isEmpty() ? "Nothing on the list yet." :
            items.size() + (items.size() == 1 ? " item" : " items") + (checked > 0 ? " · " + checked + " checked" : ""));
        status.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        card.addView(status);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView scan = AiSummaryActivity.primaryButton(context, "Scan for items");
        scan.setOnClickListener(v -> scan(fragment, refresh));
        buttons.addView(scan, LayoutHelper.createLinear(0, 44, 1f));
        if (!items.isEmpty()) {
            TextView open = new TextView(context);
            open.setText("Open list");
            open.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            open.setTypeface(AndroidUtilities.bold());
            open.setGravity(Gravity.CENTER);
            open.setIncludeFontPadding(false);
            open.setTextColor(accent);
            open.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(10), Theme.multAlpha(accent, 0.1f), Theme.multAlpha(accent, 0.2f)));
            open.setOnClickListener(v -> fragment.presentFragment(new ListActivity()));
            buttons.addView(open, LayoutHelper.createLinear(0, 44, 1f, 10, 0, 0, 0));
        }
        card.addView(buttons);
        return card;
    }

    private static TextView smallText(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        return view;
    }

    private static void pickChat(BaseFragment fragment, Runnable refresh) {
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
            refresh.run();
            return true;
        });
        fragment.presentFragment(picker);
    }

    // ---- Scanning ----

    private static void scan(BaseFragment fragment, Runnable refresh) {
        if (AiSummarizer.prefs().getString("apiKey", "").isEmpty()) {
            BulletinFactory.of(fragment).createErrorBulletin("Set your OpenAI API key first.").show();
            return;
        }
        long did = chat();
        if (did == 0) {
            pickChat(fragment, refresh);
            return;
        }
        if (fragment.getParentActivity() == null) {
            return;
        }
        int days = days();
        ArrayList<Long> chats = new ArrayList<>();
        chats.add(did);
        int since = ConnectionsManager.getInstance(fragment.getCurrentAccount()).getCurrentTime() - days * 24 * 3600;
        AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(true);
        progress.setCanceledOnTouchOutside(false);
        AiSummarizer[] reader = new AiSummarizer[1];
        boolean[] cancelled = {false};
        progress.setOnCancelListener(d -> {
            cancelled[0] = true;
            reader[0].cancel();
        });
        reader[0] = new AiSummarizer(fragment.getCurrentAccount(), chats, since, new AiSummarizer.Callback() {
            @Override
            public void onProgress(String text) {
                progress.setMessage(text);
            }

            @Override
            public void onCollected(int chatCount, int messages, int approxTokens) {
                if (messages == 0) {
                    progress.dismiss();
                    BulletinFactory.of(fragment).createErrorBulletin("No messages in the last " + (days == 1 ? "day" : days + " days") + ".").show();
                    return;
                }
                progress.setMessage("Finding shopping items…");
                AiSummarizer.askOnce(PROMPT, reader[0].transcript(MAX_CHARS), answer -> {
                    if (cancelled[0]) {
                        return;
                    }
                    progress.dismiss();
                    int added = merge(answer, reader[0].refs);
                    refresh.run();
                    if (added < 0) {
                        BulletinFactory.of(fragment).createErrorBulletin("Couldn't read the AI's answer. Try again.").show();
                    } else if (added == 0) {
                        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.done, "No new shopping items found.").show();
                    } else {
                        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.done, "Added " + added + (added == 1 ? " item." : " items.")).show();
                        fragment.presentFragment(new ListActivity());
                    }
                }, error -> {
                    if (!cancelled[0]) {
                        progress.dismiss();
                        BulletinFactory.of(fragment).createErrorBulletin("❌ " + error).show();
                    }
                });
            }

            @Override
            public void onDone(String summary) {
            }

            @Override
            public void onError(String error) {
                progress.dismiss();
                BulletinFactory.of(fragment).createErrorBulletin("❌ " + error).show();
            }
        });
        progress.show();
        reader[0].start();
    }

    // Adds the new items to the saved list (names already on it are skipped, checkmarks kept); -1 if unreadable.
    private static int merge(String answer, ArrayList<AiSummarizer.Ref> refs) {
        JSONArray found;
        try {
            int start = answer.indexOf('{');
            int end = answer.lastIndexOf('}');
            found = new JSONObject(answer.substring(start, end + 1)).getJSONArray("items");
        } catch (Exception e) {
            return -1;
        }
        ArrayList<Item> items = load();
        java.util.HashSet<String> known = new java.util.HashSet<>();
        for (Item item : items) {
            known.add(normalized(item.name));
        }
        int added = 0;
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
            added++;
        }
        save(items);
        return added;
    }

    // ---- The checklist ----

    public static class ListActivity extends BaseFragment {

        private static final int MENU_CLEAR_CHECKED = 1;
        private static final int MENU_CLEAR_ALL = 2;

        private ArrayList<Item> items;
        private UniversalRecyclerView listView;

        @Override
        public View createView(Context context) {
            items = load();
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setTitle("Shopping list");
            ActionBarMenuItem other = actionBar.createMenu().addItem(0, R.drawable.ic_ab_other);
            other.addSubItem(MENU_CLEAR_CHECKED, R.drawable.msg_select, "Clear checked");
            other.addSubItem(MENU_CLEAR_ALL, R.drawable.msg_delete, "Clear all");
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        finishFragment();
                    } else if (id == MENU_CLEAR_CHECKED) {
                        for (int i = items.size() - 1; i >= 0; i--) {
                            if (items.get(i).checked) {
                                items.remove(i);
                            }
                        }
                        changed();
                    } else if (id == MENU_CLEAR_ALL) {
                        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                        builder.setTitle("Clear the shopping list?");
                        builder.setMessage("All " + items.size() + " items will be removed.");
                        builder.setPositiveButton("Clear", (d, w) -> {
                            items.clear();
                            changed();
                        });
                        builder.setNegativeButton("Cancel", null);
                        showDialog(builder.create());
                    }
                }
            });
            FrameLayout frame = new FrameLayout(context);
            frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            listView = new UniversalRecyclerView(this, this::fillItems, null, null);
            frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            fragmentView = frame;
            return frame;
        }

        private void changed() {
            save(items);
            listView.adapter.update(true);
        }

        private void fillItems(ArrayList<UItem> out, UniversalAdapter adapter) {
            if (items.isEmpty()) {
                out.add(UItem.asShadow("The list is empty. Scan a chat from the AI tab to fill it."));
                return;
            }
            // To buy first, then what's checked off.
            for (int pass = 0; pass < 2; pass++) {
                for (Item item : items) {
                    if (item.checked == (pass == 1)) {
                        out.add(UItem.asCustom(row(getContext(), item)));
                    }
                }
            }
            out.add(UItem.asShadow("Tap an item to check it off. Long-press to open the message it came from."));
        }

        // An item row, right to left for Hebrew: round checkbox, name and quantity; checked items struck through.
        private View row(Context context, Item item) {
            boolean rtl = (item.name + item.qty).matches("(?s).*[\\u0590-\\u05FF].*");
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            row.setBackground(Theme.createSelectorWithBackgroundDrawable(Theme.getColor(Theme.key_windowBackgroundWhite), Theme.getColor(Theme.key_listSelector)));
            row.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(12));

            CheckBox2 check = new CheckBox2(context, 22);
            check.setColor(Theme.key_checkbox, Theme.key_radioBackground, Theme.key_checkboxCheck);
            check.setDrawUnchecked(true);
            check.setDrawBackgroundAsArc(10);
            check.setChecked(item.checked, false);
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
                changed();
            });
            row.setOnLongClickListener(v -> {
                if (item.dialogId == 0 || item.messageId == 0) {
                    BulletinFactory.of(this).createErrorBulletin("No source message for this item.").show();
                } else {
                    AiSummaryActivity.openMessage(this, new AiSummarizer.Ref(item.dialogId, item.messageId));
                }
                return true;
            });
            return row;
        }
    }
}
