package io.freemytv.lite;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public final class HomeActivity extends Activity {
    private HomeView view;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        view = new HomeView(this);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.start();   // reloads the apps: something may have been installed or removed meanwhile
        HomeButton.handOver(view);   // we were shown as an overlay meanwhile: carry on from there
        HomeButton.start(this);   // in case Fire OS stopped the Home button listener
    }

    @Override
    protected void onPause() {
        view.stop();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        view.goHome();
    }

    @Override
    public void onBackPressed() {
        view.back();  // from "All apps" to the favorites; on the home screen itself Back has nowhere to go
    }
}
