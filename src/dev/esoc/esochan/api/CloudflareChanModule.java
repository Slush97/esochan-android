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

import java.io.OutputStream;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import androidx.preference.CheckBoxPreference;
import androidx.preference.PreferenceGroup;
import dev.esoc.esochan.R;
import dev.esoc.esochan.api.interfaces.CancellableTask;
import dev.esoc.esochan.api.interfaces.ProgressListener;
import dev.esoc.esochan.api.util.LazyPreferences;
import dev.esoc.esochan.http.HttpCookie;
import dev.esoc.esochan.http.cloudflare.CloudflareException;
import dev.esoc.esochan.http.streamer.HttpRequestModel;
import dev.esoc.esochan.http.streamer.HttpStreamer;
import dev.esoc.esochan.http.streamer.HttpWrongStatusCodeException;
import org.json.JSONArray;
import org.json.JSONObject;

public abstract class CloudflareChanModule extends AbstractChanModule {

    protected static final String PREF_KEY_CLOUDFLARE_RECAPTCHA_FALLBACK = "PREF_KEY_CLOUDFLARE_RECAPTCHA_FALLBACK";

    protected static final String PREF_KEY_CLOUDFLARE_COOKIE_VALUE = "PREF_KEY_CLOUDFLARE_COOKIE";
    protected static final String PREF_KEY_CLOUDFLARE_COOKIE_DOMAIN = "PREF_KEY_CLOUDFLARE_COOKIE_DOMAIN";

    protected static final String CLOUDFLARE_COOKIE_NAME = "cf_clearance";
    protected static final String CLOUDFLARE_RECAPTCHA_KEY = "6LfOYgoTAAAAAInWDVTLSc8Yibqp-c9DaLimzNGM";

    public CloudflareChanModule(SharedPreferences preferences, Resources resources) {
        super(preferences, resources);
    }

    protected boolean canCloudflare() {
        return true;
    }

    protected String getCloudflareCookieDomain() {
        return preferences.getString(getSharedKey(PREF_KEY_CLOUDFLARE_COOKIE_DOMAIN), null);
    }

    @Override
    protected void initHttpClient() {
        if (canCloudflare()) {
            String cloudflareCookieValue = preferences.getString(getSharedKey(PREF_KEY_CLOUDFLARE_COOKIE_VALUE), null);
            String cloudflareCookieDomain = getCloudflareCookieDomain();
            if (cloudflareCookieValue != null && cloudflareCookieDomain != null) {
                HttpCookie c = new HttpCookie(CLOUDFLARE_COOKIE_NAME, cloudflareCookieValue);
                c.setDomain(cloudflareCookieDomain);
                httpClient.getCookieStore().addCookie(c);
            }
        }
    }

    @Override
    public void saveCookie(HttpCookie cookie) {
        super.saveCookie(cookie);
        if (cookie != null) {
            if (canCloudflare() && cookie.getName().equals(CLOUDFLARE_COOKIE_NAME)) {
                preferences.edit().
                        putString(getSharedKey(PREF_KEY_CLOUDFLARE_COOKIE_VALUE), cookie.getValue()).
                        putString(getSharedKey(PREF_KEY_CLOUDFLARE_COOKIE_DOMAIN), cookie.getDomain()).commit();
            }
        }
    }

    protected void checkCloudflareError(HttpWrongStatusCodeException e, String url) throws CloudflareException {
        String html = e.getHtmlString();
        if (html != null && html.contains("Just a moment...")) {
            throw CloudflareException.antiDDOS(url, getChanName());
        }
        if (e.getStatusCode() == 403) {
            if (html != null && html.contains("CAPTCHA")) {
                throw CloudflareException.withRecaptcha(url, getChanName(), html, cloudflareRecaptchaFallback());
            }
        } else if (e.getStatusCode() == 503) {
            if (html != null && html.contains("cloudflare")) {
                throw CloudflareException.antiDDOS(url, getChanName());
            }
        }
    }

    @Override
    public void addPreferencesOnScreen(PreferenceGroup preferenceGroup) {
        super.addPreferencesOnScreen(preferenceGroup);
        addCloudflareRecaptchaFallbackPreference(preferenceGroup);
    }

    protected void addCloudflareRecaptchaFallbackPreference(PreferenceGroup preferenceGroup) {
        if (canCloudflare()) {
            Context context = preferenceGroup.getContext();
            CheckBoxPreference fallbackPref = new LazyPreferences.CheckBoxPreference(context);
            fallbackPref.setTitle(R.string.pref_cf_recaptcha_fallback);
            fallbackPref.setSummary(R.string.pref_cf_recaptcha_fallback_summary);
            fallbackPref.setKey(getSharedKey(PREF_KEY_CLOUDFLARE_RECAPTCHA_FALLBACK));
            fallbackPref.setDefaultValue(false);
            preferenceGroup.addPreference(fallbackPref);
        }
    }

    protected boolean cloudflareRecaptchaFallback() {
        return preferences.getBoolean(getSharedKey(PREF_KEY_CLOUDFLARE_RECAPTCHA_FALLBACK), false);
    }

    @Override
    public void downloadFile(String url, OutputStream out, ProgressListener listener, CancellableTask task) throws Exception {
        String fixedUrl = fixRelativeUrl(url);
        try {
            HttpRequestModel rqModel = HttpRequestModel.DEFAULT_GET;
            HttpStreamer.getInstance().downloadFileFromUrl(fixedUrl, out, rqModel, httpClient, listener, task, canCloudflare());
        } catch (HttpWrongStatusCodeException e) {
            if (canCloudflare()) checkCloudflareError(e, fixedUrl);
            throw e;
        }
    }

    @Override
    protected JSONObject downloadJSONObject(String url, boolean checkIfModidied, ProgressListener listener, CancellableTask task) throws Exception {
        try {
            HttpRequestModel rqModel = HttpRequestModel.builder().setGET().setCheckIfModified(checkIfModidied).build();
            JSONObject object = HttpStreamer.getInstance().getJSONObjectFromUrl(url, rqModel, httpClient, listener, task, canCloudflare());
            if (task != null && task.isCancelled()) throw new Exception("interrupted");
            if (listener != null) listener.setIndeterminate();
            return object;
        } catch (HttpWrongStatusCodeException e) {
            if (canCloudflare()) checkCloudflareError(e, url);
            throw e;
        }
    }

    @Override
    protected JSONArray downloadJSONArray(String url, boolean checkIfModidied, ProgressListener listener, CancellableTask task) throws Exception {
        try {
            HttpRequestModel rqModel = HttpRequestModel.builder().setGET().setCheckIfModified(checkIfModidied).build();
            JSONArray array = HttpStreamer.getInstance().getJSONArrayFromUrl(url, rqModel, httpClient, listener, task, canCloudflare());
            if (task != null && task.isCancelled()) throw new Exception("interrupted");
            if (listener != null) listener.setIndeterminate();
            return array;
        } catch (HttpWrongStatusCodeException e) {
            if (canCloudflare()) checkCloudflareError(e, url);
            throw e;
        }
    }
}
