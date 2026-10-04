package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.forkgram.ForkDialogs;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;

// "GIPHY instead of Stickers": the emoji panel's third page shows GIPHY GIFs (trending, search). EmojiView moves its
// page-2 search bar in here, so typing works like Telegram's own sticker search; its category icons search by emoji.
// The picked GIF's mp4 is downloaded to Telegram's cache, then sent as a normal Telegram GIF through the old
// web-search path (SearchImage type 1 -> SendMessagesHelper.prepareSendingMedia). The API key is a per-device
// setting, never in the APK, and stays out of settings backups.
public class MyGiphyView extends FrameLayout {

    public interface Delegate {
        void onPick(View cell, MediaController.SearchImage gif, String query);
    }

    public static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mygiphy", Context.MODE_PRIVATE);
    }

    public static boolean enabled() {
        return prefs().getBoolean("enabled", false);
    }

    public static void toggle() {
        prefs().edit().putBoolean("enabled", !enabled()).apply();
    }

    public static String apiKey() {
        return prefs().getString("apiKey", "");
    }

    public static void askKey(Context context, Runnable done) {
        ForkDialogs.createFieldAlert(context, "GIPHY API key", apiKey(), result -> {
            prefs().edit().putString("apiKey", result.trim()).apply();
            if (done != null) {
                done.run();
            }
            return null;
        });
    }

    private static class Gif {
        String preview, send;
        int width, height;
    }

    private final Delegate delegate;
    private final ArrayList<Gif> gifs = new ArrayList<>();
    private final RecyclerListView listView;
    private final TextView emptyView;
    private String query = "";
    private int requestId;
    private boolean loaded;

    public MyGiphyView(Context context, Theme.ResourcesProvider resourcesProvider, Delegate delegate) {
        super(context);
        this.delegate = delegate;
        int textColor = Theme.getColor(Theme.key_chat_emojiPanelIcon, resourcesProvider);


        listView = new RecyclerListView(context);
        GridLayoutManager layoutManager = new GridLayoutManager(context, 3);
        listView.setLayoutManager(layoutManager);
        listView.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), AndroidUtilities.dp(60));
        listView.setClipToPadding(false);
        listView.setAdapter(new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return true;
            }

            @Override
            public int getItemCount() {
                return gifs.size();
            }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                BackupImageView image = new BackupImageView(context) {
                    @Override
                    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                        int size = MeasureSpec.getSize(widthMeasureSpec);
                        super.onMeasure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
                    }
                };
                image.setRoundRadius(AndroidUtilities.dp(6));
                image.setPadding(AndroidUtilities.dp(2), AndroidUtilities.dp(2), AndroidUtilities.dp(2), AndroidUtilities.dp(2));
                image.getImageReceiver().setAllowStartAnimation(true);
                image.getImageReceiver().setAutoRepeat(1);
                return new RecyclerListView.Holder(image);
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                BackupImageView image = (BackupImageView) holder.itemView;
                image.setAlpha(1f);
                image.setImage(ImageLocation.getForPath(gifs.get(position).preview), "100_100", (android.graphics.drawable.Drawable) null, null);
            }
        });
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < gifs.size()) {
                send(view, gifs.get(position));
            }
        });
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 0, 0, 0));

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setTextColor(textColor);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), AndroidUtilities.dp(40));
        emptyView.setOnClickListener(v -> {
            if (TextUtils.isEmpty(apiKey())) {
                askKey(context, () -> load(query));
            }
        });
        addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 0, 0, 0));

        TextView attribution = new TextView(context);
        attribution.setText("Powered by GIPHY");
        attribution.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
        attribution.setTextColor(textColor);
        attribution.setAlpha(0.6f);
        addView(attribution, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 12, 52));
    }

    // EmojiView hook: its search bar sits on top, the grid starts below it.
    public void setSearchField(View field, int height) {
        addView(field, new LayoutParams(LayoutParams.MATCH_PARENT, height, Gravity.TOP));
        ((LayoutParams) listView.getLayoutParams()).topMargin = height;
        ((LayoutParams) emptyView.getLayoutParams()).topMargin = height;
    }

    // EmojiView hook: called on every keystroke, on clear (null) and with a category's emoji list.
    public void search(String text) {
        String q = text == null ? "" : text.trim();
        if (!q.isEmpty() && !q.matches(".*[\\p{L}\\p{N}].*")) {
            q = new String(Character.toChars(q.codePointAt(0))); // a category: search its first emoji
        }
        AndroidUtilities.cancelRunOnUIThread(pendingSearch);
        String finalQuery = q;
        pendingSearch = () -> load(finalQuery);
        AndroidUtilities.runOnUIThread(pendingSearch, 400);
    }

    private Runnable pendingSearch;

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!loaded) {
            load("");
        }
    }

    private void showMessage(String text) {
        emptyView.setText(text);
        emptyView.setVisibility(text == null ? GONE : VISIBLE);
    }

    private void load(String newQuery) {
        query = newQuery == null ? "" : newQuery;
        String key = apiKey();
        if (TextUtils.isEmpty(key)) {
            gifs.clear();
            listView.getAdapter().notifyDataSetChanged();
            showMessage("Tap to set your GIPHY API key (free from developers.giphy.com).");
            return;
        }
        loaded = true;
        int id = ++requestId;
        showMessage("Loading…");
        String q = query;
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<Gif> result = new ArrayList<>();
            String error = null;
            try {
                String url = q.isEmpty()
                    ? "https://api.giphy.com/v1/gifs/trending?limit=60&api_key=" + URLEncoder.encode(key, "UTF-8")
                    : "https://api.giphy.com/v1/gifs/search?limit=60&api_key=" + URLEncoder.encode(key, "UTF-8")
                        + "&q=" + URLEncoder.encode(q, "UTF-8") + (q.matches(".*[\\u0590-\\u05FF].*") ? "&lang=he" : "");
                JSONArray data = new JSONObject(get(url)).getJSONArray("data");
                for (int i = 0; i < data.length(); i++) {
                    JSONObject images = data.getJSONObject(i).getJSONObject("images");
                    JSONObject preview = images.optJSONObject("fixed_width");
                    JSONObject original = images.optJSONObject("original");
                    if (preview == null || original == null || TextUtils.isEmpty(preview.optString("mp4")) || TextUtils.isEmpty(original.optString("mp4"))) {
                        continue;
                    }
                    Gif gif = new Gif();
                    gif.preview = stripQuery(preview.getString("mp4"));
                    gif.send = stripQuery(original.getString("mp4"));
                    gif.width = original.optInt("width");
                    gif.height = original.optInt("height");
                    result.add(gif);
                }
            } catch (Exception e) {
                FileLog.e(e);
                error = "Couldn't load GIFs. Check the GIPHY API key and your connection.";
            }
            String finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (id != requestId) {
                    return;
                }
                gifs.clear();
                gifs.addAll(result);
                listView.getAdapter().notifyDataSetChanged();
                listView.scrollToPosition(0);
                showMessage(finalError != null ? finalError : result.isEmpty() ? "No GIFs found." : null);
            });
        });
    }

    private void send(View cell, Gif gif) {
        cell.setAlpha(0.5f);
        Utilities.globalQueue.postRunnable(() -> {
            File file = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), Utilities.MD5(gif.send) + ".mp4");
            boolean ok = file.exists() && file.length() > 0;
            if (!ok) {
                try {
                    HttpURLConnection connection = (HttpURLConnection) new URL(gif.send).openConnection();
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(30000);
                    File temp = new File(file.getPath() + ".part");
                    try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(temp)) {
                        byte[] buffer = new byte[32 * 1024];
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                        }
                    }
                    ok = temp.renameTo(file);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            boolean finalOk = ok;
            AndroidUtilities.runOnUIThread(() -> {
                cell.setAlpha(1f);
                if (!finalOk) {
                    return;
                }
                MediaController.SearchImage image = new MediaController.SearchImage();
                image.id = gif.send;
                image.imageUrl = gif.send;
                image.thumbUrl = gif.preview;
                image.width = gif.width;
                image.height = gif.height;
                image.size = (int) file.length();
                image.type = 1;
                delegate.onPick(cell, image, query);
            });
        });
    }

    private static String stripQuery(String url) {
        int index = url.indexOf('?');
        return index >= 0 ? url.substring(0, index) : url;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }
}
