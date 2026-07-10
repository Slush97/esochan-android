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

package dev.esoc.esochan.ui.gallery;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.esoc.esochan.common.Tuples.Triple;

import dev.esoc.esochan.R;
import dev.esoc.esochan.databinding.GalleryLayoutBinding;
import dev.esoc.esochan.databinding.GalleryLayoutFullscreenBinding;
import dev.esoc.esochan.api.interfaces.CancellableTask;
import dev.esoc.esochan.api.interfaces.ProgressListener;
import dev.esoc.esochan.api.models.AttachmentModel;
import dev.esoc.esochan.api.models.BoardModel;
import dev.esoc.esochan.common.Async;
import dev.esoc.esochan.common.Logger;
import dev.esoc.esochan.lib.gallery.FixedSubsamplingScaleImageView;
import dev.esoc.esochan.lib.gallery.JSWebView;
import dev.esoc.esochan.lib.gallery.Jpeg;
import dev.esoc.esochan.lib.gallery.TouchGifView;
import dev.esoc.esochan.lib.gallery.WebViewFixed;
import dev.esoc.esochan.lib.gallery.verticalviewpager.VerticalViewPagerFixed;
import dev.esoc.esochan.lib.gifdrawable.GifDrawable;
import dev.esoc.esochan.ui.AppearanceUtils;
import dev.esoc.esochan.ui.Attachments;
import dev.esoc.esochan.ui.ReverseImageSearch;
import dev.esoc.esochan.ui.downloading.DownloadStorage;
import dev.esoc.esochan.ui.downloading.DownloadingService;
import dev.esoc.esochan.ui.presentation.BoardFragment;
import dev.esoc.esochan.ui.settings.ApplicationSettings;
import dev.esoc.esochan.ui.tabs.UrlHandler;
import dev.esoc.esochan.ui.theme.ThemeUtils;
import android.annotation.SuppressLint;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import androidx.core.view.MotionEventCompat;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Matrix;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import androidx.core.content.FileProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

public class GalleryActivity extends AppCompatActivity implements View.OnClickListener {
    private static final String TAG = "GalleryActivity";
    
    public static final String EXTRA_SETTINGS = "settings";
    public static final String EXTRA_ATTACHMENT = "attachment";
    public static final String EXTRA_SAVED_ATTACHMENTHASH = "attachmenthash";
    public static final String EXTRA_BOARDMODEL = "boardmodel";
    public static final String EXTRA_PAGEHASH = "pagehash";
    public static final String EXTRA_LOCALFILENAME = "localfilename";
    
    @SuppressLint("InlinedApi")
    private static final int BINDING_FLAGS = Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT;
    
    private static final int REQUEST_HANDLE_INTERACTIVE_EXCEPTION = 1;
    
    private LayoutInflater inflater;
    private ExecutorService tnDownloadingExecutor;
    
    private BoardModel boardModel;
    private String chan;
    
    private GalleryLayoutBinding binding;
    private GalleryLayoutFullscreenBinding bindingFullscreen;
    private ProgressBar progressBar;
    private ViewPager viewPager;
    private TextView navigationInfo;
    private SparseArray<View> instantiatedViews;
    
    private BroadcastReceiver broadcastReceiver;
    private BroadcastReceiver downloadReportReceiver;
    private ServiceConnection serviceConnection;
    private GalleryRemote remote;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Bundle backendRestoreState;
    private boolean backendInitialized;
    private boolean serviceBound;
    private boolean destroyed;
    
    private GallerySettings settings;
    private List<Triple<AttachmentModel, String, String>> attachments = null;
    private int currentPosition = 0;
    private int previousPosition = -1;
    
    private boolean firstScroll = true;
    
    private Menu menu;
    private boolean currentLoaded;
    
    private static class ProgressHandler extends Handler {
        private final WeakReference<GalleryActivity> reference;
        
        public ProgressHandler(GalleryActivity activity) {
            reference = new WeakReference<GalleryActivity>(activity);
        }
        
        @Override
        public void handleMessage(Message msg) {
            GalleryActivity activity = reference.get();
            if (activity == null || activity.destroyed || activity.progressBar == null) return;
            int progress = msg.arg1;
            if (progress != Window.PROGRESS_END) {
                if (activity.progressBar.getVisibility() == View.GONE) activity.progressBar.setVisibility(View.VISIBLE);
                activity.progressBar.setProgress(progress);
            } else {
                if (activity.progressBar.getVisibility() == View.VISIBLE) activity.progressBar.setVisibility(View.GONE);
            }
        }
    }
    
    private ProgressListener progressListener = new ProgressListener() {
        private long maxValue = Window.PROGRESS_END;
        private Handler progressHandler = new ProgressHandler(GalleryActivity.this);
        
        @Override
        public void setProgress(final long value) {
            progressHandler.obtainMessage(0, (int)(Window.PROGRESS_END * value / maxValue), 0).sendToTarget();
        }
        
        @Override
        public void setMaxValue(long value) {
            if (value > 0) maxValue = value;
        }
        
        @Override
        public void setIndeterminate() {
        }
        
    };
    
    private void hideProgress() {
        progressListener.setMaxValue(1);
        progressListener.setProgress(1);
    }
    
    private abstract class AbstractGetterCallback extends GalleryGetterCallback.Stub {
        private final CancellableTask task;
        public AbstractGetterCallback(CancellableTask task) {
            this.task = task;
        }
        @Override
        public boolean isTaskCancelled() throws RemoteException {
            return task.isCancelled();
        }
        @Override
        public void setProgress(long value) throws RemoteException {
            progressListener.setProgress(value);
        }
        @Override
        public void setProgressIndeterminate() throws RemoteException {
            progressListener.setIndeterminate();
        }
        @Override
        public void setProgressMaxValue(long value) throws RemoteException {
            progressListener.setMaxValue(value);
        }
    }
    
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        settings = getIntent().getParcelableExtra(EXTRA_SETTINGS);
        if (settings == null) settings = GallerySettings.fromSettings(
                new ApplicationSettings(PreferenceManager.getDefaultSharedPreferences(getApplication()), getResources()));
        settings.getTheme().setTo(this, R.style.Transparent);
        super.onCreate(savedInstanceState);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) actionBar.setDisplayShowHomeEnabled(false);
        
        inflater = getLayoutInflater();
        instantiatedViews = new SparseArray<View>();
        tnDownloadingExecutor = Executors.newFixedThreadPool(4, Async.LOW_PRIORITY_FACTORY);
        
        if (settings.fullscreenGallery()) {
            bindingFullscreen = GalleryLayoutFullscreenBinding.inflate(getLayoutInflater());
            setContentView(bindingFullscreen.getRoot());
            GalleryFullscreen.initFullscreen(this);
            viewPager = bindingFullscreen.galleryViewpager;
            navigationInfo = bindingFullscreen.galleryNavigationInfo;
            bindingFullscreen.galleryNavigationPrevious.setOnClickListener(this);
            bindingFullscreen.galleryNavigationNext.setOnClickListener(this);
        } else {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
            binding = GalleryLayoutBinding.inflate(getLayoutInflater());
            setContentView(binding.getRoot());
            viewPager = binding.galleryViewpager;
            navigationInfo = binding.galleryNavigationInfo;
            binding.galleryNavigationPrevious.setOnClickListener(this);
            binding.galleryNavigationNext.setOnClickListener(this);
        }

        progressBar = (ProgressBar) findViewById(android.R.id.progress);
        progressBar.setMax(Window.PROGRESS_END);

        backendRestoreState = savedInstanceState == null ? null : new Bundle(savedInstanceState);
        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                initializeBackend(GalleryBinder.Stub.asInterface(service));
            }
            
            @Override
            public void onServiceDisconnected(ComponentName name) {
                Logger.e(TAG, "backend service disconnected");
                handleBackendUnavailable();
            }

            @Override
            public void onBindingDied(ComponentName name) {
                Logger.e(TAG, "backend service binding died");
                handleBackendUnavailable();
                rebindGalleryBackend();
            }

            @Override
            public void onNullBinding(ComponentName name) {
                Logger.e(TAG, "backend service returned a null binding");
                handleBackendUnavailable();
                rebindGalleryBackend();
            }
        };
        bindGalleryBackend();
    }

    private void bindGalleryBackend() {
        if (destroyed || serviceBound) return;
        try {
            serviceBound = bindService(new Intent(this, GalleryBackend.class), serviceConnection, BINDING_FLAGS);
            if (!serviceBound) handleBackendUnavailable();
        } catch (Exception e) {
            Logger.e(TAG, "cannot bind gallery backend", e);
            handleBackendUnavailable();
        }
    }

    private void rebindGalleryBackend() {
        if (serviceBound) {
            try {
                unbindService(serviceConnection);
            } catch (Exception e) {
                Logger.e(TAG, "cannot unbind dead gallery backend", e);
            }
            serviceBound = false;
        }
        mainHandler.removeCallbacks(rebindBackendRunnable);
        mainHandler.postDelayed(rebindBackendRunnable, 500);
    }

    private final Runnable rebindBackendRunnable = new Runnable() {
        @Override
        public void run() {
            bindGalleryBackend();
        }
    };

    private void initializeBackend(GalleryBinder galleryBinder) {
        if (destroyed || galleryBinder == null) return;
        try {
            Bundle restoreState = backendRestoreState;
            if (backendInitialized && attachments != null && !attachments.isEmpty()) {
                restoreState = new Bundle();
                restoreState.putString(EXTRA_SAVED_ATTACHMENTHASH,
                        attachments.get(Math.min(currentPosition, attachments.size() - 1)).getMiddle());
            }
            GalleryInitData initData = new GalleryInitData(getIntent(), restoreState);
            boardModel = initData.boardModel;
            chan = boardModel.chan;
            int contextId = galleryBinder.initContext(initData);
            if (contextId < 0) throw new RemoteException("Gallery backend did not create a context");

            GalleryRemote connectedRemote = new GalleryRemote(galleryBinder, contextId);
            GalleryInitResult initResult = connectedRemote.getInitResult();
            remote = connectedRemote;

            if (!backendInitialized) {
                if (initResult != null) {
                    attachments = initResult.attachments;
                    currentPosition = initResult.initPosition;
                    if (initResult.shouldWaitForPageLoaded) waitForPageLoaded(backendRestoreState);
                } else {
                    attachments = Collections.singletonList(
                            Triple.of(initData.attachment, initData.attachmentHash, (String)null));
                    currentPosition = 0;
                }
                backendInitialized = true;
                backendRestoreState = null;
                viewPager.setAdapter(new GalleryAdapter());
                viewPager.setCurrentItem(currentPosition);
                viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
                    @Override
                    public void onPageSelected(int position) {
                        currentPosition = position;
                        updateItem();
                    }
                });
            } else {
                GalleryItemViewTag currentTag = getCurrentTag();
                if (currentTag != null && currentTag.file == null) updateItem();
            }
        } catch (RemoteException e) {
            Logger.e(TAG, "cannot initialize gallery backend", e);
            handleBackendUnavailable();
            rebindGalleryBackend();
        } catch (Exception e) {
            Logger.e(TAG, "invalid gallery initialization data", e);
            Toast.makeText(this, R.string.error_unknown, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void handleBackendUnavailable() {
        remote = null;
        if (destroyed) return;
        hideProgress();
        Toast.makeText(this, R.string.error_connection, Toast.LENGTH_SHORT).show();
        if (instantiatedViews == null) return;
        for (int i = 0; i < instantiatedViews.size(); ++i) {
            View view = instantiatedViews.valueAt(i);
            if (view == null) continue;
            Object value = view.getTag();
            if (value instanceof GalleryItemViewTag) {
                GalleryItemViewTag tag = (GalleryItemViewTag) value;
                if (tag.file == null && tag.downloadingTask != null) {
                    tag.downloadingTask.cancel();
                    showErrorView(tag, getString(R.string.error_connection));
                }
            }
        }
    }
    
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (attachments != null && !attachments.isEmpty()) {
            outState.putString(EXTRA_SAVED_ATTACHMENTHASH,
                    attachments.get(Math.min(currentPosition, attachments.size() - 1)).getMiddle());
        }
    }
    
    private void waitForPageLoaded(Bundle savedInstanceState) {
        final String savedHash = savedInstanceState != null ? savedInstanceState.getString(EXTRA_SAVED_ATTACHMENTHASH) : null;
        if (savedHash != null) {
            broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction() != null && intent.getAction().equals(BoardFragment.BROADCAST_PAGE_LOADED)) {
                    unregisterReceiver(this);
                    broadcastReceiver = null;
                    
                    Intent activityIntent = getIntent();
                    String pagehash = activityIntent.getStringExtra(EXTRA_PAGEHASH);
                    GalleryRemote currentRemote = remote;
                    if (pagehash != null && currentRemote != null && currentRemote.isPageLoaded(pagehash)) {
                        startActivity(activityIntent.putExtra(EXTRA_SAVED_ATTACHMENTHASH, savedHash));
                        finish();
                    }
                }
            }
            };
            ContextCompat.registerReceiver(this, broadcastReceiver,
                    new IntentFilter(BoardFragment.BROADCAST_PAGE_LOADED), ContextCompat.RECEIVER_NOT_EXPORTED);
        }
    }
    
    @Override
    protected void onStart() {
        super.onStart();
        downloadReportReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getIntExtra(DownloadingService.EXTRA_DOWNLOADING_REPORT, DownloadingService.REPORT_NONE)
                        == DownloadingService.REPORT_OK) {
                    Toast.makeText(GalleryActivity.this, R.string.notification_download_saved, Toast.LENGTH_SHORT).show();
                }
            }
        };
        ContextCompat.registerReceiver(this, downloadReportReceiver,
                new IntentFilter(DownloadingService.BROADCAST_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED);
        resumePlayback();
    }

    @Override
    protected void onStop() {
        if (downloadReportReceiver != null) {
            unregisterReceiver(downloadReportReceiver);
            downloadReportReceiver = null;
        }
        pausePlayback();
        super.onStop();
    }
    
    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        if (broadcastReceiver != null) {
            try {
                unregisterReceiver(broadcastReceiver);
            } catch (IllegalArgumentException e) {
                Logger.e(TAG, "page receiver was already unregistered", e);
            }
            broadcastReceiver = null;
        }
        if (instantiatedViews != null) {
            for (int i=0; i<instantiatedViews.size(); ++i) {
                View v = instantiatedViews.valueAt(i);
                if (v != null) {
                    Object tag = v.getTag();
                    if (tag != null && tag instanceof GalleryItemViewTag) {
                        recycleTag((GalleryItemViewTag) tag, true);
                    }
                }
            }
            instantiatedViews.clear();
        }
        if (tnDownloadingExecutor != null) tnDownloadingExecutor.shutdownNow();
        if (serviceBound && serviceConnection != null) {
            try {
                unbindService(serviceConnection);
            } catch (Exception e) {
                Logger.e(TAG, "cannot unbind gallery backend", e);
            }
            serviceBound = false;
        }
        remote = null;
        binding = null;
        bindingFullscreen = null;
        progressBar = null;
        viewPager = null;
        navigationInfo = null;
        super.onDestroy();
    }
    
    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.gallery_navigation_previous:
                if (currentPosition > 0) {
                    viewPager.setCurrentItem(--currentPosition);
                    updateItem();
                }
                break;
            case R.id.gallery_navigation_next:
                if (currentPosition < attachments.size() - 1) {
                    viewPager.setCurrentItem(++currentPosition);
                    updateItem();
                }
                break;
        }
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        this.menu = menu;
        MenuItem itemUpdate = menu.add(Menu.NONE, R.id.menu_update, 1, R.string.menu_update);
        MenuItem itemSave = menu.add(Menu.NONE, R.id.menu_save_attachment, 2, R.string.menu_save_attachment);
        itemUpdate.setIcon(ThemeUtils.getTintedIcon(getTheme(), getResources(), R.drawable.ic_action_refresh, R.attr.iconTint));
        itemSave.setIcon(ThemeUtils.getTintedIcon(getTheme(), getResources(), R.drawable.ic_action_save, R.attr.iconTint));
        itemUpdate.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        itemSave.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(Menu.NONE, R.id.menu_open_external, 3, R.string.menu_open).setIcon(
                ThemeUtils.getTintedIcon(getTheme(), getResources(), R.drawable.ic_menu_open_external, R.attr.iconTint));
        menu.add(Menu.NONE, R.id.menu_share, 4, R.string.menu_share).setIcon(
                ThemeUtils.getTintedIcon(getTheme(), getResources(), R.drawable.ic_menu_share, R.attr.iconTint));
        menu.add(Menu.NONE, R.id.menu_share_link, 5, R.string.menu_share_link).setIcon(
                ThemeUtils.getTintedIcon(getTheme(), getResources(), R.drawable.ic_menu_share, R.attr.iconTint));
        menu.add(Menu.NONE, R.id.menu_reverse_search, 6, R.string.menu_reverse_search).setIcon(
                ThemeUtils.getTintedIcon(getTheme(), getResources(), R.drawable.ic_menu_search, R.attr.iconTint));
        menu.add(Menu.NONE, R.id.menu_open_browser, 7, R.string.menu_open_browser).setIcon(
                ThemeUtils.getTintedIcon(getTheme(), getResources(), R.drawable.ic_menu_browser, R.attr.iconTint));
        updateMenu();
        
        return true;
    }
    
    private void updateMenu() {
        if (this.menu == null) return;
        View current = instantiatedViews.get(currentPosition);
        if (current == null) {
            Logger.e(TAG, "VIEW == NULL");
            return;
        }
        GalleryItemViewTag tag = (GalleryItemViewTag) current.getTag();
        boolean externalVideo = tag.attachmentModel.type == AttachmentModel.TYPE_VIDEO && settings.doNotDownloadVideos();
        menu.findItem(R.id.menu_update).setVisible(!currentLoaded);
        menu.findItem(R.id.menu_save_attachment).setVisible(externalVideo ||
                (currentLoaded && tag.attachmentModel.type != AttachmentModel.TYPE_OTHER_NOTFILE));
        menu.findItem(R.id.menu_open_external).setVisible(currentLoaded && (tag.attachmentModel.type == AttachmentModel.TYPE_OTHER_FILE ||
                tag.attachmentModel.type == AttachmentModel.TYPE_AUDIO || tag.attachmentModel.type == AttachmentModel.TYPE_VIDEO));
        menu.findItem(R.id.menu_open_external).setTitle(tag.attachmentModel.type != AttachmentModel.TYPE_OTHER_FILE ?
                R.string.menu_open_player : R.string.menu_open);
        menu.findItem(R.id.menu_share).setVisible(currentLoaded && tag.attachmentModel.type != AttachmentModel.TYPE_OTHER_NOTFILE);
        menu.findItem(R.id.menu_reverse_search).setVisible(
                tag.attachmentModel.type == AttachmentModel.TYPE_IMAGE_STATIC || tag.attachmentModel.type == AttachmentModel.TYPE_IMAGE_GIF);
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_update:
                updateItem();
                return true;
            case R.id.menu_save_attachment:
                downloadAttachment();
                return true;
            case R.id.menu_open_external:
                openExternal();
                return true;
            case R.id.menu_share:
                share();
                return true;
            case R.id.menu_share_link:
                shareLink();
                return true;
            case R.id.menu_reverse_search:
                reverseSearch();
                return true;
            case R.id.menu_open_browser:
                openBrowser();
                return true;
        }
        return false;
    }
    
    private GalleryItemViewTag getCurrentTag() {
        View current = instantiatedViews.get(currentPosition);
        if (current == null) {
            Logger.e(TAG, "VIEW == NULL (position=" + currentPosition + ")");
            return null;
        }
        return (GalleryItemViewTag) current.getTag();
    }
    
    private void downloadAttachment() {
        GalleryItemViewTag tag = getCurrentTag();
        if (tag == null) return;
        DownloadingService.DownloadingQueueItem queueItem = new DownloadingService.DownloadingQueueItem(tag.attachmentModel, boardModel);
        String fileName = Attachments.getAttachmentLocalFileName(tag.attachmentModel, boardModel);
        String itemName = Attachments.getAttachmentLocalShortName(tag.attachmentModel, boardModel);
        if (DownloadingService.isInQueue(queueItem)) {
            Toast.makeText(this, getString(R.string.notification_download_already_in_queue, itemName), Toast.LENGTH_LONG).show();
        } else {
            if (DownloadStorage.fileExists(this, chan, null, fileName)) {
                Toast.makeText(this, getString(R.string.notification_download_already_exists, fileName), Toast.LENGTH_LONG).show();
            } else {
                Intent downloadIntent = new Intent(this, DownloadingService.class);
                downloadIntent.putExtra(DownloadingService.EXTRA_DOWNLOADING_ITEM, queueItem);
                startService(downloadIntent);
                Toast.makeText(this, getString(R.string.notification_download_started, itemName), Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    private Uri getFileUri(File file) {
        return FileProvider.getUriForFile(this, "dev.esoc.esochan.fileprovider", file);
    }

    private void openExternal() {
        GalleryItemViewTag tag = getCurrentTag();
        if (tag == null) return;
        String mime;
        switch (tag.attachmentModel.type) {
            case AttachmentModel.TYPE_VIDEO:
                mime = "video/*";
                break;
            case AttachmentModel.TYPE_AUDIO:
                mime = "audio/*";
                break;
            default:
                mime = "*/*";
                break;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(getFileUri(tag.file), mime);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }
    
    private void share() {
        GalleryItemViewTag tag = getCurrentTag();
        if (tag == null) return;
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        String extension = Attachments.getAttachmentExtention(tag.attachmentModel);
        switch (tag.attachmentModel.type) {
            case AttachmentModel.TYPE_IMAGE_GIF:
                shareIntent.setType("image/gif");
                break;
            case AttachmentModel.TYPE_IMAGE_SVG:
                shareIntent.setType("image/svg+xml");
                break;
            case AttachmentModel.TYPE_IMAGE_STATIC:
                if (extension.equalsIgnoreCase(".png")) {
                    shareIntent.setType("image/png");
                } else if (extension.equalsIgnoreCase(".jpg") || extension.equalsIgnoreCase(".jpeg")) {
                    shareIntent.setType("image/jpeg");
                } else if (extension.equalsIgnoreCase(".webp")) {
                    shareIntent.setType("image/webp");
                } else {
                    shareIntent.setType("image/*");
                }
                break;
            case AttachmentModel.TYPE_VIDEO:
                if (extension.equalsIgnoreCase(".mp4") || extension.equalsIgnoreCase(".m4v")) {
                    shareIntent.setType("video/mp4");
                } else if (extension.equalsIgnoreCase(".webm")) {
                    shareIntent.setType("video/webm");
                } else if (extension.equalsIgnoreCase(".avi")) {
                    shareIntent.setType("video/avi");
                } else if (extension.equalsIgnoreCase(".mov")) {
                    shareIntent.setType("video/quicktime");
                } else if (extension.equalsIgnoreCase(".mkv")) {
                    shareIntent.setType("video/x-matroska");
                } else if (extension.equalsIgnoreCase(".flv")) {
                    shareIntent.setType("video/x-flv");
                } else if (extension.equalsIgnoreCase(".wmv")) {
                    shareIntent.setType("video/x-ms-wmv");
                } else if (extension.equalsIgnoreCase(".gifv")) {
                    shareIntent.setType("video/mp4");
                } else {
                    shareIntent.setType("video/*");
                }
                break;
            case AttachmentModel.TYPE_AUDIO:
                if (extension.equalsIgnoreCase(".mp3")) {
                    shareIntent.setType("audio/mpeg");
                } else if (extension.equalsIgnoreCase(".mp4")) {
                    shareIntent.setType("audio/mp4");
                } else if (extension.equalsIgnoreCase(".ogg")) {
                    shareIntent.setType("audio/ogg");
                } else if (extension.equalsIgnoreCase(".webm")) {
                    shareIntent.setType("audio/webm");
                } else if (extension.equalsIgnoreCase(".flac")) {
                    shareIntent.setType("audio/flac");
                } else if (extension.equalsIgnoreCase(".wav")) {
                    shareIntent.setType("audio/vnd.wave");
                } else if (extension.equalsIgnoreCase(".opus")) {
                    shareIntent.setType("audio/opus");
                } else if (extension.equalsIgnoreCase(".m4a")) {
                    shareIntent.setType("audio/mp4");
                } else if (extension.equalsIgnoreCase(".aac")) {
                    shareIntent.setType("audio/aac");
                } else {
                    shareIntent.setType("audio/*");
                }
                break;
            case AttachmentModel.TYPE_OTHER_FILE:
                shareIntent.setType("application/octet-stream");
                break;
        }
        Logger.d(TAG, shareIntent.getType());
        shareIntent.putExtra(Intent.EXTRA_STREAM, getFileUri(tag.file));
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_via)));
    }
    
    private void shareLink() {
        GalleryItemViewTag tag = getCurrentTag();
        if (tag == null) return;
        GalleryRemote currentRemote = getConnectedBackend();
        if (currentRemote == null) return;
        String absoluteUrl = currentRemote.getAbsoluteUrl(tag.attachmentModel.path);
        if (absoluteUrl == null) return;
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, absoluteUrl);
        shareIntent.putExtra(Intent.EXTRA_TEXT, absoluteUrl);
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_via)));
    }
    
    private void reverseSearch() {
        GalleryItemViewTag tag = getCurrentTag();
        if (tag == null) return;
        GalleryRemote currentRemote = getConnectedBackend();
        if (currentRemote == null) return;
        String absoluteUrl = currentRemote.getAbsoluteUrl(tag.attachmentModel.path);
        if (absoluteUrl == null) return;
        ReverseImageSearch.openDialog(this, absoluteUrl);
    }
    
    private void openBrowser() {
        GalleryItemViewTag tag = getCurrentTag();
        if (tag == null) return;
        GalleryRemote currentRemote = getConnectedBackend();
        if (currentRemote == null) return;
        String absoluteUrl = currentRemote.getAbsoluteUrl(tag.attachmentModel.path);
        if (absoluteUrl == null) return;
        UrlHandler.launchExternalBrowser(this, absoluteUrl);
    }

    private GalleryRemote getConnectedBackend() {
        GalleryRemote currentRemote = remote;
        if (currentRemote == null && !destroyed) {
            Toast.makeText(this, R.string.error_connection, Toast.LENGTH_SHORT).show();
        }
        return currentRemote;
    }
    
    private class GalleryAdapter extends PagerAdapter {
        private boolean firstTime = true;
        private final Runnable finishCallback = new Runnable() {
            @Override
            public void run() {
                GalleryActivity.this.finish();
            }
        };
        
        @Override
        public int getCount() {
            return attachments.size();
        }
        
        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }
        
        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            View v = inflater.inflate(R.layout.gallery_item, container, false);
            GalleryItemViewTag tag = new GalleryItemViewTag();
            tag.attachmentModel = attachments.get(position).getLeft();
            tag.attachmentHash = attachments.get(position).getMiddle();
            tag.thumbnailView = (ImageView) v.findViewById(R.id.gallery_thumbnail_preview);
            
            int tnWidth = Math.min(container.getMeasuredWidth(), tag.attachmentModel.width * 2);
            if (tnWidth > 0) tag.thumbnailView.getLayoutParams().width = tnWidth;
            
            tag.layout = (FrameLayout) v.findViewById(R.id.gallery_item_layout);
            tag.errorView = v.findViewById(R.id.gallery_error);
            tag.errorText = (TextView) tag.errorView.findViewById(R.id.frame_error_text);
            tag.errorText.setTextColor(Color.WHITE);
            tag.loadingView = v.findViewById(R.id.gallery_loading);
            v.setTag(tag);
            instantiatedViews.put(position, v);
            
            String hash = tag.attachmentHash;
            GalleryRemote currentRemote = remote;
            Bitmap bmp = currentRemote == null ? null : currentRemote.getBitmapFromMemory(hash);
            if (bmp != null) {
                tag.thumbnailView.setImageBitmap(bmp);
            } else {
                tnDownloadingExecutor.execute(new AsyncThumbnailDownloader(position, hash, tag.attachmentModel.thumbnail));
            }
            if (settings.swipeToCloseGallery()) v = VerticalViewPagerFixed.wrap(v, finishCallback, settings.fullscreenGallery());
            container.addView(v);
            if (firstTime && position == currentPosition) {
                updateItem();
                firstTime = false;
            }
            return v;
        }
        
        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            View v = (View) object;
            Object tag = v.getTag();
            if (tag != null && tag instanceof View) tag = ((View) tag).getTag();
            if (tag != null && tag instanceof GalleryItemViewTag) recycleTag((GalleryItemViewTag) tag, true);
            container.removeView(v);
            instantiatedViews.delete(position);
        }
        
        private class AsyncThumbnailDownloader implements Runnable {
            private final int position;
            private final String hash;
            private final String url;
            
            public AsyncThumbnailDownloader(int position, String hash, String url) {
                this.position = position;
                this.hash = hash;
                this.url = url;
            }
            
            @Override
            public void run() {
                GalleryRemote currentRemote = remote;
                if (currentRemote == null) return;
                Bitmap bmp = currentRemote.getBitmap(hash, url);
                if (bmp != null) {
                    View v = instantiatedViews.get(position);
                    if (v != null) {
                        final ImageView tnView = ((GalleryItemViewTag) v.getTag()).thumbnailView;
                        final Bitmap bmpSet = bmp;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (tnView != null) {
                                    tnView.setImageBitmap(bmpSet);
                                }
                            }
                        });
                    }
                }
            }
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_HANDLE_INTERACTIVE_EXCEPTION && resultCode == RESULT_OK) updateItem();
    }
    
    private void updateItem() {
        if (attachments == null || attachments.isEmpty()) return;
        AttachmentModel attachment = attachments.get(currentPosition).getLeft();
        GalleryRemote currentRemote = remote;
        if (settings.scrollThreadFromGallery() && !firstScroll && currentRemote != null) {
            currentRemote.tryScrollParent(attachments.get(currentPosition).getRight());
        }
        firstScroll = false;
        String navText = attachment.size == -1 ? (currentPosition + 1) + "/" + attachments.size() :
                (currentPosition + 1) + "/" + attachments.size() + " (" + Attachments.getAttachmentSizeString(attachment, getResources()) + ")";
        navigationInfo.setText(navText);
        setTitle(Attachments.getAttachmentDisplayName(attachment));
        
        if (previousPosition != -1) {
            View previous = instantiatedViews.get(previousPosition);
            if (previous != null) {
                GalleryItemViewTag tag = (GalleryItemViewTag) previous.getTag();
                tag.thumbnailView.setVisibility(View.VISIBLE);
                tag.layout.setVisibility(View.GONE);
                tag.errorView.setVisibility(View.GONE);
                tag.loadingView.setVisibility(View.GONE);
                recycleTag(tag, true);
            }
        }
        previousPosition = currentPosition;
        
        GalleryItemViewTag tag = getCurrentTag();
        if (tag == null) return;
        currentLoaded = false;
        updateMenu();
        tag.downloadingTask = new AttachmentGetter(tag);
        tag.loadingView.setVisibility(View.VISIBLE);
        hideProgress();
        Async.runAsync((Runnable) tag.downloadingTask);
    }
    
    private class AttachmentGetter extends CancellableTask.BaseCancellableTask implements Runnable {
        private final GalleryItemViewTag tag;
        public AttachmentGetter(GalleryItemViewTag tag) {
            this.tag = tag;
        }
        
        @Override
        public void run() {
            if (tag.attachmentModel.type == AttachmentModel.TYPE_OTHER_NOTFILE ||
                    (settings.doNotDownloadVideos() && tag.attachmentModel.type == AttachmentModel.TYPE_VIDEO)) {
                setExternalLink(tag);
                return;
            } else if (tag.attachmentModel.path == null || tag.attachmentModel.path.length() == 0) {
                showError(tag, getString(R.string.gallery_error_incorrect_attachment));
                return;
            }
            final String[] exception = new String[1];
            GalleryRemote currentRemote = remote;
            if (currentRemote == null) {
                showError(tag, getString(R.string.error_connection));
                return;
            }
            File file = currentRemote.getAttachment(new GalleryAttachmentInfo(tag.attachmentModel, tag.attachmentHash), new AbstractGetterCallback(this) {
                @Override
                public void showLoading() {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tag.loadingView.setVisibility(View.VISIBLE);
                        }
                    });
                }
                @Override
                public void onException(String message) {
                    exception[0] = message;
                }
                @Override
                public void onInteractiveException(GalleryInteractiveExceptionHolder holder) {
                    if (holder.e == null) return;
                    exception[0] = getString(R.string.error_interactive_cancelled_format, holder.e.getServiceName());
                    startActivityForResult(new Intent(GalleryActivity.this, GalleryInteractiveExceptionHandler.class).
                            putExtra(GalleryInteractiveExceptionHandler.EXTRA_INTERACTIVE_EXCEPTION, holder.e), REQUEST_HANDLE_INTERACTIVE_EXCEPTION);
                }
            });
            
            if (isCancelled()) return;
            if (file == null) {
                showError(tag, exception[0]);
                return;
            }
            tag.file = file;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (isCancelled()) return;
                    hideProgress();
                    currentLoaded = true;
                    updateMenu();
                }
            });
            switch (tag.attachmentModel.type) {
                case AttachmentModel.TYPE_IMAGE_STATIC:
                    setStaticImage(tag, file);
                    break;
                case AttachmentModel.TYPE_IMAGE_GIF:
                    setGif(tag, file);
                    break;
                case AttachmentModel.TYPE_IMAGE_SVG:
                    setSvg(tag, file);
                    break;
                case AttachmentModel.TYPE_VIDEO:
                    setVideo(tag, file);
                    break;
                case AttachmentModel.TYPE_AUDIO:
                    setAudio(tag, file);
                    break;
                case AttachmentModel.TYPE_OTHER_FILE:
                    setOtherFile(tag, file);
                    break;
            }
        }
        
    }
    
    private void showError(final GalleryItemViewTag tag, final String message) {
        final CancellableTask task = tag.downloadingTask;
        if (task == null || task.isCancelled()) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (task.isCancelled() || tag.downloadingTask != task) return;
                showErrorView(tag, message);
            }
        });
    }

    private void showErrorView(GalleryItemViewTag tag, String message) {
        hideProgress();
        tag.layout.setVisibility(View.GONE);
        recycleTag(tag, true);
        tag.thumbnailView.setVisibility(View.GONE);
        tag.loadingView.setVisibility(View.GONE);
        tag.errorView.setVisibility(View.VISIBLE);
        tag.errorText.setText(message == null || message.length() == 0
                ? getString(R.string.error_unknown) : message);
    }
    
    private void recycleTag(GalleryItemViewTag tag, boolean cancelTask) {
        if (tag.layout != null) {
            for (int i=0; i<tag.layout.getChildCount(); ++i) {
                View v = tag.layout.getChildAt(i);
                if (v instanceof FixedSubsamplingScaleImageView) {
                    ((FixedSubsamplingScaleImageView) v).recycle();
                } else if (v instanceof WebView) {
                    WebView webView = (WebView) v;
                    webView.stopLoading();
                    webView.loadUrl("about:blank");
                    webView.clearHistory();
                    webView.removeAllViews();
                    webView.destroy();
                } else if (v != null) {
                    Object gifTag = v.getTag();
                    if (gifTag != null && gifTag instanceof GifDrawable) {
                        ((GifDrawable) gifTag).recycle();
                    }
                }
            }
            tag.layout.removeAllViews();
        }
        
        if (cancelTask && tag.downloadingTask != null) {
            tag.downloadingTask.cancel();
        }
        stopPlaybackProgressUpdates(tag);
        if (tag.exoPlayer != null) {
            try {
                tag.exoPlayer.stop();
                tag.exoPlayer.clearVideoSurface();
                tag.exoPlayer.release();
            } catch (Exception e) {
                Logger.e(TAG, e);
            } finally {
                tag.exoPlayer = null;
            }
        }
        tag.playbackDurationView = null;
        tag.resumePlaybackOnStart = false;
    }

    private void pausePlayback() {
        if (instantiatedViews == null) return;
        for (int i = 0; i < instantiatedViews.size(); ++i) {
            View view = instantiatedViews.valueAt(i);
            if (view == null || !(view.getTag() instanceof GalleryItemViewTag)) continue;
            GalleryItemViewTag tag = (GalleryItemViewTag) view.getTag();
            if (tag.exoPlayer != null) {
                tag.resumePlaybackOnStart = tag.exoPlayer.getPlayWhenReady();
                tag.exoPlayer.pause();
                stopPlaybackProgressUpdates(tag);
            }
        }
    }

    private void resumePlayback() {
        if (instantiatedViews == null) return;
        for (int i = 0; i < instantiatedViews.size(); ++i) {
            View view = instantiatedViews.valueAt(i);
            if (view == null || !(view.getTag() instanceof GalleryItemViewTag)) continue;
            GalleryItemViewTag tag = (GalleryItemViewTag) view.getTag();
            if (tag.exoPlayer != null && tag.resumePlaybackOnStart) {
                tag.resumePlaybackOnStart = false;
                tag.exoPlayer.play();
                startPlaybackProgressUpdates(tag);
            }
        }
    }

    private void startPlaybackProgressUpdates(final GalleryItemViewTag tag) {
        stopPlaybackProgressUpdates(tag);
        if (tag.exoPlayer == null || tag.playbackDurationView == null) return;
        tag.playbackProgressUpdater = new Runnable() {
            @Override
            public void run() {
                ExoPlayer player = tag.exoPlayer;
                TextView durationView = tag.playbackDurationView;
                if (destroyed || player == null || durationView == null) return;
                String text = formatMediaPlayerTime(player.getCurrentPosition()) + " / "
                        + formatMediaPlayerTime(player.getDuration());
                durationView.setText(tag.playbackTimeSpanned ? getSpannedText(text) : text);
                mainHandler.postDelayed(this, 1000);
            }
        };
        mainHandler.post(tag.playbackProgressUpdater);
    }

    private void stopPlaybackProgressUpdates(GalleryItemViewTag tag) {
        if (tag.playbackProgressUpdater != null) {
            mainHandler.removeCallbacks(tag.playbackProgressUpdater);
            tag.playbackProgressUpdater = null;
        }
    }
    
    private void setStaticImage(final GalleryItemViewTag tag, final File file) {
        if (!settings.useScaleImageView() || Jpeg.isNonStandardGrayscaleImage(file)) {
            setWebView(tag, file);
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    FixedSubsamplingScaleImageView iv = new FixedSubsamplingScaleImageView(GalleryActivity.this);
                    iv.setInitCallback(new FixedSubsamplingScaleImageView.InitedCallback() {
                        @Override
                        public void onInit() {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    tag.thumbnailView.setVisibility(View.GONE);
                                    tag.loadingView.setVisibility(View.GONE);
                                }
                            });
                        }
                    });
                    iv.setImageFile(file.getAbsolutePath(), new FixedSubsamplingScaleImageView.FailedCallback() {
                        @Override
                        public void onFail() {
                            setWebView(tag, file);
                        }
                    });
                    if (tag.downloadingTask.isCancelled()) return;
                    tag.layout.setVisibility(View.VISIBLE);
                    tag.layout.addView(iv);
                } catch (Throwable t) {
                    System.gc();
                    Logger.e(TAG, t);
                    if (tag.downloadingTask.isCancelled()) return;
                    setWebView(tag, file);
                }
            }
        });
    }
    
    private void setGif(final GalleryItemViewTag tag, final File file) {
        if (!settings.useNativeGif()) {
            setWebView(tag, file);
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ImageView iv = new TouchGifView(GalleryActivity.this);
                try {
                    GifDrawable drawable = new GifDrawable(file);
                    iv.setTag(drawable);
                    iv.setImageDrawable(drawable);
                } catch (Throwable e) {
                    System.gc();
                    Logger.e(TAG, "cannot init GifDrawable", e);
                    if (tag.downloadingTask.isCancelled()) return;
                    setWebView(tag, file);
                    return;
                }
                
                if (tag.downloadingTask.isCancelled()) return;
                
                tag.thumbnailView.setVisibility(View.GONE);
                tag.loadingView.setVisibility(View.GONE);
                
                tag.layout.setVisibility(View.VISIBLE);
                tag.layout.addView(iv);
            }
        });
    }
    
    private void setSvg(GalleryItemViewTag tag, File file) {
        setWebView(tag, file);
    }
    
    private void setVideo(final GalleryItemViewTag tag, final File file) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setOnClickView(tag, getString(R.string.gallery_tap_to_play), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!settings.useInternalVideoPlayer()) {
                            openExternal();
                        } else {
                            recycleTag(tag, false);
                            tag.thumbnailView.setVisibility(View.GONE);
                            View videoContainer = inflater.inflate(R.layout.gallery_videoplayer, tag.layout);
                            final TextureView textureView = (TextureView)videoContainer.findViewById(R.id.gallery_video_surface);
                            final TextView durationView = (TextView)videoContainer.findViewById(R.id.gallery_video_duration);
                            final View videoFrame = videoContainer.findViewById(R.id.gallery_video_frame);

                            final ExoPlayer exoPlayer = new ExoPlayer.Builder(GalleryActivity.this).build();
                            tag.exoPlayer = exoPlayer;
                            tag.playbackDurationView = durationView;
                            tag.playbackTimeSpanned = false;
                            exoPlayer.setVideoTextureView(textureView);
                            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
                            exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);

                            final float[] videoScale = {1f};
                            final float[] videoPan = {0f, 0f};
                            final float[] videoFit = {0f, 0f, 1f, 1f}; // fitX, fitY, fitW, fitH
                            final int[] videoNativeSize = {0, 0};
                            final float[] videoPixelRatio = {1f};

                            final Runnable updateTransform = new Runnable() {
                                @Override
                                public void run() {
                                    int vw = videoNativeSize[0];
                                    int vh = videoNativeSize[1];
                                    if (vw == 0 || vh == 0) return;
                                    int tw = textureView.getWidth();
                                    int th = textureView.getHeight();
                                    if (tw == 0 || th == 0) return;

                                    float videoAspect = (float) vw * videoPixelRatio[0] / vh;
                                    float viewAspect = (float) tw / th;

                                    float fitScaleX, fitScaleY;
                                    if (videoAspect > viewAspect) {
                                        fitScaleX = 1f;
                                        fitScaleY = viewAspect / videoAspect;
                                    } else {
                                        fitScaleX = videoAspect / viewAspect;
                                        fitScaleY = 1f;
                                    }

                                    float scale = videoScale[0];
                                    float totalScaleX = fitScaleX * scale;
                                    float totalScaleY = fitScaleY * scale;

                                    float maxPanX = Math.max(0, (totalScaleX - 1f) * tw / 2f);
                                    float maxPanY = Math.max(0, (totalScaleY - 1f) * th / 2f);
                                    videoPan[0] = Math.max(-maxPanX, Math.min(maxPanX, videoPan[0]));
                                    videoPan[1] = Math.max(-maxPanY, Math.min(maxPanY, videoPan[1]));

                                    Matrix matrix = new Matrix();
                                    matrix.setScale(totalScaleX, totalScaleY, tw / 2f, th / 2f);
                                    matrix.postTranslate(videoPan[0], videoPan[1]);
                                    textureView.setTransform(matrix);
                                }
                            };

                            final ScaleGestureDetector scaleDetector = new ScaleGestureDetector(GalleryActivity.this,
                                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                                        @Override
                                        public boolean onScale(ScaleGestureDetector detector) {
                                            videoScale[0] = Math.max(1f, Math.min(10f, videoScale[0] * detector.getScaleFactor()));
                                            updateTransform.run();
                                            return true;
                                        }
                                        @Override
                                        public boolean onScaleBegin(ScaleGestureDetector detector) {
                                            return true;
                                        }
                                    });

                            final float[] lastTouch = {0f, 0f};
                            final boolean[] isDragging = {false};
                            textureView.setOnTouchListener(new View.OnTouchListener() {
                                @Override
                                public boolean onTouch(View v, MotionEvent event) {
                                    scaleDetector.onTouchEvent(event);
                                    int action = event.getActionMasked();
                                    if (event.getPointerCount() == 1) {
                                        switch (action) {
                                            case MotionEvent.ACTION_DOWN:
                                                lastTouch[0] = event.getX();
                                                lastTouch[1] = event.getY();
                                                isDragging[0] = false;
                                                break;
                                            case MotionEvent.ACTION_MOVE:
                                                if (videoScale[0] > 1.01f) {
                                                    float dx = event.getX() - lastTouch[0];
                                                    float dy = event.getY() - lastTouch[1];
                                                    if (isDragging[0] || Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                                                        isDragging[0] = true;
                                                        videoPan[0] += dx;
                                                        videoPan[1] += dy;
                                                        updateTransform.run();
                                                    }
                                                    lastTouch[0] = event.getX();
                                                    lastTouch[1] = event.getY();
                                                }
                                                break;
                                        }
                                    } else {
                                        lastTouch[0] = event.getX();
                                        lastTouch[1] = event.getY();
                                    }
                                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                                        if (videoScale[0] < 1.01f) {
                                            videoScale[0] = 1f;
                                            videoPan[0] = 0f;
                                            videoPan[1] = 0f;
                                            updateTransform.run();
                                        }
                                    }
                                    return true;
                                }
                            });

                            exoPlayer.addListener(new Player.Listener() {
                                @Override
                                public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize) {
                                    if (videoSize.width == 0 || videoSize.height == 0) return;
                                    videoNativeSize[0] = videoSize.width;
                                    videoNativeSize[1] = videoSize.height;
                                    videoPixelRatio[0] = videoSize.pixelWidthHeightRatio;
                                    updateTransform.run();
                                }

                                @Override
                                public void onPlaybackStateChanged(int state) {
                                    if (state == Player.STATE_READY) {
                                        long duration = exoPlayer.getDuration();
                                        durationView.setText("00:00 / " + formatMediaPlayerTime(duration));

                                        startPlaybackProgressUpdates(tag);
                                    }
                                }
                                @Override
                                public void onPlayerError(PlaybackException error) {
                                    Logger.e(TAG, "(Video) ExoPlayer error: " + error.getMessage());
                                    stopPlaybackProgressUpdates(tag);
                                    showError(tag, getString(R.string.gallery_error_play));
                                }
                            });

                            exoPlayer.prepare();
                            exoPlayer.play();
                        }
                    }

                });
            }
        });
    }
    
    private void setAudio(final GalleryItemViewTag tag, final File file) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setOnClickView(tag, getString(R.string.gallery_tap_to_play), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!settings.useInternalAudioPlayer()) {
                            openExternal();
                        } else {
                            recycleTag(tag, false);
                            final TextView durationView = new TextView(GalleryActivity.this);
                            durationView.setGravity(Gravity.CENTER);
                            tag.layout.setVisibility(View.VISIBLE);
                            tag.layout.addView(durationView);

                            final ExoPlayer exoPlayer = new ExoPlayer.Builder(GalleryActivity.this).build();
                            tag.exoPlayer = exoPlayer;
                            tag.playbackDurationView = durationView;
                            tag.playbackTimeSpanned = true;
                            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
                            exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);

                            exoPlayer.addListener(new Player.Listener() {
                                @Override
                                public void onPlaybackStateChanged(int state) {
                                    if (state == Player.STATE_READY) {
                                        long duration = exoPlayer.getDuration();
                                        durationView.setText(getSpannedText("00:00 / " + formatMediaPlayerTime(duration)));

                                        startPlaybackProgressUpdates(tag);

                                        exoPlayer.play();
                                    }
                                }
                                @Override
                                public void onPlayerError(PlaybackException error) {
                                    Logger.e(TAG, "(Audio) ExoPlayer error: " + error.getMessage());
                                    stopPlaybackProgressUpdates(tag);
                                    showError(tag, getString(R.string.gallery_error_play));
                                }
                            });

                            exoPlayer.prepare();
                        }
                    }
                });
            }
        });
    }
    
    private String formatMediaPlayerTime(long milliseconds) {
        if (milliseconds < 0) return "--:--";
        long seconds = milliseconds / 1000 % 60;
        long minutes = milliseconds / 60000;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
    
    private void setOtherFile(final GalleryItemViewTag tag, final File file) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setOnClickView(tag, getString(R.string.gallery_tap_to_open), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openExternal();
                    }
                });
            }
        });
    }
    
    private void setExternalLink(final GalleryItemViewTag tag) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                int stringResId = R.string.gallery_tap_to_external_link;
                try {
                    if (settings.doNotDownloadVideos() && tag.attachmentModel.type == AttachmentModel.TYPE_VIDEO)
                        stringResId = R.string.gallery_tap_to_play;
                } catch (Exception e) {}
                setOnClickView(tag, getString(stringResId), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openBrowser();
                    }
                });
            }
        });
    }
    
    private void setOnClickView(GalleryItemViewTag tag, String message, View.OnClickListener handler) {
        tag.thumbnailView.setVisibility(View.VISIBLE);
        tag.loadingView.setVisibility(View.GONE);
        TextView v = new TextView(GalleryActivity.this);
        v.setGravity(Gravity.CENTER);
        v.setText(getSpannedText(message));
        tag.layout.setVisibility(View.VISIBLE);
        tag.layout.addView(v);
        v.setOnClickListener(handler);
    }
    
    private Spanned getSpannedText(String message) {
        message = " " + message + " ";
        SpannableStringBuilder spanned = new SpannableStringBuilder(message);
        for (Object span : new Object[] { new ForegroundColorSpan(Color.WHITE), new BackgroundColorSpan(Color.parseColor("#88000000")) }) { 
            spanned.setSpan(span, 0, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return spanned;
    }
    
    private void setWebView(final GalleryItemViewTag tag, final File file) {
        runOnUiThread(new Runnable() {
            private boolean oomFlag = false;
            
            private final ViewGroup.LayoutParams MATCH_PARAMS =
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            
            private void prepareWebView(WebView webView) {
                webView.setBackgroundColor(Color.TRANSPARENT);
                webView.setInitialScale(100);
                webView.setScrollBarStyle(WebView.SCROLLBARS_OUTSIDE_OVERLAY);
                webView.setScrollbarFadingEnabled(true);

                WebSettings settings = webView.getSettings();
                settings.setBuiltInZoomControls(true);
                settings.setSupportZoom(true);
                settings.setAllowFileAccess(true);
                settings.setDefaultZoom(WebSettings.ZoomDensity.FAR);
                settings.setLoadWithOverviewMode(true);
                settings.setUseWideViewPort(true);
                settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

                settings.setBlockNetworkLoads(true);
                
                setScaleWebView(webView);
            }
            
            private void setScaleWebView(final WebView webView) {
                Runnable callSetScaleWebView = new Runnable() {
                    @Override
                    public void run() {
                        setPrivateScaleWebView(webView);
                    }
                };

                Point resolution = new Point(tag.layout.getWidth(), tag.layout.getHeight());
                if (resolution.equals(0, 0)) {
                    // wait until the view is measured and its size is known
                    AppearanceUtils.callWhenLoaded(tag.layout, callSetScaleWebView);
                } else {
                    callSetScaleWebView.run();
                }
            }
            
            private void setPrivateScaleWebView(WebView webView) {
                Point imageSize = getImageSize(file);
                Point resolution = new Point(tag.layout.getWidth(), tag.layout.getHeight());

                //Logger.d(TAG, "Resolution: "+resolution.x+"x"+resolution.y);
                double scaleX = (double)resolution.x / (double)imageSize.x;
                double scaleY = (double)resolution.y / (double)imageSize.y;
                int scale = (int)Math.round(Math.min(scaleX, scaleY) * 100d);
                scale = Math.max(scale, 1);
                //Logger.d(TAG, "Scale: "+(Math.min(scaleX, scaleY) * 100d));
                double picdpi = (getResources().getDisplayMetrics().density * 160d) / scaleX;
                if (picdpi >= 240) {
                    webView.getSettings().setDefaultZoom(WebSettings.ZoomDensity.FAR);
                } else if (picdpi <= 120) {
                    webView.getSettings().setDefaultZoom(WebSettings.ZoomDensity.CLOSE);
                } else {
                    webView.getSettings().setDefaultZoom(WebSettings.ZoomDensity.MEDIUM);
                }
                
                webView.setInitialScale(scale);
                webView.setPadding(0, 0, 0, 0);
            }
            
            private Point getImageSize(File file) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                return new Point(options.outWidth, options.outHeight);
            }
            
            private boolean useFallback(File file) {
                String path = file.getPath().toLowerCase(Locale.US);
                if (path.endsWith(".png")) return false;
                if (path.endsWith(".jpg")) return false;
                if (path.endsWith(".gif")) return false;
                if (path.endsWith(".jpeg")) return false;
                if (path.endsWith(".webp")) return false;
                return true;
            }
            
            @Override
            public void run() {
                try {
                    recycleTag(tag, false);
                    WebView webView = new WebViewFixed(GalleryActivity.this);
                    webView.setLayoutParams(MATCH_PARAMS);
                    tag.layout.addView(webView);
                    if (settings.fallbackWebView() || useFallback(file)) {
                        prepareWebView(webView);
                        webView.loadUrl(Uri.fromFile(file).toString());
                    } else {
                        JSWebView.setImage(webView, file);
                    }
                    tag.thumbnailView.setVisibility(View.GONE);
                    tag.loadingView.setVisibility(View.GONE);
                    tag.layout.setVisibility(View.VISIBLE);
                } catch (OutOfMemoryError oom) {
                    System.gc();
                    Logger.e(TAG, oom);
                    if (!oomFlag) {
                        oomFlag = true;
                        run();
                    } else showError(tag, getString(R.string.error_out_of_memory));
                }
            }
            
        });
    }
    
    public static interface FullscreenCallback {
        void showUI(boolean hideAfterDelay);
        void keepUI(boolean hideAfterDelay);
    }
    
    private FullscreenCallback fullscreenCallback;
    private GestureDetector fullscreenGestureDetector;
    
    public void setFullscreenCallback(FullscreenCallback fullscreenCallback) {
        if (fullscreenGestureDetector == null) {
            fullscreenGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    FullscreenCallback fullscreenCallback = GalleryActivity.this.fullscreenCallback;
                    if (fullscreenCallback != null) fullscreenCallback.showUI(true);
                    return true;
                }
            });
        }
        this.fullscreenCallback = fullscreenCallback;
    }
    
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (fullscreenCallback != null) {
            fullscreenCallback.keepUI(MotionEventCompat.getActionMasked(ev) == MotionEvent.ACTION_UP);
            fullscreenGestureDetector.onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }
    
    @Override
    public void onPanelClosed(int featureId, Menu menu) {
        if (fullscreenCallback != null) fullscreenCallback.showUI(true);
        super.onPanelClosed(featureId, menu);
    }
    
    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        if (fullscreenCallback != null) fullscreenCallback.showUI(false);
        return super.onMenuOpened(featureId, menu);
    }
    
    private class GalleryItemViewTag {
        public CancellableTask downloadingTask;
        public ExoPlayer exoPlayer;
        public Runnable playbackProgressUpdater;
        public TextView playbackDurationView;
        public boolean playbackTimeSpanned;
        public boolean resumePlaybackOnStart;
        public AttachmentModel attachmentModel;
        public String attachmentHash;
        public File file;
        
        public ImageView thumbnailView;
        public FrameLayout layout;
        public View errorView;
        public TextView errorText;
        public View loadingView;
    }
    
}
