package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.glass.GlassTabView;

// The "AI" main tab takes over the Contacts slot (page 1, left of Settings): a real tab page, switched to like
// Chats and Settings. Contacts stay reachable from the compose button. Upstream tab positions stay untouched.
public class MyAiTab {

    // Page positions in MainTabsActivity (Chats=0, AI in the Contacts slot=1, Calls/Settings=2, Profile=3).
    private static final int PROFILE_POSITION = 3;
    private static boolean profileHidden;

    public static GlassTabView createTab(Context context, Theme.ResourcesProvider resourcesProvider) {
        return GlassTabView.createMainTab(context, resourcesProvider, GlassTabView.TabAnimation.ARTICLE, R.string.MyAiTab);
    }

    public static BaseFragment createPage() {
        Bundle args = new Bundle();
        args.putBoolean("hasMainTabs", true);
        return new AiSummaryActivity(args);
    }

    public static void install(MainTabsLayout tabsView, View profileTab, boolean callsTabShown) {
        // With the Calls tab shown, Settings lives only behind Profile, so Profile stays in that case.
        profileHidden = MyUiTweaks.hideProfileTab() && !callsTabShown;
        if (profileHidden) {
            tabsView.setViewVisible(profileTab, false, false);
        }
    }

    // A hidden Profile tab must not be reachable by swiping either.
    public static boolean blocksSlide(int currentPosition, boolean forward) {
        return profileHidden && (forward ? currentPosition + 1 : currentPosition - 1) == PROFILE_POSITION;
    }
}
