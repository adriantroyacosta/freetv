package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Home screen background: Bing's photo of the day in UHD (3840x2160), downloaded in the background once a day and
 * kept on disk, so startup only decodes a local file (sampled down to the screen size: sharp, no wasted memory).
 * Until the first download, assets/wallpaper.jpg.
 */
final class Wallpaper {
    private static final String API = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=en-US";
    private static final long EVERY = 24 * 60 * 60_000L;
    private static volatile boolean fetching;
    private static volatile Backdrop cached;

    private Wallpaper() {}

    private static File file(Context c) {
        return new File(c.getFilesDir(), "wallpaper.jpg");
    }

    static Backdrop cached() {
        return cached;
    }

    /** The cached photo (or the bundled one), built once as a GPU-backed Backdrop with Liquid Glass refraction arrays. */
    static Backdrop load(Context c) {
        if (cached != null) return cached;
        File f = file(c);
        try {
            if (f.length() > 0) {
                Bitmap b = decode(new java.io.FileInputStream(f), new java.io.FileInputStream(f), Backdrop.W, Backdrop.H);
                if (b != null) {
                    cached = Backdrop.wallpaper(b);
                    return cached;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            Bitmap b = decode(c.getAssets().open("wallpaper.jpg"), c.getAssets().open("wallpaper.jpg"), Backdrop.W, Backdrop.H);
            if (b != null) cached = Backdrop.wallpaper(b);
            return cached;
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decode(InputStream probe, InputStream data, int w, int h) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        try (InputStream in = probe) {
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = data) {
            return BitmapFactory.decodeStream(in, null, d);
        }
    }

    /** Downloads today's photo when the cached one is older than a day; `done` runs (off the main thread) if a new one arrived. */
    static void refresh(Context c, Runnable done) {
        File f = file(c);
        long age = System.currentTimeMillis() - f.lastModified();
        if (fetching || (f.length() > 0 && age >= 0 && age < EVERY)) return;
        fetching = true;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject image = new JSONObject(new String(get(API), "UTF-8")).getJSONArray("images").getJSONObject(0);
                String url = "https://www.bing.com" + image.getString("urlbase") + "_UHD.jpg";
                byte[] jpg = get(url);
                if (jpg.length < 50_000) return;
                File tmp = new File(app.getFilesDir(), "wallpaper.tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(jpg);
                }
                File dst = file(app);
                if (tmp.renameTo(dst)) {
                    Bitmap b = decode(new java.io.FileInputStream(dst), new java.io.FileInputStream(dst), Backdrop.W, Backdrop.H);
                    if (b != null) {
                        cached = Backdrop.wallpaper(b);
                        done.run();
                    }
                }
            } catch (Exception e) {
                AppLog.w("wallpaper: " + e);
            } finally {
                fetching = false;
            }
        }, "wallpaper").start();
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10_000);
        con.setReadTimeout(30_000);
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            con.disconnect();
        }
    }
}

