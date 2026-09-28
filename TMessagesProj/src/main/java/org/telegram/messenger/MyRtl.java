package org.telegram.messenger;

import android.text.Layout;
import android.text.TextDirectionHeuristic;
import android.text.TextDirectionHeuristics;
import android.view.View;
import android.widget.TextView;

// "Prefer RTL": a paragraph with any Hebrew/Arabic in it is laid out right-to-left, even when it starts with an
// English word, number, @mention, link or emoji. Android's default (first strong character) makes those LTR.
public class MyRtl {

    public static boolean preferRtl() {
        return MessagesController.getGlobalMainSettings().getBoolean("preferRtl", false);
    }

    public static void togglePreferRtl() {
        MessagesController.getGlobalMainSettings().edit().putBoolean("preferRtl", !preferRtl()).apply();
    }

    // MessageObject.makeStaticLayout hook (message bubbles). FIRSTSTRONG_LTR is StaticLayout's own default.
    public static TextDirectionHeuristic textDirection() {
        return preferRtl() ? TextDirectionHeuristics.ANYRTL_LTR : TextDirectionHeuristics.FIRSTSTRONG_LTR;
    }

    // DialogCell hook (LTR app): Telegram shifts each preview left so its longest line starts at the text column.
    // For a right-to-left preview that left-anchors the block, so every row ends at a different right edge.
    // Keep RTL previews right-aligned instead, all ending at the same edge.
    public static float previewShift(Layout layout, float shift) {
        if (preferRtl() && layout.getLineCount() > 0 && layout.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT) {
            return 0;
        }
        return shift;
    }

    // DialogCell hook (LTR app): Telegram left-anchors a right-to-left name. Keep it right-aligned instead, ending
    // just before the mute/verified icons and the time, so it lines up with its right-aligned preview.
    public static boolean keepRtlNameRight(Layout nameLayout) {
        return preferRtl() && nameLayout.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT;
    }

    // DialogCell hook: previews end at the time's 15dp margin instead of running almost to the screen edge.
    public static int previewEndTrim(int cellWidth, int messageLeft, int messageWidth) {
        if (!preferRtl() || LocaleController.isRTL || cellWidth <= 0) {
            return 0;
        }
        return Math.max(0, messageLeft + messageWidth - (cellWidth - AndroidUtilities.dp(15)));
    }

    // DialogCell hook: extra room between a preview's leading thumbnail and its text (4dp) for RTL previews.
    public static int thumbExtraGap() {
        return preferRtl() ? AndroidUtilities.dp(4) : 0;
    }

    // ChatActivityEnterView hook: the message field follows the same rule while typing.
    public static void applyToInput(TextView input) {
        if (preferRtl()) {
            input.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        }
    }
}
