package io.freemytv.lite;

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The whole home screen on one Canvas: black, a clock, and the apps in a grid. Nothing else, so old sticks keep up. */
final class HomeView extends View {
    private static final int COLUMNS = 5;
    private static final String AMAZON_HOME = "com.amazon.tv.launcher";
    // Not apps you'd open: Amazon's own home pieces, and us.
    private static final String[] HIDDEN = {AMAZON_HOME, "com.amazon.firehomestarter", "com.amazon.tv.website_launcher"};
    // The home screen shows only these, in this order (the rest are under "All apps"). Missing ones are skipped.
    private static final String[] FAVORITES = {
            "com.disney.disneyplus",
            "com.amazon.firebat",           // Prime Video on Fire TV
            "com.hbo.hbonow",               // HBO Max
            "com.netflix.ninja",
            "com.xuper.netxxus",
            "com.amazon.firetv.youtube",
            "com.apple.atve.amazon.appletv",   // Apple TV
    };

    private static final class Tile {
        String label, shown, title, pkg;  // shown: under the icon; title: big, above the grid
        // Loaded only while on screen: banners are big and this stick has 1 GB for everything.
        volatile Drawable art;
        boolean banner, iconOnly;
        ResolveInfo info;
        Intent intent;
        long lastUsed;
    }

    private final PackageManager pm;
    private final SharedPreferences used;
    // Last lists loaded, shared by the overlay and the activity so the overlay has its apps on its first frame.
    private static List<Tile> lastFavorites = new ArrayList<>(), lastEverything = new ArrayList<>();
    private List<Tile> favorites = lastFavorites, everything = lastEverything;
    private List<Tile> tiles = favorites;   // what's on screen: favorites, or everything after "All apps"
    private int focus, firstRow, loads;
    private boolean running;

    private final Paint clockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path clip = new Path();

    // Layout, set in onSizeChanged.
    private float margin, gap, tileW, tileH, gridTop, radius, clockY, dateY, nameY;
    private int visibleRows = 1;

    private String time = "", date = "";
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            time = DateFormat.getTimeFormat(getContext()).format(now);
            Locale l = Locale.getDefault();
            String d = new SimpleDateFormat(DateFormat.getBestDateTimePattern(l, "EEEEdMMMM"), l).format(now);
            date = d.isEmpty() ? d : d.substring(0, 1).toUpperCase(l) + d.substring(1);
            invalidate();
            long ms = System.currentTimeMillis();
            postDelayed(this, 60_000 - ms % 60_000 + 50);
        }
    };

    HomeView(Context c) {
        super(c);
        pm = c.getPackageManager();
        used = c.getSharedPreferences("used", Context.MODE_PRIVATE);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.BLACK);

        Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
        clockPaint.setColor(Color.WHITE);
        clockPaint.setTypeface(light);
        datePaint.setColor(0xFF9A9AA0);
        labelPaint.setColor(0xFFE5E5EA);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        namePaint.setColor(Color.WHITE);
        namePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cardPaint.setColor(0xFF1C1C1E);
        ringPaint.setColor(Color.WHITE);
        ringPaint.setStyle(Paint.Style.STROKE);
    }

    void start() {
        running = true;
        requestFocus();
        removeCallbacks(tick);
        tick.run();
        load();
        freeMemory(getContext(), null);
    }

    // Amazon's ad and telemetry apps. Fire OS 5.2.9 won't let adb turn them off, but any app may stop them while
    // they sit in the background, which gives their memory to the app you open next.
    private static final String[] AMAZON_EXTRAS = {
            "com.amazon.kso.blackbird",            // home screen ads
            "com.amazon.ftvads.deeplinking",
            "com.amazon.shoptv.client",            // shopping
            "com.amazon.shoptv.firetv.client",
            "com.amazon.alexashopping",
            "com.amazon.kor.demo",                 // store demo mode
            "com.amazon.storm.lightning.tutorial",
            "com.amazon.gamehub",
            "com.amazon.client.metrics",           // telemetry
            "com.amazon.device.metrics",
            "com.amazon.tv.fw.metrics",
            "com.amazon.perfcollection",
            "com.amazon.logan",
            "com.amazon.ftv.screensaver",          // photo screensaver: >100 MB, kept around after it ends
            "com.amazon.ftv.profilepicker",
            "com.amazon.avod",                     // Prime Video's player (Prime itself is com.amazon.firebat)
    };

    /**
     * Stops everything that sits in the background: Amazon's extras and every app you've left (keep: the one being
     * opened, or null on the home screen). On a 1 GB stick a streaming app that has to wait for memory is the one
     * that gets closed while loading, so we'd rather it finds the memory free; the price is that an app you've just
     * left starts afresh. Only background processes: Android never lets this touch what's on screen, nor
     * persistent system services (Alexa, Amazon's home), which would only restart and cost more.
     */
    static void freeMemory(Context c, final String keep) {
        final ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        final List<Tile> apps = lastEverything;
        final String me = c.getPackageName();
        new Thread(new Runnable() {
            @Override public void run() {
                Set<String> stop = new HashSet<>();
                Collections.addAll(stop, AMAZON_EXTRAS);
                Collections.addAll(stop, FAVORITES);
                for (Tile t : apps) if (t.pkg != null && !t.pkg.isEmpty()) stop.add(t.pkg);
                stop.remove(me);
                stop.remove(AMAZON_HOME);
                if (keep != null) stop.remove(keep);
                if ("com.amazon.firebat".equals(keep)) stop.remove("com.amazon.avod");
                for (String p : stop) am.killBackgroundProcesses(p);
            }
        }, "free-memory").start();
    }

    void stop() {
        running = false;
        removeCallbacks(tick);
    }

    void goHome() {
        tiles = favorites;
        focus = 0;
        firstRow = 0;
        invalidate();
    }

    // --- Apps

    private void load() {
        final int id = ++loads;
        final String settingsLabel = getContext().getString(R.string.settings);
        final String amazonLabel = getContext().getString(R.string.amazon_home);
        final String allLabel = getContext().getString(R.string.all_apps);
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Tile> all = readApps(settingsLabel, amazonLabel);
                final List<Tile> favs = pickFavorites(all, allLabel);
                for (Tile t : favs) loadArt(t);
                post(new Runnable() {
                    @Override public void run() {
                        if (id == loads && running) show(favs, all);
                    }
                });
            }
        }, "load-apps").start();
    }

    /** The favorites in their order, then Settings and "All apps". */
    private List<Tile> pickFavorites(List<Tile> all, String allLabel) {
        List<Tile> favs = new ArrayList<>();
        for (String pkg : FAVORITES) {
            Tile found = null;
            for (Tile t : all) if (pkg.equals(t.pkg)) { found = t; break; }
            if (found == null) found = hiddenEntry(pkg);   // e.g. Prime Video: no launcher entry on Fire OS
            if (found != null) favs.add(found);
        }
        if (!all.isEmpty() && Settings.ACTION_SETTINGS.equals(all.get(0).intent.getAction())) favs.add(all.get(0));
        Tile more = new Tile();
        more.pkg = "";
        more.label = allLabel;
        favs.add(more);   // no intent: opens the full list
        return favs;
    }

    /** Some Fire TV apps (Prime Video) only have a plain MAIN activity, without a launcher category. */
    private Tile hiddenEntry(String pkg) {
        try {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).setPackage(pkg), 0)) {
                if (!r.activityInfo.exported) continue;
                return tile(r, new Intent(Intent.ACTION_MAIN).setClassName(pkg, r.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            }
        } catch (RuntimeException e) {
            Log.w(HomeButton.TAG, "Can't read " + pkg, e);
        }
        return null;
    }

    private List<Tile> readApps(String settingsLabel, String amazonLabel) {
        String me = getContext().getPackageName();
        Set<String> skip = new HashSet<>();
        Collections.addAll(skip, HIDDEN);
        skip.add(me);

        // Settings first, so it's always one press away.
        List<Tile> front = new ArrayList<>();
        ResolveInfo s = pm.resolveActivity(new Intent(Settings.ACTION_SETTINGS), 0);
        if (s != null) {
            Tile t = tile(s, new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            t.label = settingsLabel;
            front.add(t);
            skip.add(s.activityInfo.packageName);
        }

        List<Tile> apps = new ArrayList<>();
        // TV apps first (they come with a banner), then phone-style apps that aren't there yet.
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
                String pkg = r.activityInfo.packageName;
                if (!skip.add(pkg)) continue;
                Intent open = new Intent(Intent.ACTION_MAIN).addCategory(category)
                        .setClassName(pkg, r.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                try {
                    apps.add(tile(r, open));
                } catch (RuntimeException e) {
                    Log.w(HomeButton.TAG, "Skipping " + pkg, e);
                }
            }
        }
        // Most recently used first, the rest alphabetically.
        final Collator az = Collator.getInstance();
        Collections.sort(apps, (a, b) -> a.lastUsed != b.lastUsed
                ? Long.compare(b.lastUsed, a.lastUsed) : az.compare(a.label, b.label));
        front.addAll(apps);

        // Last: a way to Amazon's own home, for what only it has.
        Intent amazon = pm.getLaunchIntentForPackage(AMAZON_HOME);
        if (amazon == null) amazon = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(AMAZON_HOME);
        ResolveInfo a = pm.resolveActivity(amazon, 0);
        if (a != null) {
            Tile t = tile(a, amazon.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            t.label = amazonLabel;
            t.iconOnly = true;
            front.add(t);
        }
        return front;
    }

    private Tile tile(ResolveInfo r, Intent open) {
        ActivityInfo ai = r.activityInfo;
        Tile t = new Tile();
        t.pkg = ai.packageName;
        t.intent = open;
        CharSequence l = r.loadLabel(pm);
        t.label = l == null ? t.pkg : l.toString();
        t.info = r;
        t.lastUsed = used.getLong(t.pkg, 0);
        return t;
    }

    /** Off the UI thread. */
    private void loadArt(Tile t) {
        if (t.art != null || t.info == null) return;
        try {
            ActivityInfo ai = t.info.activityInfo;
            Drawable b = t.iconOnly ? null : ai.loadBanner(pm);
            if (b == null && !t.iconOnly) b = ai.applicationInfo.loadBanner(pm);
            t.banner = b != null;
            t.art = shrink(b != null ? b : t.info.loadIcon(pm), t.banner);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(HomeButton.TAG, "No picture for " + t.pkg, e);
        }
    }

    /**
     * Apps ship banners and icons far bigger than our tiles (and the full drawable keeps it all in memory): redraw
     * each one once at the size it's shown, focused, on a 1080p screen. The original goes to the garbage collector.
     */
    private Drawable shrink(Drawable d, boolean banner) {
        int w = banner ? 336 : 96, h = banner ? 189 : 96;
        Bitmap bmp = Bitmap.createBitmap(w, h, banner ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(bmp));
        BitmapDrawable small = new BitmapDrawable(getResources(), bmp);
        small.setFilterBitmap(true);
        return small;
    }

    /** "All apps": show the names now, the pictures as they load. */
    private void loadAllArt() {
        final List<Tile> list = everything;
        new Thread(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < list.size() && tiles == list; i++) {
                    loadArt(list.get(i));
                    if (i % 5 == 4 || i == list.size() - 1) postInvalidate();
                }
            }
        }, "load-art").start();
    }

    private void show(List<Tile> favs, List<Tile> all) {
        String focused = focus < tiles.size() ? tiles.get(focus).pkg : null;
        boolean showingAll = tiles == everything && tiles != favorites;
        favorites = lastFavorites = favs;
        everything = lastEverything = all;
        setList(showingAll ? all : favs, focused);
        if (showingAll) loadAllArt();   // fresh tiles come without pictures
    }

    private void setList(List<Tile> list, String focusPkg) {
        tiles = list;
        focus = 0;
        firstRow = 0;
        if (focusPkg != null) {
            for (int i = 0; i < list.size(); i++) if (focusPkg.equals(list.get(i).pkg)) { focus = i; break; }
        }
        fit();
        invalidate();
    }

    private Runnable afterDraw;

    /** Runs r once the next frame has been drawn. */
    void afterNextDraw(Runnable r) {
        afterDraw = r;
        invalidate();
    }

    /** Takes over from the overlay: same list, same focus. */
    void copyFrom(HomeView o) {
        tiles = o.tiles == o.everything ? everything : favorites;
        focus = Math.min(o.focus, Math.max(0, tiles.size() - 1));
        firstRow = o.firstRow;
        fit();
        invalidate();
    }

    /** Back: from "All apps" to the favorites. False when already there. */
    boolean back() {
        if (tiles == favorites) return false;
        setList(favorites, "");
        for (Tile t : everything) if (!favorites.contains(t)) t.art = null;   // give the memory back
        return true;
    }

    private void open(Tile t) {
        if (t.intent == null) {   // "All apps"
            setList(everything, null);
            loadAllArt();
            return;
        }
        if (AMAZON_HOME.equals(t.pkg)) HomeButton.amazonWanted = true;
        else used.edit().putLong(t.pkg, System.currentTimeMillis()).apply();
        freeMemory(getContext(), t.pkg);
        try {
            getContext().startActivity(t.intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(HomeButton.TAG, "Couldn't open " + t.pkg, e);
        }
    }

    private void details(Tile t) {
        if (t.intent == null || AMAZON_HOME.equals(t.pkg)) return;
        try {
            getContext().startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", t.pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Log.w(HomeButton.TAG, "No app details screen", e);
        }
    }

    // --- Remote

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        boolean select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_BUTTON_A;
        if (!select) return super.onKeyUp(code, e);
        // Opening on press sent the release to the new app before it had a window; a slow one (Disney+ on this
        // stick) then sat on that key for 5 s and Android closed it as not responding.
        if (selectDown && !e.isCanceled() && !tiles.isEmpty()) open(tiles.get(focus));
        selectDown = false;
        return true;
    }

    private boolean selectDown;

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        int n = tiles.size();
        if (n == 0) return super.onKeyDown(code, e);
        int f = focus;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (f % COLUMNS > 0) f--; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (f % COLUMNS < COLUMNS - 1 && f + 1 < n) f++; break;
            case KeyEvent.KEYCODE_DPAD_UP: if (f >= COLUMNS) f -= COLUMNS; break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (f + COLUMNS < n) f += COLUMNS;
                else if (f / COLUMNS < (n - 1) / COLUMNS) f = n - 1;  // short last row
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                selectDown = true;   // opens on release: see onKeyUp
                return true;
            case KeyEvent.KEYCODE_BACK:   // here, not in the activity: the overlay has no activity
                back();
                return true;
            case KeyEvent.KEYCODE_MENU:
                details(tiles.get(focus));
                return true;
            default:
                return super.onKeyDown(code, e);
        }
        if (f != focus) {
            focus = f;
            fit();
            invalidate();
        }
        return true;
    }

    /** Scrolls so the focused row is on screen. */
    private void fit() {
        int row = focus / COLUMNS;
        if (row < firstRow) firstRow = row;
        else if (row >= firstRow + visibleRows) firstRow = row - visibleRows + 1;
    }

    // --- Drawing

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        margin = w * 0.06f;
        gap = w * 0.018f;
        tileW = (w - 2 * margin - (COLUMNS - 1) * gap) / COLUMNS;
        tileH = tileW * 9 / 16;
        radius = tileH * 0.07f;
        clockPaint.setTextSize(h * 0.10f);
        datePaint.setTextSize(h * 0.034f);
        namePaint.setTextSize(h * 0.034f);
        labelPaint.setTextSize(tileH * 0.13f);
        ringPaint.setStrokeWidth(Math.max(3, h * 0.004f));
        clockY = h * 0.17f;
        dateY = clockY + h * 0.06f;
        nameY = h * 0.335f;
        gridTop = h * 0.38f;
        visibleRows = Math.max(1, (int) ((h - gridTop - margin * 0.5f + gap) / (tileH + gap)));
        fit();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (afterDraw != null) {
            // This frame reaches the screen just after onDraw: give it a moment before the overlay goes.
            postDelayed(afterDraw, 60);
            afterDraw = null;
        }
        c.drawText(time, margin, clockY, clockPaint);
        c.drawText(date, margin, dateY, datePaint);
        int n = tiles.size();
        if (n == 0) return;
        Tile f = tiles.get(focus);
        if (f.title == null) f.title = ellipsize(f.label, namePaint, getWidth() - 2 * margin);
        c.drawText(f.title, margin, nameY, namePaint);

        int from = firstRow * COLUMNS, to = Math.min(n, (firstRow + visibleRows) * COLUMNS);
        for (int i = from; i < to; i++) if (i != focus) drawTile(c, i, false);
        if (focus >= from && focus < to) drawTile(c, focus, true);  // last, so it sits on top of its neighbours
    }

    private void drawTile(Canvas c, int i, boolean focused) {
        Tile t = tiles.get(i);
        Drawable art = t.art;   // read once: the loader may fill it in meanwhile
        float x = margin + (i % COLUMNS) * (tileW + gap);
        float y = gridTop + (i / COLUMNS - firstRow) * (tileH + gap);
        box.set(x, y, x + tileW, y + tileH);
        int save = c.save();
        if (focused) c.scale(1.08f, 1.08f, box.centerX(), box.centerY());
        // Dim the rest so focus is obvious (alpha, not a layer: layers are costly on these GPUs).
        int alpha = focused ? 255 : 150;
        cardPaint.setAlpha(alpha);
        labelPaint.setAlpha(alpha);

        c.drawRoundRect(box, radius, radius, cardPaint);
        if (art == null) {   // "All apps": just its name
            if (t.shown == null) t.shown = ellipsize(t.label, labelPaint, tileW * 0.9f);
            c.drawText(t.shown, box.centerX(), box.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2,
                    labelPaint);
        } else {
            art.setAlpha(alpha);
            if (t.banner) {
                clip.reset();
                clip.addRoundRect(box, radius, radius, Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                art.setBounds((int) x, (int) y, (int) (x + tileW), (int) (y + tileH));
                art.draw(c);
                c.restore();
            } else {
                float s = tileH * 0.46f;
                int l = (int) (box.centerX() - s / 2), top = (int) (y + tileH * 0.12f);
                art.setBounds(l, top, (int) (l + s), (int) (top + s));
                art.draw(c);
                if (t.shown == null) t.shown = ellipsize(t.label, labelPaint, tileW * 0.9f);
                c.drawText(t.shown, box.centerX(), y + tileH * 0.85f, labelPaint);
            }
        }
        if (focused) c.drawRoundRect(box, radius, radius, ringPaint);
        c.restoreToCount(save);
    }

    private static String ellipsize(String s, Paint p, float width) {
        return TextUtils.ellipsize(s, new android.text.TextPaint(p), width, TextUtils.TruncateAt.END).toString();
    }
}
