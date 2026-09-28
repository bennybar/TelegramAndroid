package org.telegram.ui;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

// "Remind me" in the message menu: a local reminder that later reopens the message. Nothing is sent to anyone.
public class MyReminders {

    public static final int OPTION_REMIND = 1000;
    private static final String CHANNEL_ID = "my_reminders";
    private static final String ACTION_OPEN = "org.telegram.ui.MyReminders.OPEN";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("myreminders", Context.MODE_PRIVATE);
    }

    // fillMessageMenu hook: add "Remind me" just above Delete.
    public static void addMenuItem(MessageObject message, ArrayList<Integer> icons, ArrayList<CharSequence> items, ArrayList<Integer> options) {
        if (message == null || message.getId() <= 0 || message.scheduled) {
            return;
        }
        int index = options.indexOf(ChatActivity.OPTION_DELETE);
        if (index < 0) {
            index = options.size();
        }
        items.add(index, "Remind me");
        options.add(index, OPTION_REMIND);
        icons.add(index, R.drawable.msg_mute_period);
    }

    // processSelectedOption hook.
    public static boolean handleOption(ChatActivity chat, int option, MessageObject message) {
        if (option != OPTION_REMIND || message == null) {
            return false;
        }
        Calendar now = Calendar.getInstance();
        boolean beforeEvening = now.get(Calendar.HOUR_OF_DAY) < 20;
        String[] labels = {"In 1 hour", "In 3 hours", beforeEvening ? "This evening (20:00)" : "Tomorrow evening (20:00)", "Tomorrow morning (09:00)"};
        AlertDialog.Builder builder = new AlertDialog.Builder(chat.getParentActivity());
        builder.setTitle("Remind me");
        builder.setItems(labels, (dialog, which) -> {
            Calendar at = Calendar.getInstance();
            if (which == 0) {
                at.add(Calendar.HOUR_OF_DAY, 1);
            } else if (which == 1) {
                at.add(Calendar.HOUR_OF_DAY, 3);
            } else {
                if (which == 3 || !beforeEvening) {
                    at.add(Calendar.DAY_OF_YEAR, 1);
                }
                at.set(Calendar.HOUR_OF_DAY, which == 3 ? 9 : 20);
                at.set(Calendar.MINUTE, 0);
                at.set(Calendar.SECOND, 0);
            }
            add(message, at.getTimeInMillis());
            BulletinFactory.of(chat).createSimpleBulletin(R.raw.contacts_sync_on,
                "Reminder set for " + formatWhen(at)).show();
        });
        chat.showDialog(builder.create());
        return true;
    }

    private static String formatWhen(Calendar at) {
        Calendar today = Calendar.getInstance();
        String time = String.format(Locale.US, "%02d:%02d", at.get(Calendar.HOUR_OF_DAY), at.get(Calendar.MINUTE));
        return at.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) ? time : "tomorrow " + time;
    }

    private static void add(MessageObject message, long atMillis) {
        try {
            JSONArray list = new JSONArray(prefs().getString("list", "[]"));
            int id = prefs().getInt("nextId", 1);
            String preview = message.messageText == null ? "" : message.messageText.toString();
            if (preview.length() > 120) {
                preview = preview.substring(0, 120) + "…";
            }
            list.put(new JSONObject()
                .put("id", id)
                .put("dialogId", message.getDialogId())
                .put("messageId", message.getId())
                .put("at", atMillis)
                .put("title", AiSummarizer.chatTitle(message.currentAccount, message.getDialogId()))
                .put("text", preview));
            prefs().edit().putString("list", list.toString()).putInt("nextId", id + 1).commit();
            schedule(ApplicationLoader.applicationContext, id, atMillis);
        } catch (Exception ignore) {}
    }

    private static PendingIntent alarmIntent(Context context, int id) {
        Intent intent = new Intent(context, AlarmReceiver.class).putExtra("id", id);
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void schedule(Context context, int id, long atMillis) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, alarmIntent(context, id));
    }

    // App start hook: alarms don't survive a reboot, so re-arm every pending reminder.
    public static void scheduleAll(Context context) {
        try {
            JSONArray list = new JSONArray(context.getSharedPreferences("myreminders", Context.MODE_PRIVATE).getString("list", "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject reminder = list.getJSONObject(i);
                schedule(context, reminder.getInt("id"), Math.max(reminder.getLong("at"), System.currentTimeMillis() + 5000));
            }
        } catch (Exception ignore) {}
    }

    public static class AlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int id = intent.getIntExtra("id", 0);
            try {
                JSONArray list = new JSONArray(prefs().getString("list", "[]"));
                JSONArray rest = new JSONArray();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject reminder = list.getJSONObject(i);
                    if (reminder.getInt("id") == id) {
                        postNotification(context, reminder);
                    } else {
                        rest.put(reminder);
                    }
                }
                prefs().edit().putString("list", rest.toString()).commit();
            } catch (Exception ignore) {}
        }
    }

    private static void postNotification(Context context, JSONObject reminder) throws Exception {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Message reminders", NotificationManager.IMPORTANCE_HIGH));
        }
        int id = reminder.getInt("id");
        Intent open = new Intent(context, LaunchActivity.class).setAction(ACTION_OPEN)
            .putExtra("dialogId", reminder.getLong("dialogId"))
            .putExtra("messageId", reminder.getInt("messageId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String text = reminder.optString("text");
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.notification)
            .setContentTitle("Reminder · " + reminder.optString("title"))
            .setContentText(TextUtils.isEmpty(text) ? "Tap to open the message" : text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        manager.notify(0x7E70 + id, builder.build());
    }

    // LaunchActivity hook: tapping the reminder opens the chat at the message.
    public static boolean handleIntent(LaunchActivity activity, Intent intent) {
        if (intent == null || !ACTION_OPEN.equals(intent.getAction()) || AndroidUtilities.needShowPasscode(false)) {
            return false;
        }
        long dialogId = intent.getLongExtra("dialogId", 0);
        Bundle args = new Bundle();
        if (DialogObject.isEncryptedDialog(dialogId)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
        } else if (DialogObject.isUserDialog(dialogId)) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        args.putInt("message_id", intent.getIntExtra("messageId", 0));
        AndroidUtilities.runOnUIThread(() -> activity.presentFragment(new ChatActivity(args)), 300);
        return true;
    }
}
