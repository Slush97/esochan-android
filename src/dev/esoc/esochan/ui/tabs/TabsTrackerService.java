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

package dev.esoc.esochan.ui.tabs;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import dev.esoc.esochan.common.Tuples.Triple;

import dev.esoc.esochan.R;
import dev.esoc.esochan.api.ChanModule;
import dev.esoc.esochan.api.interfaces.CancellableTask;
import dev.esoc.esochan.api.interfaces.CancellableTask.BaseCancellableTask;
import dev.esoc.esochan.api.models.UrlPageModel;
import dev.esoc.esochan.api.util.PageLoaderFromChan;
import dev.esoc.esochan.cache.PagesCache;
import dev.esoc.esochan.cache.SerializablePage;
import dev.esoc.esochan.common.Async;
import dev.esoc.esochan.common.InternalBroadcasts;
import dev.esoc.esochan.common.Logger;
import dev.esoc.esochan.common.MainApplication;
import dev.esoc.esochan.http.interactive.InteractiveException;
import dev.esoc.esochan.ui.MainActivity;
import dev.esoc.esochan.ui.downloading.BackgroundThumbDownloader;
import dev.esoc.esochan.ui.presentation.BoardFragment;
import dev.esoc.esochan.ui.presentation.PresentationModel;
import dev.esoc.esochan.ui.settings.ApplicationSettings;
import dev.esoc.esochan.ui.settings.Wifi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

/**
 * Сервис автообновления
 * @author miku-nyan
 *
 */
public class TabsTrackerService extends Service {
    private static final String TAG = "TabsTrackerService";
    
    public static final String EXTRA_UPDATE_IMMEDIATELY = "UpdateImmediately";
    public static final String EXTRA_CLEAR_SUBSCRIPTIONS = "ClearSubscriptions";
    public static final String BROADCAST_ACTION_NOTIFY = "dev.esoc.esochan.BROADCAST_ACTION_TRACKER_NOTIFY";
    public static final String BROADCAST_ACTION_CLEAR_SUBSCRIPTIONS = "dev.esoc.esochan.BROADCAST_ACTION_CLEAR_SUBSCRIPTIONS";
    /** In-app toast for a reply to a tracked post while the UI is visible. */
    public static final String BROADCAST_ACTION_SUBSCRIPTION_REPLY = "dev.esoc.esochan.BROADCAST_ACTION_SUBSCRIPTION_REPLY";
    public static final String EXTRA_SUBSCRIPTION_TITLE = "SubscriptionTitle";
    public static final String EXTRA_SUBSCRIPTION_URL = "SubscriptionUrl";
    public static final int TRACKER_NOTIFICATION_UPDATE_ID = 40;
    public static final int TRACKER_NOTIFICATION_SUBSCRIPTIONS_ID = 50;
    /** Cap on how long the worker waits for a UI-thread tab snapshot before skipping the cycle. */
    private static final long SNAPSHOT_TIMEOUT_SECONDS = 5;
    
    /** true, если сервис сейчас работает */
    private static volatile boolean running = false;
    /** если true, в заголовке уведомления будет написано "есть новые сообщения" */
    private static boolean unread = false;
    /** если true, выведется уведомление об ответе на отслеживаемые посты */
    private static boolean subscriptions = false;
    /** список тредов, в которых есть ответы на отслеживаемые посты (triple: url вкладки, url со ссылкой на пост, заголовок вкладки) */
    private static List<Triple<String, String, String>> subscriptionsData = null;
    /** ID вкладки, которая обновляется в данный момент или -1 */
    private static volatile long currentUpdatingTabId = -1;

    /** Keep high-frequency tracking scoped to the visible main UI. */
    public static void syncWithVisibleUi(Context context) {
        if (MainActivity.isUiStarted()
                && MainApplication.getInstance().settings.isAutoupdateEnabled()) {
            context.startService(new Intent(context, TabsTrackerService.class));
        } else {
            context.stopService(new Intent(context, TabsTrackerService.class));
        }
    }

    /** Request one update while the main UI is visible. */
    public static void requestImmediateUpdate(Context context) {
        if (!MainActivity.isUiStarted()) return;
        context.startService(new Intent(context, TabsTrackerService.class)
                .putExtra(EXTRA_UPDATE_IMMEDIATELY, true));
    }
    
    /**
     * Record a reply to a tracked post.
     * Resumed UI: in-app toast. Started but not resumed: system notification.
     */
    public static void addSubscriptionNotification(String tabUrl, String postNumber, String tabTitle) {
        List<Triple<String, String, String>> list = subscriptionsData;
        if (list == null) list = new ArrayList<>();
        String postUrl = tabUrl;
        try {
            UrlPageModel pageModel = UrlHandler.getPageModel(tabUrl);
            if (pageModel != null) {
                pageModel.postNumber = postNumber;
                postUrl = MainApplication.getInstance().getChanModule(pageModel.chanName).buildUrl(pageModel);
            }
        } catch (Exception e) {
            Logger.e(TAG, e);
        }
        int index = findTab(list, tabUrl, tabTitle);
        if (index == -1) {
            list.add(Triple.of(tabUrl, postUrl, tabTitle));
        } else {
            list.set(index, Triple.of(tabUrl, postUrl, tabTitle));
        }
        subscriptionsData = list;
        if (MainActivity.isUiResumed()) {
            Context ctx = MainApplication.getInstance();
            Intent intent = InternalBroadcasts.intent(ctx, BROADCAST_ACTION_SUBSCRIPTION_REPLY);
            intent.putExtra(EXTRA_SUBSCRIPTION_TITLE, tabTitle);
            intent.putExtra(EXTRA_SUBSCRIPTION_URL, postUrl);
            InternalBroadcasts.send(ctx, intent);
        } else {
            subscriptions = true;
        }
    }
    
    /** установить флаг непрочитанных сообщений: в заголовке уведомления об автообновлении будет написано "есть новые сообщения" */
    public static void setUnread() {
        unread = true;
    }
    
    /** очистить состояние уведомления об автообновлении: убрать надпись "есть новые сообщения" */
    public static void clearUnread() {
        unread = false;
    }
    
    /** очистить список тредов, в которых есть ответы на отслеживаемые посты, в уведомлении об отслеживаемых
     *  (при этом, если уведомление об отслеживаемых на данный момент не было создано, оно не будет создано) */
    public static void clearSubscriptions() {
        subscriptions = false;
        subscriptionsData = null;
    }
    
    /** получить ID вкладки, которая обновляется в данный момент; вернёт -1, если обновление не выполняется в данный момент */
    public static long getCurrentUpdatingTabId() {
        return currentUpdatingTabId;
    }
    
    /** вызывается, когда открыта вкладка; если уведомление об отслеживаемых ссылается на эту вкладку, оно будет отменено */
    public static void onResumeTab(Context context, String tabUrl, String tabTitle) {
        List<Triple<String, String, String>> list = subscriptionsData;
        if (list == null) return;
        int index = findTab(list, tabUrl, tabTitle);
        if (index != -1) {
            ((NotificationManager) context.getSystemService(NOTIFICATION_SERVICE)).cancel(TRACKER_NOTIFICATION_SUBSCRIPTIONS_ID);
            clearSubscriptions();
        }
    }
    
    private static int findTab(List<Triple<String, String, String>> list, String tabUrl, String tabTitle) {
        for (int i=0; i<list.size(); ++i) {
            Triple<String, String, String> triple = list.get(i);
            if (tabUrl == null) {
                if (triple.getLeft() == null && tabTitle.equals(triple.getRight())) {
                    return i;
                }
            } else {
                if (tabUrl.equals(triple.getLeft())) {
                    return i;
                }
            }
        }
        return -1;
    }
    
    private ApplicationSettings settings;
    private TabsState tabsState;
    private TabsSwitcher tabsSwitcher;
    private PagesCache pagesCache;
    private NotificationManager notificationManager;
    private BroadcastReceiver broadcastReceiver;
    private volatile int timerDelay;
    private volatile boolean enableNotification;
    private volatile boolean backgroundTabs;
    private final AtomicBoolean updateImmediately = new AtomicBoolean(false);
    
    private CancellableTask task = null;
    /** The worker currently owning the service, or null; used to hand it the newest start id. */
    private TrackerLoop currentLoop = null;

    private static class TrackingSnapshot {
        final TabModel[] tabs;
        final Long selectedTabId;

        TrackingSnapshot(TabModel[] tabs, Long selectedTabId) {
            this.tabs = tabs;
            this.selectedTabId = selectedTabId;
        }
    }

    private void cancelUpdateNotification() {
        notificationManager.cancel(TRACKER_NOTIFICATION_UPDATE_ID);
    }

    private TrackingSnapshot getTrackingSnapshot() {
        FutureTask<TrackingSnapshot> snapshotTask = new FutureTask<>(() ->
                new TrackingSnapshot(
                        tabsState.snapshotTabs(),
                        tabsSwitcher.currentId));
        Async.runOnUiThread(snapshotTask);
        try {
            return snapshotTask.get(SNAPSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (TimeoutException e) {
            snapshotTask.cancel(false);
            Logger.e(TAG, "Timed out snapshotting tabs for auto-update; skipping this cycle");
            return null;
        } catch (ExecutionException e) {
            Logger.e(TAG, "Unable to snapshot tabs for auto-update", e);
            return null;
        }
    }
    
    @Override
    public void onCreate() {
        Logger.d(TAG, "TabsTrackerService creating");
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        settings = MainApplication.getInstance().settings;
        tabsState = MainApplication.getInstance().tabsState;
        tabsSwitcher = MainApplication.getInstance().tabsSwitcher;
        pagesCache = MainApplication.getInstance().pagesCache;
        ContextCompat.registerReceiver(this, broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Logger.d(TAG, "received BROADCAST_ACTION_CLEAR_SUBSCRIPTIONS");
                clearSubscriptions();
            }
        }, new IntentFilter(BROADCAST_ACTION_CLEAR_SUBSCRIPTIONS), ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.d(TAG, "TabsTrackerService starting");
        boolean immediate = intent != null && intent.getBooleanExtra(EXTRA_UPDATE_IMMEDIATELY, false);
        if (!MainActivity.isUiStarted() || (!settings.isAutoupdateEnabled() && !immediate)) {
            stopSelfResult(startId);
            return Service.START_NOT_STICKY;
        }

        enableNotification = settings.isAutoupdateNotification();
        backgroundTabs = settings.isAutoupdateBackground();
        timerDelay = settings.getAutoupdateDelay();
        if (!enableNotification) cancelUpdateNotification();
        if (immediate) updateImmediately.set(true);
        if (running) {
            Logger.d(TAG, "TabsTrackerService reconfigured while running");
            // The live worker absorbs this start; hand it the newest id so it can stop under it.
            if (currentLoop != null) currentLoop.startId = startId;
            return Service.START_NOT_STICKY;
        }
        clearUnread();
        clearSubscriptions();
        TrackerLoop loop = new TrackerLoop(startId);
        task = loop;
        currentLoop = loop;
        running = true;
        Async.runAsync(loop);
        return Service.START_NOT_STICKY;
    }
    
    private void doUpdate(final CancellableTask task, boolean immediate) {
        TrackingSnapshot snapshot = getTrackingSnapshot();
        if (snapshot == null || task.isCancelled()) return;

        if (backgroundTabs || immediate) {
            for (final TabModel tab : snapshot.tabs) {
                if (task.isCancelled()) return;
                if (settings.isAutoupdateWifiOnly() && !Wifi.isConnected() && !immediate) return;
                if (tab.type == TabModel.TYPE_NORMAL && tab.pageModel.type == UrlPageModel.TYPE_THREADPAGE && tab.autoupdateBackground) {
                    if (snapshot.selectedTabId != null && snapshot.selectedTabId.equals(tab.id)) continue;
                    final String hash = tab.hash;
                    ChanModule chan = MainApplication.getInstance().getChanModule(tab.pageModel.chanName);
                    currentUpdatingTabId = tab.id;
                    final PresentationModel presentationModel = pagesCache.getPresentationModel(hash);
                    final SerializablePage serializablePage;
                    if (presentationModel != null) {
                        serializablePage = presentationModel.source;
                    } else {
                        SerializablePage pageFromFilecache = pagesCache.getSerializablePage(hash);
                        if (pageFromFilecache != null) {
                            serializablePage = pageFromFilecache;
                        } else {
                            serializablePage = new SerializablePage();
                            serializablePage.pageModel = tab.pageModel;
                        }
                    }
                    
                    final int oldCount = serializablePage.posts != null ? serializablePage.posts.length : 0;
                    new PageLoaderFromChan(serializablePage, new PageLoaderFromChan.PageLoaderCallback() {
                        @Override
                        public void onSuccess() {
                            BackgroundThumbDownloader.download(serializablePage, task);
                            MainApplication.getInstance().subscriptions.checkOwnPost(serializablePage, oldCount);
                            tab.autoupdateError = false;
                            int newCount = serializablePage.posts != null ? serializablePage.posts.length : 0;
                            if (oldCount != newCount) {
                                if (oldCount != 0) tab.unreadPostsCount += (newCount - oldCount);
                                setUnread();
                                int checkSubscriptions = MainApplication.getInstance().subscriptions.checkSubscriptions(serializablePage, oldCount);
                                if (checkSubscriptions >= 0) {
                                    addSubscriptionNotification(tab.webUrl, serializablePage.posts[checkSubscriptions].number, tab.title);
                                    tab.unreadSubscriptions = true;
                                }
                            }
                            if (presentationModel != null) {
                                presentationModel.setNotReady();
                                pagesCache.putPresentationModel(hash, presentationModel);
                            } else {
                                pagesCache.putSerializablePage(hash, serializablePage);
                            }
                        }
                        @Override
                        public void onInteractiveException(InteractiveException e) {
                            tab.autoupdateError = true;
                        }
                        @Override
                        public void onError(String message) {
                            tab.autoupdateError = true;
                        }
                    }, chan, task).run();
                }
            }
            currentUpdatingTabId = -1;
        }
        if (task.isCancelled()) return;
        if (settings.isAutoupdateWifiOnly() && !Wifi.isConnected() && !immediate) return;
        Async.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Fragment currentFragment = tabsSwitcher.getCurrentFragment();
                if (task.isCancelled() || !MainActivity.isUiStarted()
                        || !(currentFragment instanceof BoardFragment)
                        || tabsSwitcher.currentId == null) {
                    return;
                }
                TabModel tab = tabsState.findTabById(tabsSwitcher.currentId);
                if (tab != null && tab.pageModel != null && tab.type == TabModel.TYPE_NORMAL
                        && tab.pageModel.type == UrlPageModel.TYPE_THREADPAGE) {
                    try {
                        ((BoardFragment) currentFragment).updateSilent();
                    } catch (Exception e) {
                        Logger.e(TAG, e);
                    }
                }
            }
        });
    }
    
    private class TrackerLoop extends BaseCancellableTask implements Runnable {
        private int timerCounter = 0;
        /** Newest start id this worker has absorbed; stops the service without tearing down a newer start. */
        volatile int startId;

        TrackerLoop(int startId) {
            this.startId = startId;
        }

        @Override
        public void run() {
            try {
                while (!isCancelled() && MainActivity.isUiStarted()) {
                    Notification subscriptionsNotification = getSubscriptionsNotification();
                    if (subscriptionsNotification != null) {
                        notificationManager.notify(TRACKER_NOTIFICATION_SUBSCRIPTIONS_ID, subscriptionsNotification);
                    }

                    boolean immediate = updateImmediately.getAndSet(false);
                    if (++timerCounter > timerDelay || immediate) {
                        timerCounter = 0;
                        if (enableNotification) {
                            notificationManager.notify(TRACKER_NOTIFICATION_UPDATE_ID, getUpdateNotification(-1));
                        }
                        if (!settings.isAutoupdateWifiOnly() || Wifi.isConnected() || immediate) {
                            doUpdate(this, immediate);
                        }

                        if (isCancelled() || !MainActivity.isUiStarted()) {
                            return;
                        }
                        InternalBroadcasts.send(TabsTrackerService.this, BROADCAST_ACTION_NOTIFY);

                        if (!settings.isAutoupdateEnabled()) {
                            return;
                        }
                    } else {
                        if (enableNotification) {
                            int remainingTime = timerDelay - timerCounter + 1;
                            notificationManager.notify(
                                    TRACKER_NOTIFICATION_UPDATE_ID,
                                    getUpdateNotification(remainingTime));
                        }
                    }

                    LockSupport.parkNanos(1000000000);
                }
            } finally {
                currentUpdatingTabId = -1;
                running = false;
                cancelUpdateNotification();
                // stopSelf(startId) no-ops if a newer start arrived, so we never cancel a fresh loop.
                stopSelf(startId);
            }
        }
        
        //если secondsRemaining == -1, текст будет "выполняется обновление"
        private Notification getUpdateNotification(int secondsRemaining) {
            return notifUpdate.
                    setContentTitle(getString(unread ? R.string.tabs_tracker_title_unread : R.string.tabs_tracker_title)).
                    setContentText(secondsRemaining == -1 ? getString(R.string.tabs_tracker_updating) :
                        getResources().getQuantityString(R.plurals.tabs_tracker_timer, secondsRemaining, secondsRemaining)).
                    build();
        }
        
        private Notification getSubscriptionsNotification() {
            if (!subscriptions) return null;
            subscriptions = false;
            List<Triple<String, String, String>> list = subscriptionsData;
            if (list == null || list.size() == 0) return null;
            String url = list.get(0).getMiddle();
            Intent activityIntent = new Intent(TabsTrackerService.this, MainActivity.class).putExtra(EXTRA_CLEAR_SUBSCRIPTIONS, true);
            if (url != null) activityIntent.setData(Uri.parse(url));
            NotificationCompat.InboxStyle style = list.size() == 1 ? null : new NotificationCompat.InboxStyle().
                    addLine(getString(R.string.subscriptions_notification_text_format, list.get(0).getRight())).
                    addLine(getString(R.string.subscriptions_notification_text_format, list.get(1).getRight()));
            if (list.size() > 2) style.setSummaryText(getString(R.string.subscriptions_notification_text_more, list.size() - 2));
            
            return notifSubscription.
                    setContentText(list.size() > 1 ?
                            getString(R.string.subscriptions_notification_text_multiple) :
                                getString(R.string.subscriptions_notification_text_format, list.get(0).getRight())).
                    setStyle(style).
                    setContentIntent(PendingIntent.getActivity(TabsTrackerService.this, 0, activityIntent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE)).
                    build();
        }
        
        private NotificationCompat.Builder notifUpdate = new NotificationCompat.Builder(TabsTrackerService.this, "tabs_tracker").
                setSmallIcon(R.mipmap.ic_launcher).
                setOngoing(true).
                setCategory(NotificationCompat.CATEGORY_SERVICE).
                setContentIntent(PendingIntent.getActivity(
                        TabsTrackerService.this, 0, new Intent(TabsTrackerService.this, MainActivity.class), PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        
        
        private NotificationCompat.Builder notifSubscription = new NotificationCompat.Builder(TabsTrackerService.this, "tabs_tracker").
                setSmallIcon(R.mipmap.ic_launcher).
                setDefaults(NotificationCompat.DEFAULT_ALL).
                setOngoing(false).
                setAutoCancel(true).
                setOnlyAlertOnce(true).
                setCategory(NotificationCompat.CATEGORY_MESSAGE).
                setContentTitle(getString(R.string.subscriptions_notification_title)).
                setDeleteIntent(PendingIntent.getBroadcast(
                        TabsTrackerService.this, 0, new Intent(BROADCAST_ACTION_CLEAR_SUBSCRIPTIONS), PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        
    }
    
    @Override
    public void onDestroy() {
        Logger.d(TAG, "TabsTrackerService destroying");
        if (task != null) task.cancel();
        running = false;
        currentUpdatingTabId = -1;
        cancelUpdateNotification();
        unregisterReceiver(broadcastReceiver);
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
}
