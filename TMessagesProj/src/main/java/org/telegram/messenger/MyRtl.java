package org.telegram.messenger;

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

    // ChatActivityEnterView hook: the message field follows the same rule while typing.
    public static void applyToInput(TextView input) {
        if (preferRtl()) {
            input.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        }
    }
}
