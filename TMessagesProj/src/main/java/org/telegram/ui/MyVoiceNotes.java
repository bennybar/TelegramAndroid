package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.forkgram.ForkDialogs;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TranscribeButton;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

// The transcribe button (→A) on voice notes and video messages runs "Transcribe & summarize", mirroring the owner's voice bot (tgbot) step by step:
// gpt-4o-transcribe in Hebrew (3 tries with backoff); recordings over 2 minutes split at silences into ~90 s chunks
// transcribed 4 at a time and stitched with boundary overlap removed, a failed chunk marked "[…קטע לא תומלל…]";
// an empty transcript retried once on a loudness-normalized copy; then the bot's JSON prompt (tone + sections,
// names fixed from the "Names to spell correctly" setting) as one user message with json_object output, on
// gpt-6.1-sol; the reply shows 📨 forwarded-from, 🎭 tone and 📝 sections, with 📜 full transcript, 🔊 listen
// (gpt-4o-mini-tts, voice alloy) and a follow-up question answered from the transcript only. Results are cached per
// voice file, like the bot's file_unique_id cache. The transcript also fills Telegram's own spot under the voice
// message (locally). Secret chats are never sent; the OpenAI key is the Digest's.
public class MyVoiceNotes {

    private static final String TRANSCRIBE_MODEL = "gpt-4o-transcribe";
    private static final String MODEL = "gpt-6.1-sol";
    private static final String TTS_MODEL = "gpt-4o-mini-tts";
    private static final String TTS_VOICE = "alloy";
    private static final long WHISPER_MAX_BYTES = 24L * 1024 * 1024;
    private static final String CHUNK_FAILED = "[…קטע לא תומלל…]";

    private static SharedPreferences cache() {
        return ApplicationLoader.applicationContext.getSharedPreferences("myvoice", Context.MODE_PRIVATE);
    }

    // KNOWN_NAMES: a per-device setting, never in the APK (they're the owner's family and friends).
    public static String names() {
        return AiSummarizer.prefs().getString("voiceNames", "");
    }

    public static void setNames(String value) {
        if (!value.trim().equals(names())) {
            cache().edit().clear().apply(); // redo saved notes with the new names
        }
        AiSummarizer.prefs().edit().putString("voiceNames", value.trim()).apply();
    }

    private static boolean isVoice(MessageObject message) {
        return message != null && message.getDocument() != null && (message.isVoice() || message.isRoundVideo());
    }

    private static boolean hasKey() {
        return !AiSummarizer.prefs().getString("apiKey", "").isEmpty();
    }

    // ChatMessageCell hook: show the transcribe button on voice notes and video messages (outside secret chats)
    // once there's an OpenAI key, Premium or not.
    public static boolean useButton(MessageObject message) {
        return hasKey() && isVoice(message) && !DialogObject.isEncryptedDialog(message.getDialogId());
    }

    // TranscribeButton hook: a tap on the closed button runs Transcribe & summarize instead of Telegram's
    // transcription (or its Premium offer). Tapping the open button still collapses the transcript.
    public static boolean onButtonTap(MessageObject message) {
        if (!useButton(message)) {
            return false;
        }
        org.telegram.ui.ActionBar.BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (!(fragment instanceof ChatActivity)) {
            return false;
        }
        start((ChatActivity) fragment, message);
        return true;
    }

    // A note being processed in the background; its button spins until dismiss().
    private static class Job {
        final long docId;
        java.lang.ref.WeakReference<View> cell = new java.lang.ref.WeakReference<>(null);

        Job(long docId) {
            this.docId = docId;
        }

        void setMessage(String text) {
            // No dialog: the spinning button is the progress.
        }

        void dismiss() {
            running.remove(docId);
            View view = cell.get();
            if (view != null) {
                view.invalidate();
            }
        }
    }

    private static final java.util.HashMap<Long, Job> running = new java.util.HashMap<>();
    private static Paint ringPaint;
    private static final RectF ringRect = new RectF();

    private static Paint buttonPaint;
    private static Drawable sparkle;
    private static final RectF buttonRect = new RectF();

    // TranscribeButton hook: the closed button as an accent gradient pill with a white sparkle.
    public static boolean drawButton(Canvas canvas, Rect bounds, int radius, float alpha, org.telegram.ui.Cells.ChatMessageCell cell) {
        MessageObject message = cell.getMessageObject();
        if (!useButton(message) || bounds.width() <= 0) {
            return false;
        }
        Job job = running.get(message.getDocument().id);
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        int second = Theme.blendOver(accent, Theme.multAlpha(0xFF9B5CF6, 0.55f));
        if (buttonPaint == null) {
            buttonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        buttonPaint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom, accent, second, Shader.TileMode.CLAMP));
        buttonPaint.setAlpha((int) (255 * alpha));
        buttonRect.set(bounds);
        canvas.drawRoundRect(buttonRect, radius, radius, buttonPaint);
        if (sparkle == null) {
            sparkle = ApplicationLoader.applicationContext.getResources().getDrawable(R.drawable.summary_stars).mutate();
            sparkle.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        }
        int size = Math.min(AndroidUtilities.dp(20), Math.min(bounds.width(), bounds.height()) - AndroidUtilities.dp(6));
        sparkle.setBounds(bounds.centerX() - size / 2, bounds.centerY() - size / 2, bounds.centerX() + size / 2, bounds.centerY() + size / 2);
        sparkle.setAlpha((int) (255 * alpha * (job != null ? 0.85f : 1f)));
        if (job != null) {
            // Working: the sparkle turns slowly inside a spinning ring.
            job.cell = new java.lang.ref.WeakReference<>(cell);
            float t = (android.os.SystemClock.elapsedRealtime() % 1200) / 1200f;
            canvas.save();
            canvas.rotate(t * 360, bounds.centerX(), bounds.centerY());
            sparkle.draw(canvas);
            canvas.restore();
            if (ringPaint == null) {
                ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeCap(Paint.Cap.ROUND);
            }
            ringPaint.setStrokeWidth(AndroidUtilities.dp(1.6f));
            ringPaint.setColor(0xFFFFFFFF);
            ringPaint.setAlpha((int) (230 * alpha));
            float inset = AndroidUtilities.dp(3);
            ringRect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);
            canvas.drawArc(ringRect, t * 360 * 2, 100, false, ringPaint);
            cell.invalidate();
        } else {
            sparkle.draw(canvas);
        }
        return true;
    }

    // From the transcribe button on a voice note or video message.
    private static void start(ChatActivity chat, MessageObject message) {
        Activity activity = chat.getParentActivity();
        if (activity == null) {
            return;
        }
        Note cached = Note.load(cacheKey(message));
        if (cached != null) {
            applyTranscript(message, cached.transcript);
            showNote(chat, message, cached);
            return;
        }
        // In the background: the button spins until it's done, then the summary pops up.
        long docId = message.getDocument().id;
        if (running.containsKey(docId)) {
            return;
        }
        Job progress = new Job(docId);
        running.put(docId, progress);
        boolean[] cancelled = {false};
        withFile(chat, message, progress, cancelled, file -> Utilities.globalQueue.postRunnable(() -> process(chat, message, file, progress, cancelled)));
    }

    // The voice file id is the same for every forward of the note, like the bot's file_unique_id.
    private static String cacheKey(MessageObject message) {
        return "doc_" + message.getDocument().id;
    }

    // ---- The note: transcript, tone and sections (the bot's cache entry) ----

    private static class Note {
        String transcript;
        String tone;
        final ArrayList<String[]> sections = new ArrayList<>();

        void save(String key) {
            try {
                JSONArray list = new JSONArray();
                for (String[] s : sections) {
                    list.put(new JSONObject().put("title", s[0]).put("body", s[1]));
                }
                cache().edit().putString(key, new JSONObject().put("transcript", transcript).put("tone", tone).put("sections", list).toString()).apply();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        static Note load(String key) {
            String raw = cache().getString(key, null);
            if (raw == null) {
                return null;
            }
            try {
                JSONObject json = new JSONObject(raw);
                Note note = new Note();
                note.transcript = json.getString("transcript");
                note.tone = json.optString("tone", "—");
                JSONArray list = json.optJSONArray("sections");
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject s = list.getJSONObject(i);
                    note.sections.add(new String[]{s.optString("title", ""), s.optString("body", "")});
                }
                return note;
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ---- Pipeline (the bot's transcribe_voice) ----

    private static void process(ChatActivity chat, MessageObject message, File file, Job progress, boolean[] cancelled) {
        String key = AiSummarizer.prefs().getString("apiKey", "");
        File work = new File(ApplicationLoader.applicationContext.getCacheDir(), "voicenote_" + System.currentTimeMillis());
        MyAudioChunker audio = null;
        try {
            setProgress(progress, "🎙️ מתמלל...");
            String transcript;
            double duration = 0;
            try {
                audio = new MyAudioChunker(file, work);
                duration = audio.durationSec();
            } catch (Exception e) {
                FileLog.e(e); // can't decode: send the file as it is, like a short note
            }
            if (audio != null && duration > 120) {
                ArrayList<File> chunks = audio.splitOnSilence();
                int total = Math.max(chunks.size(), 1);
                String[] parts = new String[chunks.size()];
                AtomicInteger done = new AtomicInteger();
                ExecutorService pool = Executors.newFixedThreadPool(4);
                try {
                    ArrayList<Future<?>> futures = new ArrayList<>();
                    for (int i = 0; i < chunks.size(); i++) {
                        int index = i;
                        futures.add(pool.submit(() -> {
                            try {
                                parts[index] = transcribe(key, chunks.get(index));
                            } catch (Exception ce) {
                                // One dead chunk shouldn't discard the chunks that did transcribe.
                                FileLog.e(ce);
                                parts[index] = CHUNK_FAILED;
                            }
                            setProgress(progress, "🎙️ מתמלל " + done.incrementAndGet() + "/" + total + "...");
                        }));
                    }
                    for (Future<?> f : futures) {
                        f.get();
                    }
                } finally {
                    pool.shutdown();
                }
                boolean allFailed = true;
                for (String p : parts) {
                    allFailed &= CHUNK_FAILED.equals(p);
                }
                if (allFailed) {
                    throw new Exception("all chunks failed to transcribe");
                }
                transcript = stitchChunks(parts);
            } else {
                transcript = transcribe(key, file);
            }
            if (cancelled[0]) {
                return;
            }
            if (transcript.trim().isEmpty() && audio != null) {
                // Silence is usually a very quiet recording, not an empty one: boost and retry once.
                File loud = audio.boostQuiet(WHISPER_MAX_BYTES);
                if (loud != null) {
                    try {
                        transcript = transcribe(key, loud);
                    } catch (Exception le) {
                        FileLog.e(le);
                    }
                }
            }
            if (cancelled[0]) {
                return;
            }
            if (transcript.trim().isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> {
                    progress.dismiss();
                    BulletinFactory.of(chat).createErrorBulletin("🤷 לא הצלחתי לשמוע כלום.").show();
                });
                return;
            }
            String finalTranscript = transcript;
            AndroidUtilities.runOnUIThread(() -> applyTranscript(message, finalTranscript)); // visible in the bubble while summarizing
            setProgress(progress, "✍️ מסכם...");

            JSONObject analysis;
            try {
                analysis = parseJson(llmCall(key, summaryPrompt(transcript), true));
            } catch (Exception e) {
                FileLog.e(e);
                analysis = new JSONObject();
            }
            Note note = new Note();
            note.transcript = transcript;
            String tone = analysis.optString("tone", analysis.optString("טון", ""));
            note.tone = tone.isEmpty() ? "—" : tone;
            JSONArray sections = analysis.optJSONArray("sections");
            for (int i = 0; sections != null && i < sections.length(); i++) {
                JSONObject s = sections.optJSONObject(i);
                if (s != null) {
                    note.sections.add(new String[]{s.optString("title", ""), s.optString("body", "")});
                }
            }
            if (note.sections.isEmpty()) {
                String summary = analysis.optString("summary", analysis.optString("סיכום", ""));
                if (!summary.isEmpty()) {
                    note.sections.add(new String[]{"סיכום", summary});
                }
            }
            note.save(cacheKey(message));
            if (cancelled[0]) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                progress.dismiss();
                if (LaunchActivity.getSafeLastFragment() == chat) {
                    showNote(chat, message, note); // otherwise it's cached: the next tap shows it
                }
            });
        } catch (Exception e) {
            FileLog.e(e);
            String error = TextUtils.isEmpty(e.getMessage()) ? e.toString() : e.getMessage();
            AndroidUtilities.runOnUIThread(() -> {
                progress.dismiss();
                if (!cancelled[0]) {
                    BulletinFactory.of(chat).createErrorBulletin("❌ " + error).show();
                }
            });
        } finally {
            if (audio != null) {
                audio.cleanup();
            } else {
                work.delete();
            }
        }
    }

    private static void setProgress(Job progress, String text) {
        AndroidUtilities.runOnUIThread(() -> progress.setMessage(text));
    }

    // The bot's prompt, word for word: names list (when set) and the gap note when a chunk failed.
    private static String summaryPrompt(String transcript) {
        String gapNote = transcript.contains(CHUNK_FAILED)
            ? "\n\nחלק מהתמלול חסר: הסימון \"" + CHUNK_FAILED + "\" מציין קטע שלא ניתן היה לתמלל. אל תנחש מה נאמר בו."
            : "";
        String namesRule = names().isEmpty() ? "" :
            "\n\nתיקון שמות: התמלול עלול להכיל שמות שנכתבו בצורה שגויה כתוצאה מתמלול אוטומטי. אם אתה מזהה בתמלול מילה שצליל " +
            "דומה לאחד מהשמות הבאים, השתמש בשם הנכון מהרשימה במקום הצורה השגויה (גם בכותרות וגם בתוכן הסעיפים): " +
            names().replaceAll("\\s*,\\s*", ", ") + ". אל תוסיף שמות שלא הוזכרו, רק תקן צורות שגויות של שמות שכן הוזכרו.";
        return "תקבל תמלול הודעה קולית בעברית. החזר JSON בלבד, ללא טקסט נוסף, במבנה הבא:\n" +
            "{\n" +
            "  \"tone\": \"משפט אחד קצר בעברית על הטון/האנרגיה\",\n" +
            "  \"sections\": [\n" +
            "    {\"title\": \"כותרת קצרה לסעיף\", \"body\": \"תוכן מפורט, מספר משפטים\"}\n" +
            "  ]\n" +
            "}\n" +
            "חלק את הסיכום ל‍-2 עד 6 סעיפים נפרדים לפי התוכן. דוגמאות לכותרות (השתמש רק במה שרלוונטי): \"נושאים מרכזיים\", " +
            "\"החלטות\", \"משימות ובקשות\", \"שאלות פתוחות\", \"שמות, תאריכים ומספרים\", \"פרטים חשובים\". כל סעיף עם כותרת " +
            "קצרה וברורה ותוכן מפורט. אל תשמיט מידע מהותי. אל תוסיף פרשנות או מידע שלא נאמר." +
            namesRule + gapNote + "\n\nתמלול:\n" + transcript;
    }

    // stitch_chunks: join with paragraph breaks, dropping a repeated phrase at a chunk boundary (7-80 chars).
    static String stitchChunks(String[] parts) {
        ArrayList<String> cleaned = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.trim().isEmpty()) {
                cleaned.add(p.trim());
            }
        }
        if (cleaned.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(cleaned.get(0));
        for (int i = 1; i < cleaned.size(); i++) {
            String p = cleaned.get(i);
            int overlap = 0;
            for (int k = Math.min(80, Math.min(out.length(), p.length())); k > 6; k--) {
                if (out.substring(out.length() - k).equals(p.substring(0, k))) {
                    overlap = k;
                    break;
                }
            }
            out.append("\n\n").append(p.substring(overlap));
        }
        return out.toString().trim();
    }

    // ---- OpenAI calls, retried 3 times with exponential backoff (1-10 s) on network, rate-limit and server errors ----

    private static class RetryableException extends Exception {
        RetryableException(String message) {
            super(message);
        }
    }

    private interface Call<T> {
        T run() throws Exception;
    }

    private static <T> T withRetries(Call<T> call) throws Exception {
        long wait = 1000;
        for (int attempt = 1; ; attempt++) {
            try {
                return call.run();
            } catch (RetryableException | java.io.IOException e) {
                if (attempt >= 3) {
                    throw e;
                }
                Thread.sleep(wait);
                wait = Math.min(10_000, wait * 2);
            }
        }
    }

    private static String transcribe(String key, File file) throws Exception {
        if (file.length() > WHISPER_MAX_BYTES) {
            throw new Exception("audio too large to transcribe: " + file.length() + " bytes");
        }
        return withRetries(() -> {
            String boundary = "----tegram" + System.nanoTime();
            HttpURLConnection connection = (HttpURLConnection) new URL("https://api.openai.com/v1/audio/transcriptions").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(300_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            String fileName = file.getName().contains(".") ? file.getName() : file.getName() + ".ogg";
            try (DataOutputStream out = new DataOutputStream(connection.getOutputStream())) {
                writeField(out, boundary, "model", TRANSCRIBE_MODEL);
                writeField(out, boundary, "language", "he");
                if (!names().isEmpty()) {
                    // A spelling hint for the transcription itself (the bot only fixes names in the summary).
                    writeField(out, boundary, "prompt", "שמות שעשויים להופיע בהקלטה: " + names().replaceAll("\\s*,\\s*", ", ") + ".");
                }
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                try (InputStream in = new FileInputStream(file)) {
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
                out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            return new JSONObject(response(connection)).optString("text", "");
        });
    }

    private static void writeField(DataOutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    // llm_call: one user message; json_mode asks for a JSON object.
    private static String llmCall(String key, String prompt, boolean jsonMode) throws Exception {
        return withRetries(() -> {
            JSONObject body = new JSONObject();
            body.put("model", MODEL);
            body.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)));
            if (jsonMode) {
                body.put("response_format", new JSONObject().put("type", "json_object"));
            }
            HttpURLConnection connection = post(key, "https://api.openai.com/v1/chat/completions", body);
            String text = new JSONObject(response(connection)).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
            return text.isEmpty() && jsonMode ? "{}" : text;
        });
    }

    private static HttpURLConnection post(String key, String url, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(300_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + key);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        return connection;
    }

    private static String response(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = readAll(in);
        connection.disconnect();
        if (code >= 400) {
            String message = "OpenAI error " + code;
            try {
                message += ": " + new JSONObject(text).getJSONObject("error").getString("message");
            } catch (Exception ignore) {
            }
            if (code == 429 || code >= 500) {
                throw new RetryableException(message);
            }
            throw new Exception(message);
        }
        return text;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
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

    private static JSONObject parseJson(String raw) {
        try {
            String text = raw.trim();
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            return new JSONObject(start >= 0 && end > start ? text.substring(start, end + 1) : text);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ---- Download, transcript in the bubble ----

    private static void withFile(ChatActivity chat, MessageObject message, Job progress, boolean[] cancelled, Utilities.Callback<File> then) {
        File file = localFile(message);
        if (file != null) {
            then.run(file);
            return;
        }
        progress.setMessage("Downloading…");
        int account = message.currentAccount;
        String name = message.getFileName();
        NotificationCenter center = NotificationCenter.getInstance(account);
        NotificationCenter.NotificationCenterDelegate[] observer = new NotificationCenter.NotificationCenterDelegate[1];
        observer[0] = (id, acc, args) -> {
            if (!name.equals(args[0])) {
                return;
            }
            center.removeObserver(observer[0], NotificationCenter.fileLoaded);
            center.removeObserver(observer[0], NotificationCenter.fileLoadFailed);
            if (cancelled[0]) {
                return;
            }
            File loaded = id == NotificationCenter.fileLoaded ? localFile(message) : null;
            if (loaded == null) {
                progress.dismiss();
                BulletinFactory.of(chat).createErrorBulletin("❌ קובץ האודיו לא נמצא.").show();
                return;
            }
            then.run(loaded);
        };
        center.addObserver(observer[0], NotificationCenter.fileLoaded);
        center.addObserver(observer[0], NotificationCenter.fileLoadFailed);
        FileLoader.getInstance(account).loadFile(message.getDocument(), message, FileLoader.PRIORITY_HIGH, 0);
    }

    private static File localFile(MessageObject message) {
        String attachPath = message.messageOwner.attachPath;
        if (!TextUtils.isEmpty(attachPath) && new File(attachPath).exists()) {
            return new File(attachPath);
        }
        File path = FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner);
        if (path != null && path.exists()) {
            return path;
        }
        path = FileLoader.getInstance(message.currentAccount).getPathToAttach(message.getDocument(), true);
        return path != null && path.exists() ? path : null;
    }

    // Fills Telegram's transcription spot under the voice message, locally (like the fork's offline transcription).
    private static void applyTranscript(MessageObject message, String transcript) {
        if (message.messageOwner == null || transcript.equals(message.messageOwner.voiceTranscription) && message.messageOwner.voiceTranscriptionOpen) {
            return;
        }
        long id = Utilities.random.nextLong();
        message.messageOwner.voiceTranscription = transcript;
        message.messageOwner.voiceTranscriptionId = id;
        message.messageOwner.voiceTranscriptionOpen = true;
        TranscribeButton.openVideoTranscription(message);
        TranscribeButton.finishTranscription(message, id, transcript);
    }

    // ---- The reply (build_reply_messages) and its buttons ----

    // extract_forward_name: the original sender's name when the voice note was forwarded.
    private static String forwardName(MessageObject message) {
        TLRPC.MessageFwdHeader fwd = message.messageOwner.fwd_from;
        if (fwd == null) {
            return "";
        }
        if (!TextUtils.isEmpty(fwd.from_name)) {
            return fwd.from_name;
        }
        if (fwd.from_id != null) {
            MessagesController controller = MessagesController.getInstance(message.currentAccount);
            if (fwd.from_id.user_id != 0) {
                TLRPC.User user = controller.getUser(fwd.from_id.user_id);
                return user == null ? "" : UserObject.getUserName(user);
            }
            long chatId = fwd.from_id.channel_id != 0 ? fwd.from_id.channel_id : fwd.from_id.chat_id;
            TLRPC.Chat chat = controller.getChat(chatId);
            return chat == null ? "" : chat.title;
        }
        return "";
    }

    private static CharSequence replyText(MessageObject message, Note note) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String forward = forwardName(message);
        if (!forward.isEmpty()) {
            bold(out, "📨 מועבר מ:");
            out.append(" ").append(forward).append("\n");
        }
        bold(out, "🎭 טון:");
        out.append(" ").append(note.tone);
        for (String[] s : note.sections) {
            String title = s[0].trim().isEmpty() ? "סיכום" : s[0].trim();
            String body = s[1].trim();
            if (body.isEmpty()) {
                continue;
            }
            out.append("\n\n");
            int start = out.length();
            bold(out, "📝 " + title);
            out.setSpan(new RelativeSizeSpan(1.05f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.append("\n").append(body);
        }
        return out;
    }

    private static void bold(SpannableStringBuilder out, String text) {
        int start = out.length();
        out.append(text);
        out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static TextView sheetText(Context context, CharSequence text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setLineSpacing(AndroidUtilities.dp(3), 1f);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        // Not selectable, so dragging always scrolls; long-press copies the whole text.
        view.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        view.setOnLongClickListener(v -> {
            AndroidUtilities.addToClipboard(text.toString());
            return true;
        });
        view.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(8), AndroidUtilities.dp(18), AndroidUtilities.dp(12));
        return view;
    }

    private static TextView tonalButton(Context context, String text) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setSingleLine(true);
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        button.setTextColor(accent);
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(10), Theme.multAlpha(accent, 0.1f), Theme.multAlpha(accent, 0.2f)));
        return button;
    }

    private static MediaPlayer player;

    private static void showNote(ChatActivity chat, MessageObject message, Note note) {
        Activity activity = chat.getParentActivity();
        if (activity == null) {
            return;
        }
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(sheetText(activity, replyText(message, note)));

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), AndroidUtilities.dp(14));
        TextView transcriptButton = tonalButton(activity, "📜 תמלול מלא");
        TextView listenButton = tonalButton(activity, "🔊 האזן לסיכום");
        TextView askButton = tonalButton(activity, "💬 שאל");
        buttons.addView(transcriptButton, LayoutHelper.createLinear(0, 42, 1f, 4, 0, 4, 0));
        buttons.addView(listenButton, LayoutHelper.createLinear(0, 42, 1f, 4, 0, 4, 0));
        buttons.addView(askButton, LayoutHelper.createLinear(0, 42, 0.7f, 4, 0, 4, 0));
        column.addView(buttons);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(column);
        BottomSheet.Builder builder = new BottomSheet.Builder(activity);
        builder.setTitle("Voice message summary", true);
        builder.setCustomView(scroll);
        BottomSheet sheet = builder.create();
        sheet.setOnDismissListener(d -> stopPlayback());

        transcriptButton.setOnClickListener(v -> {
            SpannableStringBuilder text = new SpannableStringBuilder();
            bold(text, "📜 תמלול מלא");
            text.append("\n").append(note.transcript);
            column.addView(sheetText(activity, text), 1);
            transcriptButton.setEnabled(false);
            transcriptButton.setAlpha(0.5f);
        });
        listenButton.setOnClickListener(v -> speak(chat, note, listenButton));
        askButton.setOnClickListener(v -> ForkDialogs.createFieldAlert(activity, "שאלה על ההודעה", "", question -> {
            if (!question.trim().isEmpty()) {
                ask(chat, note, question.trim());
            }
            return null;
        }));
        chat.showDialog(sheet);
    }

    // speak_summary: the tone and sections read aloud.
    private static void speak(ChatActivity chat, Note note, TextView button) {
        if (player != null) {
            stopPlayback();
            button.setText("🔊 האזן לסיכום");
            return;
        }
        StringBuilder text = new StringBuilder(note.tone);
        for (String[] s : note.sections) {
            text.append("\n").append(s[0].trim()).append(". ").append(s[1].trim());
        }
        String input = text.length() > 4000 ? text.substring(0, 4000) : text.toString();
        if (input.trim().isEmpty() || "—".equals(input.trim())) {
            BulletinFactory.of(chat).createErrorBulletin("🤷 אין מה להקריא.").show();
            return;
        }
        button.setText("מכין הקלטה…");
        String key = AiSummarizer.prefs().getString("apiKey", "");
        Utilities.globalQueue.postRunnable(() -> {
            try {
                File out = new File(ApplicationLoader.applicationContext.getCacheDir(), "voicenote_summary.ogg");
                withRetries(() -> {
                    JSONObject body = new JSONObject().put("model", TTS_MODEL).put("voice", TTS_VOICE).put("input", input).put("response_format", "opus");
                    HttpURLConnection connection = post(key, "https://api.openai.com/v1/audio/speech", body);
                    int code = connection.getResponseCode();
                    if (code >= 400) {
                        response(connection); // throws with OpenAI's message
                    }
                    try (InputStream in = connection.getInputStream(); OutputStream file = new FileOutputStream(out)) {
                        byte[] buffer = new byte[16 * 1024];
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            file.write(buffer, 0, read);
                        }
                    }
                    connection.disconnect();
                    return null;
                });
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        stopPlayback();
                        player = new MediaPlayer();
                        player.setDataSource(out.getAbsolutePath());
                        player.setOnCompletionListener(mp -> {
                            stopPlayback();
                            button.setText("🔊 האזן לסיכום");
                        });
                        player.prepare();
                        player.start();
                        button.setText("⏹ עצור");
                    } catch (Exception e) {
                        FileLog.e(e);
                        button.setText("🔊 האזן לסיכום");
                        BulletinFactory.of(chat).createErrorBulletin("❌ לא הצלחתי להקריא את הסיכום.").show();
                    }
                });
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    button.setText("🔊 האזן לסיכום");
                    BulletinFactory.of(chat).createErrorBulletin("❌ לא הצלחתי להקריא את הסיכום.").show();
                });
            }
        });
    }

    private static void stopPlayback() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignore) {
            }
            player.release();
            player = null;
        }
    }

    // answer_question: strictly from the transcript.
    private static void ask(ChatActivity chat, Note note, String question) {
        Activity activity = chat.getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.show();
        String prompt = "ענה על השאלה על סמך התמלול בלבד. אם התשובה לא מופיעה בתמלול, כתוב \"לא נאמר בהודעה\".\n" +
            "אל תוסיף מידע חיצוני ואל תנחש. ענה בעברית, בקצרה ולעניין.\n\n" +
            "תמלול:\n" + note.transcript + "\n\nשאלה: " + question;
        String key = AiSummarizer.prefs().getString("apiKey", "");
        Utilities.globalQueue.postRunnable(() -> {
            String answer;
            try {
                answer = llmCall(key, prompt, false).trim();
            } catch (Exception e) {
                FileLog.e(e);
                answer = null;
            }
            String finalAnswer = answer;
            AndroidUtilities.runOnUIThread(() -> {
                progress.dismiss();
                if (chat.getParentActivity() == null) {
                    return;
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(chat.getParentActivity());
                builder.setTitle(question);
                builder.setMessage(finalAnswer == null ? "❌ לא הצלחתי לענות." : finalAnswer.isEmpty() ? "🤷 אין לי תשובה." : "💬 " + finalAnswer);
                builder.setPositiveButton("OK", null);
                chat.showDialog(builder.create());
            });
        });
    }
}
