package com.bliss.headless;

import android.app.Application;

/** Keeps the Shizuku client alive across activity recreation. */
public final class App extends Application {
    private EngineClient client;

    @Override
    public void onCreate() {
        super.onCreate();
        client = EngineClient.get(this);
    }

    public EngineClient client() {
        if (client == null) {
            client = EngineClient.get(this);
        }
        return client;
    }
}
