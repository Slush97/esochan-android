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

package dev.esoc.esochan.ui.settings;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import android.app.ListActivity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import dev.esoc.esochan.R;
import dev.esoc.esochan.api.interfaces.CancellableTask;
import dev.esoc.esochan.common.Async;
import dev.esoc.esochan.common.Logger;
import dev.esoc.esochan.common.MainApplication;
import dev.esoc.esochan.http.client.ExtendedHttpClient;
import dev.esoc.esochan.http.streamer.HttpRequestModel;
import dev.esoc.esochan.http.streamer.HttpStreamer;
import dev.esoc.esochan.lib.UriFileUtils;
import org.json.JSONArray;

public class CustomThemeListActivity extends ListActivity {
    private static final int REQUEST_CODE_SELECT_CUSTOM_THEME = 1;
    
    private static final String TAG = "CustomThemeListActivity";
    
    private static final String URL_PATH = "https://raw.githubusercontent.com/miku-nyan/Overchan-Themes/master/themes/";
    private static final String URL_INDEX = URL_PATH + "index.json";
    
    private ApplicationSettings settings;
    private ArrayAdapter<String> adapter;
    private ArrayList<String> names, files;
    
    private ExtendedHttpClient httpClient = new ExtendedHttpClient();
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        settings = MainApplication.getInstance().settings;
        settings.getTheme().setToPreferencesActivity(this);
        super.onCreate(savedInstanceState);
        setTitle(R.string.custom_themes_title);
        names = new ArrayList<String>();
        names.add(getString(R.string.custom_themes_local));
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names);
        setListAdapter(adapter);
        
        final CancellableTask task = new CancellableTask.BaseCancellableTask();
        final AlertDialog progressDialog = showProgressDialog(task);
        Async.runAsync(new Runnable() {
            @Override
            public void run() {
                JSONArray r;
                try {
                    HttpRequestModel request = HttpRequestModel.DEFAULT_GET;
                    r = HttpStreamer.getInstance().getJSONArrayFromUrl(URL_INDEX, request, httpClient, null, task, false);
                } catch (Exception e) {
                    r = null;
                }
                final JSONArray response = r;
                
                if (task.isCancelled()) return;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (task.isCancelled()) return;
                        progressDialog.dismiss();
                        try {
                            if (response == null) throw new Exception();
                            files = new ArrayList<String>(response.length() + 1);
                            files.add(null);
                            for (int i=0; i<response.length(); ++i) {
                                JSONArray current = response.getJSONArray(i);
                                files.add(current.getString(1));
                                names.add(current.getString(0));
                            }
                        } catch (Exception e) {
                            Logger.e(TAG, e);
                            Toast.makeText(CustomThemeListActivity.this, R.string.error_connection, Toast.LENGTH_LONG).show();
                        }
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        });
    }
    
    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        if (position == 0) {
            Intent selectFile = UriFileUtils.createOpenDocumentIntent(new String[] { "json" });
            startActivityForResult(selectFile, REQUEST_CODE_SELECT_CUSTOM_THEME);
            return;
        }
        final String url = URL_PATH + files.get(position);
        final CancellableTask task = new CancellableTask.BaseCancellableTask();
        final AlertDialog progressDialog = showProgressDialog(task);
        Async.runAsync(new Runnable() {
            @Override
            public void run() {
                String r;
                try {
                    HttpRequestModel request = HttpRequestModel.DEFAULT_GET;
                    r = HttpStreamer.getInstance().getStringFromUrl(url, request, httpClient, null, task, false);
                } catch (Exception e) {
                    r = null;
                }
                final String response = r;
                
                if (task.isCancelled()) return;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (task.isCancelled()) return;
                        progressDialog.dismiss();
                        try {
                            MainApplication.getInstance().settings.setCustomTheme(response);
                        } catch (Exception e) {
                            Logger.e(TAG, e);
                            Toast.makeText(CustomThemeListActivity.this,
                                    e.getMessage() != null ? e.getMessage() : e.toString(), Toast.LENGTH_LONG).show();
                        }
                        finish();
                    }
                });
            }
        });
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && requestCode == REQUEST_CODE_SELECT_CUSTOM_THEME
                && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            Async.runAsync(new Runnable() {
                @Override
                public void run() {
                    String theme = null;
                    int errorResId = 0;
                    Exception failure = null;
                    if (!UriFileUtils.hasAllowedDocument(
                            CustomThemeListActivity.this, uri, new String[] { "json" })) {
                        errorResId = R.string.custom_themes_invalid_file;
                    } else {
                        try {
                            theme = UriFileUtils.readText(CustomThemeListActivity.this, uri,
                                    UriFileUtils.MAX_THEME_BYTES, StandardCharsets.UTF_8);
                        } catch (UriFileUtils.FileTooLargeException e) {
                            errorResId = R.string.custom_themes_file_too_large;
                        } catch (Exception e) {
                            failure = e;
                        }
                    }

                    final String importedTheme = theme;
                    final int importedErrorResId = errorResId;
                    final Exception importedFailure = failure;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            try {
                                if (importedErrorResId != 0) {
                                    Toast.makeText(CustomThemeListActivity.this,
                                            importedErrorResId, Toast.LENGTH_LONG).show();
                                } else if (importedFailure != null) {
                                    Logger.e(TAG, importedFailure);
                                    Toast.makeText(CustomThemeListActivity.this,
                                            R.string.error_unknown, Toast.LENGTH_LONG).show();
                                } else {
                                    MainApplication.getInstance().settings.setCustomTheme(importedTheme);
                                    finish();
                                }
                            } catch (Exception e) {
                                Logger.e(TAG, e);
                                Toast.makeText(CustomThemeListActivity.this,
                                        e.getMessage() != null ? e.getMessage() : e.toString(), Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                }
            });
        }
    }
    
    private AlertDialog showProgressDialog(final CancellableTask task) {
        return SettingsProgress.show(this, getString(R.string.custom_themes_loading),
                new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        task.cancel();
                    }
                });
    }
}
