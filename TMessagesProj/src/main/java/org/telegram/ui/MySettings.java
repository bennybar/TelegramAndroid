package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import org.telegram.messenger.SharedConfig;

import org.telegram.messenger.R;

import org.telegram.messenger.MyFcmDistributor;
import org.telegram.messenger.MyFonts;
import org.telegram.messenger.MyRtl;
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
    private static final int ID_MD3_TAB_BAR = 208;
    private static final int ID_HIDE_PROFILE_TAB = 209;
    private static final int ID_COMPACT_SEARCH = 210;
    private static final int ID_PINNED_TINT = 211;
    private static final int ID_DOUBLE_TAP_REPLY = 212;
    private static final int ID_PREFER_RTL = 213;
    private static final int ID_MIRROR_LIST = 214;
    private static final int ID_BADGE_ON_PHOTO = 215;
    private static final int ID_TICKS_UNDER_TIME = 216;
    private static final int ID_BADGE_BORDER = 217;
    private static final int ID_HIDE_GIFT = 218;
    private static final int ID_BUBBLE_ICON = 219;
    private static final int ID_READ_LABEL = 220;
    private static final int ID_TYPING_BUBBLE = 221;
    private static final int ID_CENTERED_HEADER = 222;
    private static final int ID_BUBBLE_BLUE = 223;
    private static final int ID_CLUTTER_BASE = 230; // 230..235, one per MyChatTweaks.CLUTTER_KEYS
    private static final String[][] CLUTTER_ROWS = {
        {"Star reactions", "The ⭐ paid reaction in reaction bars and under posts."},
        {"Boost prompts", "\"Boost\" in chat menus and boost counts next to group members' names."},
        {"Similar channels", "The recommendations strip after joining a channel."},
        {"Emoji statuses", "Animated emoji and Premium stars next to names. Verified checks stay."},
        {"Suggest post button", "\"Suggest a post\" in channel bars and direct messages."},
        {"Bot app buttons", "Mini-app \"Open\" buttons in bot chats, the chat list and search. Bot commands stay."},
    };

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
        items.add(1, UItem.asButtonCheck(ID_BUBBLE_ICON, "Messages-style icon", "A green speech-bubble app icon on your home screen.")
            .setChecked(MyIcons.bubbleEnabled()).setMultiline(true));
        items.add(2, UItem.asButtonCheck(ID_BUBBLE_BLUE, "Telegram blue", "Use Telegram blue instead of green for the Messages-style icon.")
            .setChecked(MyIcons.blue()).setMultiline(true));
        int next = 3; // after the "Look" header and the two icon rows
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
        items.add(next++, UItem.asHeader("Line spacing in chats"));
        items.add(next++, UItem.asSlideView(MyChatListSize.LINE_LABELS, MyChatListSize.lineIndex(), MyChatListSize::setLineIndex));
        items.add(next++, UItem.asShadow(null));
        items.add(next++, UItem.asHeader("Chat list row spacing"));
        items.add(next++, UItem.asSlideView(MyChatListSize.ROW_LABELS, MyChatListSize.rowIndex(), MyChatListSize::setRowIndex));
        items.add(next++, UItem.asShadow(null));
        items.add(next++, UItem.asHeader("Chat list side padding"));
        items.add(next++, UItem.asSlideView(MyChatListSize.SIDE_LABELS, MyChatListSize.sideIndex(), MyChatListSize::setSideIndex));
        items.add(next++, UItem.asShadow(null));
        items.add(next++, UItem.asHeader("Chat list photo size"));
        items.add(next++, UItem.asSlideView(MyChatListSize.AVATAR_LABELS, MyChatListSize.avatarIndex(), MyChatListSize::setAvatarIndex));
        items.add(next++, UItem.asShadow(null));
        items.add(next++, UItem.asButtonCheck(ID_DIVIDERS, "List dividers", "A thin line between chats, with the same margin on both sides.")
            .setChecked(MyChatListSize.dividers()).setMultiline(true));
        items.add(next++, UItem.asButtonCheck(ID_BADGE_ON_PHOTO, "Unread count on photo", "The unread count sits on the photo's top corner, like app icon badges.")
            .setChecked(MyChatListSize.badgeOnPhoto()).setMultiline(true));
        items.add(next++, UItem.asButtonCheck(ID_BADGE_BORDER, "Blue border on photo badge", "Makes the unread count on the photo stand out, muted chats included.")
            .setChecked(MyChatListSize.badgeBorder()).setMultiline(true));
        items.add(next++, UItem.asButtonCheck(ID_TICKS_UNDER_TIME, "Read ticks under the time", "The ✓✓ move from beside the time to the line below it. Best with the unread count on the photo.")
            .setChecked(MyChatListSize.ticksUnderTime()).setMultiline(true));
        items.add(next++, UItem.asButtonCheck(ID_PINNED_TINT, "Tint pinned chats", "A faint accent background on pinned chats instead of the pin next to the time.")
            .setChecked(MyUiTweaks.pinnedTint()).setMultiline(true));
        items.add(next++, UItem.asButtonCheck(ID_COMPACT_SEARCH, "Compact search bar", "A slimmer search field above the chat list.")
            .setChecked(MyUiTweaks.compactSearch()).setMultiline(true));
        items.add(next++, UItem.asButtonCheck(ID_MD3_TAB_BAR, "Material bottom bar", "A flat, full-width Material 3 bar instead of the floating glass one.")
            .setChecked(MyUiTweaks.md3TabBar()).setMultiline(true));
        items.add(next++, UItem.asButtonCheck(ID_HIDE_PROFILE_TAB, "Hide Profile tab", "Your profile stays reachable from Settings. Kept while the Calls tab is shown.")
            .setChecked(MyUiTweaks.hideProfileTab()).setMultiline(true));
        items.add(next++, UItem.asShadow(null));
        items.add(next++, UItem.asHeader("Inside chats"));
        items.add(next++, UItem.asButtonCheck(ID_DOUBLE_TAP_REPLY, "Double-tap to reply", "Double-tap a message to reply to it, instead of sending a reaction.")
            .setChecked(MyChatTweaks.doubleTapReply()).setMultiline(true));
        items.add(next++, UItem.asShadow(null));
        items.add(next++, UItem.asHeader("Hide clutter"));
        for (int i = 0; i < CLUTTER_ROWS.length; i++) {
            items.add(next++, UItem.asButtonCheck(ID_CLUTTER_BASE + i, CLUTTER_ROWS[i][0], CLUTTER_ROWS[i][1])
                .setChecked(MyChatTweaks.isOn(MyChatTweaks.CLUTTER_KEYS[i])).setMultiline(true));
        }
        items.add(next++, UItem.asButtonCheck(ID_HIDE_GIFT, "Hide gift button", "No gift button in any chat, including channels and groups.")
            .setChecked(MyChatTweaks.hideGiftButton()).setMultiline(true));
        items.add(next++, UItem.asButtonCheck(ID_CENTERED_HEADER, "Centered chat header", "The photo and name sit in the middle of the chat's top bar, like iMessage.")
            .setChecked(MyChatExtras.centeredHeader()).setMultiline(true));
        items.add(next++, UItem.asButtonCheck(ID_TYPING_BUBBLE, "Typing bubble", "An animated ••• bubble at the bottom of the chat while the other side types.")
            .setChecked(MyChatExtras.typingBubble()).setMultiline(true));
        items.add(next++, UItem.asButtonCheck(ID_READ_LABEL, "\"Read\" under your last message", "Sending… / Delivered / Read below your newest message, like iMessage.")
            .setChecked(MyChatExtras.readLabel()).setMultiline(true));
        items.add(next++, UItem.asButtonCheck(ID_PREFER_RTL, "Prefer right-to-left", "Messages with any Hebrew in them read right-to-left, even when they start with an English word, number, link or emoji. Also while typing.")
            .setChecked(MyRtl.preferRtl()).setMultiline(true));
        items.add(next++, UItem.asButtonCheck(ID_MIRROR_LIST, "Mirror chat list", "Right-to-left chat rows, like Telegram in Hebrew: photo on the right, time on the left. Menus stay in English.")
            .setChecked(MyRtl.mirrorList()).setMultiline(true));
        items.add(next++, UItem.asShadow(null));
        items.add(next++, UItem.asHeader("Message bubble width"));
        items.add(next++, UItem.asSlideView(MyChatTweaks.BUBBLE_LABELS, MyChatTweaks.bubbleIndex(), MyChatTweaks::setBubbleIndex));
        items.add(next, UItem.asShadow("Reopen Tegram after changing any of these."));
        items.add(UItem.asHeader("Google push"));
        String relay = MyFcmDistributor.relayUrl();
        items.add(UItem.asSettingsCell(ID_GOOGLE_PUSH_RELAY, "Google push relay", relay.isEmpty() ? "Not set" : Uri.parse(relay).getHost()));
        items.add(UItem.asShadow("Your own relay (gateway/cloudflare-worker) lets Tegram receive notifications through Google Play Services, without ntfy. "
            + "After setting it, pick this app as the UnifiedPush distributor in Notifications and Sounds."));
    }

    // SettingsBackup hook: also back up Telegram's notification settings, other accounts' settings and this
    // build's own preference files (AI, Google push relay, reminders).
    public static java.util.List<String> extraBackupPrefs() {
        java.util.List<String> names = new ArrayList<>(Arrays.asList("Notifications", "myai", "mypush", "myreminders"));
        for (int account = 1; account < 10; account++) {
            names.add("mainconfig" + account);
            names.add("Notifications" + account);
        }
        return names;
    }

    // The OpenAI key stays out of the (plain text) backup file.
    public static boolean skipInBackup(String prefsName, String key) {
        return "myai".equals(prefsName) && "apiKey".equals(key);
    }

    public static boolean onClick(BaseFragment fragment, UItem item, Runnable refresh) {
        if (item.id == ID_BADGE_ON_PHOTO || item.id == ID_TICKS_UNDER_TIME || item.id == ID_BADGE_BORDER) {
            MyChatListSize.toggle(item.id == ID_BADGE_ON_PHOTO ? "badgeOnPhoto" : item.id == ID_TICKS_UNDER_TIME ? "ticksUnderTime" : "badgeBorder");
            refresh.run();
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contacts_sync_on, "Reopen Tegram to apply.").show();
            return true;
        }
        if (item.id == ID_MIRROR_LIST) {
            MyRtl.toggleMirrorList();
            refresh.run();
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contacts_sync_on, "Reopen Tegram to apply.").show();
            return true;
        }
        if (item.id >= ID_CLUTTER_BASE && item.id < ID_CLUTTER_BASE + CLUTTER_ROWS.length) {
            MyChatTweaks.toggle(MyChatTweaks.CLUTTER_KEYS[item.id - ID_CLUTTER_BASE]);
            refresh.run();
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contacts_sync_on, "Reopen Tegram to apply everywhere.").show();
            return true;
        }
        if (item.id == ID_READ_LABEL || item.id == ID_TYPING_BUBBLE || item.id == ID_CENTERED_HEADER) {
            MyChatExtras.toggle(item.id == ID_READ_LABEL ? "readLabel" : item.id == ID_TYPING_BUBBLE ? "typingBubble" : "centeredHeader");
            refresh.run();
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contacts_sync_on, "Applies to chats opened from now on.").show();
            return true;
        }
        if (item.id == ID_BUBBLE_BLUE) {
            MyIcons.setBlue(!MyIcons.blue());
            refresh.run();
            return true;
        }
        if (item.id == ID_BUBBLE_ICON) {
            MyIcons.setBubbleEnabled(!MyIcons.bubbleEnabled());
            refresh.run();
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contacts_sync_on, "Icon changed. Your launcher may take a moment to update it.").show();
            return true;
        }
        if (item.id == ID_HIDE_GIFT) {
            MyChatTweaks.toggleHideGiftButton();
            refresh.run();
            return true;
        }
        if (item.id == ID_PREFER_RTL) {
            MyRtl.togglePreferRtl();
            refresh.run();
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contacts_sync_on, "Reopen Tegram to apply.").show();
            return true;
        }
        if (item.id == ID_DOUBLE_TAP_REPLY) {
            MyChatTweaks.toggleDoubleTapReply();
            refresh.run();
            return true;
        }
        if (item.id == ID_MD3_TAB_BAR || item.id == ID_HIDE_PROFILE_TAB || item.id == ID_COMPACT_SEARCH || item.id == ID_PINNED_TINT) {
            MyUiTweaks.toggle(item.id == ID_MD3_TAB_BAR ? "md3TabBar" : item.id == ID_HIDE_PROFILE_TAB ? "hideProfileTab" : item.id == ID_COMPACT_SEARCH ? "compactSearch" : "pinnedTint");
            refresh.run();
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contacts_sync_on, "Reopen Tegram to apply.").show();
            return true;
        }
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
