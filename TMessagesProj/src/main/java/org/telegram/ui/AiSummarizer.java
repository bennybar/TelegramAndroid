package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

// Collects messages for a time window and summarizes them with the user's OpenAI key.
// History is loaded with the same messages.getHistory requests as scrolling a chat, paced to one per second.
public class AiSummarizer {

    public static final String MODEL = "gpt-5.4-mini"; // fixed, same as Scoops
    private static final int MAX_MESSAGES_PER_CHAT = 1500;
    private static final int PAGE_SIZE = 100;
    private static final long REQUEST_INTERVAL_MS = 1000;
    private static final int CHUNK_CHARS = 60_000;

    private static final String SYSTEM_PROMPT =
        "You summarize Telegram chats for the user, who appears as \"Me\".\n" +
        "Language: write each chat's section in the language that chat is mostly written in. " +
        "Write the \"Needs you\" section, including its heading, in the language most of the input is written in.\n" +
        "Output plain text in exactly this shape:\n" +
        "## Needs you\n" +
        "- one bullet per thing that asks, mentions, or waits on Me (or a single bullet \"Nothing\")\n" +
        "## <chat title>\n" +
        "- 2 to 6 short bullets: key points, decisions, plans, links shared\n" +
        "(repeat the chat section for every chat with meaningful content; skip chats with only small talk)\n" +
        "End every bullet with 1 or 2 source references copied from the input, like [r12]. " +
        "Only use references that appear in the input. Never invent facts. Be concise.";

    private static final String MERGE_PROMPT =
        "You are given several partial summaries of the same set of Telegram chats, in the format below. " +
        "Merge them into one summary in exactly the same format, combining duplicate chat sections and the \"Needs you\" section. " +
        "Keep the [r..] references.\n\n" + SYSTEM_PROMPT;

    // The Digest tab: news from the kept chats and channels, grouped by topic (after Scoops' digest), always in
    // Hebrew. Duplicates across channels are merged; [rN] references link back to the source posts.
    private static final String DIGEST_RULES =
        "Write the digest in Hebrew.\n" +
        "Format it as Markdown. Group related messages into topics, at most six. Give each topic a `## ` heading, " +
        "then `-` bullets, one per distinct development, newest first. Start every bullet with its posting time " +
        "exactly as given in the input (\"HH:MM\", or \"אתמול HH:MM\" for yesterday). Use `**bold**` for the key " +
        "fact of each bullet. Merge messages that report the same thing, even from different chats. End every bullet " +
        "with 1 or 2 references copied from the input, like [r12]; only use references that appear in the input. " +
        "Skip small talk, ads and promotions. Do not use tables, code blocks or other headings. Keep the whole " +
        "digest under 450 words.\n" +
        "Do not invent details that are not in the messages. Many are unverified first reports: describe them as " +
        "reports, and say so plainly when messages contradict each other.";

    private static final String DIGEST_PROMPT =
        "You summarize the latest news from Telegram channels and chats the user follows. You will be given the " +
        "messages posted in the last %s, newest first, one per line: a reference like [r12], the chat, the posting " +
        "time, then the text.\n" + DIGEST_RULES;

    private static final String DIGEST_MERGE_PROMPT =
        "You are given several partial news digests of the same Telegram chats over the last %s, each covering a " +
        "different stretch of time. Merge them into one digest: combine topics that are about the same story, keep " +
        "every bullet's time and [r..] references, and drop exact repeats.\n" + DIGEST_RULES;

    // At most this many posts per digest (about 8 busy hours of a few channels), shared across the chats, and at
    // most 3 slices of SLICE_CHARS each: every slice is summarized, then the parts are merged.
    private static final int DIGEST_MAX_POSTS = 550;
    private static final int SLICE_CHARS = 40_000;
    private static final int MAX_SLICES = 3;

    private static final String ASK_PROMPT =
        "You answer the user's question about a Telegram chat. The user appears as \"Me\". " +
        "Answer in the language of the question, concisely. Support each point with 1 or 2 source references copied " +
        "from the input, like [r12]. Only use references that appear in the input. " +
        "If the messages don't contain the answer, say so plainly. Never invent facts.";

    // Pointer from an [rN] reference back to the original message.
    public static class Ref {
        public final long dialogId;
        public final int messageId;
        public boolean out; // sent by the user: never counted as unread

        public Ref(long dialogId, int messageId) {
            this.dialogId = dialogId;
            this.messageId = messageId;
        }
    }

    public interface Callback {
        void onProgress(String text);
        // Messages collected; call send() to continue or cancel() to stop.
        void onCollected(int chats, int messages, int approxTokens);
        void onDone(String summary);
        void onError(String error);
    }

    public static SharedPreferences prefs() {
        // Separate file, so "Export settings" (mainconfig) never includes the API key.
        return ApplicationLoader.applicationContext.getSharedPreferences("myai", Context.MODE_PRIVATE);
    }

    public final ArrayList<Ref> refs = new ArrayList<>();
    private final int account;
    private final ArrayList<Long> dialogIds;
    private final int sinceDate;
    private final Callback callback;
    private final StringBuilder[] chatTexts;
    private volatile boolean cancelled;
    private int messageCount;
    private int minMessageId;

    public AiSummarizer(int account, ArrayList<Long> dialogIds, int sinceDate, Callback callback) {
        this.account = account;
        this.dialogIds = dialogIds;
        this.sinceDate = sinceDate;
        this.callback = callback;
        this.chatTexts = new StringBuilder[dialogIds.size()];
    }

    public static String chatTitle(int account, long did) {
        MessagesController controller = MessagesController.getInstance(account);
        if (did > 0) {
            TLRPC.User user = controller.getUser(did);
            return user == null ? "Unknown" : UserObject.isUserSelf(user) ? "Saved Messages" : UserObject.getUserName(user);
        }
        TLRPC.Chat chat = controller.getChat(-did);
        return chat == null ? "Unknown" : chat.title;
    }

    private String question;
    private String digestWindow;
    private java.util.HashMap<Long, Integer> minIds;
    private int[] collected;

    // Digest results besides the text: posts summarized, and whether a busy chat or the slice limit cut older
    // posts (then coveredSince is the oldest posting time that made it in).
    public int digestPosts;
    public boolean truncated;
    public int coveredSince;

    // One collected message, for the digest's newest-first, evenly budgeted transcript.
    private static class Entry {
        final int date;
        final String chat;
        final String ref;
        final String body;
        final long groupId;
        final boolean mediaOnly;

        Entry(int date, String chat, String ref, String body, long groupId, boolean mediaOnly) {
            this.date = date;
            this.chat = chat;
            this.ref = ref;
            this.body = body;
            this.groupId = groupId;
            this.mediaOnly = mediaOnly;
        }
    }

    private final ArrayList<Entry> entries = new ArrayList<>();

    // Digest tab: a news digest of all chats together; window names the time span ("2 hours").
    public void setNewsDigest(String window) {
        this.digestWindow = window;
    }

    // "Since last digest": per chat, only messages newer than these ids.
    public void setMinMessageIds(java.util.HashMap<Long, Integer> minIds) {
        this.minIds = minIds;
    }

    private int minIdFor(long did) {
        Integer id = minIds == null ? null : minIds.get(did);
        return Math.max(minMessageId, id == null ? 0 : id);
    }

    // Per-chat fetch cap: in a digest, the post budget shared across the chats.
    private int capPerChat() {
        return digestWindow == null ? MAX_MESSAGES_PER_CHAT : Math.max(20, (DIGEST_MAX_POSTS + dialogIds.size() - 1) / dialogIds.size());
    }

    // "08:54", or "אתמול 23:40" for a post from before midnight.
    private static String digestTime(int date) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(date * 1000L);
        String clock = new SimpleDateFormat("HH:mm", Locale.US).format(then.getTime());
        boolean today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        return today ? clock : "אתמול " + clock;
    }

    // Newest first, without exact repeats (forwards), caption-less media or extra album items; the budget is
    // shared evenly over what's left and packed into at most MAX_SLICES slices.
    private ArrayList<String> digestSlices() {
        ArrayList<Entry> sorted = new ArrayList<>(entries);
        java.util.Collections.sort(sorted, (a, b) -> Integer.compare(b.date, a.date));
        java.util.HashSet<String> seenBodies = new java.util.HashSet<>();
        java.util.HashSet<Long> seenGroups = new java.util.HashSet<>();
        ArrayList<Entry> kept = new ArrayList<>();
        for (Entry entry : sorted) {
            if (entry.mediaOnly || entry.groupId != 0 && !seenGroups.add(entry.groupId) || !seenBodies.add(entry.body)) {
                continue;
            }
            kept.add(entry);
        }
        int perItem = Math.max(150, Math.min(700, MAX_SLICES * SLICE_CHARS / Math.max(1, kept.size()) - 60));
        ArrayList<String> slices = new ArrayList<>();
        StringBuilder slice = new StringBuilder();
        digestPosts = 0;
        int oldestIncluded = 0;
        for (Entry entry : kept) {
            String body = entry.body.length() > perItem ? entry.body.substring(0, perItem) + "…" : entry.body;
            String line = entry.ref + " " + entry.chat + " · " + digestTime(entry.date) + ": " + body + "\n";
            if (slice.length() + line.length() > SLICE_CHARS && slice.length() > 0) {
                if (slices.size() + 1 >= MAX_SLICES) {
                    // Older posts beyond the last slice are left out: complete only from the oldest one included.
                    truncated = true;
                    coveredSince = Math.max(coveredSince, oldestIncluded);
                    break;
                }
                slices.add(slice.toString());
                slice = new StringBuilder();
            }
            slice.append(line);
            digestPosts++;
            oldestIncluded = entry.date;
        }
        if (slice.length() > 0) {
            slices.add(slice.toString());
        }
        return slices;
    }

    // "Ask this chat" (MyAiChat): answer this question from the newest collected messages instead of summarizing.
    public void setQuestion(String question) {
        this.question = question;
    }

    // One prompt, one answer, off the UI thread (MyAiChat's Explain / Summarize / Translate).
    public static void askOnce(String system, String user, Utilities.Callback<String> onDone, Utilities.Callback<String> onError) {
        String key = prefs().getString("apiKey", "");
        String model = MODEL;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String answer = complete(key, model, system, user);
                AndroidUtilities.runOnUIThread(() -> onDone.run(answer));
            } catch (Exception e) {
                String message = TextUtils.isEmpty(e.getMessage()) ? e.toString() : e.getMessage();
                AndroidUtilities.runOnUIThread(() -> onError.run(message));
            }
        });
    }

    // Only collect messages newer than this id (e.g. the chat's last read message).
    public void setMinMessageId(int minMessageId) {
        this.minMessageId = minMessageId;
    }

    public void start() {
        loadPage(0, 0);
    }

    public void cancel() {
        cancelled = true;
    }

    private void loadPage(int chatIndex, int offsetId) {
        if (cancelled) {
            return;
        }
        if (chatIndex >= dialogIds.size()) {
            int chats = 0;
            int chars = 0;
            for (StringBuilder text : chatTexts) {
                if (text != null) {
                    chats++;
                    chars += text.length();
                }
            }
            callback.onCollected(chats, messageCount, chars / 4);
            return;
        }
        long did = dialogIds.get(chatIndex);
        if (collected == null) {
            collected = new int[dialogIds.size()];
        }
        if (offsetId == 0) {
            // Nothing new in this chat (known locally): skip it without a request.
            TLRPC.Dialog dialog = MessagesController.getInstance(account).dialogs_dict.get(did);
            if (dialog != null && (dialog.last_message_date != 0 && dialog.last_message_date < sinceDate || minIdFor(did) > 0 && dialog.top_message <= minIdFor(did))) {
                loadPage(chatIndex + 1, 0);
                return;
            }
        }
        callback.onProgress("Reading " + chatTitle(account, did) + " (" + (chatIndex + 1) + "/" + dialogIds.size() + ")…");

        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = MessagesController.getInstance(account).getInputPeer(did);
        req.offset_id = offsetId;
        req.limit = Math.max(1, Math.min(PAGE_SIZE, capPerChat() - collected[chatIndex]));
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (cancelled) {
                return;
            }
            if (error != null && error.text != null && error.text.startsWith("FLOOD_WAIT")) {
                // Telegram asked to slow down: stop the whole run instead of sending more requests.
                cancelled = true;
                String wait = error.text.substring("FLOOD_WAIT".length()).replace("_", "");
                callback.onError("Telegram asked to slow down. Try again in " + (wait.isEmpty() ? "a few" : wait) + " seconds.");
                return;
            }
            if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                // Skip a chat that fails to load instead of failing the whole run.
                AndroidUtilities.runOnUIThread(() -> loadPage(chatIndex + 1, 0), REQUEST_INTERVAL_MS);
                return;
            }
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            MessagesController controller = MessagesController.getInstance(account);
            controller.putUsers(res.users, false);
            controller.putChats(res.chats, false);

            boolean reachedStart = res.messages.isEmpty();
            int lastId = offsetId;
            // Newest first; collect into a list and prepend so the chat text reads oldest to newest.
            ArrayList<String> lines = new ArrayList<>();
            int oldestDate = 0;
            for (TLRPC.Message message : res.messages) {
                lastId = message.id;
                if (message.date < sinceDate || message.id <= minIdFor(did)) {
                    reachedStart = true;
                    break;
                }
                oldestDate = message.date;
                String line = formatMessage(did, message);
                if (line != null) {
                    lines.add(line);
                }
            }
            StringBuilder text = chatTexts[chatIndex];
            if (!lines.isEmpty()) {
                StringBuilder page = new StringBuilder();
                for (int i = lines.size() - 1; i >= 0; i--) {
                    page.append(lines.get(i)).append('\n');
                }
                if (text == null) {
                    text = chatTexts[chatIndex] = new StringBuilder();
                }
                text.insert(0, page);
                messageCount += lines.size();
            }
            collected[chatIndex] += lines.size();
            boolean capped = !reachedStart && res.messages.size() >= req.limit && collected[chatIndex] >= capPerChat();
            if (capped) {
                // This chat had more posts in the window than its share: complete only from the oldest one read.
                truncated = true;
                coveredSince = Math.max(coveredSince, oldestDate);
            }
            boolean next = reachedStart || res.messages.size() < req.limit || capped;
            int nextChat = next ? chatIndex + 1 : chatIndex;
            int nextOffset = next ? 0 : lastId;
            AndroidUtilities.runOnUIThread(() -> loadPage(nextChat, nextOffset), REQUEST_INTERVAL_MS);
        }));
    }

    private String formatMessage(long did, TLRPC.Message message) {
        if (message instanceof TLRPC.TL_messageService || message instanceof TLRPC.TL_messageEmpty) {
            return null;
        }
        MessageObject object = new MessageObject(account, message, false, false);
        String body = message.message == null ? "" : message.message.trim();
        String media = mediaPlaceholder(object);
        if (media != null) {
            body = body.isEmpty() ? media : media + " " + body;
        }
        if (body.isEmpty()) {
            return null;
        }
        Ref ref = new Ref(did, message.id);
        ref.out = message.out;
        refs.add(ref);
        entries.add(new Entry(message.date, chatTitle(account, did), "[r" + refs.size() + "]", body.replace('\n', ' '),
            message.grouped_id, media != null && (message.message == null || message.message.trim().isEmpty())));
        String time = new SimpleDateFormat("EEE HH:mm", Locale.US).format(new Date(message.date * 1000L));
        return "[r" + refs.size() + "] " + time + " " + senderName(message) + (message.mentioned ? " (mentions Me)" : "") + ": " + body.replace('\n', ' ');
    }

    private String senderName(TLRPC.Message message) {
        if (message.out) {
            return "Me";
        }
        long fromId = MessageObject.getFromChatId(message);
        if (fromId == UserConfig.getInstance(account).getClientUserId()) {
            return "Me";
        }
        return fromId == 0 ? "Unknown" : chatTitle(account, fromId);
    }

    private static String mediaPlaceholder(MessageObject object) {
        if (object.isRoundVideo()) return "[video message]";
        if (object.isVoice()) return "[voice " + AndroidUtilities.formatShortDuration((int) object.getDuration()) + "]";
        if (object.isSticker() || object.isAnimatedSticker()) return "[sticker]";
        if (object.isGif()) return "[GIF]";
        if (object.isVideo()) return "[video]";
        if (object.isPhoto()) return "[photo]";
        if (object.isMusic()) return "[music]";
        TLRPC.MessageMedia media = object.messageOwner.media;
        if (media instanceof TLRPC.TL_messageMediaDocument) return "[file " + object.getDocumentName() + "]";
        if (media instanceof TLRPC.TL_messageMediaGeo || media instanceof TLRPC.TL_messageMediaGeoLive || media instanceof TLRPC.TL_messageMediaVenue) return "[location]";
        if (media instanceof TLRPC.TL_messageMediaContact) return "[contact]";
        if (media instanceof TLRPC.TL_messageMediaPoll) return "[poll]";
        return null;
    }

    // Second step, after the user confirms: split into chunks, summarize each, then merge.
    public void send() {
        String key = prefs().getString("apiKey", "");
        String model = MODEL;
        if (digestWindow != null) {
            ArrayList<String> slices = digestSlices();
            String system = String.format(Locale.US, DIGEST_PROMPT, digestWindow);
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    String digest;
                    if (slices.size() <= 1) {
                        AndroidUtilities.runOnUIThread(() -> callback.onProgress("Summarizing " + digestPosts + " posts…"));
                        digest = complete(key, model, system, slices.isEmpty() ? "" : slices.get(0));
                    } else {
                        StringBuilder parts = new StringBuilder();
                        for (int i = 0; i < slices.size(); i++) {
                            if (cancelled) return;
                            String progress = "Summarizing part " + (i + 1) + " of " + slices.size() + "…";
                            AndroidUtilities.runOnUIThread(() -> callback.onProgress(progress));
                            parts.append("--- Part ").append(i + 1).append(" ---\n").append(complete(key, model, system, slices.get(i))).append("\n\n");
                        }
                        if (cancelled) return;
                        AndroidUtilities.runOnUIThread(() -> callback.onProgress("Merging…"));
                        digest = complete(key, model, String.format(Locale.US, DIGEST_MERGE_PROMPT, digestWindow), parts.toString());
                    }
                    String finalDigest = digest;
                    if (!cancelled) {
                        AndroidUtilities.runOnUIThread(() -> callback.onDone(finalDigest));
                    }
                } catch (Exception e) {
                    String message = TextUtils.isEmpty(e.getMessage()) ? e.toString() : e.getMessage();
                    if (!cancelled) {
                        AndroidUtilities.runOnUIThread(() -> callback.onError(message));
                    }
                }
            });
            return;
        }
        ArrayList<String> chunks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < chatTexts.length; i++) {
            StringBuilder text = chatTexts[i];
            if (text == null) {
                continue;
            }
            String header = "=== Chat: " + chatTitle(account, dialogIds.get(i)) + " ===\n";
            for (String line : text.toString().split("\n")) {
                if (chunk.length() + line.length() > CHUNK_CHARS && chunk.length() > 0) {
                    chunks.add(chunk.toString());
                    chunk = new StringBuilder(header);
                } else if (chunk.indexOf(header) < 0) {
                    chunk.append(header);
                }
                chunk.append(line).append('\n');
            }
        }
        if (chunk.length() > 0) {
            chunks.add(chunk.toString());
        }
        if (question != null) {
            // A question is answered from the newest chunk only: one request, no merge step.
            String newest = chunks.isEmpty() ? "" : chunks.get(chunks.size() - 1);
            chunks.clear();
            chunks.add("Question: " + question + "\n\n" + newest);
        }
        String system = question != null ? ASK_PROMPT : SYSTEM_PROMPT;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String summary;
                if (chunks.size() == 1) {
                    AndroidUtilities.runOnUIThread(() -> callback.onProgress(question != null ? "Thinking…" : "Summarizing…"));
                    summary = complete(key, model, system, chunks.get(0));
                } else {
                    StringBuilder partials = new StringBuilder();
                    for (int i = 0; i < chunks.size(); i++) {
                        if (cancelled) return;
                        final String progress = "Summarizing part " + (i + 1) + "/" + chunks.size() + "…";
                        AndroidUtilities.runOnUIThread(() -> callback.onProgress(progress));
                        partials.append("--- Partial summary ").append(i + 1).append(" ---\n")
                            .append(complete(key, model, SYSTEM_PROMPT, chunks.get(i))).append("\n\n");
                    }
                    if (cancelled) return;
                    AndroidUtilities.runOnUIThread(() -> callback.onProgress("Merging…"));
                    summary = complete(key, model, MERGE_PROMPT, partials.toString());
                }
                if (!cancelled) {
                    AndroidUtilities.runOnUIThread(() -> callback.onDone(summary));
                }
            } catch (Exception e) {
                String message = TextUtils.isEmpty(e.getMessage()) ? e.toString() : e.getMessage();
                if (!cancelled) {
                    AndroidUtilities.runOnUIThread(() -> callback.onError(message));
                }
            }
        });
    }

    private static String complete(String key, String model, String system, String user) throws Exception {
        try {
            return complete(key, model, system, user, true);
        } catch (Exception e) {
            // A model that doesn't take reasoning_effort says so in its error: ask once more without it.
            if (e.getMessage() == null || !e.getMessage().toLowerCase(Locale.US).contains("reasoning")) {
                throw e;
            }
            return complete(key, model, system, user, false);
        }
    }

    private static String complete(String key, String model, String system, String user, boolean lowEffort) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", system));
        messages.put(new JSONObject().put("role", "user").put("content", user));
        body.put("messages", messages);
        if (lowEffort) {
            body.put("reasoning_effort", "low"); // a summary needs little deliberation; this is most of the latency
        }

        HttpURLConnection connection = (HttpURLConnection) new URL("https://api.openai.com/v1/chat/completions").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(180_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + key);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = connection.getResponseCode();
        InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = in == null ? "" : readAll(in);
        connection.disconnect();
        if (code >= 400) {
            String message = "OpenAI error " + code;
            try {
                message += ": " + new JSONObject(text).getJSONObject("error").getString("message");
            } catch (Exception ignore) {}
            throw new Exception(message);
        }
        return new JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }
}
