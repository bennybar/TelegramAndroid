package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;

// Share a not-yet-downloaded video/file to another app: download it (same as tapping download, into Telegram's
// own cache, which "Keep media" cleans up) behind a progress dialog, then open the share sheet by itself.
// PhotoViewer.onSharePressed hook, in place of the "Please download first" alert.
public class MyShare {

    public static boolean downloadThenShare(Activity activity, MessageObject message) {
        if (activity == null || message == null || message.getDocument() == null) {
            return false;
        }
        int account = message.currentAccount;
        String fileName = message.getFileName();
        FileLoader loader = FileLoader.getInstance(account);
        NotificationCenter center = NotificationCenter.getInstance(account);

        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_LOADING);
        progress.setMessage(activity.getString(R.string.Loading));
        progress.setCanCancel(true);
        progress.setCanceledOnTouchOutside(false);

        NotificationCenter.NotificationCenterDelegate[] observer = new NotificationCenter.NotificationCenterDelegate[1];
        Runnable stopListening = () -> {
            center.removeObserver(observer[0], NotificationCenter.fileLoaded);
            center.removeObserver(observer[0], NotificationCenter.fileLoadFailed);
            center.removeObserver(observer[0], NotificationCenter.fileLoadProgressChanged);
        };
        observer[0] = (id, acc, args) -> {
            if (!fileName.equals(args[0])) {
                return;
            }
            if (id == NotificationCenter.fileLoadProgressChanged) {
                long loaded = (Long) args[1], total = (Long) args[2];
                if (total > 0) {
                    progress.setProgress((int) (100 * loaded / total));
                }
            } else if (id == NotificationCenter.fileLoaded) {
                stopListening.run();
                progress.dismiss();
                share(activity, message, (File) args[1]);
            } else {
                stopListening.run();
                progress.dismiss();
            }
        };
        center.addObserver(observer[0], NotificationCenter.fileLoaded);
        center.addObserver(observer[0], NotificationCenter.fileLoadFailed);
        center.addObserver(observer[0], NotificationCenter.fileLoadProgressChanged);
        // Back button: stop waiting (the download itself keeps going, like any started download).
        progress.setOnCancelListener(d -> stopListening.run());
        progress.show();
        loader.loadFile(message.getDocument(), message, FileLoader.PRIORITY_HIGH, 0);
        return true;
    }

    private static void share(Activity activity, MessageObject message, File file) {
        if (file == null || !file.exists()) {
            file = FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner);
        }
        if (file == null || !file.exists()) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(message.isVideo() ? "video/mp4" : message.getMimeType());
            Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivityForResult(Intent.createChooser(intent, activity.getString(R.string.ShareFile)), 500);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
