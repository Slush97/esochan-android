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

package dev.esoc.esochan.cache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import dev.esoc.esochan.common.Tuples.Pair;

import dev.esoc.esochan.api.models.AttachmentModel;
import dev.esoc.esochan.api.models.BadgeIconModel;
import dev.esoc.esochan.api.models.BoardModel;
import dev.esoc.esochan.api.models.DeletePostModel;
import dev.esoc.esochan.api.models.PostModel;
import dev.esoc.esochan.api.models.SendPostModel;
import dev.esoc.esochan.api.models.SimpleBoardModel;
import dev.esoc.esochan.api.models.ThreadModel;
import dev.esoc.esochan.api.models.UrlPageModel;
import dev.esoc.esochan.common.Async;
import dev.esoc.esochan.common.IOUtils;
import dev.esoc.esochan.common.Logger;
import dev.esoc.esochan.common.MainApplication;
import dev.esoc.esochan.ui.tabs.TabModel;
import dev.esoc.esochan.ui.tabs.TabsIdStack;
import dev.esoc.esochan.ui.tabs.TabsState;
import androidx.core.util.AtomicFile;

import com.esotericsoftware.kryo.kryo5.Kryo;
import com.esotericsoftware.kryo.kryo5.io.Input;
import com.esotericsoftware.kryo.kryo5.io.Output;
import com.esotericsoftware.kryo.kryo5.serializers.TaggedFieldSerializer;

/**
 * Сериализация объектов (на основе kryo)
 * @author miku-nyan
 *
 */
public class Serializer {
    private static final String TAG = "Serializer";
    
    private final FileCache fileCache;
    private final AtomicFile tabsStateFile;
    private final Kryo kryo;
    private final Object kryoLock = new Object();
    private final ExecutorService persistenceExecutor =
            Executors.newSingleThreadExecutor(Async.LOW_PRIORITY_FACTORY);
    
    /**
     * Конструктор
     * @param fileCache объект файлового кэша
     */
    public Serializer(FileCache fileCache) {
        this.fileCache = fileCache;
        this.tabsStateFile = new AtomicFile(new File(fileCache.getFilesDirectory(), FileCache.TABS_FILENAME));
        
        this.kryo = new Kryo();
        this.kryo.setReferences(false);
        this.kryo.setRegistrationRequired(false);
        this.kryo.setDefaultSerializer(TaggedFieldSerializer.class);
        
        this.kryo.register(TabsState.class, 100);
        this.kryo.register(TabModel.class, 101);
        this.kryo.register(TabsIdStack.class, 102);

        this.kryo.register(SerializablePage.class, 103);
        this.kryo.register(SerializableBoardsList.class, 104);

        this.kryo.register(AttachmentModel.class, 105);
        this.kryo.register(BadgeIconModel.class, 106);
        this.kryo.register(BoardModel.class, 107);
        this.kryo.register(DeletePostModel.class, 108);
        this.kryo.register(PostModel.class, 109);
        this.kryo.register(SendPostModel.class, 110);
        this.kryo.register(SimpleBoardModel.class, 111);
        this.kryo.register(ThreadModel.class, 112);
        this.kryo.register(UrlPageModel.class, 113);

        this.kryo.register(AttachmentModel[].class, 114);
        this.kryo.register(BadgeIconModel[].class, 115);
        this.kryo.register(BoardModel[].class, 116);
        this.kryo.register(DeletePostModel[].class, 117);
        this.kryo.register(PostModel[].class, 118);
        this.kryo.register(SendPostModel[].class, 119);
        this.kryo.register(SimpleBoardModel[].class, 120);
        this.kryo.register(ThreadModel[].class, 121);
        this.kryo.register(UrlPageModel[].class, 122);

        this.kryo.register(java.util.ArrayList.class, 123);
        this.kryo.register(java.util.LinkedList.class, 124);
        this.kryo.register(java.io.File.class, new FileSerializer(), 125);
        this.kryo.register(java.io.File[].class, 126);
    }
    
    private byte[] snapshot(Object obj) {
        synchronized (kryoLock) {
            Output output = null;
            try {
                output = new Output(4096, -1);
                kryo.writeObject(output, obj);
                return output.toBytes();
            } catch (Exception e) {
                Logger.e(TAG, e);
            } catch (OutOfMemoryError oom) {
                MainApplication.freeMemory();
                Logger.e(TAG, oom);
            } finally {
                IOUtils.closeQuietly(output);
            }
        }
        return null;
    }

    private void writeCacheSnapshot(String filename, byte[] snapshot) {
        File file = fileCache.create(filename);
        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream fileStream = null;
        try {
            fileStream = atomicFile.startWrite();
            fileStream.write(snapshot);
            atomicFile.finishWrite(fileStream);
            fileStream = null;
        } catch (Exception e) {
            Logger.e(TAG, e);
            atomicFile.failWrite(fileStream);
            fileCache.abort(file);
            return;
        }
        try {
            fileCache.put(file);
        } catch (Exception e) {
            Logger.e(TAG, e);
            fileCache.abort(file);
        }
    }

    private void serializeAsync(final String filename, Object obj) {
        final byte[] objectSnapshot = snapshot(obj);
        if (objectSnapshot == null) return;
        persistenceExecutor.execute(new Runnable() {
            @Override
            public void run() {
                writeCacheSnapshot(filename, objectSnapshot);
            }
        });
    }

    private <T> T executeOrdered(Callable<T> task, T fallback) {
        Future<T> future = persistenceExecutor.submit(task);
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.e(TAG, e);
        } catch (ExecutionException e) {
            Logger.e(TAG, e.getCause() != null ? e.getCause() : e);
        }
        return fallback;
    }
    
    private <T> T deserialize(File file, Class<T> type) {
        if (file == null || !file.exists()) {
            return null;
        }
        
        synchronized (kryoLock) {
            Input input = null;
            try {
                input = new Input(new FileInputStream(file));
                return kryo.readObject(input, type);
            } catch (Exception e) {
                Logger.e(TAG, e);
            } catch (OutOfMemoryError oom) {
                MainApplication.freeMemory();
                Logger.e(TAG, oom);
            } finally {
                IOUtils.closeQuietly(input);
            }
        }
        
        return null;
    }
    
    public <T> T deserialize(String fileName, Class<T> type) {
        return executeOrdered(() -> deserialize(fileCache.get(fileName), type), null);
    }
    
    public void serializePage(String hash, SerializablePage page) {
        serializeAsync(FileCache.PREFIX_PAGES + hash, page);
    }
    
    public SerializablePage deserializePage(String hash) {
        try {
            return deserialize(FileCache.PREFIX_PAGES + hash, SerializablePage.class);
        } catch (Exception e) {
            Logger.e(TAG, e);
            return null;
        }
    }
    
    public void serializeBoardsList(String hash, SerializableBoardsList boardsList) {
        serializeAsync(FileCache.PREFIX_BOARDS + hash, boardsList);
    }
    
    public SerializableBoardsList deserializeBoardsList(String hash) {
        try {
            return deserialize(FileCache.PREFIX_BOARDS + hash, SerializableBoardsList.class);
        } catch (Exception e) {
            Logger.e(TAG, e);
            return null;
        }
    }
    
    public void serializeDraft(String hash, SendPostModel draft) {
        serializeAsync(FileCache.PREFIX_DRAFTS + hash, draft);
    }
    
    public SendPostModel deserializeDraft(String hash) {
        try {
            return deserialize(FileCache.PREFIX_DRAFTS + hash, SendPostModel.class);
        } catch (Exception e) {
            Logger.e(TAG, e);
            return null;
        }
    }
    
    public void removeDraft(String hash) {
        final String filename = FileCache.PREFIX_DRAFTS + hash;
        persistenceExecutor.execute(() -> {
            File file = fileCache.get(filename);
            if (file != null) fileCache.delete(file);
        });
    }
    
    public void serializeTabsState(TabsState state) {
        TabsState stableState = new TabsState();
        stableState.tabsArray = new ArrayList<>(Arrays.asList(state.snapshotTabs()));
        stableState.tabsIdStack = state.tabsIdStack;
        stableState.position = state.position;
        final byte[] stateSnapshot = snapshot(stableState);
        if (stateSnapshot == null) return;
        persistenceExecutor.execute(new Runnable() {
            @Override
            public void run() {
                FileOutputStream fileStream = null;
                try {
                    fileStream = tabsStateFile.startWrite();
                    fileStream.write(stateSnapshot);
                    tabsStateFile.finishWrite(fileStream);
                } catch (Exception e) {
                    Logger.e(TAG, e);
                    tabsStateFile.failWrite(fileStream);
                }
            }
        });
    }
    
    public TabsState deserializeTabsState() {
        return executeOrdered(() -> {
            synchronized (kryoLock) {
                Input input = null;
                try {
                    input = new Input(tabsStateFile.openRead());
                    TabsState obj = kryo.readObject(input, TabsState.class);
                    if (obj != null && obj.tabsArray != null && obj.tabsIdStack != null) return obj;
                } catch (Exception e) {
                    Logger.e(TAG, e);
                } catch (OutOfMemoryError e) {
                    MainApplication.freeMemory();
                    Logger.e(TAG, e);
                } finally {
                    IOUtils.closeQuietly(input);
                }
            }
            return TabsState.obtainDefault();
        }, TabsState.obtainDefault());
    }
    
    public void savePage(OutputStream out, String title, UrlPageModel pageModel, SerializablePage page) {
        synchronized (kryoLock) {
            Output output = null;
            try {
                output = new Output(out);
                output.writeString(title);
                kryo.writeObject(output, pageModel);
                kryo.writeObject(output, page);
            } finally {
                IOUtils.closeQuietly(output);
            }
        }
    }
    
    public Pair<String, UrlPageModel> loadPageInfo(InputStream in) {
        synchronized (kryoLock) {
            Input input = null;
            try {
                input = new Input(in);
                String title = input.readString();
                UrlPageModel pageModel = kryo.readObject(input, UrlPageModel.class);
                return Pair.of(title, pageModel);
            } finally {
                IOUtils.closeQuietly(input);
            }
        }
    }
    
    public SerializablePage loadPage(InputStream in) {
        synchronized (kryoLock) {
            Input input = null;
            try {
                input = new Input(in);
                input.readString();
                kryo.readObject(input, UrlPageModel.class);
                return kryo.readObject(input, SerializablePage.class);
            } finally {
                IOUtils.closeQuietly(input);
            }
        }
    }
    
    private class FileSerializer extends com.esotericsoftware.kryo.kryo5.Serializer<java.io.File> {
        @Override
        public void write (Kryo kryo, Output output, File object) {
            output.writeString(object.getPath());
        }
        @Override
        public File read (Kryo kryo, Input input, Class<? extends File> type) {
            return new File(input.readString());
        }
    }
    
}
