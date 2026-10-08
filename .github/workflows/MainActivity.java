package com.sur.music;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.database.Cursor;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity {
    final int BG = Color.rgb(9, 9, 20);
    final int CARD = Color.rgb(24, 20, 38);
    final int PURPLE = Color.rgb(187, 134, 252);
    final int PINK = Color.rgb(255, 105, 180);
    final int WHITE = Color.WHITE;
    final int GRAY = Color.rgb(170, 165, 180);
    final int PLAYING_BLUE = Color.rgb(25, 105, 210);
    final int PLAYING_BLUE_TEXT = Color.rgb(110, 190, 255);

    final int NOW_RED = Color.rgb(239, 51, 78);
    final int NOW_GRAY = Color.rgb(145, 145, 145);

    LinearLayout root, content, mini;
    TextView miniTitle, miniArtist;
    Button miniPlay;
    Button fullPlayPause;
    CountDownTimer sleepTimer;
    TextView timerRemaining;
    EditText search;
    MediaPlayer player;
    SeekBar seek;
    TextView fullTitle, fullArtist, timeNow, timeEnd;
    ImageView albumArt;
    int current = -1;
    boolean fullPlayer = false;
    boolean updatingSeek = false;
    Handler handler = new Handler();

    MediaSession mediaSession;
    NotificationManager notificationManager;
    static final String CHANNEL_ID = "sur_music_playback";
    static final String ACTION_PLAY_PAUSE = "com.sur.music.PLAY_PAUSE";
    static final String ACTION_PREVIOUS = "com.sur.music.PREVIOUS";
    static final String ACTION_NEXT = "com.sur.music.NEXT";

    ArrayList<String> titles = new ArrayList<>();
    ArrayList<String> artists = new ArrayList<>();
    ArrayList<Uri> uris = new ArrayList<>();
    ArrayList<Long> albumIds = new ArrayList<>();
    Set<String> favorites = new HashSet<>();

    boolean isDarkTheme() {
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    int pageBackground() {
        return isDarkTheme() ? BG : Color.WHITE;
    }

    int primaryText() {
        return isDarkTheme() ? Color.WHITE : Color.rgb(25, 25, 25);
    }

    int secondaryText() {
        return isDarkTheme() ? GRAY : Color.rgb(120, 120, 120);
    }

    int listCardBackground() {
        return isDarkTheme() ? Color.rgb(17, 16, 29) : Color.rgb(247, 247, 249);
    }

    int listArtBackground() {
        return isDarkTheme() ? Color.rgb(35, 28, 50) : Color.rgb(240, 232, 255);
    }

    void applyListTheme() {
        int bgColor = pageBackground();
        getWindow().setStatusBarColor(bgColor);
        getWindow().setNavigationBarColor(bgColor);

        if (Build.VERSION.SDK_INT >= 23) {
            int flags = 0;
            if (!isDarkTheme()) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26)
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    int dp(float v) {
        return (int)(v * getResources().getDisplayMetrics().density + .5f);
    }

    TextView tv(String text, float size, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    GradientDrawable bg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        applyListTheme();
        buildHome();
        setupMediaSession();
        createNotificationChannel();
        requestNotificationPermission();
        handleNotificationIntent(getIntent());
    }

    void handleNotificationIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (ACTION_PLAY_PAUSE.equals(action)) {
            toggle();
        } else if (ACTION_PREVIOUS.equals(action)) {
            previous();
        } else if (ACTION_NEXT.equals(action)) {
            next();
        }
        if (action != null) intent.setAction(null);
    }

    void setupMediaSession() {
        mediaSession = new MediaSession(this, "SurMusic");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() {
                runOnUiThread(() -> toggle());
            }

            @Override public void onPause() {
                runOnUiThread(() -> {
                    if (player != null && player.isPlaying()) toggle();
                });
            }

            @Override public void onSkipToNext() {
                runOnUiThread(() -> next());
            }

            @Override public void onSkipToPrevious() {
                runOnUiThread(() -> previous());
            }
        });
        mediaSession.setActive(true);
    }

    void createNotificationChannel() {
        notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Sur Music Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Music playback controls");
            notificationManager.createNotificationChannel(channel);
        }
    }

    void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 60);
        } else {
            requestMusicPermission();
        }
    }

    PendingIntent notificationAction(String action) {
        Intent intent = new Intent(this, NotificationReceiver.class);
        intent.setAction(action);
        return PendingIntent.getBroadcast(
                this,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT |
                        (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );
    }

    void updateMediaSession() {
        if (mediaSession == null) return;

        boolean playing = player != null && player.isPlaying();

        if (current >= 0 && current < titles.size()) {
            MediaMetadata metadata = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, titles.get(current))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artists.get(current))
                    .putLong(
                            MediaMetadata.METADATA_KEY_DURATION,
                            player != null ? Math.max(0, player.getDuration()) : 0)
                    .build();
            mediaSession.setMetadata(metadata);
        }

        PlaybackState.Builder state = new PlaybackState.Builder()
                .setActions(
                        PlaybackState.ACTION_PLAY |
                        PlaybackState.ACTION_PAUSE |
                        PlaybackState.ACTION_PLAY_PAUSE |
                        PlaybackState.ACTION_SKIP_TO_NEXT |
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                        playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        player != null ? player.getCurrentPosition() : 0,
                        playing ? 1f : 0f
                );

        mediaSession.setPlaybackState(state.build());
        showPlaybackNotification();
    }

    void showPlaybackNotification() {
        if (notificationManager == null || current < 0 || current >= titles.size())
            return;

        boolean playing = player != null && player.isPlaying();

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP |
                Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 100, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT |
                        (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setSmallIcon(com.sur.music.R.drawable.ic_sur_icon)
                .setContentTitle(titles.get(current))
                .setContentText(artists.get(current))
                .setContentIntent(contentIntent)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        null, "⏮", notificationAction(ACTION_PREVIOUS)).build())
                .addAction(new Notification.Action.Builder(
                        null, playing ? "Ⅱ" : "▶",
                        notificationAction(ACTION_PLAY_PAUSE)).build())
                .addAction(new Notification.Action.Builder(
                        null, "⏭", notificationAction(ACTION_NEXT)).build());

        if (Build.VERSION.SDK_INT >= 21) {
            builder.setStyle(new Notification.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }

        notificationManager.notify(1001, builder.build());
    }

    void hidePlaybackNotification() {
        if (notificationManager != null)
            notificationManager.cancel(1001);
    }

    public static class NotificationReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Intent activityIntent = new Intent(context, MainActivity.class);
            activityIntent.setAction(intent.getAction());
            activityIntent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            );
            context.startActivity(activityIntent);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationIntent(intent);
    }

    void requestMusicPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO}, 50);
            } else loadSongs();
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 50);
            } else loadSongs();
        }
    }

    void buildHome() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        mini = createMiniPlayer();
        root.addView(mini, new LinearLayout.LayoutParams(-1, dp(72)));

        root.addView(createBottomNav(), new LinearLayout.LayoutParams(-1, dp(62)));
        setContentView(root);

        showHome();
    }

    TextView heading(String s) {
        TextView h = tv(s, 21, WHITE);
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        h.setPadding(0, dp(16), 0, dp(9));
        return h;
    }

    void showHome() {
        fullPlayer = false;

        if (mini != null) mini.setVisibility(View.VISIBLE);
        // Home screen follows the reference design: no bottom navigation bar.
        if (root != null && root.getChildCount() >= 3) {
            root.getChildAt(2).setVisibility(View.GONE);
        }

        applyListTheme();
        content.setBackgroundColor(pageBackground());
        content.setPadding(dp(18), dp(8), dp(18), dp(8));

        search = null;
        content.removeAllViews();

        // Top bar: Search + menu
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        Space left = new Space(this);
        top.addView(left, new LinearLayout.LayoutParams(0, dp(52), 1));

        Button searchBtn = new Button(this);
        searchBtn.setText("⌕");
        searchBtn.setTextSize(28);
        searchBtn.setTextColor(isDarkTheme() ? Color.WHITE : Color.rgb(195, 65, 90));
        searchBtn.setBackgroundColor(Color.TRANSPARENT);
        searchBtn.setPadding(0, 0, 0, 0);
        searchBtn.setOnClickListener(v -> showSearch());
        top.addView(searchBtn, new LinearLayout.LayoutParams(dp(52), dp(52)));

        Button menuBtn = new Button(this);
        menuBtn.setText("⋮");
        menuBtn.setTextSize(27);
        menuBtn.setTextColor(isDarkTheme() ? Color.WHITE : Color.rgb(195, 65, 90));
        menuBtn.setBackgroundColor(Color.TRANSPARENT);
        menuBtn.setPadding(0, 0, 0, 0);
        menuBtn.setOnClickListener(v -> showHomeMenu(menuBtn));
        top.addView(menuBtn, new LinearLayout.LayoutParams(dp(42), dp(52)));

        content.addView(top, new LinearLayout.LayoutParams(-1, dp(62)));

        // Tabs: Songs / Artists / Albums / Playlists
        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER_VERTICAL);

        TextView songs = homeTab("Songs", true);
        TextView artistsTab = homeTab("Artists", false);
        TextView albumsTab = homeTab("Albums", false);
        TextView playlistsTab = homeTab("Playlists", false);

        songs.setOnClickListener(v -> showHome());
        artistsTab.setOnClickListener(v -> showArtistsPage());
        albumsTab.setOnClickListener(v -> showAlbumsPage());
        playlistsTab.setOnClickListener(v -> showPlaylistsPage());

        tabs.addView(songs, new LinearLayout.LayoutParams(0, dp(58), 1));
        tabs.addView(artistsTab, new LinearLayout.LayoutParams(0, dp(58), 1));
        tabs.addView(albumsTab, new LinearLayout.LayoutParams(0, dp(58), 1));
        tabs.addView(playlistsTab, new LinearLayout.LayoutParams(0, dp(58), 1));
        content.addView(tabs);

        // Selected-tab underline
        View underline = new View(this);
        underline.setBackgroundColor(isDarkTheme() ? PURPLE : Color.rgb(195, 65, 90));
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(dp(70), dp(2));
        ulp.setMargins(dp(1), 0, 0, dp(10));
        content.addView(underline, ulp);

        // Songs list, similar to the reference music player.
        for (int i = 0; i < titles.size(); i++) addHomeSongRow(i);

        if (titles.size() == 0) {
            TextView empty = tv("No music found on this device.", 15, secondaryText());
            empty.setPadding(dp(2), dp(25), 0, dp(30));
            content.addView(empty);
        }
    }

    TextView homeTab(String text, boolean selected) {
        TextView t = tv(text, 16,
                selected
                        ? (isDarkTheme() ? PURPLE : Color.rgb(195, 65, 90))
                        : primaryText());
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    void addHomeSongRow(final int i) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(8), dp(7), dp(4), dp(7));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(bg(
                isDarkTheme()
                        ? (i == current ? PLAYING_BLUE : Color.TRANSPARENT)
                        : (i == current ? Color.rgb(238, 232, 255) : Color.TRANSPARENT),
                12));
        row.setOnClickListener(v -> play(i));

        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(0, 0, dp(6), 0);

        TextView title = tv(
                (i == current ? "▶  " : "") + titles.get(i),
                16,
                i == current
                        ? (isDarkTheme() ? PLAYING_BLUE_TEXT : Color.rgb(25, 105, 210))
                        : primaryText());
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView meta = tv(
                artists.get(i) + "–Unknown album",
                12,
                i == current
                        ? (isDarkTheme() ? PLAYING_BLUE_TEXT : Color.rgb(25, 105, 210))
                        : secondaryText());
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);

        info.addView(title, new LinearLayout.LayoutParams(-1, dp(31)));
        info.addView(meta, new LinearLayout.LayoutParams(-1, dp(23)));

        line.addView(info, new LinearLayout.LayoutParams(0, dp(58), 1));

        Button more = new Button(this);
        more.setText("⋮");
        more.setTextSize(22);
        more.setTextColor(isDarkTheme() ? Color.LTGRAY : Color.rgb(205, 205, 205));
        more.setBackgroundColor(Color.TRANSPARENT);
        more.setPadding(0, 0, 0, 0);
        more.setOnClickListener(v -> showSongMenu(i));
        line.addView(more, new LinearLayout.LayoutParams(dp(40), dp(58)));

        row.addView(line, new LinearLayout.LayoutParams(-1, dp(58)));

        View divider = new View(this);
        divider.setBackgroundColor(isDarkTheme() ? Color.rgb(45, 43, 55) : Color.rgb(245, 245, 245));
        row.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        content.addView(row, new LinearLayout.LayoutParams(-1, dp(66)));
    }

    void showSongMenu(final int i) {
        final String key = uris.get(i).toString();
        String favoriteText = favorites.contains(key) ? "Remove from Favorites" : "Add to Favorites";
        new android.app.AlertDialog.Builder(this)
                .setItems(new String[]{"Play", favoriteText}, (dialog, which) -> {
                    if (which == 0) {
                        play(i);
                    } else {
                        if (favorites.contains(key)) favorites.remove(key);
                        else favorites.add(key);
                        showHome();
                    }
                }).show();
    }

    void showHomeMenu(View anchor) {
        final String[] items = {"Search", "Library", "Favorites"};
        new android.app.AlertDialog.Builder(this)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) showSearch();
                    else if (which == 1) showLibrary();
                    else showFavorites();
                }).show();
    }

    void showArtistsPage() {
        fullPlayer = false;
        if (mini != null) mini.setVisibility(View.VISIBLE);
        if (root != null && root.getChildCount() >= 3) root.getChildAt(2).setVisibility(View.GONE);
        applyListTheme();
        content.setBackgroundColor(pageBackground());
        content.setPadding(dp(18), dp(8), dp(18), dp(8));
        content.removeAllViews();

        TextView h = tv("Artists", 25, primaryText());
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(h, new LinearLayout.LayoutParams(-1, dp(58)));

        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (String artist : artists) {
            if (artist == null || seen.contains(artist)) continue;
            seen.add(artist);
            TextView a = tv(artist, 17, primaryText());
            a.setPadding(dp(8), 0, 0, 0);
            content.addView(a, new LinearLayout.LayoutParams(-1, dp(55)));
        }
    }

    void showAlbumsPage() {
        fullPlayer = false;
        if (mini != null) mini.setVisibility(View.VISIBLE);
        if (root != null && root.getChildCount() >= 3) root.getChildAt(2).setVisibility(View.GONE);
        applyListTheme();
        content.setBackgroundColor(pageBackground());
        content.setPadding(dp(18), dp(8), dp(18), dp(8));
        content.removeAllViews();

        TextView h = tv("Albums", 25, primaryText());
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(h, new LinearLayout.LayoutParams(-1, dp(58)));
        TextView e = tv("Album information is not available for some songs.", 14, secondaryText());
        content.addView(e, new LinearLayout.LayoutParams(-1, dp(45)));

        for (int i = 0; i < titles.size(); i++) {
            addHomeSongRow(i);
        }
    }

    void showPlaylistsPage() {
        fullPlayer = false;
        if (mini != null) mini.setVisibility(View.VISIBLE);
        if (root != null && root.getChildCount() >= 3) root.getChildAt(2).setVisibility(View.GONE);
        applyListTheme();
        content.setBackgroundColor(pageBackground());
        content.setPadding(dp(18), dp(8), dp(18), dp(8));
        content.removeAllViews();

        TextView h = tv("Playlists", 25, primaryText());
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(h, new LinearLayout.LayoutParams(-1, dp(58)));

        TextView f = tv("♥  Favorites", 17, primaryText());
        f.setPadding(dp(8), 0, 0, 0);
        f.setOnClickListener(v -> showFavorites());
        content.addView(f, new LinearLayout.LayoutParams(-1, dp(58)));
    }

    View space(int w) {
        Space s = new Space(this);
        s.setLayoutParams(new LinearLayout.LayoutParams(w, 1));
        return s;
    }

    LinearLayout card(final int i, boolean large) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(8), dp(8), dp(8), dp(8));
        c.setBackground(bg(isDarkTheme() ? CARD : Color.rgb(247, 247, 249), 16));

        TextView art = tv("♫", large ? 45 : 40, PURPLE);
        art.setGravity(Gravity.CENTER);
        art.setBackground(bg(isDarkTheme() ? Color.rgb(35, 25, 55) : Color.rgb(240, 232, 255), 13));
        c.addView(art, new LinearLayout.LayoutParams(-1, dp(105)));

        TextView title = tv(titles.get(i), 13, primaryText());
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        c.addView(title, new LinearLayout.LayoutParams(-1, dp(42)));

        TextView artist = tv(artists.get(i), 12, GRAY);
        artist.setSingleLine(true);
        artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        c.addView(artist, new LinearLayout.LayoutParams(-1, dp(22)));

        c.setOnClickListener(v -> play(i));
        return c;
    }

    LinearLayout tile(String title, String sub, int accent) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(12), dp(9), dp(8), dp(8));
        t.setBackground(bg(isDarkTheme() ? Color.rgb(27, 23, 43) : Color.rgb(247, 247, 249), 15));

        TextView a = tv(title, 16, primaryText());
        a.setTypeface(null, Typeface.BOLD);

        TextView b = tv(sub, 12, GRAY);

        t.addView(a);
        t.addView(b);

        TextView line = tv("●", 22, accent);
        t.addView(line);

        return t;
    }

    void addSongRow(final int i) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(3), dp(4), dp(3));
        row.setBackground(bg(
                isDarkTheme()
                        ? (i == current ? PLAYING_BLUE : Color.rgb(17, 16, 29))
                        : (i == current ? Color.rgb(238, 232, 255) : Color.rgb(247, 247, 249)),
                12));

        TextView art = tv("♫", 23, isDarkTheme() ? PURPLE : Color.rgb(155, 93, 229));
        art.setGravity(Gravity.CENTER);
        art.setBackground(bg(listArtBackground(), 10));

        row.addView(art,
                new LinearLayout.LayoutParams(dp(55), dp(64)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(10), 0, dp(4), 0);

        TextView a = tv(
                titles.get(i),
                14,
                i == current
                        ? (isDarkTheme() ? PLAYING_BLUE_TEXT : Color.rgb(25, 105, 210))
                        : primaryText());

        a.setMaxLines(2);
        a.setHorizontallyScrolling(false);
        a.setEllipsize(android.text.TextUtils.TruncateAt.END);
        a.setTypeface(null, Typeface.BOLD);
        a.setGravity(Gravity.CENTER_VERTICAL);

        TextView b = tv(
                artists.get(i),
                12,
                i == current
                        ? (isDarkTheme() ? PLAYING_BLUE_TEXT : Color.rgb(25, 105, 210))
                        : secondaryText());

        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);

        info.addView(a, new LinearLayout.LayoutParams(-1, dp(44)));
        info.addView(b, new LinearLayout.LayoutParams(-1, dp(22)));

        row.addView(info,
                new LinearLayout.LayoutParams(0, dp(68), 1));

        Button fav = new Button(this);

        fav.setText(
                favorites.contains(uris.get(i).toString())
                        ? "♥" : "♡");

        fav.setTextColor(Color.rgb(239, 51, 78));
        fav.setTextSize(22);
        fav.setBackgroundColor(Color.TRANSPARENT);

        fav.setOnClickListener(v -> {
            String key = uris.get(i).toString();

            if (favorites.contains(key)) {
                favorites.remove(key);
                fav.setText("♡");
            } else {
                favorites.add(key);
                fav.setText("♥");
            }
        });

        row.addView(fav,
                new LinearLayout.LayoutParams(dp(55), dp(64)));

        row.setOnClickListener(v -> play(i));

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(-1, dp(78));

        lp.setMargins(0, dp(2), 0, dp(2));

        content.addView(row, lp);
    }

    void refreshCurrentSongHighlight() {
        if (fullPlayer || content == null) return;

        if (search != null && search.getParent() == content) {
            filter(search.getText().toString());
            return;
        }

        showHome();
    }

    LinearLayout createMiniPlayer() {
        LinearLayout m = new LinearLayout(this);
        m.setGravity(Gravity.CENTER_VERTICAL);
        m.setPadding(dp(9), dp(5), dp(8), dp(5));
        m.setBackgroundColor(isDarkTheme() ? Color.rgb(20, 18, 30) : Color.WHITE);
        m.setClickable(true);
        m.setOnClickListener(v -> showFullPlayer());

        TextView art = tv("♫", 28, isDarkTheme() ? PURPLE : Color.WHITE);
        art.setGravity(Gravity.CENTER);
        art.setBackground(bg(isDarkTheme() ? Color.rgb(35, 28, 50) : Color.rgb(232, 232, 232), 12));
        art.setOnClickListener(v -> showFullPlayer());
        m.addView(art, new LinearLayout.LayoutParams(dp(76), dp(60)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(10), 0, dp(4), 0);
        info.setGravity(Gravity.CENTER_VERTICAL);

        miniTitle = tv("Nothing playing", 14, primaryText());
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        miniArtist = tv("Unknown artist", 11, secondaryText());
        miniArtist.setSingleLine(true);
        miniArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(miniTitle, new LinearLayout.LayoutParams(-1, dp(28)));
        info.addView(miniArtist, new LinearLayout.LayoutParams(-1, dp(22)));
        info.setOnClickListener(v -> showFullPlayer());
        m.addView(info, new LinearLayout.LayoutParams(0, dp(60), 1));

        miniPlay = new Button(this);
        miniPlay.setText("▶");
        miniPlay.setTextSize(22);
        miniPlay.setTextColor(primaryText());
        miniPlay.setBackgroundColor(Color.TRANSPARENT);
        miniPlay.setPadding(0, 0, 0, 0);
        miniPlay.setOnClickListener(v -> toggle());
        m.addView(miniPlay, new LinearLayout.LayoutParams(dp(55), dp(60)));

        Button queue = new Button(this);
        queue.setText("☷");
        queue.setTextSize(25);
        queue.setTextColor(primaryText());
        queue.setBackgroundColor(Color.TRANSPARENT);
        queue.setPadding(0, 0, 0, 0);
        queue.setOnClickListener(v -> showPlaylistsPage());
        m.addView(queue, new LinearLayout.LayoutParams(dp(55), dp(60)));

        return m;
    }

    LinearLayout createBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setBackgroundColor(isDarkTheme() ? Color.rgb(13, 12, 23) : Color.WHITE);

        Button home = navButton("⌂\nHome");
        Button searchB = navButton("⌕\nSearch");
        Button library = navButton("♫\nLibrary");

        home.setOnClickListener(v -> showHome());
        searchB.setOnClickListener(v -> showSearch());
        library.setOnClickListener(v -> showLibrary());

        nav.addView(home, new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(searchB, new LinearLayout.LayoutParams(0, -1, 1));
        nav.addView(library, new LinearLayout.LayoutParams(0, -1, 1));

        return nav;
    }

    Button navButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(11);
        b.setTextColor(GRAY);
        b.setBackgroundColor(Color.TRANSPARENT);
        return b;
    }

    void showSearch() {
        if (mini != null) mini.setVisibility(View.VISIBLE);
        if (root != null && root.getChildCount() >= 3)
            root.getChildAt(2).setVisibility(View.VISIBLE);

        fullPlayer = false;
        applyListTheme();
        content.setBackgroundColor(pageBackground());
        content.setPadding(dp(18), dp(8), dp(18), 0);

        content.removeAllViews();

        TextView h = tv("Search", 27, primaryText());
        h.setTypeface(null, Typeface.BOLD);

        content.addView(h,
                new LinearLayout.LayoutParams(-1, dp(55)));

        search = new EditText(this);
        search.setHint("Search songs, artists...");
        search.setHintTextColor(GRAY);
        search.setTextColor(primaryText());
        search.setSingleLine(true);
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(bg(isDarkTheme() ? CARD : Color.rgb(247, 247, 249), 18));

        content.addView(search,
                new LinearLayout.LayoutParams(-1, dp(55)));

        TextView hint = tv(
                "Type to search your music",
                13, GRAY);

        content.addView(hint);

        search.addTextChangedListener(
                new android.text.TextWatcher() {
                    public void beforeTextChanged(
                            CharSequence s, int a, int c, int d) {}

                    public void onTextChanged(
                            CharSequence s, int a, int b, int c) {
                        filter(s.toString());
                    }

                    public void afterTextChanged(
                            android.text.Editable e) {}
                });
    }

    void filter(String q) {
        while (content.getChildCount() > 3)
            content.removeViewAt(content.getChildCount() - 1);

        q = q.toLowerCase();

        for (int i = 0; i < titles.size(); i++) {
            if (titles.get(i).toLowerCase().contains(q)
                    || artists.get(i).toLowerCase().contains(q)) {
                addSongRow(i);
            }
        }
    }

    void showLibrary() {
        if (mini != null) mini.setVisibility(View.VISIBLE);
        if (root != null && root.getChildCount() >= 3)
            root.getChildAt(2).setVisibility(View.VISIBLE);

        fullPlayer = false;
        applyListTheme();
        content.setBackgroundColor(pageBackground());
        content.setPadding(dp(18), dp(8), dp(18), 0);

        search = null;
        content.removeAllViews();

        TextView h = tv("Your Library", 27, primaryText());
        h.setTypeface(null, Typeface.BOLD);

        content.addView(h,
                new LinearLayout.LayoutParams(-1, dp(60)));

        Button all = new Button(this);
        all.setText("♫  All Songs");
        all.setTextColor(primaryText());
        all.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        all.setBackground(bg(listCardBackground(), 14));
        all.setOnClickListener(v -> showHome());

        content.addView(all,
                new LinearLayout.LayoutParams(-1, dp(60)));

        Button fav = new Button(this);
        fav.setText("♥  Favorites");
        fav.setTextColor(primaryText());
        fav.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        fav.setBackground(bg(listCardBackground(), 14));
        fav.setOnClickListener(v -> showFavorites());

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(-1, dp(60));

        lp.setMargins(0, dp(8), 0, 0);
        content.addView(fav, lp);
    }

    void showFavorites() {
        if (mini != null) mini.setVisibility(View.VISIBLE);
        if (root != null && root.getChildCount() >= 3)
            root.getChildAt(2).setVisibility(View.VISIBLE);

        fullPlayer = false;
        applyListTheme();
        content.setBackgroundColor(pageBackground());
        content.setPadding(dp(18), dp(8), dp(18), 0);

        search = null;
        content.removeAllViews();

        TextView h = tv("Favorites", 27, primaryText());
        h.setTypeface(null, Typeface.BOLD);

        content.addView(h,
                new LinearLayout.LayoutParams(-1, dp(60)));

        for (int i = 0; i < titles.size(); i++) {
            if (favorites.contains(uris.get(i).toString()))
                addSongRow(i);
        }

        if (favorites.isEmpty()) {
            TextView e = tv(
                    "No favorite songs yet.\nTap ♡ beside a song to add it.",
                    15, GRAY);

            e.setPadding(0, dp(25), 0, 0);
            content.addView(e);
        }
    }

    void loadSongs() {
        titles.clear();
        artists.clear();
        uris.clear();
        albumIds.clear();

        String[] p = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM_ID
        };

        try {
            Cursor c = getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    p,
                    MediaStore.Audio.Media.IS_MUSIC + " != 0",
                    null,
                    MediaStore.Audio.Media.TITLE + " ASC"
            );

            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);

                    titles.add(
                            c.getString(1) == null
                                    ? "Unknown Song"
                                    : c.getString(1));

                    artists.add(
                            c.getString(2) == null
                                    ? "Unknown Artist"
                                    : c.getString(2));

                    albumIds.add(c.getLong(3));

                    uris.add(ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            id));
                }

                c.close();
            }
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Could not read music",
                    Toast.LENGTH_LONG).show();
        }

        showHome();
    }

    void play(int i) {
        if (i < 0 || i >= uris.size()) return;

        try {
            if (player != null) player.release();

            player = MediaPlayer.create(this, uris.get(i));

            if (player == null) throw new Exception();

            current = i;
            player.start();

            miniTitle.setText(titles.get(i));
            miniArtist.setText(artists.get(i));

            updatePlayButtons();
            updateMediaSession();
            refreshCurrentSongHighlight();

            player.setOnCompletionListener(mp -> next());

            if (fullPlayer) updateFullPlayer();

            startSeekUpdates();

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Cannot play this song",
                    Toast.LENGTH_SHORT).show();
        }
    }

    void toggle() {
        if (player == null) {
            if (current >= 0) play(current);
            return;
        }

        if (player.isPlaying()) {
            player.pause();
        } else {
            player.start();
        }

        updatePlayButtons();
        updateMediaSession();
    }

    void previous() {
        if (titles.isEmpty()) return;

        int n = current <= 0
                ? titles.size() - 1
                : current - 1;

        play(n);
    }

    void next() {
        if (titles.isEmpty()) return;

        int n = current < 0 ||
                current >= titles.size() - 1
                ? 0
                : current + 1;

        play(n);
    }

    // =========================================================
    // NEW NOW PLAYING SCREEN
    // =========================================================

    void showFullPlayer() {
        if (current < 0) {
            Toast.makeText(
                    this,
                    "Play a song first",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        fullPlayer = true;

        // Hide mini player and bottom navigation
        if (mini != null) mini.setVisibility(View.GONE);

        if (root != null && root.getChildCount() >= 3) {
            root.getChildAt(2).setVisibility(View.GONE);
        }

        // White Now Playing screen
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);

        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR |
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            );
        }

        content.removeAllViews();
        content.setPadding(0, 0, 0, 0);
        content.setBackgroundColor(Color.WHITE);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setBackgroundColor(Color.WHITE);
        page.setPadding(dp(18), dp(5), dp(18), 0);

        // Top bar
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(38);
        back.setTextColor(Color.BLACK);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setPadding(0, 0, 0, 0);
        back.setOnClickListener(v -> showHome());

        top.addView(
                back,
                new LinearLayout.LayoutParams(dp(52), dp(55)));

        TextView label = tv("NOW PLAYING", 13, Color.BLACK);
        label.setGravity(Gravity.CENTER);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        top.addView(
                label,
                new LinearLayout.LayoutParams(0, dp(55), 1));

        Button topMore = new Button(this);
        topMore.setText("⋯");
        topMore.setTextSize(25);
        topMore.setTextColor(Color.BLACK);
        topMore.setBackgroundColor(Color.TRANSPARENT);
        topMore.setContentDescription("More options");
        topMore.setOnClickListener(v -> showNowPlayingMoreMenu());

        top.addView(
                topMore,
                new LinearLayout.LayoutParams(dp(52), dp(55)));

        page.addView(
                top,
                new LinearLayout.LayoutParams(-1, dp(58)));

        // Album art
        albumArt = new ImageView(this);
        albumArt.setImageResource(android.R.drawable.ic_media_play);
        albumArt.setColorFilter(Color.WHITE);
        albumArt.setScaleType(ImageView.ScaleType.CENTER);
        albumArt.setBackground(
                bg(Color.rgb(235, 235, 235), 24));

        LinearLayout.LayoutParams artLp =
                new LinearLayout.LayoutParams(
                        -1,
                        dp(310));

        artLp.setMargins(
                dp(55),
                dp(18),
                dp(55),
                dp(12));

        page.addView(albumArt, artLp);

        // Page dots
        LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);

        TextView dot1 = tv("●", 11, Color.BLACK);
        TextView dot2 = tv("●", 11, Color.LTGRAY);

        dot1.setGravity(Gravity.CENTER);
        dot2.setGravity(Gravity.CENTER);

        dots.addView(dot1,
                new LinearLayout.LayoutParams(dp(18), dp(22)));

        dots.addView(dot2,
                new LinearLayout.LayoutParams(dp(18), dp(22)));

        page.addView(
                dots,
                new LinearLayout.LayoutParams(-1, dp(24)));

        // Seek bar
        seek = new SeekBar(this);
        seek.setMax(1000);

        if (Build.VERSION.SDK_INT >= 21) {
            seek.setProgressTintList(
                    android.content.res.ColorStateList.valueOf(NOW_RED));

            seek.setThumbTintList(
                    android.content.res.ColorStateList.valueOf(NOW_RED));

            seek.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            Color.rgb(215, 215, 215)));
        }

        seek.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(
                            SeekBar s,
                            int p,
                            boolean fromUser) {

                        if (fromUser &&
                                player != null &&
                                player.getDuration() > 0) {

                            player.seekTo(
                                    (int)((long)p *
                                            player.getDuration() / 1000));
                        }
                    }

                    public void onStartTrackingTouch(SeekBar s) {
                        updatingSeek = true;
                    }

                    public void onStopTrackingTouch(SeekBar s) {
                        updatingSeek = false;
                    }
                });

        page.addView(
                seek,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(38)));

        // Time labels
        LinearLayout times = new LinearLayout(this);

        timeNow = tv("0:00", 12, NOW_RED);
        timeEnd = tv("0:00", 12, NOW_GRAY);

        times.addView(
                timeNow,
                new LinearLayout.LayoutParams(
                        0, dp(25), 1));

        timeEnd.setGravity(Gravity.RIGHT);

        times.addView(
                timeEnd,
                new LinearLayout.LayoutParams(
                        0, dp(25), 1));

        page.addView(
                times,
                new LinearLayout.LayoutParams(-1, dp(25)));

        // Song title
        fullTitle = tv(
                titles.get(current),
                23,
                Color.BLACK);

        fullTitle.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD);

        fullTitle.setGravity(Gravity.CENTER);
        fullTitle.setMaxLines(1);
        fullTitle.setEllipsize(
                android.text.TextUtils.TruncateAt.END);

        page.addView(
                fullTitle,
                new LinearLayout.LayoutParams(-1, dp(48)));

        // Artist
        fullArtist = tv(
                artists.get(current),
                15,
                Color.rgb(155, 155, 155));

        fullArtist.setGravity(Gravity.CENTER);
        fullArtist.setSingleLine(true);
        fullArtist.setEllipsize(
                android.text.TextUtils.TruncateAt.END);

        page.addView(
                fullArtist,
                new LinearLayout.LayoutParams(-1, dp(27)));

        // Sleep timer countdown - keep it visible on the Now Playing screen
        timerRemaining = tv("Timer বন্ধ", 13, NOW_GRAY);
        timerRemaining.setGravity(Gravity.CENTER);
        page.addView(
                timerRemaining,
                new LinearLayout.LayoutParams(-1, dp(30)));

        // Main controls
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(6), 0, dp(3));

        Button prev = control("⏮");
        prev.setTextColor(NOW_RED);

        fullPlayPause = createRoundPlayButton();
        fullPlayPause.setBackground(
                bg(NOW_RED, 100));

        Button nextB = control("⏭");
        nextB.setTextColor(NOW_RED);

        prev.setOnClickListener(v -> previous());
        fullPlayPause.setOnClickListener(v -> toggle());
        nextB.setOnClickListener(v -> next());

        controls.addView(
                prev,
                new LinearLayout.LayoutParams(dp(76), dp(76)));

        controls.addView(
                fullPlayPause,
                new LinearLayout.LayoutParams(dp(88), dp(88)));

        controls.addView(
                nextB,
                new LinearLayout.LayoutParams(dp(76), dp(76)));

        page.addView(
                controls,
                new LinearLayout.LayoutParams(-1, dp(94)));

        // Bottom actions
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);

        Button playlist = bottomAction("☷");
        Button favorite = bottomAction(
                favorites.contains(uris.get(current).toString())
                        ? "♥" : "♡");
        Button repeat = bottomAction("↻");
        Button sleep = bottomAction("⏰");

        sleep.setContentDescription("Sleep Timer");
        sleep.setOnClickListener(v -> {
            showSleepTimer();
        });

        favorite.setOnClickListener(v -> {
            String key = uris.get(current).toString();

            if (favorites.contains(key)) {
                favorites.remove(key);
                favorite.setText("♡");
            } else {
                favorites.add(key);
                favorite.setText("♥");
            }
        });

        actions.addView(
                playlist,
                new LinearLayout.LayoutParams(
                        0, dp(60), 1));

        actions.addView(
                favorite,
                new LinearLayout.LayoutParams(
                        0, dp(60), 1));

        actions.addView(
                repeat,
                new LinearLayout.LayoutParams(
                        0, dp(60), 1));

        actions.addView(
                sleep,
                new LinearLayout.LayoutParams(
                        0, dp(60), 1));

        // Anchor the bottom action row near the bottom of the Now Playing
        // screen. The previous weight spacer could not work correctly
        // because this page is inside a ScrollView.
        int playerHeight = root != null ? root.getHeight() : dp(650);
        if (playerHeight > dp(80)) {
            page.setMinimumHeight(playerHeight);
        }

        Space actionSpacer = new Space(this);
        page.addView(
                actionSpacer,
                new LinearLayout.LayoutParams(
                        -1, 0, 1));

        LinearLayout.LayoutParams actionLp =
                new LinearLayout.LayoutParams(-1, dp(62));
        actionLp.setMargins(0, 0, 0, dp(38));

        page.addView(actions, actionLp);

        content.addView(
                page,
                new LinearLayout.LayoutParams(-1, -1));

        updateFullPlayer();
    }

    Button bottomAction(String icon) {
        Button b = new Button(this);
        b.setText(icon);
        b.setTextSize(27);
        b.setTextColor(Color.BLACK);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    Button createRoundPlayButton() {
        Button b = new Button(this);

        b.setText("▶");
        b.setTextSize(28);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setIncludeFontPadding(false);
        b.setBackground(bg(PURPLE, 100));

        return b;
    }

    Button control(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(27);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        return b;
    }


    void updatePlayButtons() {
        boolean playing = player != null && player.isPlaying();
        String icon = playing ? "Ⅱ" : "▶";

        if (miniPlay != null) {
            miniPlay.setText(icon);
        }

        if (fullPlayPause != null) {
            fullPlayPause.setText(icon);
        }
    }

    void showNowPlayingMoreMenu() {
        if (current < 0 || current >= titles.size()) return;

        String[] options = {
                "Add to Playlist",
                "Repeat",
                "Song Info",
                "Share",
                "Add to Queue"
        };

        new android.app.AlertDialog.Builder(this)
                .setTitle(titles.get(current))
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showPlaylistsPage();
                    } else if (which == 1) {
                        Toast.makeText(this, "Repeat option selected", Toast.LENGTH_SHORT).show();
                    } else if (which == 2) {
                        showCurrentSongInfo();
                    } else if (which == 3) {
                        shareCurrentSong();
                    } else if (which == 4) {
                        Toast.makeText(this, "Song added to queue", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    void showCurrentSongInfo() {
        if (current < 0 || current >= titles.size()) return;

        String duration = player != null ? format(player.getDuration()) : "Unknown";
        String info = "Song: " + titles.get(current) + "\n\n" +
                "Artist: " + artists.get(current) + "\n\n" +
                "Duration: " + duration;

        new android.app.AlertDialog.Builder(this)
                .setTitle("Song Info")
                .setMessage(info)
                .setPositiveButton("OK", null)
                .show();
    }

    void shareCurrentSong() {
        if (current < 0 || current >= titles.size()) return;

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT,
                titles.get(current) + " - " + artists.get(current));

        startActivity(Intent.createChooser(shareIntent, "Share Song"));
    }

    void showSleepTimer() {
        final String[] choices = {
                "Off",
                "15 minutes",
                "30 minutes",
                "45 minutes",
                "60 minutes",
                "90 minutes"
        };

        final int[] mins = {0, 15, 30, 45, 60, 90};

        android.app.AlertDialog dialog =
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Sleep Timer")
                        .setSingleChoiceItems(choices, -1,
                                (d, which) -> {
                                    setSleepTimer(mins[which]);
                                    d.dismiss();
                                })
                        .setNegativeButton("Cancel", null)
                        .create();

        dialog.show();
    }

    void setSleepTimer(int minutes) {
        if (minutes == 0) {
            if (sleepTimer != null) {
                sleepTimer.cancel();
                sleepTimer = null;
            }

            if (timerRemaining != null)
                timerRemaining.setText("Timer বন্ধ");

            Toast.makeText(
                    this,
                    "Sleep timer off",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (sleepTimer != null)
            sleepTimer.cancel();

        final int selected = minutes;

        sleepTimer = new CountDownTimer(
                selected * 60L * 1000L,
                1000L) {

            @Override
            public void onTick(long left) {
                if (timerRemaining != null)
                    timerRemaining.setText(
                            "বাকি " + formatTimer(left));
            }

            @Override
            public void onFinish() {
                if (timerRemaining != null)
                    timerRemaining.setText("Timer শেষ");

                if (player != null && player.isPlaying()) {
                    player.pause();
                    updatePlayButtons();
                    updateMediaSession();
                }

                sleepTimer = null;

                Toast.makeText(
                        MainActivity.this,
                        "Sleep timer finished",
                        Toast.LENGTH_SHORT).show();
            }
        }.start();

        if (timerRemaining != null)
            timerRemaining.setText("বাকি " + selected + ":00");

        Toast.makeText(
                this,
                "Sleep timer: " + selected + " minutes",
                Toast.LENGTH_SHORT).show();
    }

    String formatTimer(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        long min = totalSec / 60;
        long sec = totalSec % 60;

        return min + ":" +
                String.format("%02d", sec);
    }

    void updateFullPlayer() {
        if (current < 0 || fullTitle == null) return;

        fullTitle.setText(titles.get(current));
        fullArtist.setText(artists.get(current));

        updatePlayButtons();

        if (player != null) {
            int d = player.getDuration();

            if (timeEnd != null)
                timeEnd.setText(format(d));

            if (d > 0 &&
                    seek != null &&
                    !updatingSeek) {

                seek.setProgress(
                        (int)((long)
                                player.getCurrentPosition()
                                * 1000 / d));
            }
        }
    }

    String format(int ms) {
        int sec = Math.max(0, ms / 1000);

        return (sec / 60) +
                ":" +
                String.format("%02d", sec % 60);
    }

    void startSeekUpdates() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {

                if (player != null) {

                    if (fullPlayer &&
                            seek != null &&
                            !updatingSeek) {

                        int d = player.getDuration();

                        if (d > 0) {

                            seek.setProgress(
                                    (int)((long)
                                            player.getCurrentPosition()
                                            * 1000 / d));

                            if (timeNow != null)
                                timeNow.setText(
                                        format(
                                                player.getCurrentPosition()));

                            if (timeEnd != null)
                                timeEnd.setText(format(d));
                        }
                    }

                    handler.postDelayed(this, 500);
                }
            }
        }, 200);
    }

    @Override
    public void onBackPressed() {
        if (fullPlayer) {
            showHome();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int r,
            String[] p,
            int[] g) {

        super.onRequestPermissionsResult(r, p, g);

        if (r == 60) {
            requestMusicPermission();

        } else if (r == 50 &&
                g.length > 0 &&
                g[0] == PackageManager.PERMISSION_GRANTED) {

            loadSongs();

        } else if (r == 50) {

            Toast.makeText(
                    this,
                    "Music permission is required",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);

        if (sleepTimer != null)
            sleepTimer.cancel();

        hidePlaybackNotification();

        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }

        if (player != null)
            player.release();

        super.onDestroy();
    }
          }
