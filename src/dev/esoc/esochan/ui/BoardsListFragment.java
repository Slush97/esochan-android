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

package dev.esoc.esochan.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.esoc.esochan.R;
import dev.esoc.esochan.databinding.BoardslistFragmentBinding;
import dev.esoc.esochan.api.ChanModule;
import dev.esoc.esochan.api.interfaces.CancellableTask;
import dev.esoc.esochan.api.models.SimpleBoardModel;
import dev.esoc.esochan.api.models.UrlPageModel;
import dev.esoc.esochan.cache.PagesCache;
import dev.esoc.esochan.cache.SerializableBoardsList;
import dev.esoc.esochan.common.Async;
import dev.esoc.esochan.common.Logger;
import dev.esoc.esochan.common.MainApplication;
import dev.esoc.esochan.http.interactive.InteractiveException;
import dev.esoc.esochan.lib.ClickableToast;
import dev.esoc.esochan.ui.settings.ApplicationSettings;
import dev.esoc.esochan.ui.tabs.TabModel;
import dev.esoc.esochan.ui.tabs.TabsState;
import dev.esoc.esochan.ui.tabs.UrlHandler;
import dev.esoc.esochan.ui.theme.ThemeUtils;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class BoardsListFragment extends Fragment implements AdapterView.OnItemClickListener, View.OnClickListener {
    public static final String TAG = "BoardsListFragment";
    
    private boolean isFailInstance = false;
    
    private PagesCache pagesCache = MainApplication.getInstance().pagesCache;
    private ChanModule chan;
    
    private MainActivity activity;
    private Resources resources;
    private ApplicationSettings settings;
    private Database database;
    private CancellableTask currentTask;
    
    private TabModel tabModel;
    private String startItem;
    private int startItemTop;
    
    private BoardslistFragmentBinding binding;
    private View rootView;
    private View loadingView;
    private View errorView;
    private TextView errorTextView;
    private ListView listView;
    private EditText boardField;
    private Button buttonGo;
    
    private SimpleBoardModel[] boardsList;
    private BoardsListAdapter adapter;
    
    private List<QuickAccess.Entry> quickAccessList;
    private ClickableToast toast;
    
    public static BoardsListFragment newInstance(long tabId) {
        TabsState tabsState = MainApplication.getInstance().tabsState;
        if (tabsState == null) throw new IllegalStateException("tabsState was not initialized in the MainApplication singleton");
        TabModel model = tabsState.findTabById(tabId);
        if (model == null) throw new IllegalArgumentException("cannot find tab with id "+tabId);
        
        if (model.pageModel.type != UrlPageModel.TYPE_INDEXPAGE) {
            throw new IllegalArgumentException("pageModel.type != INDEXPAGE (this fragment can show only boardslists)");
        }
        BoardsListFragment fragment = new BoardsListFragment();
        Bundle args = new Bundle(1);
        args.putLong("TabModelId", tabId);
        fragment.setArguments(args);
        return fragment;
    }
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activity = (MainActivity) getActivity();
        resources = MainApplication.getInstance().resources;
        settings = MainApplication.getInstance().settings;
        database = MainApplication.getInstance().database;
        
        TabsState tabsState = MainApplication.getInstance().tabsState;
        if (tabsState == null) throw new IllegalStateException("tabsState was not initialized in the MainApplication singleton");
        tabModel = tabsState.findTabById(getArguments().getLong("TabModelId"));
        if (tabModel == null) {
            isFailInstance = true;
            return;
        }
        
        if (tabModel.forceUpdate) {
            tabModel.forceUpdate = false;
            MainApplication.getInstance().serializer.serializeTabsState(tabsState);
            saveHistory();
        }
        
        chan = MainApplication.getInstance().getChanModule(tabModel.pageModel.chanName);
        setHasOptionsMenu(true);
        
        quickAccessList = QuickAccess.getQuickAccessFromPreferences();
        if (quickAccessList.isEmpty()) {
            toast = ClickableToast.showText(activity, resources.getString(R.string.boardslist_quickaccess_tip), new ClickableToast.OnClickListener() {
                @Override
                public void onClick() {
                    try {
                        activity.openOptionsMenu();
                    } catch (Exception e) {
                        Logger.e(TAG, e);
                    }
                }
            }, true);
        }
    }
    
    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        MenuItem itemUpdate = menu.add(Menu.NONE, R.id.menu_update, 101, resources.getString(R.string.menu_update));
        itemUpdate.setIcon(ThemeUtils.getActionbarIcon(activity.getTheme(), resources, R.attr.actionRefresh));
        itemUpdate.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        
        if (quickAccessList == null) return;
        for (QuickAccess.Entry entry : quickAccessList)
            if (entry.boardName == null && entry.chan != null && entry.chan.getChanName().equals(chan.getChanName()))
                return;
        menu.add(Menu.NONE, R.id.menu_quickaccess_add, 102, resources.getString(R.string.menu_quickaccess_add)).
                setIcon(ThemeUtils.getTintedIcon(activity.getTheme(), resources, R.drawable.ic_menu_favorite, R.attr.iconTint));
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case R.id.menu_update:
                update(true);
                return true;
            case R.id.menu_quickaccess_add:
                QuickAccess.Entry newEntry = new QuickAccess.Entry();
                newEntry.chan = chan;
                quickAccessList.add(0, newEntry);
                QuickAccess.saveQuickAccessToPreferences(quickAccessList);
                item.setVisible(false);
                return true;
        }
        return super.onContextItemSelected(item);
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (isFailInstance) {
            Toast.makeText(activity, R.string.error_unknown, Toast.LENGTH_LONG).show();
            return new View(activity);
        }
        startItem = tabModel.startItemNumber;
        startItemTop = tabModel.startItemTop;
        binding = BoardslistFragmentBinding.inflate(inflater, container, false);
        rootView = binding.getRoot();
        loadingView = binding.boardslistLoading.getRoot();
        errorView = binding.boardslistError.getRoot();
        errorTextView = binding.boardslistError.frameErrorText;
        listView = (ListView) rootView.findViewById(android.R.id.list);
        listView.setOnItemClickListener(this);
        registerForContextMenu(listView);
        boardField = binding.boardslistBoardField;
        boardField.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if ((event.getAction() == KeyEvent.ACTION_DOWN) && (keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onClick(v);
                    return true;
                }
                return false;
            }
        });
        buttonGo = binding.boardslistBtnGo;
        buttonGo.setOnClickListener(this);
        activity.setTitle(chan.getDisplayingName());

        update(false);
        return rootView;
    }
    
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (adapter != null) {
            hideKeyboard(listView);
            SimpleBoardModel boardModel = adapter.getItem(position).model;
            if (boardModel != null) {
                UrlPageModel model = getUrlModel(boardModel.boardName);
                String url = chan.buildUrl(model);
                UrlHandler.open(url, activity);
            }
        }
        
    }
    
    @Override
    public void onClick(View v) {
        String boardName = boardField.getText().toString();
        if (boardName.length() == 0) return;
        hideKeyboard(v);
        UrlPageModel model = getUrlModel(boardName);
        try {
            String url = chan.buildUrl(model);
            UrlHandler.open(url, activity);
        } catch (Exception e) {
            Logger.e(TAG, e);
        }
    }
    
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        if (adapter == null) return;
        try {
            if (v.getId() == android.R.id.list) {
                AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
                SimpleBoardModel boardModel = adapter.getItem(info.position).model;
                if (boardModel != null) {
                    UrlPageModel model = getUrlModel(boardModel.boardName);
                    model = UrlHandler.getPageModel(chan.buildUrl(model));
                    if (model != null) {
                        boolean isFavorite = database.isFavorite(model.chanName, model.boardName, Integer.toString(model.boardPage), null);
                        menu.add(Menu.NONE, R.id.context_menu_favorites_from_fragment, 1,
                                isFavorite ? R.string.context_menu_remove_favorites : R.string.context_menu_add_favorites);
                        
                        for (QuickAccess.Entry entry : quickAccessList)
                            if (entry.boardName != null && entry.chan != null)
                                if (entry.chan.getChanName().equals(model.chanName) && entry.boardName.equals(model.boardName))
                                    return;
                        menu.add(Menu.NONE, R.id.context_menu_quickaccess_add, 2, R.string.context_menu_quickaccess_add);
                    }
                }
            }
        } catch (Exception e) {
            Logger.e(TAG, e);
        }
    }
    
    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo menuInfo = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        try {
            if (item.getItemId() == R.id.context_menu_favorites_from_fragment) {
                String url = chan.buildUrl(getUrlModel(adapter.getItem(menuInfo.position).model.boardName));
                UrlPageModel model = UrlHandler.getPageModel(url);
                if (model != null) {
                    if (database.isFavorite(model.chanName, model.boardName, Integer.toString(model.boardPage), null)) {
                        database.removeFavorite(model.chanName, model.boardName, Integer.toString(model.boardPage), null);
                    } else {
                        database.addFavorite(model.chanName, model.boardName, Integer.toString(model.boardPage), null,
                                getString(R.string.tabs_title_boardpage_first, model.boardName), url);
                    }
                    updateListSavePosition();
                    return true;
                }
            } else if (item.getItemId() == R.id.context_menu_quickaccess_add) {
                QuickAccess.Entry newEntry = new QuickAccess.Entry();
                newEntry.chan = chan;
                SimpleBoardModel simleBoardModel = adapter.getItem(menuInfo.position).model;
                newEntry.boardName = simleBoardModel.boardName;
                newEntry.boardDescription = simleBoardModel.boardDescription;
                quickAccessList.add(0, newEntry);
                QuickAccess.saveQuickAccessToPreferences(quickAccessList);
                return true;
            }
        } catch (Exception e) {
            Logger.e(TAG, e);
        }
        return false;
    }

    private UrlPageModel getUrlModel(String boardName) {
        UrlPageModel model = new UrlPageModel();
        model.chanName = chan.getChanName();
        model.type = UrlPageModel.TYPE_BOARDPAGE;
        model.boardName = boardName;
        model.boardPage = UrlPageModel.DEFAULT_FIRST_PAGE;
        return model;
    }

    private void hideKeyboard(View v) {
        try {
            InputMethodManager imm = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Exception e) {
            Logger.e(TAG, e);
        }
    }
    
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        saveCurrentPostPosition();
    }
    
    @Override
    public void onDestroyView() {
        saveCurrentPostPosition();
        if (currentTask != null) currentTask.cancel();
        currentTask = null;
        if (toast != null) toast.hide();
        toast = null;
        if (listView != null) {
            unregisterForContextMenu(listView);
            listView.setOnItemClickListener(null);
            listView.setOnLongClickListener(null);
            listView.setAdapter(null);
        }
        if (boardField != null) boardField.setOnKeyListener(null);
        if (buttonGo != null) buttonGo.setOnClickListener(null);

        binding = null;
        rootView = null;
        loadingView = null;
        errorView = null;
        errorTextView = null;
        listView = null;
        boardField = null;
        buttonGo = null;
        adapter = null;
        super.onDestroyView();
    }
    
    private void saveCurrentPostPosition() {
        if (tabModel != null && listView != null && listView.getChildCount() > 0 && adapter != null) {
            View v = listView.getChildAt(0);
            int position = listView.getPositionForView(v);
            BoardsListEntry model = adapter.getItem(position);
            tabModel.startItemNumber = model.isSeparator ? model.category : model.model.boardName;
            tabModel.startItemTop = v == null ? 0 : v.getTop();
            MainApplication.getInstance().serializer.serializeTabsState(MainApplication.getInstance().tabsState);
        }
    }
    
    private void switchToLoadingView() {
        if (loadingView == null || errorView == null || listView == null) return;
        loadingView.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.GONE);
        listView.setVisibility(View.GONE);
    }
    
    private String fixErrorMessage(String message) {
        if (message == null || message.length() == 0) {
            return resources.getString(R.string.error_unknown);
        }
        return message;
    }
    
    private void switchToErrorView(String message) {
        if (loadingView == null || errorView == null || listView == null || errorTextView == null) return;
        loadingView.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
        errorTextView.setText(fixErrorMessage(message));
    }
    
    private void switchToListView() {
        if (loadingView == null || errorView == null || listView == null) return;
        loadingView.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);
        listView.setVisibility(View.VISIBLE);
    }
    
    private void showToastError(String message) {
        Toast.makeText(activity, fixErrorMessage(message), Toast.LENGTH_LONG).show();
    }
    
    private void saveHistory() {
        MainApplication.getInstance().database.addHistory(tabModel.pageModel.chanName, null, null, null, tabModel.title, tabModel.webUrl);
    }
    
    /**
     * Обновить список (в случае изменения параметра отображения NSFW досок)
     */
    public void updateList() {
        update(false);
    }
    
    private void updateListSavePosition() {
        try {
            View v = listView.getChildAt(0);
            int position = listView.getPositionForView(v);
            BoardsListEntry model = adapter.getItem(position);
            startItem = model.isSeparator ? model.category : model.model.boardName;
            startItemTop = v == null ? 0 : v.getTop();
        } catch (Exception e) {
            Logger.e(TAG, e);
        }
        updateList();
    }
    
    private void update(boolean forceUpdate) {
        if (listView == null || binding == null) return;
        listView.setAdapter(null);
        adapter = null;
        switchToLoadingView();
        if (currentTask != null) {
            currentTask.cancel();
        }
        BoardsListGetter boardsListGetter = new BoardsListGetter(forceUpdate);
        currentTask = boardsListGetter;
        Async.runAsync(boardsListGetter);
    }
    
    private class BoardsListGetter extends CancellableTask.BaseCancellableTask implements Runnable {
        private final boolean forceUpdate;
        public BoardsListGetter(boolean forceUpdate) {
            this.forceUpdate = forceUpdate;
        }
        @Override
        public void run() {
            if (forceUpdate) saveHistory();
            SerializableBoardsList fromCache = pagesCache.getSerializableBoardsList(tabModel.hash);
            if (fromCache == null || fromCache.chanName == null || !fromCache.chanName.equals(chan.getChanName())) fromCache = null;
            if (fromCache != null) boardsList = fromCache.boards;
            if (fromCache == null || forceUpdate) {
                try {
                    SimpleBoardModel[] fromChan = chan.getBoardsList(null, this, boardsList);
                    SerializableBoardsList serializableBoardsList = new SerializableBoardsList();
                    serializableBoardsList.boards = fromChan;
                    serializableBoardsList.chanName = chan.getChanName();
                    pagesCache.putSerializableBoardsList(tabModel.hash, serializableBoardsList);
                    boardsList = fromChan;
                } catch (Exception e) {
                    Logger.e(TAG, e);
                    if (isCancelled()) return;
                    if (e instanceof InteractiveException) {
                        ((InteractiveException) e).handle(activity, BoardsListGetter.this, new InteractiveException.Callback() {
                            @Override public void onSuccess() { update(true); }
                            @Override public void onError(String message) {
                                if (boardsList == null) {
                                    switchToErrorView(message);
                                } else {
                                    showToastError(message);
                                    update(false);
                                }
                            }
                        });
                    } else {
                        final String message = e.getMessage();
                        Async.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (boardsList == null) {
                                    switchToErrorView(message);
                                } else {
                                    showToastError(message);
                                }
                            }
                        });
                    }
                }
            }
            if (isCancelled()) return;
            if (boardsList == null) return;
            
            adapter = new BoardsListAdapter(BoardsListFragment.this);
            int startItemSearch = -1;
            if (startItem != null) {
                for (int i=0; i<adapter.getCount(); ++i) {
                    String curItem = adapter.getItem(i).isSeparator ? adapter.getItem(i).category : adapter.getItem(i).model.boardName;
                    if (curItem.equals(startItem)) {
                        startItemSearch = i;
                        break;
                    }
                }
                startItem = null;
            }
            final int startItemPosition = startItemSearch;
            Async.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (isCancelled() || binding == null || listView == null) return;
                    listView.setAdapter(adapter);
                    listView.setSelectionFromTop(startItemPosition, startItemTop);
                    switchToListView();
                }
            });
        }
    }
    
    static class BoardsListEntry {
        public final SimpleBoardModel model;
        public final String category;
        public final boolean isSeparator;
        public BoardsListEntry(SimpleBoardModel model) {
            this.model = model;
            this.category = null;
            isSeparator = false;
        }
        public BoardsListEntry(String category) {
            this.model = null;
            this.category = category;
            isSeparator = true;
        }
    }
    
    static class BoardsListAdapter extends ArrayAdapter<BoardsListEntry> {
        private static final int ITEM_VIEW_TYPE_BOARD = 0;
        private static final int ITEM_VIEW_TYPE_SEPARATOR = 1;
        
        private LayoutInflater inflater;
        private Resources resources;
        
        public BoardsListAdapter(BoardsListFragment fragment) {
            super(fragment.activity, 0);
            this.inflater = LayoutInflater.from(fragment.activity);
            this.resources = fragment.resources;
            String lastCategory = "";
            
            LinkedHashMap<String, String> favBoards = new LinkedHashMap<>();
            for (String board : fragment.database.getFavoriteBoards(fragment.chan)) favBoards.put(board, "");
            if (!favBoards.isEmpty()) {
                lastCategory = resources.getString(R.string.boardslist_favorite_boards);
                add(new BoardsListEntry(lastCategory));
                for (int i=0; i<fragment.boardsList.length; ++i)
                    if (favBoards.containsKey(fragment.boardsList[i].boardName))
                        favBoards.put(fragment.boardsList[i].boardName, fragment.boardsList[i].boardDescription);
                for (Map.Entry<String, String> entry : favBoards.entrySet()) {
                    SimpleBoardModel model = new SimpleBoardModel();
                    model.chan = fragment.chan.getChanName();
                    model.boardName = entry.getKey();
                    model.boardDescription = entry.getValue();
                    model.boardCategory = lastCategory;
                    add(new BoardsListEntry(model));
                }
            }
            for (int i=0; i<fragment.boardsList.length; ++i) {
                if (!fragment.settings.showNSFWBoards() && fragment.boardsList[i].nsfw) continue;
                String curCategory = fragment.boardsList[i].boardCategory != null ? fragment.boardsList[i].boardCategory : "";
                if (!curCategory.equals(lastCategory)) {
                    add(new BoardsListEntry(curCategory));
                    lastCategory = curCategory;
                }
                add(new BoardsListEntry(fragment.boardsList[i]));
            }
        }
        
        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return this.getItem(position).isSeparator ? ITEM_VIEW_TYPE_SEPARATOR : ITEM_VIEW_TYPE_BOARD;
        }
        
        @Override
        public boolean isEnabled(int position) {
            return !this.getItem(position).isSeparator;
        }
        
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            BoardsListEntry item = this.getItem(position);
            if (convertView == null) {
                convertView = inflater.inflate(item.isSeparator ? R.layout.list_separator : android.R.layout.simple_list_item_1, parent, false);
            }
            if (item.isSeparator) {
                ((TextView) convertView).setText(item.category);
            } else {
                TextView text = (TextView) convertView.findViewById(android.R.id.text1);
                text.setText(resources.getString(R.string.boardslist_format, item.model.boardName, item.model.boardDescription));
            }
            return convertView;
        }
    }
    
}
