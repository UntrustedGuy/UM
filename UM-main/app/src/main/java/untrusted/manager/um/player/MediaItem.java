package untrusted.manager.um.player;

import android.net.Uri;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class MediaItem {
    public final Uri uri;
    public final String path;
    public final String title;
    public final String artist;
    public final String album;
    public final long duration;
    public final boolean isVideo;
    public final Map<String, String> httpHeaders;

    public MediaItem(Uri uri, String path, String title, String artist, String album, long duration, boolean isVideo) {
        this(uri, path, title, artist, album, duration, isVideo, Collections.emptyMap());
    }

    public MediaItem(Uri uri, String path, String title, String artist, String album, long duration, boolean isVideo, Map<String, String> httpHeaders) {
        this.uri = uri;
        this.path = path;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.duration = duration;
        this.isVideo = isVideo;
        this.httpHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(httpHeaders == null ? Collections.emptyMap() : httpHeaders));
    }
}
