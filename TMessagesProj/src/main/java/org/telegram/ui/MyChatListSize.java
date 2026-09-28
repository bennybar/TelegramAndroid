package org.telegram.ui;

import android.graphics.RectF;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;

// Chat list text size: scales name/preview/time fonts and grows the row height to match.
// Text positions inside the row stay stock; up to 115% the scaled text still fits between them.
public class MyChatListSize {

    public static final String[] LABELS = {"90%", "100%", "105%", "110%", "115%"};
    private static final float[] SCALES = {0.9f, 1f, 1.05f, 1.1f, 1.15f};
    public static final String[] AVATAR_LABELS = {"80%", "90%", "100%", "110%", "120%"};
    private static final float[] AVATAR_SCALES = {0.8f, 0.9f, 1f, 1.1f, 1.2f};

    public static boolean dividers() {
        return MessagesController.getGlobalMainSettings().getBoolean("chatListDividers", false);
    }

    // Divider with the same 15dp margin on both sides (the time's margin), instead of starting under the name.
    public static int dividerEndInset() {
        return dividers() ? AndroidUtilities.dp(15) : 0;
    }

    public static int dividerStartInset(int stock) {
        return dividers() ? AndroidUtilities.dp(15) : stock;
    }

    public static void toggle(String key) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(key, !MessagesController.getGlobalMainSettings().getBoolean(key, false)).apply();
    }

    public static final String[] SIDE_LABELS = {"Default", "+4", "+8", "+12", "+16"};
    private static final int[] SIDE_PADDING_DP = {0, 4, 8, 12, 16};

    public static int sideIndex() {
        int index = MessagesController.getGlobalMainSettings().getInt("chatListSidePadding", 0);
        return Math.max(0, Math.min(SIDE_PADDING_DP.length - 1, index));
    }

    public static void setSideIndex(int index) {
        MessagesController.getGlobalMainSettings().edit().putInt("chatListSidePadding", index).apply();
    }

    private static int sidePaddingDp() {
        return SIDE_PADDING_DP[sideIndex()];
    }

    // DialogCell.setLayoutParams hook: equal margins on both sides of each chat list row (the list honours them),
    // so everything in the row moves in together in both the normal and the mirrored layout.
    public static ViewGroup.LayoutParams withSideMargins(ViewGroup.LayoutParams params) {
        if (params instanceof RecyclerView.LayoutParams) {
            int margin = AndroidUtilities.dp(sidePaddingDp());
            ((RecyclerView.LayoutParams) params).leftMargin = margin;
            ((RecyclerView.LayoutParams) params).rightMargin = margin;
        }
        return params;
    }

    public static final String[] LINE_LABELS = {"Default", "+2", "+4", "+6"};
    private static final int[] LINE_GAP_DP = {0, 2, 4, 6};

    public static int lineIndex() {
        int index = MessagesController.getGlobalMainSettings().getInt("chatListLineSpacing", 0);
        return Math.max(0, Math.min(LINE_GAP_DP.length - 1, index));
    }

    public static void setLineIndex(int index) {
        MessagesController.getGlobalMainSettings().edit().putInt("chatListLineSpacing", index).apply();
    }

    // Extra space between the name and the preview, and between the two preview lines.
    public static int lineGapPx() {
        return AndroidUtilities.dp(LINE_GAP_DP[lineIndex()]);
    }

    public static final String[] ROW_LABELS = {"Default", "+4", "+8", "+12", "+16"};
    private static final int[] ROW_EXTRA_DP = {0, 4, 8, 12, 16};

    public static int rowIndex() {
        int index = MessagesController.getGlobalMainSettings().getInt("chatListRowSpacing", 0);
        return Math.max(0, Math.min(ROW_EXTRA_DP.length - 1, index));
    }

    public static void setRowIndex(int index) {
        MessagesController.getGlobalMainSettings().edit().putInt("chatListRowSpacing", index).apply();
    }

    // Half of the extra row height: the content is drawn this much lower so it stays centered.
    public static int rowShiftPx() {
        return AndroidUtilities.dp(ROW_EXTRA_DP[rowIndex()] / 2f);
    }

    // DialogCell.onLayout hook: child views (e.g. the emoji status next to the name) move with the content.
    public static void offsetChildren(ViewGroup cell) {
        int shift = rowShiftPx();
        if (shift == 0) {
            return;
        }
        for (int i = 0; i < cell.getChildCount(); i++) {
            cell.getChildAt(i).offsetTopAndBottom(shift);
        }
    }

    public static int avatarIndex() {
        int index = MessagesController.getGlobalMainSettings().getInt("chatListAvatarSize", 2);
        return Math.max(0, Math.min(AVATAR_SCALES.length - 1, index));
    }

    public static void setAvatarIndex(int index) {
        MessagesController.getGlobalMainSettings().edit().putInt("chatListAvatarSize", index).apply();
    }

    private static float avatarScale() {
        return AVATAR_SCALES[avatarIndex()];
    }

    // buildLayout hook, right after the stock avatar rect is set: resize around its center, keeping the
    // outer edge (left, or right in RTL) where it was. The text column was moved by applyToCell to match.
    public static void scaleAvatar(RectF rect) {
        float s = avatarScale();
        if (s == 1f) {
            return;
        }
        float size = rect.width() * s;
        float centerY = rect.centerY();
        if (LocaleController.isRTL) {
            rect.left = rect.right - size;
        } else {
            rect.right = rect.left + size;
        }
        if (s > 1f) {
            rect.bottom = rect.top + size; // grow down; applyToCell made the row taller by the same amount
        } else {
            rect.top = centerY - size / 2f;
            rect.bottom = centerY + size / 2f;
        }
    }

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
        if (org.telegram.messenger.MyRtl.mirrorList()) {
            cell.messagePaddingStart += 6; // a little more room between the photo and the text in mirrored rows
        }
        int lineGap = LINE_GAP_DP[lineIndex()];
        cell.heightDefault += lineGap;          // name -> preview
        cell.heightThreeLines += lineGap * 2;   // name -> preview, and between the two preview lines
        int rowExtra = ROW_EXTRA_DP[rowIndex()];
        cell.heightDefault += rowExtra;
        cell.heightThreeLines += rowExtra;
        float avatar = avatarScale();
        if (avatar != 1f) {
            // Text starts after the avatar: move it by the avatar's growth (56dp two-line avatar as reference).
            cell.messagePaddingStart = Math.round(cell.messagePaddingStart + 56 * (avatar - 1f));
            // Keep the stock gap above and below the avatar (11dp two-line, 9dp three-line).
            cell.heightDefault = Math.max(cell.heightDefault, Math.round(56 * avatar) + 22);
            cell.heightThreeLines = Math.max(cell.heightThreeLines, Math.round(52 * avatar) + 18);
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
