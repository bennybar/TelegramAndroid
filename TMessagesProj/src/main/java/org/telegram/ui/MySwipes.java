package org.telegram.ui;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.ui.ActionBar.BaseFragment;

// Chat list swipes: each direction picks its own action, so an accidental swipe doesn't spend OpenAI tokens.
// Left-to-right: off / mark read / AI catch-up. Right-to-left: Telegram's own swipe action (Chat Settings) /
// mark read / AI catch-up. Replaces forkgram's single "swipeRightToRead" toggle, which seeds the defaults.
public class MySwipes {

    public static final int NONE = 0; // left-to-right: off; right-to-left: Telegram's swipe action
    public static final int READ = 1;
    public static final int CATCH_UP = 2;

    public static final String[] LEFT_TO_RIGHT_LABELS = {"Off", "Mark read", "AI catch-up"};
    public static final String[] RIGHT_TO_LEFT_LABELS = {"Telegram's", "Mark read", "AI catch-up"};

    private static boolean legacy() {
        return MessagesController.getGlobalMainSettings().getBoolean("swipeRightToRead", false);
    }

    public static int leftToRight() {
        return MessagesController.getGlobalMainSettings().getInt("swipeLeftToRight", legacy() ? READ : NONE);
    }

    public static int rightToLeft() {
        return MessagesController.getGlobalMainSettings().getInt("swipeRightToLeft", legacy() ? CATCH_UP : NONE);
    }

    public static void setLeftToRight(int action) {
        MessagesController.getGlobalMainSettings().edit().putInt("swipeLeftToRight", action).apply();
    }

    public static void setRightToLeft(int action) {
        MessagesController.getGlobalMainSettings().edit().putInt("swipeRightToLeft", action).apply();
    }

    // What a swipe on this chat does; secret chats are never sent to OpenAI.
    public static int action(boolean leftToRight, long dialogId) {
        int action = leftToRight ? leftToRight() : rightToLeft();
        if (action == CATCH_UP && DialogObject.isEncryptedDialog(dialogId)) {
            return NONE;
        }
        return action;
    }

    public static void perform(BaseFragment fragment, int action, long dialogId, Runnable markRead) {
        if (action == READ) {
            markRead.run();
        } else if (action == CATCH_UP) {
            MyCatchUp.run(fragment, dialogId, markRead);
        }
    }
}
