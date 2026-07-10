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

import java.io.File;

import dev.esoc.esochan.common.Tuples.Pair;

import dev.esoc.esochan.R;
import dev.esoc.esochan.api.models.BoardModel;
import dev.esoc.esochan.api.models.SendPostModel;
import dev.esoc.esochan.api.models.UrlPageModel;
import dev.esoc.esochan.api.util.ChanModels;
import dev.esoc.esochan.cache.SerializablePage;
import dev.esoc.esochan.common.Async;
import dev.esoc.esochan.common.Logger;
import dev.esoc.esochan.common.MainApplication;
import dev.esoc.esochan.lib.UriFileUtils;
import dev.esoc.esochan.ui.posting.PostFormActivity;
import dev.esoc.esochan.ui.posting.PostingService;
import dev.esoc.esochan.ui.tabs.TabModel;
import dev.esoc.esochan.ui.tabs.UrlHandler;
import android.app.ListActivity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class ShareActivity extends ListActivity {
    private static final String TAG = "ShareActivity";
    
    private File selectedFile;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MainApplication.getInstance().settings.getTheme().setTo(this);
        handleIntent(getIntent());
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }
    
    private void handleIntent(Intent intent) {
        ArrayAdapter<Pair<TabModel, SerializablePage>> adapter = new ArrayAdapter<Pair<TabModel, SerializablePage>>(this, 0) {
            private final int drawablePadding = (int) (getResources().getDisplayMetrics().density * 5 + 0.5f);
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView == null ? getLayoutInflater().inflate(android.R.layout.simple_list_item_1, parent, false) : convertView;
                TextView tv = (TextView) view.findViewById(android.R.id.text1);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setSingleLine();
                tv.setText(getItem(position).getLeft().title);
                tv.setCompoundDrawablesWithIntrinsicBounds(MainApplication.getInstance().
                        getChanModule(getItem(position).getLeft().pageModel.chanName).getChanFavicon(), null, null, null);
                tv.setCompoundDrawablePadding(drawablePadding);
                return view;
            }
        };
        for (TabModel tab : MainApplication.getInstance().tabsState.tabsArray) {
            if (tab.type == TabModel.TYPE_NORMAL && tab.pageModel.type != UrlPageModel.TYPE_SEARCHPAGE) {
                SerializablePage page = MainApplication.getInstance().pagesCache.getSerializablePage(tab.hash);
                if (page != null) {
                    adapter.add(Pair.of(tab, page));
                }
            }
        }
        if (adapter.getCount() == 0) {
            for (Database.HistoryEntry entity : MainApplication.getInstance().database.getHistory()) {
                try {
                    TabModel tab = new TabModel();
                    tab.title = entity.title;
                    tab.type = TabModel.TYPE_NORMAL;
                    tab.webUrl = entity.url;
                    tab.pageModel = UrlHandler.getPageModel(entity.url);
                    tab.hash = ChanModels.hashUrlPageModel(tab.pageModel);
                    SerializablePage page = MainApplication.getInstance().pagesCache.getSerializablePage(tab.hash);
                    if (page != null) {
                        adapter.add(Pair.of(tab, page));
                    }
                } catch (Exception e) {
                    Logger.e(TAG, e);
                }
            }
            if (adapter.getCount() == 0) {
                Toast.makeText(this, R.string.share_no_tabs, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        if (PostingService.isNowPosting()) {
            Toast.makeText(this, R.string.posting_now_posting, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        selectedFile = null;
        Uri sharedUri = null;
        if (intent != null) {
            sharedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (sharedUri == null) {
            Toast.makeText(this, R.string.postform_cannot_attach, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        final Uri uri = sharedUri;
        Async.runAsync(new Runnable() {
            @Override
            public void run() {
                File importedFile = null;
                boolean tooLarge = false;
                try {
                    importedFile = UriFileUtils.copyToCache(
                            getApplicationContext(), uri, UriFileUtils.MAX_ATTACHMENT_BYTES);
                } catch (UriFileUtils.FileTooLargeException e) {
                    tooLarge = true;
                } catch (Exception e) {
                    Logger.e(TAG, e);
                }

                final File file = importedFile;
                final boolean showTooLarge = tooLarge;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (showTooLarge) {
                            long maxMb = UriFileUtils.MAX_ATTACHMENT_BYTES / (1024L * 1024L);
                            Toast.makeText(ShareActivity.this,
                                    getString(R.string.postform_attachment_too_large, maxMb), Toast.LENGTH_LONG).show();
                            finish();
                        } else if (file == null) {
                            Toast.makeText(ShareActivity.this,
                                    R.string.postform_cannot_attach, Toast.LENGTH_LONG).show();
                            finish();
                        } else {
                            selectedFile = file;
                            setListAdapter(adapter);
                        }
                    }
                });
            }
        });
    }
    
    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        @SuppressWarnings("unchecked")
        Pair<TabModel, SerializablePage> item = (Pair<TabModel, SerializablePage>) getListAdapter().getItem(position);
        SendPostModel draft = MainApplication.getInstance().draftsCache.get(item.getLeft().hash);
        if (draft == null) {
            draft = new SendPostModel();
            draft.chanName = item.getLeft().pageModel.chanName;
            draft.boardName = item.getLeft().pageModel.boardName;
            draft.threadNumber = item.getLeft().pageModel.type == UrlPageModel.TYPE_THREADPAGE ? item.getLeft().pageModel.threadNumber : null;
            draft.comment = "";
            BoardModel boardModel = item.getRight().boardModel;
            if (boardModel.allowNames) draft.name = MainApplication.getInstance().settings.getDefaultName();
            if (boardModel.allowEmails) draft.email = MainApplication.getInstance().settings.getDefaultEmail();
            if (boardModel.allowDeletePosts || boardModel.allowDeleteFiles) draft.password =
                    MainApplication.getInstance().getChanModule(item.getLeft().pageModel.chanName).getDefaultPassword();
            if (boardModel.allowRandomHash) draft.randomHash = MainApplication.getInstance().settings.isRandomHash();
        }
        
        BoardModel boardModel = item.getRight().boardModel;
        if (!UriFileUtils.hasAllowedExtension(selectedFile, boardModel.attachmentsFormatFilters)) {
            Toast.makeText(this, R.string.postform_unsupported_attachment, Toast.LENGTH_LONG).show();
            return;
        }
        int attachmentsCount = draft.attachments == null ? 0 : draft.attachments.length;
        ++attachmentsCount;
        if (attachmentsCount > boardModel.attachmentsMaxCount) {
            Toast.makeText(this, R.string.postform_max_attachments, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        File[] attachments = new File[attachmentsCount];
        for (int i=0; i<(attachmentsCount-1); ++i) attachments[i] = draft.attachments[i];
        attachments[attachmentsCount-1] = selectedFile;
        draft.attachments = attachments;
        
        if (PostingService.isNowPosting()) {
            Toast.makeText(this, R.string.posting_now_posting, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Intent addPostIntent = new Intent(this.getApplicationContext(), PostFormActivity.class);
        addPostIntent.putExtra(PostingService.EXTRA_PAGE_HASH, item.getLeft().hash);
        addPostIntent.putExtra(PostingService.EXTRA_BOARD_MODEL, item.getRight().boardModel);
        addPostIntent.putExtra(PostingService.EXTRA_SEND_POST_MODEL, draft);
        finish();
        startActivity(addPostIntent);
    }
}
