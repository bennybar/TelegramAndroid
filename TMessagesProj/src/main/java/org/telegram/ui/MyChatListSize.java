package org.telegram.ui;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;

// Chat list text size: scales name/preview/time fonts and grows the row height to match.
// Text positions inside the row stay stock; up to 115% the scaled text still fits between them.
public class MyChatListSize {

    public static final String[] LABELS = {"90%", "100%", "105%", "110%", "115%"};
    private static final float[] SCALES = {0.9f, 1f, 1.05f, 1.1f, 1.15f};

    public static int index() {
        int index = MessagesController.getGlobalMainSettings().getInt("chatListTextSize", 1);
        return Math.max(0, Math.min(SCALES.length - 1, index));
    }

    public static void setIndex(int index) {
        MessagesController.getGlobalMainSettings().edit().putInt("chatListTextSize", index).apply();
    }

    private static float scale() {
        return SCALES[index()];
    }

    // DialogCell constructor hook: taller (or shorter) rows for the scaled text.
    public static void applyToCell(DialogCell cell) {
        float extra = scale() - 1f;
        if (extra != 0) {
            cell.heightDefault = Math.round(cell.heightDefault + extra * 36);    // name + one preview line
            cell.heightThreeLines = Math.round(cell.heightThreeLines + extra * 44); // name + two preview lines
        }
    }

    // DialogCell buildLayout hook, right after the stock sizes are set.
    public static void scalePaints() {
        float s = scale();
        if (s == 1f) {
            return;
        }
        Theme.dialogs_namePaint[0].setTextSize(AndroidUtilities.dp(17 * s));
        Theme.dialogs_nameEncryptedPaint[0].setTextSize(AndroidUtilities.dp(17 * s));
        Theme.dialogs_messagePaint[0].setTextSize(AndroidUtilities.dp(16 * s));
        Theme.dialogs_messagePrintingPaint[0].setTextSize(AndroidUtilities.dp(16 * s));
        Theme.dialogs_namePaint[1].setTextSize(AndroidUtilities.dp(16 * s));
        Theme.dialogs_nameEncryptedPaint[1].setTextSize(AndroidUtilities.dp(16 * s));
        Theme.dialogs_messagePaint[1].setTextSize(AndroidUtilities.dp(15 * s));
        Theme.dialogs_messagePrintingPaint[1].setTextSize(AndroidUtilities.dp(15 * s));
        Theme.dialogs_messageNamePaint.setTextSize(AndroidUtilities.dp(14 * s));
        Theme.dialogs_timePaint.setTextSize(AndroidUtilities.dp(12 * s));
        Theme.dialogs_timePaintBold.setTextSize(AndroidUtilities.dp(12 * s));
        Theme.dialogs_timePaintBoldAccent.setTextSize(AndroidUtilities.dp(12 * s));
    }
}
