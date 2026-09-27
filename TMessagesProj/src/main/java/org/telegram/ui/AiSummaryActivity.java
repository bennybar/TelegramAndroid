package org.telegram.ui;

import android.app.TimePickerDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.forkgram.ForkDialogs;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.InviteMembersBottomSheet;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// "AI" bottom tab: summarize chats over a time window with the user's own OpenAI key.
public class AiSummaryActivity extends BaseFragment {

    private static final String[] WINDOW_LABELS = {"8 hours", "1 day", "2 days", "3 days"};
    private static final int[] WINDOW_HOURS = {8, 24, 48, 72};

    private static final int ID_MODE_UNREAD = 1;
    private static final int ID_MODE_PICK = 2;
    private static final int ID_SUMMARIZE = 3;
    private static final int ID_LAST = 4;
    private static final int ID_KEY = 5;
    private static final int ID_MODEL = 6;
    private static final int ID_DIGEST = 7;
    private static final int ID_DIGEST_TIME = 8;
    private static final int ID_DIGEST_NOW = 9;

    // Last result (from the tab or the morning digest), saved so reopening costs nothing.
    public static void saveLast(String title, String summary, ArrayList<AiSummarizer.Ref> refs) {
        StringBuilder refText = new StringBuilder();
        for (AiSummarizer.Ref ref : refs) {
            refText.append(ref.dialogId).append(':').append(ref.messageId).append(',');
        }
        AiSummarizer.prefs().edit().putString("lastTitle", title).putString("lastSummary", summary).putString("lastRefs", refText.toString()).apply();
    }

    public static BaseFragment lastResultFragment() {
        String summary = AiSummarizer.prefs().getString("lastSummary", null);
        if (summary == null) {
            return null;
        }
        ArrayList<AiSummarizer.Ref> refs = new ArrayList<>();
        for (String ref : AiSummarizer.prefs().getString("lastRefs", "").split(",")) {
            int colon = ref.indexOf(':');
            if (colon > 0) {
                refs.add(new AiSummarizer.Ref(Long.parseLong(ref.substring(0, colon)), Integer.parseInt(ref.substring(colon + 1))));
            }
        }
        return new ResultActivity(AiSummarizer.prefs().getString("lastTitle", ""), summary, refs);
    }

    // The chats chosen in this tab: the picked list, or unread chats with activity since `since`.
    public static ArrayList<Long> chatsForSummary(int account, int since) {
        if (!AiSummarizer.prefs().getBoolean("pickMode", false)) {
            return AiSummarizer.unreadChats(account, since);
        }
        ArrayList<Long> chats = new ArrayList<>();
        for (String did : AiSummarizer.prefs().getString("pickedChats", "").split(",")) {
            if (!did.isEmpty()) {
                chats.add(Long.parseLong(did));
            }
        }
        return chats;
    }

    private final ArrayList<Long> pickedChats = new ArrayList<>();
    private UniversalRecyclerView listView;
    private AiSummarizer running;
    private AlertDialog progressDialog;

    @Override
    public boolean onFragmentCreate() {
        for (String did : AiSummarizer.prefs().getString("pickedChats", "").split(",")) {
            if (!did.isEmpty()) {
                pickedChats.add(Long.parseLong(did));
            }
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("AI summary");
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
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = frameLayout;
        return fragmentView;
    }

    private int windowIndex() {
        return Math.max(0, Math.min(WINDOW_HOURS.length - 1, AiSummarizer.prefs().getInt("window", 1)));
    }

    private boolean pickMode() {
        return AiSummarizer.prefs().getBoolean("pickMode", false);
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Time window"));
        items.add(UItem.asSlideView(WINDOW_LABELS, windowIndex(), index -> AiSummarizer.prefs().edit().putInt("window", index).apply()));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Chats"));
        items.add(UItem.asRadio(ID_MODE_UNREAD, "Unread chats").setChecked(!pickMode()));
        items.add(UItem.asRadio(ID_MODE_PICK, "Choose chats", pickedChats.isEmpty() ? "" : pickedChats.size() + " selected").setChecked(pickMode()));
        items.add(UItem.asShadow("Unread chats: private chats and groups with unread messages in the window, up to " + AiSummarizer.MAX_UNREAD_CHATS + ". Channels are skipped. Secret chats are never sent."));

        items.add(UItem.asButton(ID_SUMMARIZE, "Summarize").accent());
        if (AiSummarizer.prefs().contains("lastSummary")) {
            items.add(UItem.asButton(ID_LAST, "Last summary", AiSummarizer.prefs().getString("lastTitle", "")));
        }
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Morning digest"));
        items.add(UItem.asButtonCheck(ID_DIGEST, "Morning digest", "Every day, summarize the last 12 hours of the chats above into one notification.")
            .setChecked(MyDigest.enabled()).setMultiline(true));
        if (MyDigest.enabled()) {
            items.add(UItem.asButton(ID_DIGEST_TIME, "Time", MyDigest.timeText()));
            items.add(UItem.asButton(ID_DIGEST_NOW, "Send now"));
        }
        items.add(UItem.asShadow("Android may deliver it a few minutes late while the phone is asleep."));

        String key = AiSummarizer.prefs().getString("apiKey", "");
        items.add(UItem.asHeader("OpenAI"));
        items.add(UItem.asButton(ID_KEY, "API key", key.length() > 8 ? "…" + key.substring(key.length() - 4) : "Not set"));
        items.add(UItem.asButton(ID_MODEL, "Model", AiSummarizer.prefs().getString("model", AiSummarizer.DEFAULT_MODEL)));
        items.add(UItem.asShadow("Your key is stored only on this phone and is never included in settings export."));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_MODE_UNREAD) {
            AiSummarizer.prefs().edit().putBoolean("pickMode", false).apply();
            listView.adapter.update(true);
        } else if (item.id == ID_MODE_PICK) {
            AiSummarizer.prefs().edit().putBoolean("pickMode", true).apply();
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
            }, pickedChats);
            sheet.setSelectedContacts(pickedChats);
            showDialog(sheet);
            listView.adapter.update(true);
        } else if (item.id == ID_KEY) {
            ForkDialogs.createFieldAlert(getParentActivity(), "OpenAI API key", AiSummarizer.prefs().getString("apiKey", ""), result -> {
                AiSummarizer.prefs().edit().putString("apiKey", result.trim()).apply();
                listView.adapter.update(true);
                return null;
            });
        } else if (item.id == ID_MODEL) {
            ForkDialogs.createFieldAlert(getParentActivity(), "Model", AiSummarizer.prefs().getString("model", AiSummarizer.DEFAULT_MODEL), result -> {
                String model = result.trim();
                AiSummarizer.prefs().edit().putString("model", model.isEmpty() ? AiSummarizer.DEFAULT_MODEL : model).apply();
                listView.adapter.update(true);
                return null;
            });
        } else if (item.id == ID_LAST) {
            BaseFragment last = lastResultFragment();
            if (last != null) {
                presentFragment(last);
            }
        } else if (item.id == ID_DIGEST) {
            MyDigest.setEnabled(!MyDigest.enabled());
            listView.adapter.update(true);
        } else if (item.id == ID_DIGEST_TIME) {
            new TimePickerDialog(getParentActivity(), (picker, hour, minute) -> {
                MyDigest.setMinuteOfDay(hour * 60 + minute);
                listView.adapter.update(true);
            }, MyDigest.minuteOfDay() / 60, MyDigest.minuteOfDay() % 60, true).show();
        } else if (item.id == ID_DIGEST_NOW) {
            if (AiSummarizer.prefs().getString("apiKey", "").isEmpty()) {
                BulletinFactory.of(this).createErrorBulletin("Set your OpenAI API key first.").show();
                return;
            }
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contacts_sync_on, "Preparing digest… it arrives as a notification.").show();
            MyDigest.run(() -> {
                if (listView != null) {
                    listView.adapter.update(true);
                }
            });
        } else if (item.id == ID_SUMMARIZE) {
            startSummary();
        }
    }

    private void startSummary() {
        if (AiSummarizer.prefs().getString("apiKey", "").isEmpty()) {
            BulletinFactory.of(this).createErrorBulletin("Set your OpenAI API key first.").show();
            return;
        }
        int index = windowIndex();
        int since = ConnectionsManager.getInstance(currentAccount).getCurrentTime() - WINDOW_HOURS[index] * 3600;
        ArrayList<Long> chats = chatsForSummary(currentAccount, since);
        if (chats.isEmpty()) {
            BulletinFactory.of(this).createErrorBulletin(pickMode() ? "Choose at least one chat." : "No unread chats in this window.").show();
            return;
        }
        String title = WINDOW_LABELS[index] + " · " + chats.size() + (chats.size() == 1 ? " chat" : " chats");

        progressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.setCanCancel(true);
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
                    BulletinFactory.of(AiSummaryActivity.this).createErrorBulletin("No messages in this window.").show();
                    running = null;
                    return;
                }
                running.send();
            }

            @Override
            public void onDone(String summary) {
                dismissProgress();
                saveLast(title, summary, running == null ? new ArrayList<>() : running.refs);
                running = null;
                if (listView != null) {
                    listView.adapter.update(true);
                }
                presentFragment(lastResultFragment());
            }

            @Override
            public void onError(String error) {
                dismissProgress();
                running = null;
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle("Summary failed");
                builder.setMessage(error);
                builder.setPositiveButton("OK", null);
                showDialog(builder.create());
            }
        });
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

    private static final Pattern REF = Pattern.compile("\\s*\\[r(\\d+)\\]");

    // Scrollable summary text: "## " lines are headings and [rN] references become ↗ links.
    public static ScrollView summaryView(Context context, String summary, ArrayList<AiSummarizer.Ref> refs, Utilities.Callback<AiSummarizer.Ref> onRef) {
        ScrollView scrollView = new ScrollView(context);
        TextView textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        textView.setLineSpacing(AndroidUtilities.dp(3), 1f);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText));
        textView.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(14), AndroidUtilities.dp(18), AndroidUtilities.dp(24));
        textView.setTextIsSelectable(true);
        textView.setText(render(summary, refs, onRef));
        textView.setMovementMethod(LinkMovementMethod.getInstance());
        scrollView.addView(textView, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        return scrollView;
    }

    private static CharSequence render(String text, ArrayList<AiSummarizer.Ref> refs, Utilities.Callback<AiSummarizer.Ref> onRef) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("## ")) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                int start = out.length();
                out.append(line.substring(3));
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(1.15f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }
            if (line.startsWith("- ")) {
                line = "• " + line.substring(2);
            }
            Matcher matcher = REF.matcher(line);
            int last = 0;
            while (matcher.find()) {
                out.append(line, last, matcher.start());
                int refIndex = Integer.parseInt(matcher.group(1)) - 1;
                if (refIndex >= 0 && refIndex < refs.size()) {
                    AiSummarizer.Ref ref = refs.get(refIndex);
                    int start = out.length();
                    out.append(" ↗");
                    out.setSpan(new ClickableSpan() {
                        @Override
                        public void onClick(View widget) {
                            onRef.run(ref);
                        }

                        @Override
                        public void updateDrawState(TextPaint ds) {
                            ds.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText));
                            ds.setUnderlineText(false);
                        }
                    }, start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                last = matcher.end();
            }
            out.append(line, last, line.length()).append('\n');
        }
        return out;
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

    // Shows a summary full screen.
    public static class ResultActivity extends BaseFragment {

        private final String title;
        private final String summary;
        private final ArrayList<AiSummarizer.Ref> refs;

        ResultActivity(String title, String summary, ArrayList<AiSummarizer.Ref> refs) {
            this.title = title;
            this.summary = summary;
            this.refs = refs;
        }

        @Override
        public View createView(Context context) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setTitle(title);
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        finishFragment();
                    }
                }
            });

            fragmentView = summaryView(context, summary, refs, ref -> openMessage(this, ref));
            fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return fragmentView;
        }
    }
}
