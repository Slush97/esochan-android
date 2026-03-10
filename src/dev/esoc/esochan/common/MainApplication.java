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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.esoc.esochan.R;
import dev.esoc.esochan.api.ChanModule;
import dev.esoc.esochan.cache.BitmapCache;
import dev.esoc.esochan.cache.DraftsCache;
import dev.esoc.esochan.cache.FileCache;
import dev.esoc.esochan.cache.PagesCache;
import dev.esoc.esochan.cache.Serializer;
import dev.esoc.esochan.http.client.ExtendedTrustManager;
import dev.esoc.esochan.http.streamer.HttpStreamer;
import dev.esoc.esochan.lib.org_json.JSONArray;
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
    
    private static final String[] MODULES = new String[] {
            "dev.esoc.esochan.chans.fourchan.FourchanModule",
            "dev.esoc.esochan.chans.cirno.CirnoModule",
            "dev.esoc.esochan.chans.cirno.NowereModule",
            "dev.esoc.esochan.chans.horochan.HorochanModule",
            "dev.esoc.esochan.chans.sich.SichModule",
            "dev.esoc.esochan.chans.dvachnet.DvachnetModule",
            "dev.esoc.esochan.chans.synch.SynchModule",
            "dev.esoc.esochan.chans.chan10.Chan10Module",
            "dev.esoc.esochan.chans.lainchan.LainModule",
            "dev.esoc.esochan.chans.tohnochan.TohnoChanModule",
            "dev.esoc.esochan.chans.dfwk.DFWKModule",
            "dev.esoc.esochan.chans.anonfm.AnonFmModule",
            "dev.esoc.esochan.chans.makaba.MakabaModule",
    };
    
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
    
    public List<ChanModule> chanModulesList;
    private Map<String, Integer> chanModulesIndex;
    
    private void registerChanModules() {
        chanModulesIndex = new HashMap<String, Integer>();
        chanModulesList = new ArrayList<ChanModule>();
        registerChanModules(chanModulesList, chanModulesIndex);
    }
    
    public void updateChanModulesOrder() {
        Map<String, ChanModule> instantiatedMap = new HashMap<>();
        for (ChanModule chan : chanModulesList) instantiatedMap.put(chan.getClass().getName(), chan);
        
        Map<String, Integer> indexMap = new HashMap<>();
        List<ChanModule> list = new ArrayList<>();
        registerChanModules(list, indexMap, instantiatedMap);
        chanModulesIndex = indexMap;
        chanModulesList = list;
    }
    
    private void registerChanModules(List<ChanModule> outList, Map<String, Integer> outIndexMap) {
        registerChanModules(outList, outIndexMap, null);
    }
    
    private void registerChanModules(List<ChanModule> outList, Map<String, Integer> outIndexMap, Map<String, ChanModule> instantiatedClassMap) {
        Set<String> added = new HashSet<>();
        JSONArray order;
        try {
            order = new JSONArray(settings.getChansOrderJson());
        } catch (Exception e) {
            order = new JSONArray();
        }
        for (int i=0; i<order.length(); ++i) {
            String module = order.optString(i);
            if (!added.contains(module)) {
                if (instantiatedClassMap != null && instantiatedClassMap.containsKey(module)) {
                    addChanModule(instantiatedClassMap.get(module), outList, outIndexMap);
                } else {
                    addChanModule(module, outList, outIndexMap);
                }
            }
            added.add(module);
        }
        for (String module : MODULES) {
            if (!added.contains(module)) {
                if (instantiatedClassMap != null && instantiatedClassMap.containsKey(module)) {
                    addChanModule(instantiatedClassMap.get(module), outList, outIndexMap);
                } else {
                    addChanModule(module, outList, outIndexMap);
                }
            }
            added.add(module);
        }
    }
    
    private void addChanModule(String className, List<ChanModule> list, Map<String, Integer> indexMap) {
        try {
            Class<?> c = Class.forName(className);
            addChanModule((ChanModule) c.getConstructor(SharedPreferences.class, Resources.class).newInstance(preferences, resources),
                    list, indexMap);
        } catch (Exception e) {}
    }
    
    private void addChanModule(ChanModule module, List<ChanModule> list, Map<String, Integer> indexMap) {
        indexMap.put(module.getChanName(), list.size());
        list.add(module);
    }
    
    public ChanModule getChanModule(String chanName) {
        if (!chanModulesIndex.containsKey(chanName)) return null;
        return chanModulesList.get(chanModulesIndex.get(chanName).intValue());
    }
    
    private void initObjects() {
        ExtendedTrustManager.setAppContext(this);
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
        
        registerChanModules();
        
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
        registerActivityLifecycleCallbacks(new EdgeToEdgeCallbacks());
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
     * On SDK 35+, Android enforces edge-to-edge rendering (content behind transparent
     * system bars). Restore the pre-35 default of fitting content within system bars
     * for all activities except GalleryActivity, which manages its own immersive mode.
     */
    private static class EdgeToEdgeCallbacks implements ActivityLifecycleCallbacks {
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
