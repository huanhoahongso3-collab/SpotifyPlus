package com.lenerd46.spotifyplus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.robv.android.xposed.XposedBridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Turns the different cover image values Spotify hands out into a downloadable URL.
 *
 * Local playback usually gives "spotify:image:{id}", but when playback is on another device
 * (web player, Connect speakers) the metadata may carry a plain https URL, use another key,
 * or have no image at all.
 */
public final class ArtworkUrls {
    private static final String[] IMAGE_KEYS = { "image_xlarge_url", "image_large_url", "image_url", "image_small_url" };
    private static final Pattern IMAGE_ID = Pattern.compile("[0-9a-fA-F]{40}");
    private static final Map<String, String> oembedCache = new ConcurrentHashMap<>();

    private ArtworkUrls() {
    }

    /** Returns a downloadable URL for any known image form, or null if it can't be used. */
    public static String normalize(String raw) {
        if (raw == null) return null;

        String value = raw.trim();
        if (value.isEmpty()) return null;

        if (value.startsWith("spotify:image:")) {
            String id = value.substring("spotify:image:".length());
            return id.isEmpty() ? null : "https://i.scdn.co/image/" + id;
        }

        if (value.startsWith("https://") || value.startsWith("http://")) return value;
        if (IMAGE_ID.matcher(value).matches()) return "https://i.scdn.co/image/" + value;

        return null;
    }

    /** Picks the best available image from track metadata. */
    public static String fromMetadata(Map<?, ?> metadata) {
        if (metadata == null) return null;

        for (String key : IMAGE_KEYS) {
            Object value = metadata.get(key);
            if (value instanceof String) {
                String url = normalize((String) value);
                if (url != null) return url;
            }
        }

        return null;
    }

    /**
     * Looks up the cover through Spotify's public oEmbed endpoint. Does network I/O, so only call it
     * off the main thread.
     */
    public static String fromTrackUri(String trackUri) {
        if (trackUri == null || !trackUri.startsWith("spotify:")) return null;

        String cached = oembedCache.get(trackUri);
        if (cached != null) return cached;

        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://open.spotify.com/oembed?url=" + URLEncoder.encode(trackUri, "UTF-8"));
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }

            JsonObject json = JsonParser.parseString(body.toString()).getAsJsonObject();
            if (!json.has("thumbnail_url")) return null;

            String thumbnail = normalize(json.get("thumbnail_url").getAsString());
            if (thumbnail != null) oembedCache.put(trackUri, thumbnail);
            return thumbnail;
        } catch (Throwable throwable) {
            XposedBridge.log("[SpotifyPlus] Could not look up artwork for " + trackUri + ": " + throwable);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
