package io.freemytv;

import android.view.KeyEvent;

/** What HomeActivity needs from a home screen: the full one (HomeView) or the minimal one (MinimalView). */
interface HomeScreen {
    void resume();

    void pause();

    void destroy();

    /** Home pressed while the home screen is showing: back to the entry state. */
    void reset(boolean animated);

    /** Every key event; true = handled. */
    boolean onKey(KeyEvent e);
}
