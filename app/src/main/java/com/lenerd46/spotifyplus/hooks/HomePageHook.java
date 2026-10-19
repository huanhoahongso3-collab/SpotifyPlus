package com.lenerd46.spotifyplus.hooks;

import com.lenerd46.spotifyplus.SpotifyPackage;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.lenerd46.spotifyplus.References;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class HomePageHook extends SpotifyHook {
    @Override
    protected void hook() {
        XposedBridge.hookAllMethods(ViewGroup.class, "addView", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!(param.thisObject instanceof ViewGroup)) return;
                if (param.args.length == 0 || !(param.args[0] instanceof View)) return;

                ViewGroup parent = (ViewGroup) param.thisObject;
                View child = (View) param.args[0];

                tryHookHomeRecycler(parent, child);
            }
        });
    }

    private void tryHookHomeRecycler(ViewGroup parent, View child) {
        if (!(child instanceof androidx.recyclerview.widget.RecyclerView)) return;

        RecyclerView rv = (RecyclerView) child;

        int parentId = parent.getId();
        int rvId = rv.getId();

        if (parentId == View.NO_ID || rvId == View.NO_ID) return;

        try {
            String parentName = parent.getResources().getResourceName(parentId);
            String rvName = rv.getResources().getResourceName(rvId);

            // We only care about the Home feed recycler
            if (!SpotifyPackage.matchesName(parentName, "id", "evo_swipe_refresh_layout")) return;
            if (!SpotifyPackage.matchesName(rvName, "id", "recycler_view")) return;

            // Attach our listener ONCE
//            attachSectionHider(rv);

        } catch (Throwable ignored) {
            // resource lookup can fail, just ignore
        }
    }
}