package org.telegram.ui;

import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

// Pull the chat list down past its top to drop the connection and resync.
public class PullToReconnect {

    private static final long COOLDOWN_MS = 10_000;
    private static final long[] lastRunTime = new long[UserConfig.MAX_ACCOUNT_COUNT];

    private float lastY;
    private float pulledDistance;

    // Feed every touch event of the list; `list` can't scroll up means we're at the very top.
    public void onTouchEvent(View list, MotionEvent e, BaseFragment fragment) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastY = e.getY();
                pulledDistance = 0;
                break;
            case MotionEvent.ACTION_MOVE:
                float y = e.getY();
                if (y > lastY && !list.canScrollVertically(-1)) {
                    pulledDistance += y - lastY;
                }
                lastY = y;
                break;
            case MotionEvent.ACTION_UP:
                if (pulledDistance >= AndroidUtilities.dp(120) && reconnect(fragment.getCurrentAccount())) {
                    try {
                        list.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                    } catch (Exception ignored) {}
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contacts_sync_on, LocaleController.getString(R.string.MyReconnecting)).show();
                }
                pulledDistance = 0;
                break;
            case MotionEvent.ACTION_CANCEL:
                pulledDistance = 0;
                break;
        }
    }

    // Drops the main connection (tgnet resends pending requests on the new one) and asks for missed updates.
    // Returns false when throttled, so repeated pulls never turn into extra server traffic.
    public static boolean reconnect(int account) {
        long now = SystemClock.elapsedRealtime();
        if (lastRunTime[account] != 0 && now - lastRunTime[account] < COOLDOWN_MS) {
            return false;
        }
        lastRunTime[account] = now;
        ConnectionsManager connectionsManager = ConnectionsManager.getInstance(account);
        connectionsManager.discardConnection(connectionsManager.getCurrentDatacenterId(), ConnectionsManager.ConnectionTypeGeneric);
        MessagesController.getInstance(account).getDifference();
        return true;
    }
}
