/*
 * esochan (Meta Imageboard Client)
 * Copyright (C) 2014-2016  miku-nyan <https://github.com/miku-nyan>
 *     
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package dev.esoc.esochan.common;

import dev.esoc.esochan.R;
import dev.esoc.esochan.api.ChanModule;
import dev.esoc.esochan.cache.BitmapCache;
import dev.esoc.esochan.cache.DraftsCache;
import dev.esoc.esochan.cache.FileCache;
import dev.esoc.esochan.cache.PagesCache;
import dev.esoc.esochan.cache.Serializer;
import dev.esoc.esochan.chans.fourchan.FourchanModule;
import dev.esoc.esochan.http.streamer.HttpStreamer;
import dev.esoc.esochan.ui.Database;
import dev.esoc.esochan.ui.downloading.DownloadingLocker;
import dev.esoc.esochan.ui.presentation.Subscriptions;
import dev.esoc.esochan.ui.settings.ApplicationSettings;
import dev.esoc.esochan.ui.settings.Wifi;
import dev.esoc.esochan.ui.tabs.TabsState;
import dev.esoc.esochan.ui.tabs.TabsSwitcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;

import androidx.core.view.WindowCompat;

import dev.esoc.esochan.ui.gallery.GalleryActivity;

/**
 * Класс приложения (расширяет {@link Application}).<br>
 * Экземпляр ({@link #getInstance()) хранит объекты, используемые в различных частях проекта.
 * @author miku-nyan
 *
 */

public class MainApplication extends Application {

    private static MainApplication instance;
    public static MainApplication getInstance() {
        if (instance == null) throw new IllegalStateException("Must be called after onCreate()");
        return instance;
    }
    
    public SharedPreferences preferences;
    public Resources resources;
    public ApplicationSettings settings;
    public FileCache fileCache;
    public Serializer serializer;
    public BitmapCache bitmapCache;
    public PagesCache pagesCache;
    public DraftsCache draftsCache;
    public Database database;
    public Subscriptions subscriptions;
    public DownloadingLocker downloadingLocker;
    
    public TabsState tabsState;
    public TabsSwitcher tabsSwitcher;
    
    private FourchanModule fourchanModule;

    /** Returns the only runtime chan implementation. */
    public ChanModule getChanModule() {
        return fourchanModule;
    }

    /**
     * Resolves persisted chan identifiers without changing their serialized representation.
     * Unknown identifiers from removed clients deliberately remain unsupported.
     */
    public ChanModule getChanModule(String chanName) {
        if (fourchanModule == null || !fourchanModule.getChanName().equals(chanName)) return null;
        return fourchanModule;
    }
    
    private void initObjects() {
        HttpStreamer.initInstance();
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        resources = this.getResources();
        settings = new ApplicationSettings(preferences, resources);
        fileCache = new FileCache(this, settings.getMaxCacheSize());
        serializer = new Serializer(fileCache);
        tabsState = serializer.deserializeTabsState();
        tabsSwitcher = new TabsSwitcher();
        
        long maxHeapSize = Runtime.getRuntime().maxMemory();
        bitmapCache = new BitmapCache((int)Math.min(maxHeapSize / 16, Integer.MAX_VALUE), fileCache);
        pagesCache = new PagesCache((int)Math.min(maxHeapSize / 6, Integer.MAX_VALUE), serializer);
        draftsCache = new DraftsCache(10, serializer);
        
        database = new Database(this);
        subscriptions = new Subscriptions(this);
        downloadingLocker = new DownloadingLocker();
        
        fourchanModule = new FourchanModule(preferences, resources);
        
        Wifi.register(this);
    }
    
    private String getAppProcessName() {
        int myPid = android.os.Process.myPid();
        for (RunningAppProcessInfo process : ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getRunningAppProcesses()) {
            if (myPid == process.pid) return process.processName;
        }
        return null;
    }

    private boolean isGalleryProcess() {
        try {
            return getAppProcessName().endsWith(":Gallery");
        } catch (Exception e) {
            return false;
        }
    }
    
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        registerActivityLifecycleCallbacks(new LegacyInsetsCallbacks());
        if (isGalleryProcess()) return;
        initObjects();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(
                    "downloads", getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(
                    "posting", getString(R.string.channel_posting), NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(
                    "tabs_tracker", getString(R.string.channel_tabs_tracker), NotificationManager.IMPORTANCE_LOW));
        }
    }
    
    @Override
    public void onLowMemory() {
        clearCaches();
        super.onLowMemory();
    }
    
    /**
     * Очистить все кэши в памяти. Вызывать в случае гроб-гроб-кладбище-OutOfMemory, иногда может помочь
     */
    public static void freeMemory() {
        try {
            getInstance().freeMemoryInternal();
        } catch (Exception e) {} //если синглтон MainApplication не создан 
    }
    
    private void freeMemoryInternal() {
        clearCaches();
        System.gc();
        try {
            Thread.sleep(1000);
        } catch (Throwable t) {}
    }
    
    private void clearCaches() {
        pagesCache.clearLru();
        bitmapCache.clearLru();
        draftsCache.clearLru();
    }

    /**
     * Temporary compatibility behavior for legacy activity roots that do not yet
     * consume system-bar insets consistently. GalleryActivity owns its immersive mode.
     */
    private static class LegacyInsetsCallbacks implements ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            if (!(activity instanceof GalleryActivity)) {
                WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), true);
            }
        }
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityResumed(Activity activity) {}
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
        @Override public void onActivityDestroyed(Activity activity) {}
    }

}
