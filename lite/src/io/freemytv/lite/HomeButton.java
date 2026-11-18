package io.freemytv.lite;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Fire OS keeps Amazon's home as the only home screen and handles the Home button itself, and 5.2.8.7+ ignores
 * third-party accessibility services, so key filtering (FTVLaunchX, LauncherHijack) is out. What's left:
 *
 * 1. Amazon's home logs every Home press. With READ_LOGS (`pm grant io.freemytv.lite android.permission.READ_LOGS`)
 *    we read that line. Only real presses count, so apps that open a page inside Amazon's home (Prime Video) are
 *    left alone.
 * 2. After Home, Android holds every app launch back 5 s (APP_SWITCH_DELAY_TIME), alarms included on Android 5.
 *    A window isn't a launch: we put our home on screen at once as an overlay, ask for HomeActivity (it arrives
 *    when the 5 s are up) and hand over to it then, without a visible change.
 *
 * Nothing on the TV is changed: uninstalling this app is the way back.
 */
public final class HomeButton extends Service {
    static final String TAG = "FreeMyTVLite";
    private static final String HOME_PRESS = "Processing user Home key press";
    // Amazon's home coming back for any other reason: Back out of an app, the end of boot, an app closing.
    private static final String HOME_RESUMED = "OnResume - display settings";

    /** Set by the "Amazon home" tile: let it stay until the next Home press. */
    static volatile boolean amazonWanted;
    private static final String[] LOGCAT = {"logcat", "-v", "brief", "-T", "1", "-s", "HomeActivity_vNext:I"};
    // If HomeActivity never shows up, don't leave the overlay over everything.
    private static final long OVERLAY_MAX_MS = 9_000;

    private static HomeButton running;   // main thread only

    /** Starts the listener; safe to call often. */
    static void start(Context c) {
        try {
            c.startService(new Intent(c, HomeButton.class));
        } catch (RuntimeException e) {
            Log.e(TAG, "Couldn't start the Home button listener", e);
        }
    }

    static void openHome(Context c) {
        try {
            c.startActivity(new Intent(c, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException e) {
            Log.e(TAG, "Couldn't open the home screen", e);
        }
    }

    /**
     * Called by HomeActivity when it resumes: if the overlay is up, the activity takes its place (same list, same
     * focus) and the overlay goes once the activity has drawn, so Amazon's home never shows in between.
     */
    static void handOver(HomeView activity) {
        final HomeButton s = running;
        if (s == null || !s.shown) return;
        activity.copyFrom(s.overlay);
        activity.afterNextDraw(new Runnable() {
            @Override public void run() {
                s.hideOverlay();
            }
        });
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    // Built once and kept hidden: building and fading it in on each press is what let Amazon's home show.
    private HomeView overlay;
    private WindowManager.LayoutParams overlayParams;
    private boolean shown;
    private volatile boolean listening;
    private volatile Process logcat;

    private long askedAt;   // last time we asked for HomeActivity

    private final Runnable pressed = new Runnable() {
        @Override public void run() {
            showOverlay();
            // While Amazon's home boots it resumes again and again: one request is queued already, don't pile more.
            long now = SystemClock.elapsedRealtime();
            if (now - askedAt < 6_000) return;
            askedAt = now;
            openHome(HomeButton.this);   // held back by Android until the 5 s are up
        }
    };
    private final Runnable overlayTimeout = new Runnable() {
        @Override public void run() {
            hideOverlay();
        }
    };

    // Waking the TV up lands on Amazon's home: same as Home.
    private final BroadcastReceiver wake = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            main.post(pressed);
        }
    };

    private static final int HIDDEN_FLAGS = WindowManager.LayoutParams.FLAG_FULLSCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    private static final int SHOWN_FLAGS = WindowManager.LayoutParams.FLAG_FULLSCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

    private void buildOverlay() {
        // No FLAG_HARDWARE_ACCELERATED: a GL context per window costs megabytes this stick doesn't have.
        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT, HIDDEN_FLAGS, PixelFormat.OPAQUE);
        overlayParams.windowAnimations = 0;   // no fade-in: it has to cover Amazon's home at once
        HomeView v = new HomeView(this);
        v.setVisibility(android.view.View.GONE);   // gone: no surface, no memory for pixels
        try {
            windows.addView(v, overlayParams);
            overlay = v;
        } catch (RuntimeException e) {
            Log.e(TAG, "Couldn't build the overlay", e);
        }
    }

    private void showOverlay() {
        if (overlay == null) return;
        overlay.goHome();
        if (!shown) {
            shown = true;
            overlay.start();
            overlay.setVisibility(android.view.View.VISIBLE);
            overlayParams.flags = SHOWN_FLAGS;
            windows.updateViewLayout(overlay, overlayParams);
            Log.i(TAG, "Overlay up");
        }
        main.removeCallbacks(overlayTimeout);
        main.postDelayed(overlayTimeout, OVERLAY_MAX_MS);
    }

    private void hideOverlay() {
        main.removeCallbacks(overlayTimeout);
        if (overlay == null || !shown) return;
        shown = false;
        overlay.stop();
        overlay.setVisibility(android.view.View.GONE);
        overlayParams.flags = HIDDEN_FLAGS;
        try {
            windows.updateViewLayout(overlay, overlayParams);
        } catch (RuntimeException e) {
            Log.w(TAG, "Overlay already gone", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        // Foreground: as a plain background service the low-memory killer took us first, and with us gone Home and
        // Back went to Amazon's home. A foreground service is about the last thing it kills.
        startForeground(1, new Notification.Builder(this)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle(getString(R.string.app_name))
                .setPriority(Notification.PRIORITY_MIN)
                .setOngoing(true)
                .build());
        Keeper.schedule(this);
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildOverlay();
        if (checkCallingOrSelfPermission("android.permission.READ_LOGS") != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No READ_LOGS: pm grant io.freemytv.lite android.permission.READ_LOGS");
        }
        registerReceiver(wake, new IntentFilter(Intent.ACTION_SCREEN_ON));
        listening = true;
        new Thread(new Runnable() {
            @Override public void run() {
                listen();
            }
        }, "home-button").start();
        main.postDelayed(refresh, refreshDelay());
    }

    // After a reboot a logcat started early was seen alive but reading nothing, so Home went to Amazon's.
    // A fresh logcat now and then costs nothing and makes sure we never stay deaf: often while the TV boots,
    // then every 10 minutes.
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            Process p = logcat;
            if (p != null) p.destroy();   // listen() starts a new one right away
            main.postDelayed(this, refreshDelay());
        }
    };

    private static long refreshDelay() {
        return SystemClock.elapsedRealtime() < 10 * 60_000 ? 45_000 : 10 * 60_000;
    }

    /** The "log" group (1007) is what READ_LOGS gives a process: without it we only see our own lines. */
    private static boolean canReadLogs() {
        try (BufferedReader r = new BufferedReader(new java.io.FileReader("/proc/self/status"))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("Groups:")) return (" " + line.substring(7).trim() + " ").contains(" 1007 ");
            }
        } catch (IOException ignored) {
            // can't tell: assume yes
        }
        return true;
    }

    /** Reads Amazon's home log lines; restarts logcat if it ever stops. */
    private void listen() {
        long wait = 1_000;
        while (listening) {
            long started = SystemClock.elapsedRealtime();
            try {
                logcat = Runtime.getRuntime().exec(LOGCAT);
                BufferedReader in = new BufferedReader(new InputStreamReader(logcat.getInputStream()), 1024);
                Log.i(TAG, "Listening for the Home button" + (canReadLogs() ? "" : " - but without the log group!"));
                boolean first = true;   // -T 1 replays the last old line: skip it
                for (String line; listening && (line = in.readLine()) != null; ) {
                    if (first) { first = false; continue; }
                    if (line.contains(HOME_PRESS)) {
                        amazonWanted = false;
                        main.post(pressed);
                    } else if (line.contains(HOME_RESUMED) && !amazonWanted) {
                        main.post(pressed);
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "logcat stopped", e);
            } finally {
                Process p = logcat;
                if (p != null) p.destroy();
            }
            if (!listening) return;
            // Back off if logcat keeps dying right away (no permission, say), so we don't spin. A run of 30 s or
            // more ended normally (our own refresh): start again at once.
            if (SystemClock.elapsedRealtime() - started > 30_000) {
                wait = 1_000;
                continue;
            }
            SystemClock.sleep(wait);
            wait = Math.min(wait * 2, 60_000);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        return START_STICKY;   // if Fire OS frees our memory, come back
    }

    @Override
    public void onDestroy() {
        listening = false;
        Process p = logcat;
        if (p != null) p.destroy();
        unregisterReceiver(wake);
        main.removeCallbacks(pressed);
        main.removeCallbacks(refresh);
        hideOverlay();
        if (overlay != null) {
            try {
                windows.removeView(overlay);
            } catch (RuntimeException ignored) {
                // already gone
            }
            overlay = null;
        }
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * At boot: start listening and show our home instead of Amazon's. Fire OS hands BOOT_COMPLETED out minutes
     * late on this stick, so the network coming up (seconds after boot) starts us too. That one fires again later
     * (Wi-Fi drops), so it only opens our home once, in the first minutes after boot.
     */
    public static final class Boot extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            atBoot(c, i.getAction());
        }
    }

    /**
     * Starts the listener and, once per boot, opens our home. Whichever comes first (job, broadcast) opens it; the
     * rest must not pull you out of an app you've opened since, even if our process was restarted meanwhile, so the
     * boot we opened for is kept on disk.
     */
    static void atBoot(Context c, String why) {
        start(c);
        long bootedAt = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        android.content.SharedPreferences p = c.getSharedPreferences("boot", Context.MODE_PRIVATE);
        if (Math.abs(p.getLong("opened_for", 0) - bootedAt) < 60_000) return;   // this boot: done already
        if (SystemClock.elapsedRealtime() > 10 * 60_000) return;   // long after boot: you're using the TV
        p.edit().putLong("opened_for", bootedAt).apply();
        Log.i(TAG, "Opening at boot (" + why + ")");
        openHome(c);
    }

    /**
     * A persisted job: Android restores it when the TV starts, well before Fire OS gets round to BOOT_COMPLETED (on
     * one power-up it never reached us at all). Every minute after that it checks we're still running.
     */
    public static final class Keeper extends android.app.job.JobService {
        static void schedule(Context c) {
            android.app.job.JobScheduler js =
                    (android.app.job.JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            for (android.app.job.JobInfo j : js.getAllPendingJobs()) if (j.getId() == 1) return;
            js.schedule(new android.app.job.JobInfo.Builder(1, new android.content.ComponentName(c, Keeper.class))
                    .setPeriodic(60_000)
                    .setPersisted(true)
                    .build());
        }

        @Override public boolean onStartJob(android.app.job.JobParameters params) {
            atBoot(this, "job");
            return false;
        }

        @Override public boolean onStopJob(android.app.job.JobParameters params) {
            return false;
        }
    }
}
