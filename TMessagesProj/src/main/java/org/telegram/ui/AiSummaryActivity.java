package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.forkgram.ForkDialogs;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.InviteMembersBottomSheet;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// "Digest" bottom tab (after Scoops' digest): a Hebrew news summary of the chats and channels kept in the list,
// over a window of 5 minutes to 8 hours, with the user's own OpenAI key: everything posted in that time, or with
// "Since last digest" (off by default) only what's newer than the previous digest. The last digest is kept and
// can be reopened for free.
public class AiSummaryActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {

    // The time window stops, in minutes: 5 minutes to 8 hours.
    private static final int[] WINDOW_MINUTES = {5, 10, 15, 30, 45, 60, 90, 120, 180, 240, 360, 480};

    private static final int ID_ADD = 2;
    private static final int ID_SINCE_LAST = 6;
    private static final int ID_UNREAD_ONLY = 7;
    private static final int ID_CHAT = 100;

    // The chats kept in this tab's list.
    public static ArrayList<Long> chatsForSummary() {
        ArrayList<Long> chats = new ArrayList<>();
        for (String did : AiSummarizer.prefs().getString("pickedChats", "").split(",")) {
            if (!did.isEmpty()) {
                chats.add(Long.parseLong(did));
            }
        }
        return chats;
    }

    private static boolean sinceLast() {
        return AiSummarizer.prefs().getBoolean("digestSinceLast", false);
    }

    private static boolean unreadOnly() {
        return AiSummarizer.prefs().getBoolean("digestUnreadOnly", false);
    }

    // Per chat, the first message to leave out: after the last digest and/or up to where the chat was read.
    private HashMap<Long, Integer> startAfter() {
        HashMap<Long, Integer> ids = sinceLast() ? lastDigested() : new HashMap<>();
        if (unreadOnly()) {
            for (long did : pickedChats) {
                TLRPC.Dialog dialog = getMessagesController().dialogs_dict.get(did);
                if (dialog != null) {
                    Integer known = ids.get(did);
                    ids.put(did, Math.max(known == null ? 0 : known, dialog.read_inbox_max_id));
                }
            }
        }
        return ids;
    }

    // Per chat, the newest message the last digest included ("did:id,did:id").
    private static HashMap<Long, Integer> lastDigested() {
        HashMap<Long, Integer> map = new HashMap<>();
        for (String pair : AiSummarizer.prefs().getString("lastDigested", "").split(",")) {
            int colon = pair.indexOf(':');
            if (colon > 0) {
                try {
                    map.put(Long.parseLong(pair.substring(0, colon)), Integer.parseInt(pair.substring(colon + 1)));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return map;
    }

    private static void saveLastDigested(ArrayList<AiSummarizer.Ref> refs) {
        HashMap<Long, Integer> map = lastDigested();
        for (AiSummarizer.Ref ref : refs) {
            Integer known = map.get(ref.dialogId);
            if (known == null || ref.messageId > known) {
                map.put(ref.dialogId, ref.messageId);
            }
        }
        StringBuilder out = new StringBuilder();
        for (HashMap.Entry<Long, Integer> e : map.entrySet()) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(e.getKey()).append(':').append(e.getValue());
        }
        AiSummarizer.prefs().edit().putString("lastDigested", out.toString()).apply();
    }

    // A finished digest, kept so it can be reopened without fetching or paying again.
    public static class Digest {
        int minutes;
        long time;
        int posts;
        boolean truncated;
        int coveredSince;
        String text;
        final ArrayList<AiSummarizer.Ref> refs = new ArrayList<>();

        int chatCount() {
            HashSet<Long> chats = new HashSet<>();
            for (AiSummarizer.Ref ref : refs) {
                chats.add(ref.dialogId);
            }
            return chats.size();
        }

        void save() {
            try {
                JSONArray refsJson = new JSONArray();
                for (AiSummarizer.Ref ref : refs) {
                    refsJson.put(new JSONArray().put(ref.dialogId).put(ref.messageId).put(ref.out));
                }
                JSONObject json = new JSONObject()
                    .put("minutes", minutes).put("time", time).put("posts", posts)
                    .put("truncated", truncated).put("coveredSince", coveredSince).put("text", text).put("refs", refsJson);
                AiSummarizer.prefs().edit().putString("lastDigest", json.toString()).apply();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        static Digest load() {
            String raw = AiSummarizer.prefs().getString("lastDigest", null);
            if (raw == null) {
                return null;
            }
            try {
                JSONObject json = new JSONObject(raw);
                Digest d = new Digest();
                d.minutes = json.getInt("minutes");
                d.time = json.getLong("time");
                d.posts = json.getInt("posts");
                d.truncated = json.getBoolean("truncated");
                d.coveredSince = json.getInt("coveredSince");
                d.text = json.getString("text");
                JSONArray refsJson = json.getJSONArray("refs");
                for (int i = 0; i < refsJson.length(); i++) {
                    JSONArray r = refsJson.getJSONArray(i);
                    AiSummarizer.Ref ref = new AiSummarizer.Ref(r.getLong(0), r.getInt(1));
                    ref.out = r.getBoolean(2);
                    d.refs.add(ref);
                }
                return d;
            } catch (Exception e) {
                FileLog.e(e);
                return null;
            }
        }
    }

    private final ArrayList<Long> pickedChats = new ArrayList<>();
    private UniversalRecyclerView listView;
    private AiSummarizer running;
    private AlertDialog progressDialog;
    private View windowCard;
    private TextView estimateView;

    private boolean hasMainTabs;

    public AiSummaryActivity() {
        super();
    }

    public AiSummaryActivity(Bundle args) {
        super(args);
        hasMainTabs = args != null && args.getBoolean("hasMainTabs", false);
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }

    @Override
    public boolean onFragmentCreate() {
        pickedChats.addAll(chatsForSummary());
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(false); // the last digest and source rows after reading chats
        }
        requestCounts();
    }

    @Override
    public View createView(Context context) {
        if (!hasMainTabs) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        }
        actionBar.setTitle("Digest");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        // On the tab the list runs under the top bar and above the bottom tab bar; the Summarize bar sits pinned
        // above the tab bar, so the list leaves room for all three.
        int bottomInset = hasMainTabs ? AndroidUtilities.dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) + AndroidUtilities.navigationBarHeight : 0;
        int topInset = hasMainTabs ? ActionBar.getCurrentActionBarHeight() + (actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0) : 0;
        listView.setPadding(0, topInset, 0, bottomInset + AndroidUtilities.dp(SUMMARIZE_BAR_DP));
        listView.setClipToPadding(false);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        summarizeBar = new FrameLayout(context);
        summarizeBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        summarizeBar.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        summarizeButton = primaryButton(context, "✦  Summarize");
        summarizeBar.addView(summarizeButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 46));
        FrameLayout.LayoutParams barParams = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, SUMMARIZE_BAR_DP, Gravity.BOTTOM);
        barParams.bottomMargin = bottomInset;
        frameLayout.addView(summarizeBar, barParams);
        updateSummarizeBar();

        fragmentView = frameLayout;
        return fragmentView;
    }

    private static final int SUMMARIZE_BAR_DP = 62;
    private FrameLayout summarizeBar;
    private TextView summarizeButton;

    // The pinned button: Summarize, or "Set OpenAI API key" until there is one.
    private void updateSummarizeBar() {
        if (summarizeButton == null) {
            return;
        }
        boolean hasKey = !AiSummarizer.prefs().getString("apiKey", "").isEmpty();
        summarizeButton.setText(hasKey ? "✦  Summarize" : "Set OpenAI API key");
        boolean enabled = !hasKey || !pickedChats.isEmpty();
        summarizeButton.setEnabled(enabled);
        summarizeButton.setAlpha(enabled ? 1f : 0.5f);
        summarizeButton.setOnClickListener(v -> {
            if (hasKey) {
                startSummary();
            } else {
                askKey(getParentActivity(), () -> {
                    listView.adapter.update(true);
                    updateSummarizeBar();
                });
            }
        });
    }

    private static int windowIndex() {
        return Math.max(0, Math.min(WINDOW_MINUTES.length - 1, AiSummarizer.prefs().getInt("digestWindow", 5))); // 1 hour
    }

    public static String windowLabel(int minutes) {
        if (minutes < 60) {
            return minutes + " minutes";
        }
        if (minutes % 60 != 0) {
            return minutes / 60 + "h " + minutes % 60 + "m";
        }
        return minutes == 60 ? "1 hour" : minutes / 60 + " hours";
    }

    // Posts per chat in the chosen window ("did:minutes"), fetched from Telegram one small request per chat and
    // kept for 2 minutes: getHistory at the window's start, limit 1, whose offset_id_offset is the number of
    // newer posts. Asked only after the slider settles, a quarter second apart.
    private static final HashMap<String, Integer> postCounts = new HashMap<>();
    private static final HashMap<String, Long> postCountTimes = new HashMap<>();
    private Runnable pendingCount;
    private boolean counting;

    private static String countKey(long did, int minutes) {
        return did + ":" + minutes;
    }

    // Posts in the window for this chat, or null while unknown. With "Only since last digest", channels are
    // capped at what's newer than the last digest.
    private Integer postsInWindow(long did) {
        int minutes = WINDOW_MINUTES[windowIndex()];
        TLRPC.Dialog dialog = getMessagesController().dialogs_dict.get(did);
        if (dialog != null && dialog.last_message_date != 0 && dialog.last_message_date < getConnectionsManager().getCurrentTime() - minutes * 60) {
            return 0;
        }
        Integer count = postCounts.get(countKey(did, minutes));
        if (count == null || count < 0) {
            return count == null ? null : -1;
        }
        Integer last = sinceLast() ? lastDigested().get(did) : null;
        if (last != null && dialog != null && did < 0 && ChatObject.isChannel(getMessagesController().getChat(-did))) {
            count = Math.min(count, Math.max(0, dialog.top_message - last));
        }
        if (unreadOnly()) {
            count = Math.min(count, dialog == null ? 0 : dialog.unread_count);
        }
        return count;
    }

    private void requestCounts() {
        if (pendingCount != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingCount);
        }
        pendingCount = () -> {
            pendingCount = null;
            int minutes = WINDOW_MINUTES[windowIndex()];
            ArrayList<Long> todo = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (long did : pickedChats) {
                String key = countKey(did, minutes);
                Long at = postCountTimes.get(key);
                if (postsInWindow(did) == null || at != null && now - at > 120_000) {
                    todo.add(did);
                }
            }
            if (!todo.isEmpty() && !counting) {
                counting = true;
                countNext(todo, 0, minutes);
            }
        };
        AndroidUtilities.runOnUIThread(pendingCount, 500);
    }

    private void countNext(ArrayList<Long> todo, int index, int minutes) {
        if (index >= todo.size() || getParentActivity() == null) {
            counting = false;
            if (WINDOW_MINUTES[windowIndex()] != minutes) {
                requestCounts(); // the slider moved meanwhile
            }
            return;
        }
        long did = todo.get(index);
        int windowStart = getConnectionsManager().getCurrentTime() - minutes * 60;
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = getMessagesController().getInputPeer(did);
        req.offset_date = windowStart;
        req.limit = 1;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null && error.text != null && error.text.startsWith("FLOOD_WAIT")) {
                counting = false; // Telegram asked to slow down: stop counting
                return;
            }
            int count = -1;
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                TLRPC.Dialog dialog = getMessagesController().dialogs_dict.get(did);
                if (res.messages.isEmpty()) {
                    count = res.count > 0 ? res.count : -1; // nothing older: the whole chat is in the window
                } else if (res.offset_id_offset > 0) {
                    count = res.offset_id_offset;
                } else if (dialog != null && did < 0 && ChatObject.isChannel(getMessagesController().getChat(-did))) {
                    count = Math.max(0, dialog.top_message - res.messages.get(0).id); // channel ids are consecutive
                }
            }
            postCounts.put(countKey(did, minutes), count);
            postCountTimes.put(countKey(did, minutes), System.currentTimeMillis());
            if (listView != null) {
                listView.adapter.update(false);
            }
            AndroidUtilities.runOnUIThread(() -> countNext(todo, index + 1, minutes), 250);
        }));
    }

    private void updateEstimate() {
        if (estimateView == null) {
            return;
        }
        int total = 0;
        int chats = 0;
        boolean unknown = false;
        for (long did : pickedChats) {
            Integer n = postsInWindow(did);
            if (n == null || n < 0) {
                unknown = true;
            } else if (n > 0) {
                total += n;
                chats++;
            }
        }
        int color = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
        String text;
        if (pickedChats.isEmpty()) {
            text = "Add chats or channels below";
            color = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        } else if (unknown && total == 0) {
            text = "Counting posts…";
            color = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        } else if (total == 0) {
            text = unreadOnly() ? "No unread posts in this time" : sinceLast() ? "Nothing new since the last digest" : "No posts in this time";
            color = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        } else {
            text = (unknown ? "At least " : "") + total + (unreadOnly() ? " unread" : "") + (total == 1 ? " post" : " posts") + " in " + chats + (chats == 1 ? " chat" : " chats");
        }
        estimateView.setText(text);
        estimateView.setTextColor(color);
    }

    private static TextView sectionLabel(Context context, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        label.setLetterSpacing(0.04f);
        label.setTypeface(AndroidUtilities.bold());
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        return label;
    }

    // The time window, as in Scoops: "Posted in the last", the span in large type, a stepped slider and the estimate.
    private View createWindowCard(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        card.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(16), AndroidUtilities.dp(21), AndroidUtilities.dp(14));
        card.addView(sectionLabel(context, "POSTED IN THE LAST"));

        TextView value = new TextView(context);
        value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        value.setTypeface(AndroidUtilities.bold());
        value.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        value.setText(windowLabel(WINDOW_MINUTES[windowIndex()]));
        card.addView(value, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 4));

        int last = WINDOW_MINUTES.length - 1;
        SeekBarView slider = new SeekBarView(context);
        slider.setReportChanges(true);
        slider.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                int index = Math.round(progress * last);
                if (index != windowIndex()) {
                    AiSummarizer.prefs().edit().putInt("digestWindow", index).apply();
                    value.setText(windowLabel(WINDOW_MINUTES[index]));
                    AndroidUtilities.vibrateCursor(slider);
                    updateEstimate();
                    requestCounts();
                }
            }

            @Override
            public int getStepsCount() {
                return last;
            }
        });
        slider.setProgress(windowIndex() / (float) last);
        card.addView(slider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38, 0, -15, 0, -15, 0));

        estimateView = new TextView(context);
        estimateView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        estimateView.setTypeface(AndroidUtilities.bold());
        card.addView(estimateView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        return card;
    }

    // A kept chat: avatar, name, "N new · last 08:57", and ✕ to remove it from the list. Tapping opens the chat.
    private View createSourceRow(Context context, long did) {
        FrameLayout row = new FrameLayout(context);
        row.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        TLObject peer = did > 0 ? getMessagesController().getUser(did) : getMessagesController().getChat(-did);

        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(AndroidUtilities.dp(18));
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        if (peer != null) {
            avatarDrawable.setInfo(currentAccount, peer);
            avatar.setForUserOrChat(peer, avatarDrawable);
        }
        row.addView(avatar, LayoutHelper.createFrame(36, 36, Gravity.LEFT | Gravity.CENTER_VERTICAL, 18, 8, 0, 8));

        TextView name = new TextView(context);
        name.setText(AiSummarizer.chatTitle(currentAccount, did));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextDirection(View.TEXT_DIRECTION_LTR);
        row.addView(name, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 68, 8, 56, 0));

        TLRPC.Dialog dialog = getMessagesController().dialogs_dict.get(did);
        Integer n = postsInWindow(did);
        TextView sub = new TextView(context);
        String lastText = dialog == null || dialog.last_message_date == 0 ? "" : " · last " + LocaleController.stringForMessageListDate(dialog.last_message_date);
        String count;
        if (n == null) {
            count = "Counting…";
        } else if (n < 0) {
            count = "Posted in this time";
        } else if (n == 0) {
            count = unreadOnly() ? "Nothing unread" : sinceLast() ? "Nothing new" : "No posts in this time";
        } else {
            count = n + (unreadOnly() ? " unread" : sinceLast() ? " new" : "") + (n == 1 ? " post" : " posts");
        }
        sub.setText(count + lastText);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        sub.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        sub.setSingleLine(true);
        row.addView(sub, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 68, 29, 56, 0));

        TextView remove = new TextView(context);
        remove.setText("✕");
        remove.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        remove.setGravity(Gravity.CENTER);
        remove.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        remove.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        remove.setContentDescription("Remove");
        remove.setOnClickListener(v -> {
            pickedChats.remove(did);
            AiSummarizer.prefs().edit().putString("pickedChats", TextUtils.join(",", pickedChats)).apply();
            listView.adapter.update(true);
            updateEstimate();
        });
        row.addView(remove, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        row.setMinimumHeight(AndroidUtilities.dp(52));
        return row;
    }

    // The previous digest, reopened without fetching or paying again.
    private View createLastDigestCard(Context context, Digest digest) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        card.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(14), AndroidUtilities.dp(21), AndroidUtilities.dp(14));
        card.addView(sectionLabel(context, "LAST DIGEST"));

        TextView meta = new TextView(context);
        meta.setText("Last " + windowLabel(digest.minutes) + " · " + whenText(digest.time));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        meta.setTypeface(AndroidUtilities.bold());
        meta.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        card.addView(meta, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 4));

        TextView preview = new TextView(context);
        preview.setText(previewText(digest.text));
        preview.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        preview.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        preview.setMaxLines(2);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        preview.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        card.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView open = new TextView(context);
        open.setText("Open full digest ›");
        open.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        open.setTypeface(AndroidUtilities.bold());
        open.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        card.addView(open, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        card.setOnClickListener(v -> presentFragment(new ResultActivity(digest)));
        return card;
    }

    private static String previewText(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : plainText(text).split("\n")) {
            if (!line.trim().isEmpty()) {
                if (out.length() > 0) {
                    out.append(" · ");
                }
                out.append(line.trim());
                if (out.length() > 160) {
                    break;
                }
            }
        }
        return out.toString();
    }

    static String whenText(long time) {
        String clock = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(time));
        return (android.text.format.DateUtils.isToday(time) ? "Today " : new SimpleDateFormat("EEE", Locale.US).format(new Date(time)) + " ") + clock;
    }

    private View createIntro(Context context) {
        TextView intro = new TextView(context);
        intro.setText("A Hebrew news summary of the chats and channels below, for the time you choose.");
        intro.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        intro.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        intro.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(14), AndroidUtilities.dp(21), AndroidUtilities.dp(10));
        return intro;
    }

    // A filled button as a plain TextView: text centered by gravity without font padding, so it stays centered
    // with any font (Telegram's animated button places text from font metrics, which drift with Google Sans).
    static TextView primaryButton(Context context, String text) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setSingleLine(true);
        int color = Theme.getColor(Theme.key_featuredStickers_addButton);
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(10), color, Theme.blendOver(color, Theme.multAlpha(0xFFFFFFFF, 0.15f))));
        return button;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        Context context = getContext();
        items.add(UItem.asCustom(createIntro(context)));
        if (windowCard == null) {
            windowCard = createWindowCard(context);
        }
        updateEstimate();
        items.add(UItem.asCustom(windowCard));
        items.add(UItem.asButtonCheck(ID_SINCE_LAST, "Only since last digest", "Skip posts your previous digest already covered.")
            .setChecked(sinceLast()).setMultiline(true));
        items.add(UItem.asButtonCheck(ID_UNREAD_ONLY, "Unread messages only", "Skip posts you've already read in each chat.")
            .setChecked(unreadOnly()).setMultiline(true));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Sources"));
        for (int i = 0; i < pickedChats.size(); i++) {
            items.add(UItem.asCustom(ID_CHAT + i, createSourceRow(context, pickedChats.get(i))));
        }
        items.add(UItem.asButton(ID_ADD, R.drawable.msg_add, "Add chats or channels").accent());
        items.add(UItem.asShadow("The list is kept until you change it. Secret chats are never sent."));

        if (AiSummarizer.prefs().getString("apiKey", "").isEmpty()) {
            items.add(UItem.asShadow("The digest uses your own OpenAI key. It's stored only on this phone; change it later in Tegram's settings."));
        }
        updateSummarizeBar();

        Digest last = Digest.load();
        if (last != null) {
            items.add(UItem.asShadow(null));
            items.add(UItem.asCustom(createLastDigestCard(context, last)));
            items.add(UItem.asShadow(null));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ADD) {
            InviteMembersBottomSheet sheet = new InviteMembersBottomSheet(getContext(), currentAccount, null, 0, this, null);
            sheet.setDelegate(dids -> {
                pickedChats.clear();
                for (Long did : dids) {
                    if (!DialogObject.isEncryptedDialog(did)) {
                        pickedChats.add(did);
                    }
                }
                AiSummarizer.prefs().edit().putString("pickedChats", TextUtils.join(",", pickedChats)).apply();
                listView.adapter.update(true);
                requestCounts();
            }, pickedChats);
            sheet.setSelectedContacts(pickedChats);
            showDialog(sheet);
        } else if (item.id >= ID_CHAT && item.id < ID_CHAT + pickedChats.size()) {
            long did = pickedChats.get(item.id - ID_CHAT);
            Bundle args = new Bundle();
            if (did > 0) {
                args.putLong("user_id", did);
            } else {
                args.putLong("chat_id", -did);
            }
            presentFragment(new ChatActivity(args));
        } else if (item.id == ID_UNREAD_ONLY) {
            AiSummarizer.prefs().edit().putBoolean("digestUnreadOnly", !unreadOnly()).apply();
            listView.adapter.update(true);
            updateEstimate();
        } else if (item.id == ID_SINCE_LAST) {
            AiSummarizer.prefs().edit().putBoolean("digestSinceLast", !sinceLast()).apply();
            listView.adapter.update(true);
            updateEstimate();
        }
    }

    private void startSummary() {
        if (AiSummarizer.prefs().getString("apiKey", "").isEmpty()) {
            BulletinFactory.of(this).createErrorBulletin("Set your OpenAI API key first.").show();
            return;
        }
        if (pickedChats.isEmpty() || getParentActivity() == null) {
            return;
        }
        int minutes = WINDOW_MINUTES[windowIndex()];
        int since = ConnectionsManager.getInstance(currentAccount).getCurrentTime() - minutes * 60;
        ArrayList<Long> chats = new ArrayList<>(pickedChats);

        progressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.setCanCancel(true);
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.setOnCancelListener(d -> {
            if (running != null) {
                running.cancel();
                running = null;
            }
        });
        progressDialog.show();

        running = new AiSummarizer(currentAccount, chats, since, new AiSummarizer.Callback() {
            @Override
            public void onProgress(String text) {
                if (progressDialog != null) {
                    progressDialog.setMessage(text);
                }
            }

            @Override
            public void onCollected(int chatCount, int messages, int approxTokens) {
                if (messages == 0) {
                    dismissProgress();
                    running = null;
                    BulletinFactory.of(AiSummaryActivity.this).createErrorBulletin(unreadOnly() ? "No unread posts in this time." : sinceLast() ? "Nothing new since the last digest in this time." : "No posts in this time.").show();
                    return;
                }
                running.send();
            }

            @Override
            public void onDone(String summary) {
                dismissProgress();
                if (running == null) {
                    return;
                }
                Digest digest = new Digest();
                digest.minutes = minutes;
                digest.time = System.currentTimeMillis();
                digest.posts = running.digestPosts;
                digest.truncated = running.truncated;
                digest.coveredSince = running.coveredSince;
                digest.text = summary;
                digest.refs.addAll(running.refs);
                running = null;
                digest.save();
                saveLastDigested(digest.refs);
                if (listView != null) {
                    listView.adapter.update(true);
                }
                if (getParentActivity() != null) {
                    presentFragment(new ResultActivity(digest));
                }
            }

            @Override
            public void onError(String error) {
                dismissProgress();
                running = null;
                if (getParentActivity() == null) {
                    return;
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle("Digest failed");
                builder.setMessage(error);
                builder.setPositiveButton("OK", null);
                showDialog(builder.create());
            }
        });
        running.setNewsDigest(windowLabel(minutes));
        running.setMinMessageIds(startAfter());
        running.start();
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    @Override
    public void onFragmentDestroy() {
        if (running != null) {
            running.cancel();
            running = null;
        }
        super.onFragmentDestroy();
    }

    // Also used by Tegram's settings (MySettings), where the key can be changed once set.
    public static void askKey(Context context, Runnable done) {
        ForkDialogs.createFieldAlert(context, "OpenAI API key", AiSummarizer.prefs().getString("apiKey", ""), result -> {
            AiSummarizer.prefs().edit().putString("apiKey", result.trim()).apply();
            if (done != null) {
                done.run();
            }
            return null;
        });
    }

    // ---- Rendering (also used by catch-up and Ask AI) ----

    // [r12], [r12, r15] or [r12, 15]: one or more references to source messages.
    private static final Pattern REF = Pattern.compile("\\s*\\[r(\\d+)((?:\\s*,\\s*r?\\d+)*)\\]");
    private static final Pattern MORE_REFS = Pattern.compile("r?(\\d+)");
    // A bullet's leading time: "08:54" or "אתמול 23:40".
    private static final Pattern TIME = Pattern.compile("^((?:אתמול )?\\d{1,2}:\\d{2})\\s*[-–—:·]?\\s*");

    static String plainText(String summary) {
        return summary.replaceAll("\\s*\\[r[\\d,\\sr]+\\]", "").replaceAll("(?m)^#+ ", "").replaceAll("(?m)^[-*] ", "• ").replace("**", "").trim();
    }

    // Selectable summary text: headings, bullets with a muted time and a hanging indent, **bold**, and [rN]
    // references as chat chips that open the source message. Paragraphs with Hebrew always read right-to-left.
    public static TextView summaryText(Context context, String summary, ArrayList<AiSummarizer.Ref> refs, Utilities.Callback<AiSummarizer.Ref> onRef) {
        TextView textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        textView.setLineSpacing(AndroidUtilities.dp(3), 1f);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText));
        textView.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(10), AndroidUtilities.dp(18), AndroidUtilities.dp(16));
        // Not selectable, so dragging always scrolls; source chips stay tappable and long-press copies it all.
        textView.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        textView.setText(render(summary, refs, onRef, textView.getPaint()));
        textView.setMovementMethod(LinkMovementMethod.getInstance());
        textView.setOnLongClickListener(v -> {
            AndroidUtilities.addToClipboard(plainText(summary));
            return true;
        });
        return textView;
    }

    public static ScrollView summaryView(Context context, String summary, ArrayList<AiSummarizer.Ref> refs, Utilities.Callback<AiSummarizer.Ref> onRef) {
        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(summaryText(context, summary, refs, onRef), LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        return scrollView;
    }

    private static CharSequence render(String text, ArrayList<AiSummarizer.Ref> refs, Utilities.Callback<AiSummarizer.Ref> onRef, TextPaint paint) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        int account = UserConfig.selectedAccount;
        int muted = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                int start = out.length();
                out.append(line.replaceFirst("^#+\\s*", ""));
                applyBold(out, start);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(1.12f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }
            int lineStart = out.length();
            boolean bullet = line.startsWith("- ") || line.startsWith("* ");
            if (bullet) {
                line = line.substring(2).trim();
                out.append("•  ");
                Matcher time = TIME.matcher(line);
                if (time.find()) {
                    int timeStart = out.length();
                    out.append(time.group(1));
                    out.setSpan(new ForegroundColorSpan(muted), timeStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(0.86f), timeStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.append("  ");
                    line = line.substring(time.end());
                }
            }
            Matcher matcher = REF.matcher(line);
            int last = 0;
            while (matcher.find()) {
                out.append(line, last, matcher.start());
                ArrayList<Integer> numbers = new ArrayList<>();
                numbers.add(Integer.parseInt(matcher.group(1)));
                Matcher more = MORE_REFS.matcher(matcher.group(2) == null ? "" : matcher.group(2));
                while (more.find()) {
                    numbers.add(Integer.parseInt(more.group(1)));
                }
                for (int number : numbers) {
                    int refIndex = number - 1;
                    if (refIndex < 0 || refIndex >= refs.size()) {
                        continue;
                    }
                    AiSummarizer.Ref ref = refs.get(refIndex);
                    String chat = AiSummarizer.chatTitle(account, ref.dialogId);
                    if (chat.length() > 16) {
                        chat = chat.substring(0, 15) + "…";
                    }
                    out.append(' ');
                    int start = out.length();
                    out.append(chat);
                    out.setSpan(new ChipSpan(), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ClickableSpan() {
                        @Override
                        public void onClick(View widget) {
                            onRef.run(ref);
                        }

                        @Override
                        public void updateDrawState(TextPaint ds) {
                            ds.setUnderlineText(false);
                        }
                    }, start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                last = matcher.end();
            }
            out.append(line, last, line.length()).append('\n');
            applyBold(out, lineStart);
            if (bullet) {
                // Wrapped lines start under the text, not under the bullet.
                int indent = (int) paint.measureText("•  ");
                out.setSpan(new LeadingMarginSpan.Standard(0, indent), lineStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return out;
    }

    // "**key fact**": drop the markers and bold the text between them.
    private static void applyBold(SpannableStringBuilder out, int from) {
        int open;
        while ((open = out.toString().indexOf("**", from)) >= 0) {
            int close = out.toString().indexOf("**", open + 2);
            if (close < 0) {
                break;
            }
            out.delete(close, close + 2);
            out.delete(open, open + 2);
            out.setSpan(new StyleSpan(Typeface.BOLD), open, close - 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = close - 2;
        }
    }

    // A small rounded chip with the source chat's name.
    private static class ChipSpan extends ReplacementSpan {
        private static final float SCALE = 0.78f;
        private final RectF rect = new RectF();
        private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            float old = paint.getTextSize();
            paint.setTextSize(old * SCALE);
            int width = (int) paint.measureText(text, start, end) + AndroidUtilities.dp(12);
            paint.setTextSize(old);
            return width;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            int link = Theme.getColor(Theme.key_windowBackgroundWhiteLinkText);
            Paint.FontMetrics full = paint.getFontMetrics();
            float centerY = y + (full.ascent + full.descent) / 2f; // middle of the surrounding text
            float old = paint.getTextSize();
            int oldColor = paint.getColor();
            paint.setTextSize(old * SCALE);
            Paint.FontMetrics small = paint.getFontMetrics();
            float width = paint.measureText(text, start, end) + AndroidUtilities.dp(12);
            float height = small.descent - small.ascent + AndroidUtilities.dp(4);
            rect.set(x, centerY - height / 2, x + width, centerY + height / 2);
            background.setColor(Theme.multAlpha(link, 0.12f));
            canvas.drawRoundRect(rect, AndroidUtilities.dp(7), AndroidUtilities.dp(7), background);
            paint.setColor(link);
            canvas.drawText(text, start, end, x + AndroidUtilities.dp(6), centerY - (small.ascent + small.descent) / 2, paint);
            paint.setTextSize(old);
            paint.setColor(oldColor);
        }
    }

    public static void openMessage(BaseFragment fragment, AiSummarizer.Ref ref) {
        Bundle args = new Bundle();
        if (ref.dialogId > 0) {
            args.putLong("user_id", ref.dialogId);
        } else {
            args.putLong("chat_id", -ref.dialogId);
        }
        args.putInt("message_id", ref.messageId);
        fragment.presentFragment(new ChatActivity(args));
    }

    // R1: the digest as an article, with what it covers, a disclaimer and Mark as read pinned at the bottom.
    public static class ResultActivity extends BaseFragment {

        private final Digest digest;

        ResultActivity(Digest digest) {
            this.digest = digest;
        }

        @Override
        public View createView(Context context) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setTitle("Digest");
            String share = "Digest · last " + windowLabel(digest.minutes) + " · " + whenText(digest.time) + "\n\n" + plainText(digest.text);
            actionBar.createMenu().addItem(3, R.drawable.msg_markread).setContentDescription("Mark as read");
            actionBar.createMenu().addItem(1, R.drawable.msg_copy);
            actionBar.createMenu().addItem(2, R.drawable.msg_share);
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        finishFragment();
                    } else if (id == 3) {
                        confirmMarkRead();
                    } else if (id == 1) {
                        AndroidUtilities.addToClipboard(share);
                        BulletinFactory.of(ResultActivity.this).createCopyBulletin("Copied").show();
                    } else if (id == 2 && getParentActivity() != null) {
                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setType("text/plain");
                        intent.putExtra(Intent.EXTRA_TEXT, share);
                        getParentActivity().startActivity(Intent.createChooser(intent, "Share digest"));
                    }
                }
            });

            FrameLayout frame = new FrameLayout(context);
            frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            ScrollView scroll = new ScrollView(context);
            scroll.setClipToPadding(false);
            // Room for the bottom tab bar and the system navigation bar, which can sit over this screen.
            scroll.setPadding(0, 0, 0, AndroidUtilities.dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) + AndroidUtilities.navigationBarHeight);
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(column, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
            frame.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            column.addView(sectionLabel(context, ("Digest of the last " + windowLabel(digest.minutes)).toUpperCase(Locale.US)),
                LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 18, 16, 18, 0));

            int chats = digest.chatCount();
            TextView meta = new TextView(context);
            meta.setText(whenText(digest.time) + " · " + chats + (chats == 1 ? " chat" : " chats") + " · " + digest.posts + " posts");
            meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            meta.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            column.addView(meta, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 18, 2, 18, 0));

            if (digest.truncated && digest.coveredSince > 0) {
                TextView warn = new TextView(context);
                int amber = 0xFFB26A00;
                warn.setText("Busy window: covers from " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(digest.coveredSince * 1000L)) + ". Older posts didn't fit.");
                warn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                warn.setTextColor(amber);
                warn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), Theme.multAlpha(amber, 0.12f)));
                warn.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(6), AndroidUtilities.dp(10), AndroidUtilities.dp(6));
                column.addView(warn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 18, 10, 18, 0));
            }

            column.addView(summaryText(context, digest.text, digest.refs, ref -> openMessage(this, ref)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            TextView disclaimer = new TextView(context);
            disclaimer.setText("ⓘ Written by a language model from the posts' own text. Early reports are often unverified.");
            disclaimer.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            disclaimer.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            column.addView(disclaimer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 18, 0, 18, 8));

            markRead = primaryButton(context, "Mark " + chats + (chats == 1 ? " chat" : " chats") + " as read");
            markRead.setOnClickListener(v -> confirmMarkRead());
            column.addView(markRead, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 16, 8, 16, 16));
            fragmentView = frame;
            return fragmentView;
        }

        private TextView markRead;
        private boolean marked;

        // From the button at the end of the digest or the ✓✓ icon in the top bar.
        private void confirmMarkRead() {
            if (marked) {
                return;
            }
            Runnable mark = () -> {
                int chats = markDigestedAsRead();
                marked = true;
                if (markRead != null) {
                    markRead.setEnabled(false);
                    markRead.setAlpha(0.6f);
                    markRead.setText(chats == 0 ? "Nothing to mark" : "✓ Marked as read");
                }
                String done = chats == 0 ? "Nothing to mark." : "Marked " + chats + (chats == 1 ? " chat" : " chats") + " as read.";
                // Back to the chat list: close the digest and switch the main tabs to Chats.
                MainTabsActivity tabs = null;
                if (getParentLayout() != null) {
                    for (BaseFragment fragment : getParentLayout().getFragmentStack()) {
                        if (fragment instanceof MainTabsActivity) {
                            tabs = (MainTabsActivity) fragment;
                        }
                    }
                }
                if (tabs == null) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.contacts_sync_on, done).show();
                    return;
                }
                MainTabsActivity finalTabs = tabs;
                finalTabs.myShowChats();
                finishFragment();
                AndroidUtilities.runOnUIThread(() -> BulletinFactory.of(finalTabs).createSimpleBulletin(R.raw.contacts_sync_on, done).show(), 300);
            };
            if (digest.truncated && getParentActivity() != null) {
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle("Mark as read?");
                builder.setMessage("Some older posts in this window didn't fit in the digest. They'll be marked as read too.");
                builder.setPositiveButton("Mark as read", (d, w) -> mark.run());
                builder.setNegativeButton("Cancel", null);
                showDialog(builder.create());
            } else {
                mark.run();
            }
        }

        // Each chat in the digest is marked read up to the newest message the digest read, so anything that
        // arrived since stays unread.
        private int markDigestedAsRead() {
            MessagesController controller = getMessagesController();
            android.util.LongSparseArray<Integer> newest = new android.util.LongSparseArray<>();
            android.util.LongSparseArray<Integer> unreadDigested = new android.util.LongSparseArray<>();
            for (AiSummarizer.Ref ref : digest.refs) {
                Integer known = newest.get(ref.dialogId);
                if (known == null || ref.messageId > known) {
                    newest.put(ref.dialogId, ref.messageId);
                }
                TLRPC.Dialog dialog = controller.dialogs_dict.get(ref.dialogId);
                if (!ref.out && dialog != null && ref.messageId > dialog.read_inbox_max_id) {
                    unreadDigested.put(ref.dialogId, unreadDigested.get(ref.dialogId, 0) + 1);
                }
            }
            int now = getConnectionsManager().getCurrentTime();
            for (int i = 0; i < newest.size(); i++) {
                long did = newest.keyAt(i);
                int maxId = newest.valueAt(i);
                TLRPC.Dialog dialog = controller.dialogs_dict.get(did);
                boolean reachedTop = dialog == null || maxId >= dialog.top_message;
                if (reachedTop && dialog != null && dialog.unread_mentions_count > 0) {
                    controller.markMentionsAsRead(did, 0);
                }
                // countDiff 0 zeroes the badge, right only when the digest reached the newest message. Otherwise
                // subtract just the unread messages the digest included, so newer ones stay counted.
                int countDiff = reachedTop ? 0 : unreadDigested.get(did, 0);
                if (!reachedTop && countDiff == 0) {
                    continue; // nothing digested here was unread
                }
                controller.markDialogAsRead(did, maxId, maxId, now, false, 0, countDiff, true, 0);
            }
            return newest.size();
        }
    }
}
