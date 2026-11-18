package io.freemytv;

import android.service.dreams.DreamService;

/**
 * The Free My TV screensaver (enabled in Free My TV mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. Any key exits.
 */
public class Screensaver extends DreamService {
    private DreamView view;
    private ClockDream clock;   // home_style=minimal

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Watchdog.install(this);
        Ui.init(this);
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        if (Config.MINIMAL) {
            clock = new ClockDream(this);
            setContentView(clock);
            return;
        }
        view = new DreamView(this);
        setContentView(view);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (view != null) view.start();
        if (clock != null) clock.start();
    }

    @Override public void onDreamingStopped() {
        if (view != null) view.stop();
        if (clock != null) clock.stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (view != null) view.stop();
        if (clock != null) clock.stop();
        super.onDetachedFromWindow();
    }
}
