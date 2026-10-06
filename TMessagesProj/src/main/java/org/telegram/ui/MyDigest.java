package org.telegram.ui;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

// Morning digest: once a day, summarize the last 12 hours of the AI tab's chats and post one notification.
// An inexact while-idle alarm wakes the app; it starts a network-constrained job that does the work.
public class MyDigest {

    private static final int JOB_ID = 0x7E61;
    private static final int NOTIFICATION_ID = 0x7E62;
    private static final String CHANNEL_ID = "my_morning_digest";
    private static final String ACTION_OPEN = "org.telegram.ui.MyDigest.OPEN";
    private static final int WINDOW_HOURS = 12;

    private static AiSummarizer running;

    public static boolean enabled() {
        return AiSummarizer.prefs().getBoolean("digestEnabled", false);
    }

    public static int minuteOfDay() {
        return AiSummarizer.prefs().getInt("digestMinute", 8 * 60);
    }

    public static String timeText() {
        return String.format(Locale.US, "%02d:%02d", minuteOfDay() / 60, minuteOfDay() % 60);
    }

    public static void setEnabled(boolean value) {
        AiSummarizer.prefs().edit().putBoolean("digestEnabled", value).apply();
        schedule(ApplicationLoader.applicationContext);
    }

    public static void setMinuteOfDay(int minute) {
        AiSummarizer.prefs().edit().putInt("digestMinute", minute).apply();
        schedule(ApplicationLoader.applicationContext);
    }

    // Called at app start (also after reboot, via the boot receiver) and whenever the settings change.
    public static void schedule(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent intent = PendingIntent.getBroadcast(context, 0, new Intent(context, AlarmReceiver.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        alarmManager.cancel(intent);
        if (!enabled()) {
            return;
        }
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, minuteOfDay() / 60);
        next.set(Calendar.MINUTE, minuteOfDay() % 60);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= System.currentTimeMillis()) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), intent);
    }

    public static class AlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            schedule(context);
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, Job.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build());
        }
    }

    public static class Job extends JobService {
        @Override
        public boolean onStartJob(JobParameters params) {
            AndroidUtilities.runOnUIThread(() -> run(() -> jobFinished(params, false)));
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            if (running != null) {
                running.cancel();
                running = null;
            }
            return false;
        }
    }

    // Collects, summarizes and notifies. Also used by the AI tab's "Send now".
    public static void run(Runnable onFinished) {
        ApplicationLoader.postInitApplication();
        int account = UserConfig.selectedAccount;
        if (running != null || !UserConfig.getInstance(account).isClientActivated() || AiSummarizer.prefs().getString("apiKey", "").isEmpty()) {
            onFinished.run();
            return;
        }
        ConnectionsManager.getInstance(account).resumeNetworkMaybe();
        int since = ConnectionsManager.getInstance(account).getCurrentTime() - WINDOW_HOURS * 3600;
        ArrayList<Long> chats = AiSummaryActivity.chatsForSummary(account, since);
        if (chats.isEmpty()) {
            onFinished.run();
            return;
        }
        String title = "Morning digest · " + chats.size() + (chats.size() == 1 ? " chat" : " chats");
        running = new AiSummarizer(account, chats, since, new AiSummarizer.Callback() {
            @Override
            public void onProgress(String text) {
                ConnectionsManager.getInstance(account).resumeNetworkMaybe();
            }

            @Override
            public void onCollected(int chatCount, int messages, int approxTokens) {
                if (messages == 0) {
                    running = null;
                    onFinished.run();
                    return;
                }
                running.send();
            }

            @Override
            public void onDone(String summary) {
                AiSummaryActivity.saveLast(title, summary, running.refs);
                running = null;
                postNotification(title, summary);
                onFinished.run();
            }

            @Override
            public void onError(String error) {
                running = null;
                postNotification("Morning digest failed", error);
                onFinished.run();
            }
        });
        running.setNewsDigest(WINDOW_HOURS + " hours");
        running.start();
    }

    private static void postNotification(String title, String summary) {
        Context context = ApplicationLoader.applicationContext;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Morning digest", NotificationManager.IMPORTANCE_DEFAULT));
        }
        String text = summary.replaceAll("\\s*\\[r\\d+\\]", "").replaceAll("(?m)^## ", "").replaceAll("(?m)^- ", "• ").replace("**", "").trim();
        String firstLine = text.contains("\n") ? text.substring(text.indexOf('\n') + 1).split("\n")[0] : text;
        Intent open = new Intent(context, LaunchActivity.class).setAction(ACTION_OPEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(MyIcons.notificationIcon())
            .setContentTitle(title)
            .setContentText(firstLine)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    // LaunchActivity hook: tapping the notification opens the saved summary.
    public static boolean handleIntent(LaunchActivity activity, Intent intent) {
        if (intent == null || !ACTION_OPEN.equals(intent.getAction()) || AndroidUtilities.needShowPasscode(false)) {
            return false;
        }
        AndroidUtilities.runOnUIThread(() -> {
            BaseFragment fragment = AiSummaryActivity.lastResultFragment();
            activity.presentFragment(fragment != null ? fragment : new AiSummaryActivity());
        }, 300);
        return true;
    }
}
