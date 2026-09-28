package org.telegram.ui;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.glass.GlassTabView;

// Adds the "AI" item to the main bottom bar, left of Settings. It opens AiSummaryActivity instead of
// switching pages, so upstream's tab positions stay untouched.
public class MyAiTab {

    // Page positions in MainTabsActivity (Chats=0, Contacts=1, Calls/Settings=2, Profile=3).
    private static final int CONTACTS_POSITION = 1;
    private static final int PROFILE_POSITION = 3;
    private static boolean profileHidden;

    // Hidden tabs (Contacts always, Profile optionally) must not be reachable by swiping either.
    public static boolean blocksSlide(int currentPosition, boolean forward) {
        int target = forward ? currentPosition + 1 : currentPosition - 1;
        return target == CONTACTS_POSITION || profileHidden && target == PROFILE_POSITION;
    }

    public static void install(BaseFragment parent, Context context, Theme.ResourcesProvider resourcesProvider, MainTabsLayout tabsView, View settingsTab, View contactsTab, View profileTab, boolean callsTabShown, Runnable restoreSelection) {
        tabsView.setViewVisible(contactsTab, false, false);
        // With the Calls tab shown, Settings lives only behind Profile, so Profile stays in that case.
        profileHidden = MyUiTweaks.hideProfileTab() && !callsTabShown;
        if (profileHidden) {
            tabsView.setViewVisible(profileTab, false, false);
        }
        GlassTabView tab = GlassTabView.createMainTab(context, resourcesProvider, GlassTabView.TabAnimation.ARTICLE, R.string.MyAiTab);
        tab.setOnClickListener(v -> {
            parent.presentFragment(new AiSummaryActivity());
            // The bar may have highlighted this tab on touch; put the highlight back on the current page.
            AndroidUtilities.runOnUIThread(restoreSelection, 50);
        });
        tabsView.addView(tab, tabsView.indexOfChild(settingsTab));
        tabsView.setViewVisible(tab, true, false);
    }
}
