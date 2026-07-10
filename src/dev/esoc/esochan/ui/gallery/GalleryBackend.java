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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import dev.esoc.esochan.common.Tuples.Triple;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.IBinder;
import android.os.RemoteException;
import androidx.fragment.app.Fragment;
import dev.esoc.esochan.R;
import dev.esoc.esochan.api.ChanModule;
import dev.esoc.esochan.api.interfaces.CancellableTask;
import dev.esoc.esochan.api.interfaces.ProgressListener;
import dev.esoc.esochan.api.models.AttachmentModel;
import dev.esoc.esochan.api.models.BoardModel;
import dev.esoc.esochan.api.models.UrlPageModel;
import dev.esoc.esochan.api.util.ChanModels;
import dev.esoc.esochan.cache.BitmapCache;
import dev.esoc.esochan.cache.FileCache;
import dev.esoc.esochan.common.Async;
import dev.esoc.esochan.common.IOUtils;
import dev.esoc.esochan.common.Logger;
import dev.esoc.esochan.common.MainApplication;
import dev.esoc.esochan.containers.ReadableContainer;
import dev.esoc.esochan.http.interactive.InteractiveException;
import dev.esoc.esochan.ui.Attachments;
import dev.esoc.esochan.ui.downloading.DownloadStorage;
import dev.esoc.esochan.ui.downloading.DownloadingLocker;
import dev.esoc.esochan.ui.downloading.DownloadingService;
import dev.esoc.esochan.ui.presentation.BoardFragment;
import dev.esoc.esochan.ui.presentation.PresentationModel;
import dev.esoc.esochan.ui.settings.ApplicationSettings;
import dev.esoc.esochan.ui.tabs.TabModel;
import dev.esoc.esochan.ui.tabs.TabsState;
import dev.esoc.esochan.ui.tabs.TabsSwitcher;

public class GalleryBackend extends Service {
    private static final String TAG = "GalleryBackend";
    
    private IBinder binder;
    
    private ApplicationSettings settings;
    private DownloadingLocker downloadingLocker;
    private CancellableTask tnDownloadingTask;
    private FileCache fileCache;
    private BitmapCache bitmapCache;
    private List<GalleryContext> contexts;
    
    @Override
    public void onCreate() {
        super.onCreate();
        settings = MainApplication.getInstance().settings;
        downloadingLocker = MainApplication.getInstance().downloadingLocker;
        tnDownloadingTask = new CancellableTask.BaseCancellableTask();
        fileCache = MainApplication.getInstance().fileCache;
        bitmapCache = MainApplication.getInstance().bitmapCache;
        contexts = new ArrayList<>();
        binder = new MyBinder(this).asBinder();
    }
    
    @Override
    public void onDestroy() {
        if (tnDownloadingTask != null) tnDownloadingTask.cancel();
        List<GalleryContext> activeContexts;
        synchronized (contexts) {
            activeContexts = new ArrayList<>(contexts);
            contexts.clear();
        }
        for (GalleryContext context : activeContexts) {
            if (context.localFile != null) {
                try {
                    context.localFile.close();
                } catch (Exception e) {
                    Logger.e(TAG, "cannot close local file", e);
                }
            }
        }
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    private static class MyBinder extends GalleryBinder.Stub {
        private final WeakReference<GalleryBackend> service;
        private MyBinder(GalleryBackend service) {
            this.service = new WeakReference<>(service);
        }

        private GalleryContext getContext(GalleryBackend service, int contextId) {
            synchronized (service.contexts) {
                if (contextId < 0 || contextId >= service.contexts.size()) return null;
                return service.contexts.get(contextId);
            }
        }
        
        @Override
        public boolean isPageLoaded(String pagehash) {
            return MainApplication.getInstance().pagesCache.getPresentationModel(pagehash) != null;
        }
        
        @Override
        public int initContext(GalleryInitData initData) {
            int result = -1;
            GalleryBackend service = this.service.get();
            if (service != null) {
                GalleryContext context = service.new GalleryContext(initData);
                synchronized (service.contexts) {
                    result = service.contexts.size();
                    service.contexts.add(context);
                }
            }
            return result;
        }
        
        @Override
        public GalleryInitResult getInitResult(int contextId) {
            GalleryBackend service = this.service.get();
            if (service == null) return null;
            GalleryContext context = getContext(service, contextId);
            return context == null ? null : context.getInitResult();
        }
        
        @Override
        public Bitmap getBitmapFromMemory(int contextId, String hash) {
            GalleryBackend service = this.service.get();
            if (service == null) return null;
            GalleryContext context = getContext(service, contextId);
            return context == null ? null : context.getBitmapFromMemory(hash);
        }
        
        @Override
        public Bitmap getBitmap(int contextId, String hash, String url) {
            GalleryBackend service = this.service.get();
            if (service == null) return null;
            GalleryContext context = getContext(service, contextId);
            return context == null ? null : context.getBitmap(hash, url);
        }
        
        @Override
        public String getAttachment(int contextId, GalleryAttachmentInfo attachment, GalleryGetterCallback callback) {
            GalleryBackend service = this.service.get();
            if (service == null) return null;
            GalleryContext context = getContext(service, contextId);
            if (context == null) return null;
            try {
                File file = context.getFile(attachment.hash, attachment.attachment, callback);
                if (file == null) return null;
                return file.getPath();
            } catch (Exception e) {
                Logger.e(TAG, e);
                return null;
            }
        }
        
        @Override
        public String getAbsoluteUrl(int contextId, String url) {
            GalleryBackend service = this.service.get();
            if (service == null) return null;
            GalleryContext context = getContext(service, contextId);
            return context == null ? null : context.getAbsoluteUrl(url);
        }
        
        @Override
        public void tryScrollParent(int contextId, String postNumber) {
            GalleryBackend service = this.service.get();
            if (service == null) return;
            GalleryContext context = getContext(service, contextId);
            if (context != null) context.tryScrollParent(postNumber);
        }
    }
    
    private class GalleryContext {
        private ChanModule chan;
        private String customSubdir;
        private BoardModel boardModel;
        private ReadableContainer localFile;
        private GalleryInitResult initResult;
        
        public GalleryContext(GalleryInitData initData) {
            initResult = new GalleryInitResult();
            boardModel = initData.boardModel;
            chan = MainApplication.getInstance().getChanModule(boardModel.chan);
            
            if (initData.localFileName != null) {
                try {
                    localFile = ReadableContainer.obtain(new File(initData.localFileName));
                } catch (Exception e) {
                    Logger.e(TAG, "cannot open local file", e);
                }
            }
            
            PresentationModel presentationModel = MainApplication.getInstance().pagesCache.getPresentationModel(initData.pageHash);
            if (presentationModel != null) {
                boolean isThread = presentationModel.source.pageModel.type == UrlPageModel.TYPE_THREADPAGE;
                this.customSubdir = BoardFragment.getCustomSubdir(presentationModel.source.pageModel);
                List<Triple<AttachmentModel, String, String>> attachments = presentationModel.getAttachments();
                presentationModel = null;
                
                if (attachments != null) {
                    List<Triple<AttachmentModel, String, String>> list = attachments;
                    
                    int index = -1;
                    String attachmentHash = initData.attachmentHash;
                    for (int i=0; i<list.size(); ++i) {
                        if (list.get(i).getMiddle().equals(attachmentHash)) {
                            index = i;
                            break;
                        }
                    }
                    if (index != -1) {
                        if (isThread) {
                            initResult.attachments = list;
                            initResult.initPosition = index;
                        } else {
                            int leftOffset = 0, rightOffset = 0;
                            String threadNumber = list.get(index).getRight();
                            int it = index; while (it > 0 && list.get(--it).getRight().equals(threadNumber)) ++leftOffset;
                            it = index; while (it < (list.size()-1) && list.get(++it).getRight().equals(threadNumber)) ++rightOffset;
                            initResult.attachments = list.subList(index - leftOffset, index + rightOffset + 1);
                            initResult.initPosition = leftOffset;
                        }
                    }
                }
            } else {
                initResult.shouldWaitForPageLoaded = true;
            }
            
            if (initResult.attachments == null) {
                initResult.attachments = Collections.singletonList(
                        Triple.of(initData.attachment, ChanModels.hashAttachmentModel(initData.attachment), (String)null));
                initResult.initPosition = 0;
            }
        }
        
        public GalleryInitResult getInitResult() {
            GalleryInitResult result = initResult;
            initResult = null;
            return result;
        }
        
        public Bitmap getBitmapFromMemory(String hash) {
            return bitmapCache.getFromMemory(hash);
        }
        
        public Bitmap getBitmap(String hash, String url) {
            Bitmap bmp = bitmapCache.getFromCache(hash);
            if (bmp == null && localFile != null) {
                bmp = bitmapCache.getFromContainer(hash, localFile);
            }
            if (bmp == null && url != null && url.length() != 0) {
                bmp = bitmapCache.download(hash, url, getResources().getDimensionPixelSize(R.dimen.post_thumbnail_size), chan, tnDownloadingTask);
            }
            return bmp;
        }
        
        public File getFile(String attachmentHash, AttachmentModel attachmentModel, GalleryGetterCallback callback) throws RemoteException {
            AsyncCallback asyncCallback = new AsyncCallback(callback);
            try {
                Async.runAsync(asyncCallback);
                return getFile(attachmentHash, attachmentModel, asyncCallback);
            } finally {
                asyncCallback.stop();
            }
        }
        
        public File getFile(String attachmentHash, AttachmentModel attachmentModel, final AsyncCallback callback) throws RemoteException {
            File file = fileCache.get(FileCache.PREFIX_ORIGINALS + attachmentHash + Attachments.getAttachmentExtention(attachmentModel));
            if (file != null) {
                String filename = file.getAbsolutePath();
                while (downloadingLocker.isLocked(filename)) downloadingLocker.waitUnlock(filename);
                if (callback.isCancelled()) return null;
            }
            if (file == null || !file.exists() || file.isDirectory() || file.length() == 0) {
                String localFileName = Attachments.getAttachmentLocalFileName(attachmentModel, boardModel);
                String lockKey = chan.getChanName() + "/" + localFileName;
                while (downloadingLocker.isLocked(lockKey)) downloadingLocker.waitUnlock(lockKey);
                if (callback.isCancelled()) return null;
                if (DownloadStorage.fileExists(GalleryBackend.this, chan.getChanName(), null, localFileName)) {
                    file = copyFromDownloadToCache(chan.getChanName(), null, localFileName, attachmentHash, attachmentModel);
                }
            }
            if (customSubdir != null) {
                if (file == null || !file.exists() || file.isDirectory() || file.length() == 0) {
                    String localFileName = Attachments.getAttachmentLocalFileName(attachmentModel, boardModel);
                    String lockKey = chan.getChanName() + "/" + customSubdir + "/" + localFileName;
                    while (downloadingLocker.isLocked(lockKey)) downloadingLocker.waitUnlock(lockKey);
                    if (callback.isCancelled()) return null;
                    if (DownloadStorage.fileExists(GalleryBackend.this, chan.getChanName(), customSubdir, localFileName)) {
                        file = copyFromDownloadToCache(chan.getChanName(), customSubdir, localFileName, attachmentHash, attachmentModel);
                    }
                }
            }
            if (file == null || !file.exists() || file.isDirectory() || file.length() == 0) {
                GalleryGetterCallback cb = callback.getCallback();
                if (cb != null) cb.showLoading();
                file = fileCache.create(FileCache.PREFIX_ORIGINALS + attachmentHash + Attachments.getAttachmentExtention(attachmentModel));
                String filename = file.getAbsolutePath();
                while (!downloadingLocker.lock(filename)) downloadingLocker.waitUnlock(filename);
                InputStream fromLocal = null;
                OutputStream out = null;
                boolean success = false;
                try {
                    out = new FileOutputStream(file);
                    String localName = DownloadingService.ORIGINALS_FOLDER + "/" +
                            Attachments.getAttachmentLocalFileName(attachmentModel, boardModel);
                    if (localFile != null && localFile.hasFile(localName)) {
                        fromLocal = IOUtils.modifyInputStream(localFile.openStream(localName), null, callback);
                        IOUtils.copyStream(fromLocal, out);
                    } else {
                        chan.downloadFile(attachmentModel.path, out, callback, callback);
                    }
                    fileCache.put(file);
                    success = true;
                } catch (final Exception e) {
                    if (callback.isCancelled()) return null;
                    GalleryGetterCallback errCb = callback.getCallback();
                    if (errCb != null) {
                        if (e instanceof InteractiveException) {
                            errCb.onInteractiveException(new GalleryInteractiveExceptionHolder((InteractiveException) e));
                        } else if (IOUtils.isENOSPC(e)) {
                            errCb.onException(getString(R.string.error_no_space));
                        } else {
                            errCb.onException(e.getMessage());
                        }
                    }
                    return null;
                } finally {
                    IOUtils.closeQuietly(fromLocal);
                    IOUtils.closeQuietly(out);
                    if (file != null && !success) fileCache.abort(file);
                    downloadingLocker.unlock(filename);
                }
            }
            return file;
        }
        
        private File copyFromDownloadToCache(String chanName, String subdirectory, String fileName, String attachmentHash, AttachmentModel attachmentModel) {
            File cacheFile = fileCache.create(FileCache.PREFIX_ORIGINALS + attachmentHash + Attachments.getAttachmentExtention(attachmentModel));
            InputStream is = null;
            OutputStream os = null;
            try {
                is = DownloadStorage.openDownloadedFile(GalleryBackend.this, chanName, subdirectory, fileName);
                os = new FileOutputStream(cacheFile);
                IOUtils.copyStream(is, os);
                IOUtils.closeQuietly(os);
                os = null;
                fileCache.put(cacheFile);
                return cacheFile;
            } catch (Exception e) {
                Logger.e(TAG, "Failed to copy downloaded file to cache", e);
                if (cacheFile != null) fileCache.abort(cacheFile);
                return null;
            } finally {
                IOUtils.closeQuietly(is);
                IOUtils.closeQuietly(os);
            }
        }

        public String getAbsoluteUrl(String url) {
            return chan.fixRelativeUrl(url);
        }
        
        public void tryScrollParent(final String postNumber) {
            try {
                TabsState tabsState = MainApplication.getInstance().tabsState;
                final TabsSwitcher tabsSwitcher = MainApplication.getInstance().tabsSwitcher;
                Fragment currentFragment = tabsSwitcher.getCurrentFragment();
                if (currentFragment instanceof BoardFragment) {
                    final BoardFragment fragment = (BoardFragment) currentFragment;
                    TabModel tab = tabsState.findTabById(tabsSwitcher.currentId);
                    if (tab != null && tab.pageModel != null && tab.pageModel.type == UrlPageModel.TYPE_THREADPAGE) {
                        Async.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                fragment.scrollToItem(postNumber);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Logger.e(TAG, e);
            }
        }
    }
    
    public static class AsyncCallback implements ProgressListener, CancellableTask, Runnable {
        private final static long DELAY = 50;
        
        private final GalleryGetterCallback callback;
        private final AtomicLong progress, maxValue;
        private final AtomicBoolean indeterminate, taskCancelled;
        
        private volatile boolean working = true;
        
        public AsyncCallback(GalleryGetterCallback callback) {
            this.callback = callback;
            this.progress = new AtomicLong(Long.MIN_VALUE);
            this.maxValue = new AtomicLong(Long.MIN_VALUE);
            this.indeterminate = new AtomicBoolean(false);
            this.taskCancelled = new AtomicBoolean(false);
        }
        
        public GalleryGetterCallback getCallback() {
            return callback;
        }
        
        @Override
        public void setMaxValue(long value) {
            maxValue.set(value);
        }
        
        @Override
        public void setProgress(long value) {
            progress.set(value);
        }
        
        @Override
        public void setIndeterminate() {
            indeterminate.set(true);
        }
        
        @Override
        public boolean isCancelled() {
            return taskCancelled.get();
        }
        
        @Override
        public void run() {
            while (working) {
                try {
                    long curProgress = progress.getAndSet(Long.MIN_VALUE);
                    long curMaxValue = maxValue.getAndSet(Long.MIN_VALUE);
                    boolean curIndeterminate = indeterminate.getAndSet(false);
                    if (callback.isTaskCancelled()) taskCancelled.set(true);
                    if (curProgress != Long.MIN_VALUE) callback.setProgress(curProgress);
                    if (curMaxValue != Long.MIN_VALUE) callback.setProgressMaxValue(curMaxValue);
                    if (curIndeterminate) callback.setProgressIndeterminate();
                    Thread.sleep(DELAY);
                } catch (Exception e) {
                    Logger.e(TAG, e);
                }
            }
        }
        
        public void stop() {
            working = false;
        }
        
        @Override
        public void cancel() {
            throw new UnsupportedOperationException();
        }
    }
}
