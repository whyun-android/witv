package com.whyun.witv;

import android.app.Application;

import com.whyun.witv.data.db.AppDatabase;
import com.whyun.witv.server.WebServer;

public class WiTVApp extends Application {

    private static WiTVApp instance;
    private WebServer webServer;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        AppDatabase.getInstance(this);
        startWebServer();
    }

    public static WiTVApp getInstance() {
        return instance;
    }

    private void startWebServer() {
        try {
            webServer = new WebServer(this, 9978);
            webServer.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public WebServer getWebServer() {
        return webServer;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        if (webServer != null && webServer.isAlive()) {
            webServer.stop();
        }
    }
}
