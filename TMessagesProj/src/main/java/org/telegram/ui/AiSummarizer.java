package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
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
import java.util.Date;
import java.util.Locale;

// Collects messages for a time window and summarizes them with the user's OpenAI key.
// History is loaded with the same messages.getHistory requests as scrolling a chat, paced to one per second.
public class AiSummarizer {

    public static final String DEFAULT_MODEL = "gpt-4o-mini";
    public static final int MAX_UNREAD_CHATS = 30;
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

    // Pointer from an [rN] reference back to the original message.
    public static class Ref {
        public final long dialogId;
        public final int messageId;

        Ref(long dialogId, int messageId) {
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

    // Unread private chats and groups with activity in the window. Channels and secret chats are skipped.
    public static ArrayList<Long> unreadChats(int account, int sinceDate) {
        MessagesController controller = MessagesController.getInstance(account);
        ArrayList<Long> result = new ArrayList<>();
        for (TLRPC.Dialog dialog : controller.getAllDialogs()) {
            if (result.size() >= MAX_UNREAD_CHATS) {
                break;
            }
            long did = dialog.id;
            if (DialogObject.isEncryptedDialog(did) || DialogObject.isFolderDialogId(did) || dialog.last_message_date < sinceDate) {
                continue;
            }
            if (dialog.unread_count <= 0 && !dialog.unread_mark) {
                continue;
            }
            if (did < 0 && ChatObject.isChannelAndNotMegaGroup(controller.getChat(-did))) {
                continue;
            }
            result.add(did);
        }
        return result;
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
        callback.onProgress("Reading " + chatTitle(account, did) + " (" + (chatIndex + 1) + "/" + dialogIds.size() + ")…");

        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = MessagesController.getInstance(account).getInputPeer(did);
        req.offset_id = offsetId;
        req.limit = PAGE_SIZE;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (cancelled) {
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
            for (TLRPC.Message message : res.messages) {
                lastId = message.id;
                if (message.date < sinceDate || message.id <= minMessageId) {
                    reachedStart = true;
                    break;
                }
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
            int collected = text == null ? 0 : countLines(text);
            boolean next = reachedStart || res.messages.size() < PAGE_SIZE || collected >= MAX_MESSAGES_PER_CHAT;
            int nextChat = next ? chatIndex + 1 : chatIndex;
            int nextOffset = next ? 0 : lastId;
            AndroidUtilities.runOnUIThread(() -> loadPage(nextChat, nextOffset), REQUEST_INTERVAL_MS);
        }));
    }

    private static int countLines(StringBuilder text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
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
        refs.add(new Ref(did, message.id));
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
        String model = prefs().getString("model", DEFAULT_MODEL);
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
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String summary;
                if (chunks.size() == 1) {
                    AndroidUtilities.runOnUIThread(() -> callback.onProgress("Summarizing…"));
                    summary = complete(key, model, SYSTEM_PROMPT, chunks.get(0));
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
        JSONObject body = new JSONObject();
        body.put("model", model);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", system));
        messages.put(new JSONObject().put("role", "user").put("content", user));
        body.put("messages", messages);

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
