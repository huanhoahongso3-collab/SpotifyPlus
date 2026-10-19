package com.lenerd46.spotifyplus.hooks;

import com.lenerd46.spotifyplus.SpotifyPackage;
import android.content.Context;
import android.graphics.Outline;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lenerd46.spotifyplus.SpotifyTrack;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class TestingHook extends SpotifyHook {
    private static final int TAG_OVERLAY = 0x53504C55;
    private static final int TAG_ARF = 0x53504152;
    private static final int TAG_TEXTURE = 0x53505458;

    private static volatile ExoPlayer activePlayer;
    private volatile FrameLayout activeOverlay;
    private volatile TextureView activeTextureView;

    @Override
    protected void hook() {
        try {

            XposedBridge.hookAllMethods(LayoutInflater.class, "inflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!(param.args[0] instanceof Integer)) return;

                    LayoutInflater inflater = (LayoutInflater) param.thisObject;
                    Context ctx = inflater.getContext();

                    int layoutId = (Integer) param.args[0];
                    String layoutName = "0x" + Integer.toHexString(layoutId);
                    try {
                        layoutName = ctx.getResources().getResourceName(layoutId);
                    } catch (Throwable ignored) {
                    }

                    // com.spotify.music:id/cwp_header_artwork_background

                    if (layoutName.endsWith("layout/ui_holder_content")) {
                        View root = (View) param.getResult();

                        root.post(() -> {
                            TextView title = (TextView) findByIdName(root, "com.spotify.music:id/cwp_header_title");
//                        XposedBridge.log("[SpotifyPlus] " + title.getText().toString());

                            View artwork = findByIdName(root, "com.spotify.music:id/cwp_header_artwork");
//                        artwork.setVisibility(View.INVISIBLE);

//                        View background = findByIdName(root, "com.spotify.music:id/cwp_header_artwork_background");
//
//                        if (!(background instanceof View)) {
//                            XposedBridge.log("[SpotifyPlus] cover background not found");
//                            return;
//                        }
//
//                        if (!(background.getParent() instanceof ViewGroup)) {
//                            XposedBridge.log("[SpotifyPlus] cover parent not ViewGroup");
//                            return;
//                        }

                            if (artwork != null) {
                                attachOverlay((ViewGroup) artwork.getParent(), artwork, title.getText().toString());
                            }

//                        View overlay = (View) background.getTag(TAG_OVERLAY);
//                        overlay.setBackgroundColor(0x55FF0000); // translucent red

                        });
                    }
                }
            });
        } catch (Exception e) {
            XposedBridge.log(e);
        }
    }

    private String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return "no-id";
        try {
            return v.getContext().getResources().getResourceName(id);
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private void dumpTree(View v, int depth) {
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) indent.append("  ");

        int w = v.getWidth();
        int h = v.getHeight();

        XposedBridge.log("[SpotifyPlus][tree] " + indent
                + v.getClass().getName()
                + " id=" + idName(v)
                + " wh=" + w + "x" + h
                + " vis=" + v.getVisibility());

        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;

            for (int i = 0; i < vg.getChildCount(); i++) {
                dumpTree(vg.getChildAt(i), depth + 1);
            }
        }
    }

    private View findByIdName(View root, String idName) {
        if (root == null) return null;
        int id = root.getId();
        if (id != View.NO_ID) {
            try {
                if (SpotifyPackage.matchesName(root.getContext().getResources().getResourceName(id), idName)) return root;
            } catch (Throwable ignored) {
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;

            for (int i = 0; i < vg.getChildCount(); i++) {
                View v = findByIdName(vg.getChildAt(i), idName);
                if (v != null) return v;
            }
        }
        return null;
    }

    @OptIn(markerClass = UnstableApi.class)
    private void attachOverlay(ViewGroup parent, View image, String album) {
        // avoid duplicates (carousel rebinds / reinflates)
        Object existing = image.getTag(TAG_OVERLAY);
        if (existing instanceof View) return;

        FrameLayout overlay = new FrameLayout(parent.getContext());
        overlay.setClipToPadding(false);
        overlay.setClipChildren(false);

        overlay.setClickable(false);
        overlay.setFocusable(false);
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        Context context = parent.getContext();

        AspectRatioFrameLayout arf = new AspectRatioFrameLayout(context);
        arf.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        arf.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextureView textureView = new TextureView(context);
        textureView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        activePlayer = new ExoPlayer.Builder(context).build();
        activePlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        activePlayer.setVolume(0f);
        activePlayer.setPlayWhenReady(true);

        activePlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                XposedBridge.log("[SpotifyPlus] ExoPlayer error: " + error);
            }

            @Override
            public void onVideoSizeChanged(VideoSize vs) {
                FrameLayout o = activeOverlay;
                if (o == null || vs.height <= 0 || vs.width <= 0) return;

                Object tag = o.getTag(TAG_ARF);
                if (!(tag instanceof AspectRatioFrameLayout)) return;

                float aspect = (vs.width * vs.pixelWidthHeightRatio) / (float) vs.height;
                ((AspectRatioFrameLayout) tag).setAspectRatio(aspect);
            }
        });

        if (activeTextureView != null && activeTextureView != textureView) {
            try {
                activePlayer.clearVideoSurface();
            } catch (Throwable ignored) {
            }
        }

        activeOverlay = overlay;
        activeTextureView = textureView;
        activePlayer.setVideoTextureView(textureView);

        arf.addView(textureView);
        overlay.addView(arf);

        View dim = new View(context);
        dim.setClickable(false);
        dim.setFocusable(false);
        dim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        dim.setBackgroundColor(0x22000000);
//        overlay.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay.setTag(TAG_ARF, arf);
        overlay.setTag(TAG_TEXTURE, textureView);

        overlay.setLayoutParams(copyLp(image.getLayoutParams()));

        int idx = parent.indexOfChild(image);
        parent.addView(overlay, Math.min(idx + 1, parent.getChildCount()));

        image.post(() -> {
            float radius = image.getWidth() * 0.02f;
            overlay.setClipToOutline(true);
            overlay.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            overlay.invalidateOutline();
        });

        Runnable sync = () -> {
            overlay.setTranslationX(image.getLeft() - overlay.getLeft());
            overlay.setTranslationY(image.getTop() - overlay.getTop());
        };

        image.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> sync.run());
        parent.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> sync.run());

        image.post(sync);
        image.setTag(TAG_OVERLAY, overlay);

        fetchAndPlayForActive(overlay, album);
    }

    private void fetchAndPlayForActive(FrameLayout overlay, String album) {
        OkHttpClient client = new OkHttpClient.Builder().build();

        String enc = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ? URLEncoder.encode(album, StandardCharsets.UTF_8)
                : URLEncoder.encode(album);

        Request request = new Request.Builder()
                .url("https://itunes.apple.com/search?country=us&media=music&limit=1&term=" + enc)
                .get()
                .build();

        new Thread(() -> {
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return;

                String json = response.body().string();
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                String url = root.get("results").getAsJsonArray().get(0).getAsJsonObject()
                        .get("collectionViewUrl").getAsString();

                Request appleMusicRequest = new Request.Builder().url(url).get().build();
                try (Response appleResponse = client.newCall(appleMusicRequest).execute()) {
                    if (!appleResponse.isSuccessful() || appleResponse.body() == null) return;

                    Document doc = Jsoup.parse(appleResponse.body().string());
                    Element video = doc.selectFirst("amp-ambient-video");
                    if (video == null) return;

                    String src = video.attr("src");
                    new Handler(Looper.getMainLooper()).post(() -> {
                        // stale request? ignore
                        if (overlay != activeOverlay) return;

                        if (activePlayer == null) return;
                        activePlayer.setMediaItem(MediaItem.fromUri(src));
                        activePlayer.prepare();
                        activePlayer.play();
                    });
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }).start();
    }

    private static ViewGroup.LayoutParams copyLp(ViewGroup.LayoutParams lp) {
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            return new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) lp);
        }
        return new ViewGroup.LayoutParams(lp);
    }

    private static void applyCenterCrop(TextureView tv, VideoSize vs) {
        int viewW = tv.getWidth();
        int viewH = tv.getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        int videoW = vs.width;
        int videoH = vs.height;
        if (videoW <= 0 || videoH <= 0) return;

        float par = (vs.pixelWidthHeightRatio <= 0f) ? 1f : vs.pixelWidthHeightRatio;
        float contentW = videoW * par;
        float contentH = videoH;

        // Center-crop scale (like ImageView CENTER_CROP)
        float scale = Math.max(viewW / contentW, viewH / contentH);
        float scaledW = contentW * scale;
        float scaledH = contentH * scale;

        float dx = (viewW - scaledW) / 2f;
        float dy = (viewH - scaledH) / 2f;

        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(scale, scale);
        m.postTranslate(dx, dy);

        tv.setTransform(m);
        tv.invalidate();
    }

}
