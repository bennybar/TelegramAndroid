package org.telegram.ui;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;

// In-chat toggles: double-tap a message to reply, wider message bubbles.
public class MyChatTweaks {

    public static final String[] BUBBLE_LABELS = {"Default", "91%", "93%", "95%"};
    private static final float[] BUBBLE_WIDTHS = {0f, 0.91f, 0.93f, 0.95f};
    // Bubble padding plus the margin on the bubble's own side, inside Telegram's stock "screen width - 80dp" text width.
    private static final int BUBBLE_CHROME_DP = 39;

    public static boolean doubleTapReply() {
        return MessagesController.getGlobalMainSettings().getBoolean("doubleTapReply", false);
    }

    public static void toggleDoubleTapReply() {
        MessagesController.getGlobalMainSettings().edit().putBoolean("doubleTapReply", !doubleTapReply()).apply();
    }

    // "Hide clutter" toggles, each checked at one hook in Telegram's code.
    public static final String[] CLUTTER_KEYS = {"hidePaidReactions", "hideBoost", "hideSimilarChannels", "hideEmojiStatus", "hideSuggestButton", "hideBotAppButton"};

    private static boolean pref(String key) {
        return MessagesController.getGlobalMainSettings().getBoolean(key, false);
    }

    public static void toggle(String key) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(key, !pref(key)).apply();
    }

    public static boolean isOn(String key) {
        return pref(key);
    }

    public static boolean hidePaidReactions() { return pref("hidePaidReactions"); }
    public static boolean hideBoost() { return pref("hideBoost"); }
    public static boolean hideSimilarChannels() { return pref("hideSimilarChannels"); }
    public static boolean hideEmojiStatus() { return pref("hideEmojiStatus"); }
    public static boolean hideSuggestButton() { return pref("hideSuggestButton"); }
    public static boolean hideBotAppButton() { return pref("hideBotAppButton"); }

    public static boolean hideGiftButton() {
        return MessagesController.getGlobalMainSettings().getBoolean("hideGiftButton", false);
    }

    public static void toggleHideGiftButton() {
        MessagesController.getGlobalMainSettings().edit().putBoolean("hideGiftButton", !hideGiftButton()).apply();
    }

    public static int bubbleIndex() {
        int index = MessagesController.getGlobalMainSettings().getInt("bubbleWidth", 0);
        return Math.max(0, Math.min(BUBBLE_WIDTHS.length - 1, index));
    }

    public static void setBubbleIndex(int index) {
        MessagesController.getGlobalMainSettings().edit().putInt("bubbleWidth", index).apply();
    }

    // MessageObject.getMaxMessageTextWidth hook: extra text width so bubbles can reach the chosen share of the
    // screen. Only ever widens (the empty gap on the far side shrinks); avatar/share-button room is kept.
    public static int extraTextWidth(int parentWidth) {
        float share = BUBBLE_WIDTHS[bubbleIndex()];
        if (share == 0f) {
            return 0;
        }
        int stockGap = AndroidUtilities.dp(80 - BUBBLE_CHROME_DP);
        int wantedGap = (int) (parentWidth * (1f - share));
        return Math.max(0, stockGap - wantedGap);
    }
}
