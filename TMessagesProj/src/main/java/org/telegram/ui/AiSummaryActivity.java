package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
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

    // Last result, kept for the session so reopening the tab costs nothing.
    private static String lastSummary;
    private static ArrayList<AiSummarizer.Ref> lastRefs;
    private static String lastTitle;

    private final ArrayList<Long> pickedChats = new ArrayList<>();
    private UniversalRecyclerView listView;
    private AiSummarizer running;
    private AlertDialog progressDialog;

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
        if (lastSummary != null) {
            items.add(UItem.asButton(ID_LAST, "Last summary", lastTitle));
        }
        items.add(UItem.asShadow(null));

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
            presentFragment(new ResultActivity(lastTitle, lastSummary, lastRefs));
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
        ArrayList<Long> chats = pickMode() ? new ArrayList<>(pickedChats) : AiSummarizer.unreadChats(currentAccount, since);
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
                dismissProgress();
                if (messages == 0) {
                    BulletinFactory.of(AiSummaryActivity.this).createErrorBulletin("No messages in this window.").show();
                    running = null;
                    return;
                }
                AiSummarizer summarizer = running;
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle("Send to OpenAI?");
                builder.setMessage(messages + " messages from " + chatCount + (chatCount == 1 ? " chat" : " chats") +
                    " (about " + approxTokens + " tokens). Their text will be sent to OpenAI using your key.");
                builder.setPositiveButton("Summarize", (d, w) -> {
                    progressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
                    progressDialog.setCanCancel(true);
                    progressDialog.setOnCancelListener(dd -> summarizer.cancel());
                    progressDialog.show();
                    summarizer.send();
                });
                builder.setNegativeButton("Cancel", (d, w) -> running = null);
                showDialog(builder.create());
            }

            @Override
            public void onDone(String summary) {
                dismissProgress();
                lastSummary = summary;
                lastRefs = running == null ? new ArrayList<>() : running.refs;
                lastTitle = title;
                running = null;
                if (listView != null) {
                    listView.adapter.update(true);
                }
                presentFragment(new ResultActivity(lastTitle, lastSummary, lastRefs));
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

    // Shows the summary; "## " lines are headings and [rN] references become links to the message.
    public static class ResultActivity extends BaseFragment {

        private static final Pattern REF = Pattern.compile("\\s*\\[r(\\d+)\\]");

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

            ScrollView scrollView = new ScrollView(context);
            scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            TextView textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setLineSpacing(AndroidUtilities.dp(3), 1f);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText));
            textView.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(14), AndroidUtilities.dp(18), AndroidUtilities.dp(24));
            textView.setTextIsSelectable(true);
            textView.setText(render(summary));
            textView.setMovementMethod(LinkMovementMethod.getInstance());
            scrollView.addView(textView, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
            fragmentView = scrollView;
            return fragmentView;
        }

        private CharSequence render(String text) {
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
                                openMessage(ref);
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

        private void openMessage(AiSummarizer.Ref ref) {
            Bundle args = new Bundle();
            if (ref.dialogId > 0) {
                args.putLong("user_id", ref.dialogId);
            } else {
                args.putLong("chat_id", -ref.dialogId);
            }
            args.putInt("message_id", ref.messageId);
            presentFragment(new ChatActivity(args));
        }
    }
}
