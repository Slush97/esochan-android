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

package dev.esoc.esochan.api;

import java.io.InputStream;
import java.io.OutputStream;

import dev.esoc.esochan.R;
import dev.esoc.esochan.api.interfaces.CancellableTask;
import dev.esoc.esochan.api.interfaces.ProgressListener;
import dev.esoc.esochan.api.models.CaptchaModel;
import dev.esoc.esochan.api.models.DeletePostModel;
import dev.esoc.esochan.api.models.PostModel;
import dev.esoc.esochan.api.models.SendPostModel;
import dev.esoc.esochan.api.models.ThreadModel;
import dev.esoc.esochan.api.models.UrlPageModel;
import dev.esoc.esochan.api.util.CryptoUtils;
import dev.esoc.esochan.api.util.LazyPreferences;
import dev.esoc.esochan.common.Logger;
import dev.esoc.esochan.http.HttpCookie;
import dev.esoc.esochan.http.client.ExtendedHttpClient;
import dev.esoc.esochan.http.streamer.HttpRequestModel;
import dev.esoc.esochan.http.streamer.HttpResponseModel;
import dev.esoc.esochan.http.streamer.HttpStreamer;
import org.json.JSONArray;
import org.json.JSONObject;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.InputFilter;
import android.text.InputType;

import androidx.preference.CheckBoxPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.Preference.OnPreferenceChangeListener;

public abstract class AbstractChanModule implements HttpChanModule {
    private static final String TAG = "AbstractChanModule";

    private static final String preferenceKeySplit = "_";

    protected static final String DEFAULT_PROXY_HOST = "127.0.0.1";
    protected static final String DEFAULT_PROXY_PORT = "8118";

    protected static final String PREF_KEY_USE_PROXY = "PREF_KEY_USE_PROXY";
    protected static final String PREF_KEY_PROXY_HOST = "PREF_KEY_PROXY_HOST";
    protected static final String PREF_KEY_PROXY_PORT = "PREF_KEY_PROXY_PORT";
    protected static final String PREF_KEY_PASSWORD = "PREF_KEY_PASSWORD";
    protected static final String PREF_KEY_USE_HTTPS = "PREF_KEY_USE_HTTPS";
    protected static final String PREF_KEY_ONLY_NEW_POSTS = "PREF_KEY_ONLY_NEW_POSTS";

    protected ExtendedHttpClient httpClient;
    protected final Resources resources;
    protected final SharedPreferences preferences;

    private OnPreferenceChangeListener updateHttpListener = new OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            boolean useProxy = preferences.getBoolean(getSharedKey(PREF_KEY_USE_PROXY), false);
            String proxyHost = preferences.getString(getSharedKey(PREF_KEY_PROXY_HOST), DEFAULT_PROXY_HOST);
            String proxyPort = preferences.getString(getSharedKey(PREF_KEY_PROXY_PORT), DEFAULT_PROXY_PORT);

            if (preference.getKey().equals(getSharedKey(PREF_KEY_USE_PROXY))) {
                useProxy = (boolean)newValue;
                updateHttpClient(useProxy, proxyHost, proxyPort);
                return true;
            } else if (preference.getKey().equals(getSharedKey(PREF_KEY_PROXY_HOST))) {
                if (!proxyHost.equals((String)newValue)) {
                    proxyHost = (String)newValue;
                    updateHttpClient(useProxy, proxyHost, proxyPort);
                }
                return true;
            } else if (preference.getKey().equals(getSharedKey(PREF_KEY_PROXY_PORT))) {
                if (!proxyPort.equals((String)newValue)) {
                    proxyPort = (String)newValue;
                    updateHttpClient(useProxy, proxyHost, proxyPort);
                }
                return true;
            }

            return false;
        }
    };

    public AbstractChanModule(SharedPreferences preferences, Resources resources) {
        this.preferences = preferences;
        this.resources = resources;
        updateHttpClient(
                preferences.getBoolean(getSharedKey(PREF_KEY_USE_PROXY), false),
                preferences.getString(getSharedKey(PREF_KEY_PROXY_HOST), DEFAULT_PROXY_HOST),
                preferences.getString(getSharedKey(PREF_KEY_PROXY_PORT), DEFAULT_PROXY_PORT));
    }

    protected final String getSharedKey(String key) {
        return getChanName() + preferenceKeySplit + key;
    }

    private void updateHttpClient(boolean useProxy, String proxyHost, String proxyPort) {
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (Exception e) {
                Logger.e(TAG, e);
            }
        }
        if (useProxy) {
            try {
                int port = Integer.parseInt(proxyPort);
                httpClient = new ExtendedHttpClient(proxyHost, port);
            } catch (Exception e) {
                Logger.e(TAG, e);
                httpClient = new ExtendedHttpClient();
            }
        } else {
            httpClient = new ExtendedHttpClient();
        }
        initHttpClient();
    }

    protected void initHttpClient() {}

    protected void addProxyPreferences(PreferenceGroup group) {
        final Context context = group.getContext();
        PreferenceCategory proxyCat = new PreferenceCategory(context);
        proxyCat.setTitle(R.string.pref_cat_proxy);
        group.addPreference(proxyCat);
        CheckBoxPreference useProxyPref = new LazyPreferences.CheckBoxPreference(context);
        useProxyPref.setTitle(R.string.pref_use_proxy);
        useProxyPref.setSummary(R.string.pref_use_proxy_summary);
        useProxyPref.setKey(getSharedKey(PREF_KEY_USE_PROXY));
        useProxyPref.setDefaultValue(false);
        useProxyPref.setOnPreferenceChangeListener(updateHttpListener);
        proxyCat.addPreference(useProxyPref);
        EditTextPreference proxyHostPref = new LazyPreferences.EditTextPreference(context);
        proxyHostPref.setTitle(R.string.pref_proxy_host);
        proxyHostPref.setDialogTitle(R.string.pref_proxy_host);
        proxyHostPref.setSummary(R.string.pref_proxy_host_summary);
        proxyHostPref.setKey(getSharedKey(PREF_KEY_PROXY_HOST));
        proxyHostPref.setDefaultValue(DEFAULT_PROXY_HOST);
        proxyHostPref.setOnBindEditTextListener(editText -> {
            editText.setSingleLine();
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        });
        proxyHostPref.setOnPreferenceChangeListener(updateHttpListener);
        proxyCat.addPreference(proxyHostPref);
        proxyHostPref.setDependency(getSharedKey(PREF_KEY_USE_PROXY));
        EditTextPreference proxyHostPort = new LazyPreferences.EditTextPreference(context);
        proxyHostPort.setTitle(R.string.pref_proxy_port);
        proxyHostPort.setDialogTitle(R.string.pref_proxy_port);
        proxyHostPort.setSummary(R.string.pref_proxy_port_summary);
        proxyHostPort.setKey(getSharedKey(PREF_KEY_PROXY_PORT));
        proxyHostPort.setDefaultValue(DEFAULT_PROXY_PORT);
        proxyHostPort.setOnBindEditTextListener(editText -> {
            editText.setSingleLine();
            editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        });
        proxyHostPort.setOnPreferenceChangeListener(updateHttpListener);
        proxyCat.addPreference(proxyHostPort);
        proxyHostPort.setDependency(getSharedKey(PREF_KEY_USE_PROXY));
    }

    protected void addPasswordPreference(PreferenceGroup group) {
        final Context context = group.getContext();
        EditTextPreference passwordPref = new EditTextPreference(context) {
            @Override
            protected void onClick() {
                if (createPassword()) {
                    setText(getDefaultPassword());
                }
                super.onClick();
            }
        };
        passwordPref.setTitle(R.string.pref_password_title);
        passwordPref.setDialogTitle(R.string.pref_password_title);
        passwordPref.setSummary(R.string.pref_password_summary);
        passwordPref.setKey(getSharedKey(PREF_KEY_PASSWORD));
        passwordPref.setOnBindEditTextListener(editText -> {
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            editText.setSingleLine();
            editText.setFilters(new InputFilter[] { new InputFilter.LengthFilter(255) });
        });
        group.addPreference(passwordPref);
    }

    @Override
    public ExtendedHttpClient getHttpClient() {
        return httpClient;
    }

    @Override
    public void saveCookie(HttpCookie cookie) {
        if (cookie != null) {
            httpClient.getCookieStore().addCookie(cookie);
        }
    }

    @Override
    public void addPreferencesOnScreen(PreferenceGroup preferenceGroup) {
        addPasswordPreference(preferenceGroup);
        addProxyPreferences(preferenceGroup);
    }

    protected CheckBoxPreference addHttpsPreference(PreferenceGroup group, boolean defaultValue) {
        final Context context = group.getContext();
        CheckBoxPreference httpsPref = new LazyPreferences.CheckBoxPreference(context);
        httpsPref.setTitle(R.string.pref_use_https);
        httpsPref.setSummary(R.string.pref_use_https_summary);
        httpsPref.setKey(getSharedKey(PREF_KEY_USE_HTTPS));
        httpsPref.setDefaultValue(defaultValue);
        group.addPreference(httpsPref);
        return httpsPref;
    }

    protected boolean useHttps(boolean defaultValue) {
        return preferences.getBoolean(getSharedKey(PREF_KEY_USE_HTTPS), defaultValue);
    }

    protected CheckBoxPreference addOnlyNewPostsPreference(PreferenceGroup group, boolean defaultValue) {
        final Context context = group.getContext();
        CheckBoxPreference onlyNewPostsPref = new LazyPreferences.CheckBoxPreference(context);
        onlyNewPostsPref.setTitle(R.string.pref_only_new_posts);
        onlyNewPostsPref.setSummary(R.string.pref_only_new_posts_summary);
        onlyNewPostsPref.setKey(getSharedKey(PREF_KEY_ONLY_NEW_POSTS));
        onlyNewPostsPref.setDefaultValue(defaultValue);
        group.addPreference(onlyNewPostsPref);
        return onlyNewPostsPref;
    }

    protected boolean loadOnlyNewPosts(boolean defaultValue) {
        return preferences.getBoolean(getSharedKey(PREF_KEY_ONLY_NEW_POSTS), defaultValue);
    }

    private boolean createPassword() {
        if (!preferences.contains(getSharedKey(PREF_KEY_PASSWORD))) {
            preferences.edit().putString(getSharedKey(PREF_KEY_PASSWORD), CryptoUtils.genPassword()).commit();
            return true;
        }
        return false;
    }

    @Override
    public String getDefaultPassword() {
        createPassword();
        return preferences.getString(getSharedKey(PREF_KEY_PASSWORD), "");
    }

    @Override
    public String fixRelativeUrl(String url) {
        if (url == null) return null;
        if (Uri.parse(url).getScheme() != null) return url;
        UrlPageModel model = new UrlPageModel();
        model.chanName = getChanName();
        model.type = UrlPageModel.TYPE_OTHERPAGE;
        model.otherPath = url;
        return buildUrl(model);
    }

    @Override
    public ThreadModel[] getCatalog(
            String boardName, int catalogType, ProgressListener listener, CancellableTask task, ThreadModel[] oldList) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public PostModel[] search(String boardName, String searchRequest, ProgressListener listener, CancellableTask task) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public CaptchaModel getNewCaptcha(String boardName, String threadNumber, ProgressListener listener, CancellableTask task) throws Exception {
        return null;
    }

    @Override
    public String sendPost(SendPostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public String deletePost(DeletePostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public String reportPost(DeletePostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public void downloadFile(String url, OutputStream out, ProgressListener listener, CancellableTask task) throws Exception {
        String fixedUrl = fixRelativeUrl(url);
        HttpStreamer.getInstance().downloadFileFromUrl(fixedUrl, out, HttpRequestModel.DEFAULT_GET, httpClient, listener, task, false);
    }

    /**
     * Drop cached If-Modified-Since state for a thread so the next posts fetch is unconditional.
     * Used after posting so CDN 304s do not hide a just-created reply.
     */
    public void invalidateThreadPostsCache(String boardName, String threadNumber) {
    }

    protected JSONObject downloadJSONObject(String url, boolean checkIfModidied, ProgressListener listener, CancellableTask task) throws Exception {
        HttpRequestModel rqModel = HttpRequestModel.builder().setGET().setCheckIfModified(checkIfModidied).build();
        JSONObject object = HttpStreamer.getInstance().getJSONObjectFromUrl(url, rqModel, httpClient, listener, task, false);
        if (task != null && task.isCancelled()) throw new Exception("interrupted");
        if (listener != null) listener.setIndeterminate();
        return object;
    }

    protected JSONArray downloadJSONArray(String url, boolean checkIfModidied, ProgressListener listener, CancellableTask task) throws Exception {
        HttpRequestModel rqModel = HttpRequestModel.builder().setGET().setCheckIfModified(checkIfModidied).build();
        JSONArray array = HttpStreamer.getInstance().getJSONArrayFromUrl(url, rqModel, httpClient, listener, task, false);
        if (task != null && task.isCancelled()) throw new Exception("interrupted");
        if (listener != null) listener.setIndeterminate();
        return array;
    }

    protected CaptchaModel downloadCaptcha(String captchaUrl, ProgressListener listener, CancellableTask task) throws Exception {
        Bitmap captchaBitmap = null;
        HttpRequestModel requestModel = HttpRequestModel.DEFAULT_GET;
        HttpResponseModel responseModel = HttpStreamer.getInstance().getFromUrl(captchaUrl, requestModel, httpClient, listener, task);
        try {
            InputStream imageStream = responseModel.stream;
            captchaBitmap = BitmapFactory.decodeStream(imageStream);
        } finally {
            responseModel.release();
        }
        CaptchaModel captchaModel = new CaptchaModel();
        captchaModel.type = CaptchaModel.TYPE_NORMAL;
        captchaModel.bitmap = captchaBitmap;
        return captchaModel;
    }
}
