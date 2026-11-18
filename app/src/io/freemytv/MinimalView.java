package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The minimal home screen (home_style=minimal): black, the clock, the corner buttons (Live TV, HDMI Inputs,
 * Settings), your apps, and a bottom row with the inputs and a few shortcuts. No photos, picks, weather or shaders:
 * one Canvas, a 150 ms focus animation, nothing else moving. Same rules as Free My TV Lite on Fire TV.
 *
 * Focus moves between three zones: the corner buttons (top), the apps (middle) and the bottom row.
 */
final class MinimalView extends View implements HomeScreen, Choreographer.FrameCallback {
    private static final int TOP = 0, APPS = 1, BOTTOM = 2;
    private static final int COLUMNS = 5;
    private static final long FOCUS_MS = 150;
    private static final int BACKGROUND = 0xFF111216, PLATE = 0xFF1E1F24, CARD = 0xFF23242A, CARD_FOCUSED = 0xFF3A3B42;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TvInputManager tvInputs;

    // What's on screen (main thread only).
    private final List<HomeApps.Item> buttons = new ArrayList<>();
    private List<HomeApps.Item> apps = new ArrayList<>(), others = new ArrayList<>(), bottom = new ArrayList<>();
    private int inputCount;            // the bottom row's first items are inputs, the rest shortcuts
    private boolean allApps;           // the middle shows All Apps instead of your apps
    private int zone = APPS, index, firstRow;
    private boolean selectDown;

    // Focus animation: the focused item grows in as the previous one shrinks back.
    private int prevZone = -1, prevIndex = -1;
    private long focusStart;
    private float focusT = 1;

    // Layout (onSizeChanged).
    private float k, margin, gap, tileW, tileH, gridTop, radius, btnR, btnY, rowTop, cardW, cardH;
    private int visibleRows = 1;
    private TextPaint clockPaint, datePaint, titlePaint, labelPaint, cardPaint, hintPaint;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hairline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF box = new RectF();
    private final Path clip = new Path();
    private String time = "", date = "";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            time = Ui.clock(now);
            String d = Ui.date(now);
            date = d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1);
            invalidate();
            main.postDelayed(this, 60_000 - now % 60_000 + 50);
        }
    };

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { load(); }
        @Override public void onInputAdded(String inputId) { load(); }
        @Override public void onInputRemoved(String inputId) { load(); }
    };

    MinimalView(Activity a) {
        super(a);
        activity = a;
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        // Not pure black: a near-black gray lets dark app art (Apple TV, HBO) stand out from the background.
        setBackgroundColor(BACKGROUND);
        setFocusable(true);
        buttons.add(HomeApps.action(HomeApps.LIVE_TV, "live", Ui.tr("TV aberta", "Live TV", "TV en vivo"), "live_tv"));
        buttons.add(HomeApps.action(HomeApps.INPUTS, "inputs", Ui.tr("Entradas HDMI", "HDMI Inputs", "Entradas HDMI"), "hdmi"));
        buttons.add(HomeApps.action(HomeApps.ANDROID_SETTINGS, "tv-settings",
                Ui.tr("Ajustes da TV", "TV Settings", "Ajustes de la TV"), "tv_settings"));
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Color.WHITE);
        hairline.setStyle(Paint.Style.STROKE);
    }

    // MARK: - HomeScreen

    @Override public void resume() {
        requestFocus();
        main.removeCallbacks(tick);
        tick.run();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, main);
        load();
        freeMemory();
    }

    private long freedAt;   // main thread

    /**
     * Back on the home screen: stops the TV apps left in the background (a streaming app keeps 80-150 MB), so the
     * next app finds that memory free; the price is that an app you've just left starts afresh. Android 12 on Sony
     * ignores ActivityManager.killBackgroundProcesses from apps, so this goes through Free My TV's own ADB connection
     * (`am kill`, which only touches background processes). At most every 30 s: each run opens that connection.
     */
    private void freeMemory() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (freedAt != 0 && now - freedAt < 30_000) return;
        freedAt = now;
        final Context c = getContext().getApplicationContext();
        worker.execute(new Runnable() {
            @Override public void run() {
                android.content.pm.PackageManager pm = c.getPackageManager();
                android.content.Intent tvApps = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER);
                StringBuilder cmd = new StringBuilder();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(tvApps, 0)) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
                    if (cmd.length() > 0) cmd.append("; ");
                    cmd.append("am kill ").append(pkg);
                }
                if (cmd.length() == 0) return;
                try (Adb adb = Adb.connect(c, 0, null)) {   // 0: never wait for a permission prompt
                    adb.shell(cmd.toString());
                } catch (Exception e) {
                    AppLog.w("couldn't free background apps: " + e.getMessage());
                }
            }
        });
    }

    @Override public void pause() {
        main.removeCallbacks(tick);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    @Override public void destroy() {
        pause();
        worker.shutdownNow();
    }

    @Override public void reset(boolean animated) {
        allApps = false;
        zone = APPS;
        index = 0;
        firstRow = 0;
        rowScroll = 0;
        startFocus(-1, -1);
        freeMemory();   // Home pressed on the home screen: no resume, so free here too
    }

    /** Apps, inputs and shortcuts, read in the background (the shelf may lift logos from the apps' art). */
    private void load() {
        final Context c = getContext().getApplicationContext();
        worker.execute(new Runnable() {
            @Override public void run() {
                final List<HomeApps.Item> a = HomeApps.shelf(c, false);
                // Bottom row: the HDMI inputs (Live TV has its corner button), then All Apps and Home Settings
                // (TV Settings has its corner button).
                final List<HomeApps.Item> in = new ArrayList<>();
                for (HomeApps.Item it : HomeApps.allInputs(c)) if (it.kind == HomeApps.INPUT) in.add(it);
                final List<HomeApps.Item> row = new ArrayList<>(in);
                for (HomeApps.Item it : HomeApps.drawerShortcuts()) {
                    if (it.kind != HomeApps.ANDROID_SETTINGS) row.add(it);
                }
                final List<HomeApps.Item> o = allApps ? HomeApps.others(c) : null;
                main.post(new Runnable() {
                    @Override public void run() {
                        apps = a;
                        bottom = row;
                        inputCount = in.size();
                        if (o != null) others = o;
                        layoutRow();
                        clampFocus();
                        invalidate();
                    }
                });
            }
        });
    }

    private void showAllApps() {
        allApps = true;
        zone = APPS;
        index = 0;
        firstRow = 0;
        startFocus(-1, -1);
        final Context c = getContext().getApplicationContext();
        worker.execute(new Runnable() {
            @Override public void run() {
                final List<HomeApps.Item> o = HomeApps.others(c);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (!allApps) return;
                        others = o;
                        clampFocus();
                        invalidate();
                    }
                });
            }
        });
    }

    private List<HomeApps.Item> middle() {
        return allApps ? others : apps;
    }

    private List<HomeApps.Item> zoneItems(int z) {
        return z == TOP ? buttons : z == APPS ? middle() : bottom;
    }

    private void clampFocus() {
        int n = zoneItems(zone).size();
        if (n == 0 && zone != TOP) zone = zoneItems(APPS).isEmpty() ? TOP : APPS;
        n = zoneItems(zone).size();
        index = Math.max(0, Math.min(index, n - 1));
        fit();
    }

    // MARK: - Remote

    @Override public boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            // Open on release: opening on press hands the release to the new app before it has a window, and a slow
            // app then sits on it until Android closes it as not responding.
            if (select && selectDown && !e.isCanceled()) activate();
            if (select) selectDown = false;
            return select || code == KeyEvent.KEYCODE_BACK;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (select) {
            selectDown = true;
            return true;
        }
        int oldZone = zone, oldIndex = index;
        int n = zoneItems(zone).size();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (zone == APPS ? index % COLUMNS > 0 : index > 0) index--;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (index + 1 < n && (zone != APPS || index % COLUMNS < COLUMNS - 1)) index++;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (zone == APPS && index >= COLUMNS) index -= COLUMNS;
                else if (zone == APPS) moveTo(TOP, 0);
                else if (zone == BOTTOM) moveTo(APPS, lastRowStart());
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (zone == TOP) moveTo(APPS, 0);
                else if (zone == APPS && index + COLUMNS < n) index += COLUMNS;
                else if (zone == APPS && index / COLUMNS < (n - 1) / COLUMNS) index = n - 1;   // short last row
                else if (zone == APPS && !allApps) moveTo(BOTTOM, 0);
                break;
            case KeyEvent.KEYCODE_BACK:
                if (allApps) {
                    allApps = false;
                    zone = APPS;
                    index = 0;
                    firstRow = 0;
                    startFocus(-1, -1);
                } else if (zone != APPS || index != 0) {
                    reset(true);
                }
                return true;   // the home screen: Back has nowhere else to go
            default:
                return false;
        }
        if (zone != oldZone || index != oldIndex) {
            fit();
            scrollRow();
            startFocus(oldZone, oldIndex);
        }
        return true;
    }

    private void moveTo(int z, int i) {
        if (zoneItems(z).isEmpty()) return;
        zone = z;
        index = Math.min(i, zoneItems(z).size() - 1);
    }

    private int lastRowStart() {
        int n = middle().size();
        return n == 0 ? 0 : ((n - 1) / COLUMNS) * COLUMNS;
    }

    private void activate() {
        List<HomeApps.Item> items = zoneItems(zone);
        if (index >= items.size()) return;
        HomeApps.Item it = items.get(index);
        if (it.kind == HomeApps.INPUTS) {   // the corner button: to the inputs in the bottom row
            int old = zone, oldI = index;
            moveTo(BOTTOM, 0);
            scrollRow();
            startFocus(old, oldI);
            return;
        }
        if (it.kind == HomeApps.ALL_APPS) {
            showAllApps();
            return;
        }
        rectOf(zone, index, box);
        HomeApps.open(activity, it, this, (int) box.left, (int) box.top, (int) box.width(), (int) box.height());
    }

    // MARK: - Animation

    private void startFocus(int fromZone, int fromIndex) {
        prevZone = fromZone;
        prevIndex = fromIndex;
        focusStart = System.nanoTime();
        focusT = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
        invalidate();
    }

    @Override public void doFrame(long frameTimeNanos) {
        float t = (frameTimeNanos - focusStart) / (FOCUS_MS * 1_000_000f);
        focusT = Math.max(0, Math.min(1, t));
        invalidate();
        if (focusT < 1) Choreographer.getInstance().postFrameCallback(this);
    }

    /** 0 → 1 for the focused item, 1 → 0 for the previous one, eased out. */
    private float focusAmount(int z, int i) {
        float e = 1 - (1 - focusT) * (1 - focusT);
        if (z == zone && i == index) return e;
        if (z == prevZone && i == prevIndex) return 1 - e;
        return 0;
    }

    // MARK: - Layout

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        k = w / 1920f;
        margin = 110 * k;
        gap = 32 * k;
        tileW = (w - 2 * margin - (COLUMNS - 1) * gap) / COLUMNS;
        tileH = tileW * 9 / 16;
        radius = 16 * k;
        btnR = 38 * k;
        btnY = 120 * k;
        gridTop = 390 * k;
        cardH = 112 * k;
        cardW = 250 * k;
        rowTop = h - margin * 0.75f - cardH;
        visibleRows = Math.max(1, (int) ((rowTop - 70 * k - gridTop + gap) / (tileH + gap)));
        clockPaint = new TextPaint(Ui.text(Ui.thin, 112 * k, Color.WHITE));
        clockPaint.setTextAlign(Paint.Align.RIGHT);
        datePaint = new TextPaint(Ui.text(Ui.regular, 30 * k, 0xFF9A9AA0));
        datePaint.setTextAlign(Paint.Align.RIGHT);
        titlePaint = new TextPaint(Ui.text(Ui.semibold, 40 * k, Color.WHITE));
        labelPaint = new TextPaint(Ui.text(Ui.medium, 25 * k, 0xFFE5E5EA));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        cardPaint = new TextPaint(Ui.text(Ui.medium, 25 * k, 0xFFE5E5EA));
        hintPaint = new TextPaint(Ui.text(Ui.medium, 24 * k, 0xFF8E8E93));
        ring.setStrokeWidth(Math.max(3, 4 * k));
        hairline.setStrokeWidth(Math.max(1, 1.5f * k));
        tileText.clear();
        cardText.clear();
        titleText.clear();
        layoutRow();
        fit();
    }

    /** Scrolls the apps so the focused row is on screen. */
    private void fit() {
        if (zone != APPS) return;
        int row = index / COLUMNS;
        if (row < firstRow) firstRow = row;
        else if (row >= firstRow + visibleRows) firstRow = row - visibleRows + 1;
    }

    private void rectOf(int z, int i, RectF out) {
        if (z == TOP) {
            float cx = margin + btnR + i * btnR * 2.7f;
            out.set(cx - btnR, btnY - btnR, cx + btnR, btnY + btnR);
        } else if (z == APPS) {
            float x = margin + (i % COLUMNS) * (tileW + gap);
            float y = gridTop + (i / COLUMNS - firstRow) * (tileH + gap);
            out.set(x, y, x + tileW, y + tileH);
        } else {
            float x = (i < cardX.length ? cardX[i] : 0) + margin - rowScroll;
            float cw = i < cardWs.length ? cardWs[i] : cardW;
            out.set(x, rowTop, x + cw, rowTop + cardH);
        }
    }

    // Bottom row: each card as wide as its name (no "Consola…"), and the row scrolls when it's wider than the screen.
    private float[] cardX = new float[0], cardWs = new float[0];
    private float rowScroll;

    private void layoutRow() {
        if (cardPaint == null) return;
        int n = bottom.size();
        cardX = new float[n];
        cardWs = new float[n];
        float x = 0;
        for (int i = 0; i < n; i++) {
            if (i == inputCount && i > 0) x += gap;   // a little space between inputs and shortcuts
            cardX[i] = x;
            cardWs[i] = 52 * k + 40 * k + cardPaint.measureText(bottom.get(i).label) + 36 * k;
            x += cardWs[i] + gap * 0.6f;
        }
        cardText.clear();
        scrollRow();
    }

    /** Keeps the focused card of the bottom row on screen. */
    private void scrollRow() {
        if (zone != BOTTOM || index >= cardX.length) {
            if (zone != BOTTOM) rowScroll = 0;
            return;
        }
        float visible = getWidth() - 2 * margin;
        float left = cardX[index], right = cardX[index] + cardWs[index];
        if (left - rowScroll < 0) rowScroll = left;
        else if (right - rowScroll > visible) rowScroll = right - visible;
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        if (clockPaint == null) return;
        float w = getWidth();
        c.drawText(time, w - margin, btnY + Ui.halfCap(clockPaint) + 6 * k, clockPaint);
        c.drawText(date, w - margin, btnY + 100 * k, datePaint);

        for (int i = 0; i < buttons.size(); i++) drawButton(c, i);

        List<HomeApps.Item> mid = middle();
        String title = allApps ? Ui.tr("Todos os Apps", "All Apps", "Todas las apps")
                : zone == APPS && index < mid.size() ? mid.get(index).label : "";
        if (!title.isEmpty()) {
            c.drawText(shorten(titleText, allApps ? titleText : mid.get(index), title, titlePaint, w - 2 * margin), margin,
                    gridTop - 34 * k, titlePaint);
        }
        int from = firstRow * COLUMNS, to = Math.min(mid.size(), (firstRow + visibleRows) * COLUMNS);
        c.save();
        c.clipRect(0, gridTop - 30 * k, w, rowTop - 50 * k);
        for (int i = from; i < to; i++) if (!(zone == APPS && i == index)) drawTile(c, mid.get(i), i);
        if (zone == APPS && index >= from && index < to) drawTile(c, mid.get(index), index);   // on top
        c.restore();

        if (!allApps) {
            c.save();
            c.clipRect(margin - 24 * k, rowTop - 24 * k, w - margin + 24 * k, rowTop + cardH + 24 * k);
            for (int i = 0; i < bottom.size(); i++) if (!(zone == BOTTOM && i == index)) drawCard(c, bottom.get(i), i);
            if (zone == BOTTOM && index < bottom.size()) drawCard(c, bottom.get(index), index);
            c.restore();
        } else {
            c.drawText(Ui.tr("Voltar para o Início", "Back to Home", "Atrás para volver al inicio"), margin,
                    rowTop + cardH / 2 + Ui.halfCap(hintPaint), hintPaint);
        }
    }

    private void drawButton(Canvas c, int i) {
        float f = focusAmount(TOP, i);
        rectOf(TOP, i, box);
        float r = btnR * (1 + 0.1f * f);
        float cx = box.centerX(), cy = box.centerY();
        fill.setColor(blend(CARD, Color.WHITE, f));
        c.drawCircle(cx, cy, r, fill);
        Symbols.draw(getContext(), c, buttons.get(i).symbol, cx, cy, 30 * k * (1 + 0.1f * f), blend(0xFFD1D1D6, Color.BLACK, f));
        if (f > 0.01f) {
            labelPaint.setAlpha((int) (255 * f));
            c.drawText(buttons.get(i).label, cx, cy + r + 42 * k, labelPaint);
            labelPaint.setAlpha(255);
        }
    }

    private void drawTile(Canvas c, HomeApps.Item it, int i) {
        float f = focusAmount(APPS, i);
        rectOf(APPS, i, box);
        float s = 1 + 0.08f * f;
        int save = c.save();
        c.scale(s, s, box.centerX(), box.centerY());
        int alpha = (int) (255 * (0.72f + 0.28f * f));
        clip.reset();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        fill.setColor(PLATE);   // under the art: transparent or very dark art still reads as a card
        c.drawRect(box, fill);
        Bitmap b = it.tile;
        if (b != null) {
            bitmapPaint.setAlpha(alpha);
            c.drawBitmap(b, null, box, bitmapPaint);
        } else {
            labelPaint.setAlpha(alpha);
            c.drawText(shorten(tileText, it, it.label, labelPaint, box.width() * 0.9f), box.centerX(),
                    box.centerY() + Ui.halfCap(labelPaint), labelPaint);
            labelPaint.setAlpha(255);
        }
        c.restore();
        if (f < 0.99f) {   // a hairline edge, so tiles don't melt into the background
            hairline.setColor(Ui.alpha(Color.WHITE, 0.10f * (1 - f)));
            c.drawRoundRect(box, radius, radius, hairline);
        }
        if (f > 0.01f) {
            ring.setAlpha((int) (255 * f));
            c.drawRoundRect(box, radius, radius, ring);
        }
        c.restoreToCount(save);
    }

    /** Bottom row: an input (dimmed with no device on) or a shortcut, as a small card with its symbol and name. */
    private void drawCard(Canvas c, HomeApps.Item it, int i) {
        float f = focusAmount(BOTTOM, i);
        rectOf(BOTTOM, i, box);
        float s = 1 + 0.06f * f;
        int save = c.save();
        c.scale(s, s, box.centerX(), box.centerY());
        float dim = it.connected ? 1 : 0.45f;
        fill.setColor(blend(CARD, CARD_FOCUSED, f));
        c.drawRoundRect(box, radius, radius, fill);
        float iconX = box.left + 52 * k;
        Symbols.draw(getContext(), c, it.symbol, iconX, box.centerY(), 30 * k, Ui.alpha(Color.WHITE, dim));
        cardPaint.setAlpha((int) (255 * dim));
        float textX = iconX + 40 * k;
        c.drawText(shorten(cardText, it, it.label, cardPaint, box.right - 20 * k - textX), textX,
                box.centerY() + Ui.halfCap(cardPaint), cardPaint);
        cardPaint.setAlpha(255);
        if (it.kind == HomeApps.INPUT && it.connected) {   // a device is on and sending a signal: the green dot
            fill.setColor(0xFF30D158);
            c.drawCircle(box.right - 16 * k, box.top + 16 * k, 5 * k, fill);
        }
        if (f > 0.01f) {
            ring.setAlpha((int) (255 * f));
            c.drawRoundRect(box, radius, radius, ring);
        }
        c.restoreToCount(save);
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8)
                | (int) (ab + (bb - ab) * t);
    }

    // Shortened labels per item (one map per place they're drawn): drawing builds no strings once they're in.
    private final java.util.IdentityHashMap<Object, String> tileText = new java.util.IdentityHashMap<>();
    private final java.util.IdentityHashMap<Object, String> cardText = new java.util.IdentityHashMap<>();
    private final java.util.IdentityHashMap<Object, String> titleText = new java.util.IdentityHashMap<>();

    private static String shorten(java.util.IdentityHashMap<Object, String> cache, Object key, String s, TextPaint p,
                                  float width) {
        String out = cache.get(key);
        if (out == null) {
            out = s == null ? "" : TextUtils.ellipsize(s, p, width, TextUtils.TruncateAt.END).toString();
            cache.put(key, out);
        }
        return out;
    }
}
