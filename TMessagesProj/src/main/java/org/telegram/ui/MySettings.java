package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import org.telegram.messenger.SharedConfig;

import org.telegram.messenger.R;

import org.telegram.messenger.MyFcmDistributor;
import org.telegram.messenger.MyFonts;
import org.telegram.messenger.forkgram.ForkDialogs;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

// Trims Fork Client Settings down to what this build keeps (see report.html, section 1).
public class MySettings {

    // Rows hidden from Fork Client Settings (and its search).
    private static final Set<Integer> HIDDEN = new HashSet<>(Arrays.asList(
        // Privacy
        ForkSettingsActivity.ID_HIDE_SENSITIVE_DATA,
        ForkSettingsActivity.ID_HIDE_SENSITIVE_PHONE,
        ForkSettingsActivity.ID_HIDE_SENSITIVE_USERNAME,
        ForkSettingsActivity.ID_HIDE_SENSITIVE_BIO,
        ForkSettingsActivity.ID_HIDE_SENSITIVE_ID,
        ForkSettingsActivity.ID_FORCE_BLOCK_SCREENSHOTS,
        ForkSettingsActivity.ID_SHOW_NOTIFICATION_CONTENT,
        ForkSettingsActivity.ID_DROP_SCREENSHOT_CAPTION,
        ForkSettingsActivity.ID_FORK_SETTINGS_LOCK,
        // Appearance
        ForkSettingsActivity.ID_HIDE_IN_APP_HINTS,
        ForkSettingsActivity.ID_HIDE_BOTTOM_BUTTON,
        ForkSettingsActivity.ID_CUSTOM_TITLE,
        // Chat list
        ForkSettingsActivity.ID_SYNC_PINS,
        ForkSettingsActivity.ID_HIDE_STORIES_IN_ARCHIVE,
        ForkSettingsActivity.ID_DISABLE_THUMBS_IN_DIALOG_LIST,
        ForkSettingsActivity.ID_DISABLE_GLOBAL_SEARCH,
        ForkSettingsActivity.ID_HIDE_CONTACTS_IN_DIALOGS,
        // Chats
        ForkSettingsActivity.ID_REPLACE_FORWARD,
        ForkSettingsActivity.ID_MENTION_BY_NAME,
        ForkSettingsActivity.ID_HIDE_SEND_AS,
        ForkSettingsActivity.ID_DELETE_ALL_UNPINNED,
        ForkSettingsActivity.ID_DISABLE_SLIDE_TO_NEXT_CHANNEL,
        ForkSettingsActivity.ID_FORMAT_WITH_SECONDS,
        ForkSettingsActivity.ID_FORMATTING_MENU,
        // Reactions & stickers
        ForkSettingsActivity.ID_HIDE_MESSAGE_REACTIONS,
        ForkSettingsActivity.ID_HIDE_SAVED_MESSAGES_TAGS,
        ForkSettingsActivity.ID_DISABLE_LOCKED_ANIMATED_EMOJI,
        ForkSettingsActivity.ID_FULL_RECENT_STICKERS,
        ForkSettingsActivity.ID_SHOW_ARCHIVED_STICKERS,
        ForkSettingsActivity.ID_STICKER_SIZE,
        // Photos & video
        ForkSettingsActivity.ID_INAPP_CAMERA,
        ForkSettingsActivity.ID_SYSTEM_CAMERA,
        ForkSettingsActivity.ID_PHOTO_HAS_STICKER,
        ForkSettingsActivity.ID_DISABLE_MOTION_PHOTO,
        ForkSettingsActivity.ID_DISABLE_FLIP_PHOTOS,
        ForkSettingsActivity.ID_REAR_VIDEO_MESSAGES,
        ForkSettingsActivity.ID_DISABLE_PLAY_VISIBLE_VIDEO_ON_VOLUME,
        ForkSettingsActivity.ID_DISABLE_RECENT_FILES_ATTACHMENT,
        // Voice
        ForkSettingsActivity.ID_VOICE_QUALITY,
        ForkSettingsActivity.ID_OFFLINE_STT,
        ForkSettingsActivity.ID_CLOUDFLARE_ENABLE_STT,
        ForkSettingsActivity.ID_CLOUDFLARE_CREDENTIALS,
        // Bots
        ForkSettingsActivity.ID_BOT_SKIP_SHARE,
        ForkSettingsActivity.ID_BOT_SKIP_FULLSCREEN,
        ForkSettingsActivity.ID_DISABLE_PARAMETERS_FROM_BOT_LINKS,
        // System
        ForkSettingsActivity.ID_WEBSOCKET_TRANSPORT,
        ForkSettingsActivity.ID_WEBSOCKET_DOMAIN,
        ForkSettingsActivity.ID_SATELLITE_DATA_SAVING,
        ForkSettingsActivity.ID_DISABLE_TABLET_MODE,
        ForkSettingsActivity.ID_LOCK_PREMIUM,
        ForkSettingsActivity.ID_LASTFM_LOGIN
    ));

    // Hidden rows whose value is fixed instead of left at the fork default.
    private static final String[] FORCED_ON = {
        "hideInAppHints",
        "hideContactsInDialogs",
        "disableSlideToNextChannel",
        "disableLockedAnimatedEmoji",
        "disableMotionPhoto",
        "lockPremium",
    };
    private static final String[] FORCED_OFF = {
        "replaceForward", // fork default is on (share sheet); keep Telegram's forward picker
    };

    // Called at app start, before MessagesController reads "lockPremium".
    public static void applyForcedPrefs(Context context) {
        SharedPreferences.Editor editor = context.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).edit();
        for (String key : FORCED_ON) {
            editor.putBoolean(key, true);
        }
        for (String key : FORCED_OFF) {
            editor.putBoolean(key, false);
        }
        editor.commit();
    }

    private static final int ID_GOOGLE_PUSH_RELAY = 201;
    private static final int ID_CATCH_UP_MARKS_READ = 202;
    private static final int ID_DEVICE_FONT = 204;
    private static final int ID_GOOGLE_SANS = 205;
    private static final int ID_DIVIDERS = 207;

    public static void filterItems(ArrayList<UItem> items) {
        addOwnItems(items);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == ForkSettingsActivity.ID_SWIPE_RIGHT_TO_READ) {
                items.add(i + 1, UItem.asButtonCheck(ID_CATCH_UP_MARKS_READ, "Catch-up marks chat as read", "After the right-to-left AI summary, mark the chat as read.")
                    .setChecked(MyCatchUp.marksRead()).setMultiline(true));
                break;
            }
        }
        items.removeIf(item -> item.id > 0 && HIDDEN.contains(item.id));
        // Drop sections left with nothing but a header.
        for (int i = items.size() - 2; i >= 0; i--) {
            if (items.get(i).viewType == UniversalAdapter.VIEW_TYPE_HEADER && items.get(i + 1).viewType == UniversalAdapter.VIEW_TYPE_SHADOW) {
                items.remove(i + 1);
                items.remove(i);
            }
        }
    }

    private static void addOwnItems(ArrayList<UItem> items) {
        items.add(0, UItem.asHeader("Look"));
        int next = 1;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            items.add(next++, UItem.asButtonCheck(ID_DEVICE_FONT, "Use device font", "Bold text (names, titles) uses your phone's font instead of Telegram's Roboto.")
                .setChecked(SharedConfig.useSystemBoldFont).setMultiline(true));
        }
        items.add(next++, UItem.asButtonCheck(ID_GOOGLE_SANS, "Use Google Sans", "All text in Google's font, bundled with the app (Latin and Hebrew).")
            .setChecked(MyFonts.googleSans()).setMultiline(true));
        items.add(next++, UItem.asShadow("Only one font choice can be on."));
        items.add(next++, UItem.asHeader("Chat list text size"));
        items.add(next++, UItem.asSlideView(MyChatListSize.LABELS, MyChatListSize.index(), MyChatListSize::setIndex));
        items.add(next++, UItem.asShadow(null));
        items.add(next++, UItem.asHeader("Chat list photo size"));
        items.add(next++, UItem.asSlideView(MyChatListSize.AVATAR_LABELS, MyChatListSize.avatarIndex(), MyChatListSize::setAvatarIndex));
        items.add(next++, UItem.asShadow(null));
        items.add(next++, UItem.asButtonCheck(ID_DIVIDERS, "List dividers", "A thin line between chats, with the same margin on both sides.")
            .setChecked(MyChatListSize.dividers()).setMultiline(true));
        items.add(next, UItem.asShadow("Reopen Tegram after changing any of these."));
        items.add(UItem.asHeader("Google push"));
        String relay = MyFcmDistributor.relayUrl();
        items.add(UItem.asSettingsCell(ID_GOOGLE_PUSH_RELAY, "Google push relay", relay.isEmpty() ? "Not set" : Uri.parse(relay).getHost()));
        items.add(UItem.asShadow("Your own relay (gateway/cloudflare-worker) lets Tegram receive notifications through Google Play Services, without ntfy. "
            + "After setting it, pick this app as the UnifiedPush distributor in Notifications and Sounds."));
    }

    public static boolean onClick(BaseFragment fragment, UItem item, Runnable refresh) {
        if (item.id == ID_DIVIDERS) {
            MyChatListSize.toggle("chatListDividers");
            refresh.run();
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contacts_sync_on, "Reopen Tegram to apply.").show();
            return true;
        }
        if (item.id == ID_GOOGLE_SANS) {
            MyFonts.setGoogleSans(!MyFonts.googleSans());
            if (MyFonts.googleSans() && SharedConfig.useSystemBoldFont) {
                SharedConfig.toggleUseSystemBoldFont();
            }
            refresh.run();
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contacts_sync_on, "Reopen Tegram to apply.").show();
            return true;
        }
        if (item.id == ID_DEVICE_FONT) {
            if (!SharedConfig.useSystemBoldFont && MyFonts.googleSans()) {
                MyFonts.setGoogleSans(false);
            }
            SharedConfig.toggleUseSystemBoldFont();
            refresh.run();
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contacts_sync_on, "Reopen Tegram to apply.").show();
            return true;
        }
        if (item.id == ID_CATCH_UP_MARKS_READ) {
            MyCatchUp.setMarksRead(!MyCatchUp.marksRead());
            refresh.run();
            return true;
        }
        if (item.id != ID_GOOGLE_PUSH_RELAY) {
            return false;
        }
        ForkDialogs.createFieldAlert(fragment.getParentActivity(), "Relay URL", MyFcmDistributor.relayUrl(), url -> {
            if (!url.trim().isEmpty() && !url.trim().startsWith("https://")) {
                BulletinFactory.of(fragment).createErrorBulletin("The relay URL must start with https://").show();
                return null;
            }
            if (url.trim().isEmpty()) {
                MyFcmDistributor.setConfig("", "");
                refresh.run();
                return null;
            }
            ForkDialogs.createFieldAlert(fragment.getParentActivity(), "Relay VAPID public key", MyFcmDistributor.vapidKey(), key -> {
                MyFcmDistributor.setConfig(url, key);
                refresh.run();
                return null;
            }, "The VAPID_PUBLIC_KEY printed when the relay was set up.");
            return null;
        }, "Your Cloudflare Worker address, e.g. https://tegram-push.you.workers.dev/ (leave empty to turn Google push off).");
        return true;
    }
}
