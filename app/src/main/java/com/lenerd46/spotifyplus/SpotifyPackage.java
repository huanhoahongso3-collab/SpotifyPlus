package com.lenerd46.spotifyplus;

import android.content.res.Resources;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Tracks the package of the app currently being hooked, so the module works on the original
 * Spotify app as well as any cloned variant (e.g. MochiCloner) under whatever package name
 * it has. Whichever app is enabled in the LSPosed scope gets hooked.
 *
 * appPackage is the manifest package (used for intents), resPackage is the resource table
 * package, which a cloner may or may not have renamed. Resource lookups try both, plus the
 * original package as a last resort.
 */
public final class SpotifyPackage {
    public static final String ORIGINAL = "com.spotify.music";
    private static final String MODULE_PACKAGE = "com.lenerd46.spotifyplus";

    public static volatile String appPackage = ORIGINAL;
    public static volatile String resPackage = ORIGINAL;
    private static volatile boolean appKnown = false;

    private SpotifyPackage() {
    }

    /** Accepts the main app of the hooked process, skipping packages that merely load into it (WebView, GMS...). */
    public static boolean shouldHook(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.packageName == null || isIgnored(lpparam.packageName)) return false;
        if (!lpparam.isFirstApplication) return false;

        appPackage = lpparam.packageName;
        appKnown = true;
        return true;
    }

    public static boolean shouldHook(XC_InitPackageResources.InitPackageResourcesParam resparam) {
        String pkg = resparam.packageName;
        if (pkg == null || isIgnored(pkg)) return false;

        // Prefer whichever package name the resource table actually uses
        for (String candidate : new String[] { pkg, ORIGINAL }) {
            if (hasSpotifyResources(resparam.res, candidate)) {
                resPackage = candidate;
                return true;
            }
        }

        if (appKnown && pkg.equals(appPackage)) {
            resPackage = pkg;
            return true;
        }

        return false;
    }

    private static boolean isIgnored(String pkg) {
        return pkg.equals("android") || pkg.equals(MODULE_PACKAGE);
    }

    private static boolean hasSpotifyResources(Resources res, String pkg) {
        try {
            return res.getIdentifier("now_playing_bar_layout", "id", pkg) != 0
                    || res.getIdentifier("dark_base_background_base", "color", pkg) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** getIdentifier that tries the resource package, the app package and the original package. */
    public static int id(Resources res, String name, String type) {
        int id = res.getIdentifier(name, type, resPackage);
        if (id == 0 && !appPackage.equals(resPackage)) id = res.getIdentifier(name, type, appPackage);
        if (id == 0 && !ORIGINAL.equals(resPackage) && !ORIGINAL.equals(appPackage)) id = res.getIdentifier(name, type, ORIGINAL);
        return id;
    }

    public static boolean isHookedResourcePackage(String pkg) {
        return pkg != null && (pkg.equals(resPackage) || pkg.equals(appPackage) || pkg.equals(ORIGINAL));
    }

    /** Checks whether a view id is the app's id/{entry}, regardless of the package it is stored under. */
    public static boolean matchesId(Resources res, int id, String entry) {
        if (res == null || id == 0 || id == -1) return false;

        try {
            return entry.equals(res.getResourceEntryName(id))
                    && "id".equals(res.getResourceTypeName(id))
                    && isHookedResourcePackage(res.getResourcePackageName(id));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Checks a full resource name like "com.spotify.music:id/image" against a type and entry. */
    public static boolean matchesName(String resourceName, String type, String entry) {
        if (resourceName == null) return false;

        int colon = resourceName.indexOf(':');
        if (colon < 0) return false;

        return isHookedResourcePackage(resourceName.substring(0, colon))
                && resourceName.substring(colon + 1).equals(type + "/" + entry);
    }

    /** Compares a resource name against an original Spotify name like "com.spotify.music:id/image", ignoring the package. */
    public static boolean matchesName(String resourceName, String expected) {
        if (expected == null) return false;

        int colon = expected.indexOf(':');
        int slash = expected.indexOf('/', colon + 1);
        if (colon < 0 || slash < 0) return expected.equals(resourceName);

        return matchesName(resourceName, expected.substring(colon + 1, slash), expected.substring(slash + 1));
    }
}
