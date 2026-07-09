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

package dev.esoc.esochan.chans.fourchan;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.esoc.esochan.http.HttpCookie;
import dev.esoc.esochan.http.HttpHeader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.core.content.res.ResourcesCompat;

import android.text.Html;
import android.text.InputType;
import android.widget.Toast;
import dev.esoc.esochan.BuildConfig;
import dev.esoc.esochan.R;
import dev.esoc.esochan.api.CloudflareChanModule;
import dev.esoc.esochan.ui.settings.SettingsProgress;
import dev.esoc.esochan.api.interfaces.CancellableTask;
import dev.esoc.esochan.api.interfaces.ProgressListener;
import dev.esoc.esochan.api.models.BoardModel;
import dev.esoc.esochan.api.models.CaptchaModel;
import dev.esoc.esochan.api.models.DeletePostModel;
import dev.esoc.esochan.api.models.PostModel;
import dev.esoc.esochan.api.models.SendPostModel;
import dev.esoc.esochan.api.models.SimpleBoardModel;
import dev.esoc.esochan.api.models.ThreadModel;
import dev.esoc.esochan.api.models.UrlPageModel;
import dev.esoc.esochan.api.util.ChanModels;
import dev.esoc.esochan.common.Async;
import dev.esoc.esochan.common.Logger;
import dev.esoc.esochan.common.SecurePreferences;
import dev.esoc.esochan.http.ExtendedMultipartBuilder;
import dev.esoc.esochan.http.streamer.HttpRequestModel;
import dev.esoc.esochan.http.streamer.HttpStreamer;
import dev.esoc.esochan.http.streamer.HttpWrongStatusCodeException;
import org.json.JSONArray;
import org.json.JSONObject;

public class FourchanModule extends CloudflareChanModule {
    private static final String TAG = "FourchanModule";
    private static final int POST_RESPONSE_CAPTURE_MAX_LENGTH = 12000;
    private static final int POST_RESPONSE_CAPTURE_CHUNK_SIZE = 3000;
    
    static final String CHAN_NAME = "4chan.org";
    
    private static final String PREF_KEY_PASS_TOKEN = "PREF_KEY_PASS_TOKEN";
    private static final String PREF_KEY_PASS_PIN = "PREF_KEY_PASS_PIN";
    private static final String PREF_KEY_PASS_COOKIE = "PREF_KEY_PASS_COOKIE";

    /** Official auth posts to both domains so pass cookies work on NSFW and SFW hosts. */
    private static final String[] AUTH_HOSTS = { "sys.4chan.org", "sys.4channel.org" };
    private static final String[] PASS_COOKIE_DOMAINS = { ".4chan.org", ".4channel.org" };
    
    private boolean usingPasscode = false;

    private Map<String, BoardModel> boardsMap = null;
    
    private static final Pattern AUTH_HTML_ERROR = Pattern.compile(
            "(?:class=\"msg-error\"[^>]*>|<strong style=\"color: red; font-size: larger;\">)(.*?)</");
    
    public FourchanModule(SharedPreferences preferences, Resources resources) {
        super(preferences, resources);
    }
    
    @Override
    public String getChanName() {
        return CHAN_NAME;
    }
    
    @Override
    public String getDisplayingName() {
        return "4chan";
    }
    
    @Override
    public Drawable getChanFavicon() {
        return ResourcesCompat.getDrawable(resources, R.drawable.favicon_4chan, null);
    }
    
    @Override
    protected void initHttpClient() {
        super.initHttpClient();
        String cookieKey = getSharedKey(PREF_KEY_PASS_COOKIE);
        SecurePreferences.INSTANCE.migrateFromPlain(preferences, cookieKey);
        SecurePreferences.INSTANCE.migrateFromPlain(preferences, getSharedKey(PREF_KEY_PASS_TOKEN));
        SecurePreferences.INSTANCE.migrateFromPlain(preferences, getSharedKey(PREF_KEY_PASS_PIN));
        setPasscodeCookie(SecurePreferences.INSTANCE.get(cookieKey), false);
    }
    
    private static String maskCredential(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() <= 4) return "****";
        return "****" + value.substring(value.length() - 4);
    }

    private String getPassToken() {
        return SecurePreferences.INSTANCE.get(getSharedKey(PREF_KEY_PASS_TOKEN));
    }

    private String getPassPin() {
        return SecurePreferences.INSTANCE.get(getSharedKey(PREF_KEY_PASS_PIN));
    }

    private String passStatusSummary() {
        return usingPasscode ? "Logged in — captcha skipped when posting" : "Not logged in";
    }

    private void addPasscodePreference(PreferenceGroup preferenceGroup) {
        final Context context = preferenceGroup.getContext();
        final PreferenceScreen passScreen = preferenceGroup.getPreferenceManager().createPreferenceScreen(context);
        passScreen.setTitle("4chan Pass");
        passScreen.setSummary(passStatusSummary());
        passScreen.setKey(getSharedKey("PREF_KEY_PASS_SCREEN"));

        final Preference statusPreference = new Preference(context);
        statusPreference.setKey(getSharedKey("PREF_KEY_PASS_STATUS"));
        statusPreference.setTitle("Status");
        statusPreference.setSummary(passStatusSummary());
        statusPreference.setSelectable(false);

        Preference passTokenPreference = new Preference(context);
        passTokenPreference.setTitle("Token");
        passTokenPreference.setSummary(maskCredential(getPassToken()));
        passTokenPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                final android.widget.EditText input = new android.widget.EditText(context);
                input.setSingleLine();
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                input.setText(getPassToken());
                new AlertDialog.Builder(context)
                    .setTitle("Token")
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            SecurePreferences.INSTANCE.put(getSharedKey(PREF_KEY_PASS_TOKEN), input.getText().toString().trim());
                            preference.setSummary(maskCredential(input.getText().toString().trim()));
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return true;
            }
        });
        Preference passPINPreference = new Preference(context);
        passPINPreference.setTitle("PIN");
        passPINPreference.setSummary(maskCredential(getPassPin()));
        passPINPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                final android.widget.EditText input = new android.widget.EditText(context);
                input.setSingleLine();
                input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
                input.setText(getPassPin());
                new AlertDialog.Builder(context)
                    .setTitle("PIN")
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            SecurePreferences.INSTANCE.put(getSharedKey(PREF_KEY_PASS_PIN), input.getText().toString().trim());
                            preference.setSummary(maskCredential(input.getText().toString().trim()));
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return true;
            }
        });
        Preference passLoginPreference = new Preference(context);
        passLoginPreference.setTitle("Log in");
        passLoginPreference.setSummary("Authenticate this device with Token + PIN");
        passLoginPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                final String token = getPassToken();
                final String pin = getPassPin();
                if (token.isEmpty() || pin.isEmpty()) {
                    Toast.makeText(context, "Enter Token and PIN first", Toast.LENGTH_LONG).show();
                    return true;
                }
                final CancellableTask passAuthTask = new CancellableTask.BaseCancellableTask();
                final androidx.appcompat.app.AlertDialog passAuthProgressDialog = SettingsProgress.show(context,
                        "Logging in…",
                        new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface dialog) {
                                passAuthTask.cancel();
                            }
                        });
                Async.runAsync(new Runnable() {
                    @Override
                    public void run() {
                        String error = null;
                        try {
                            if (passAuthTask.isCancelled()) return;
                            error = loginPass(token, pin, passAuthTask);
                        } catch (Exception e) {
                            error = e.getMessage() == null ? resources.getString(R.string.error_unknown) : e.getMessage();
                        }
                        final String resultError = error;
                        if (context instanceof Activity) {
                            ((Activity) context).runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    passAuthProgressDialog.dismiss();
                                    statusPreference.setSummary(passStatusSummary());
                                    passScreen.setSummary(passStatusSummary());
                                    if (resultError == null) {
                                        Toast.makeText(context, "Logged in — captcha skipped when posting", Toast.LENGTH_LONG).show();
                                    } else {
                                        Toast.makeText(context, resultError, Toast.LENGTH_LONG).show();
                                    }
                                }
                            });
                        }
                    }
                });
                return true;
            }
        });
        Preference passLogoutPreference = new Preference(context);
        passLogoutPreference.setTitle("Log out");
        passLogoutPreference.setSummary("Clear pass session on this device (keeps Token/PIN)");
        passLogoutPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                final CancellableTask logoutTask = new CancellableTask.BaseCancellableTask();
                final androidx.appcompat.app.AlertDialog progress = SettingsProgress.show(context,
                        "Logging out…",
                        new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface dialog) {
                                logoutTask.cancel();
                            }
                        });
                Async.runAsync(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            logoutPass(logoutTask);
                        } catch (Exception ignored) {
                            setPasscodeCookie(null, true);
                        }
                        if (context instanceof Activity) {
                            ((Activity) context).runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    progress.dismiss();
                                    statusPreference.setSummary(passStatusSummary());
                                    passScreen.setSummary(passStatusSummary());
                                    Toast.makeText(context, "Logged out", Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    }
                });
                return true;
            }
        });
        passScreen.addPreference(statusPreference);
        passScreen.addPreference(passTokenPreference);
        passScreen.addPreference(passPINPreference);
        passScreen.addPreference(passLoginPreference);
        passScreen.addPreference(passLogoutPreference);
        preferenceGroup.addPreference(passScreen);
    }

    /**
     * Authenticate against both 4chan hosts (official client behaviour).
     * @return null on success, user-facing error message on failure
     */
    private String loginPass(String token, String pin, CancellableTask task) throws Exception {
        if (token == null || token.isEmpty() || pin == null || pin.isEmpty()) {
            return "You have left one or more fields blank";
        }
        token = token.trim();
        pin = pin.trim();
        setPasscodeCookie(null, true);

        String credentialError = null;
        String networkError = null;
        String passId = null;

        for (String host : AUTH_HOSTS) {
            if (task != null && task.isCancelled()) return "Cancelled";
            String authUrl = "https://" + host + "/auth";
            okhttp3.FormBody body = new okhttp3.FormBody.Builder()
                    .add("xhr", "1")
                    .add("id", token)
                    .add("pin", pin)
                    .add("long_login", "1")
                    .build();
            HttpRequestModel request = HttpRequestModel.builder()
                    .setPOST(body)
                    .setCustomHeaders(getAuthHeaders(host))
                    .build();
            String response;
            try {
                response = HttpStreamer.getInstance().getStringFromUrl(authUrl, request, httpClient, null, task, false);
            } catch (Exception e) {
                networkError = e.getMessage() == null ? resources.getString(R.string.error_unknown) : e.getMessage();
                continue;
            }
            if (task != null && task.isCancelled()) return "Cancelled";
            if (response == null || response.isEmpty()) {
                networkError = "Empty auth response from " + host;
                continue;
            }

            AuthResult result = parseAuthResponse(response);
            if (result.credentialError != null) {
                // Bad credentials fail the same on both hosts — stop early.
                if (passId == null) credentialError = result.credentialError;
                break;
            }
            if (result.success) {
                String fromResponse = extractPassIdFromCookieStore();
                if (fromResponse != null) passId = fromResponse;
            } else if (result.message != null && passId == null) {
                networkError = result.message;
            }
        }

        if (passId == null) {
            passId = extractPassIdFromCookieStore();
        }
        if (passId != null) {
            setPasscodeCookie(passId, true);
            return null;
        }
        if (credentialError != null) return credentialError;
        if (networkError != null) return networkError;
        return "Could not get pass id";
    }

    private void logoutPass(CancellableTask task) {
        for (String host : AUTH_HOSTS) {
            if (task != null && task.isCancelled()) break;
            try {
                okhttp3.FormBody body = new okhttp3.FormBody.Builder()
                        .add("xhr", "1")
                        .add("logout", "1")
                        .build();
                HttpRequestModel request = HttpRequestModel.builder()
                        .setPOST(body)
                        .setCustomHeaders(getAuthHeaders(host))
                        .build();
                HttpStreamer.getInstance().getStringFromUrl(
                        "https://" + host + "/auth", request, httpClient, null, task, false);
            } catch (Exception ignored) {
            }
        }
        setPasscodeCookie(null, true);
    }

    private static final class AuthResult {
        boolean success;
        String credentialError;
        String message;
    }

    private AuthResult parseAuthResponse(String response) {
        AuthResult result = new AuthResult();
        String trimmed = response.trim();
        if (trimmed.startsWith("{")) {
            try {
                JSONObject json = new JSONObject(trimmed);
                int status = json.optInt("status", -1);
                String message = json.optString("message", "");
                if (status == -1) {
                    result.credentialError = message.isEmpty() ? "Authentication failed" : stripHtml(message);
                    return result;
                }
                result.success = true;
                return result;
            } catch (Exception e) {
                // fall through to HTML
            }
        }
        if (response.contains("Your device is now authorized") || response.contains("Success! Your device is now authorized")) {
            result.success = true;
            return result;
        }
        if (response.contains("Your Token must be exactly 10 characters")) {
            result.credentialError = "Incorrect token";
            return result;
        }
        if (response.contains("You have left one or more fields blank")) {
            result.credentialError = "You have left one or more fields blank";
            return result;
        }
        if (response.contains("Incorrect Token or PIN")) {
            result.credentialError = "Incorrect Token or PIN";
            return result;
        }
        Matcher m = AUTH_HTML_ERROR.matcher(response);
        if (m.find()) {
            result.credentialError = stripHtml(m.group(1));
            return result;
        }
        result.message = "Unexpected auth response";
        return result;
    }

    private static String stripHtml(String html) {
        if (html == null) return "";
        return Html.fromHtml(html).toString().trim();
    }

    private static void logPostResponseForCapture(String response) {
        if (!BuildConfig.DEBUG) return;
        if (response == null) {
            Logger.d(TAG, "4chan post response capture: null response");
            return;
        }
        int loggedLength = Math.min(response.length(), POST_RESPONSE_CAPTURE_MAX_LENGTH);
        int chunks = (loggedLength + POST_RESPONSE_CAPTURE_CHUNK_SIZE - 1) / POST_RESPONSE_CAPTURE_CHUNK_SIZE;
        Logger.d(TAG, "4chan post response capture length=" + response.length()
                + ", logged=" + loggedLength
                + ", chunks=" + chunks);
        for (int i = 0; i < chunks; i++) {
            int start = i * POST_RESPONSE_CAPTURE_CHUNK_SIZE;
            int end = Math.min(start + POST_RESPONSE_CAPTURE_CHUNK_SIZE, loggedLength);
            Logger.d(TAG, "4chan post response capture [" + (i + 1) + "/" + chunks + "]: "
                    + response.substring(start, end));
        }
        if (response.length() > loggedLength) {
            Logger.d(TAG, "4chan post response capture truncated at " + loggedLength + " chars");
        }
    }

    private String extractPassIdFromCookieStore() {
        for (HttpCookie cookie : httpClient.getCookieStore().getCookies()) {
            if ("pass_id".equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value != null && value.length() > 0 && !"0".equals(value)) {
                    return value;
                }
            }
        }
        return null;
    }
    
    private void setPasscodeCookie(String cookie, boolean saveToPreferences) {
        if (cookie == null || cookie.equals("0")) cookie = "";
        if (saveToPreferences) SecurePreferences.INSTANCE.put(getSharedKey(PREF_KEY_PASS_COOKIE), cookie);
        if (cookie.length() > 0) {
            usingPasscode = true;
            for (String domain : PASS_COOKIE_DOMAINS) {
                HttpCookie passId = new HttpCookie("pass_id", cookie);
                passId.setDomain(domain);
                passId.setPath("/");
                httpClient.getCookieStore().addCookie(passId);
                HttpCookie passEnabled = new HttpCookie("pass_enabled", "1");
                passEnabled.setDomain(domain);
                passEnabled.setPath("/");
                httpClient.getCookieStore().addCookie(passEnabled);
            }
        } else {
            usingPasscode = false;
            for (String domain : PASS_COOKIE_DOMAINS) {
                HttpCookie passId = new HttpCookie("pass_id", "0");
                passId.setDomain(domain);
                passId.setPath("/");
                httpClient.getCookieStore().addCookie(passId);
                HttpCookie passEnabled = new HttpCookie("pass_enabled", "0");
                passEnabled.setDomain(domain);
                passEnabled.setPath("/");
                httpClient.getCookieStore().addCookie(passEnabled);
            }
        }
    }

    /** Re-auth if we have credentials stored but no active pass session. */
    private void ensurePassSession(CancellableTask task) {
        if (usingPasscode) return;
        String token = getPassToken();
        String pin = getPassPin();
        if (token.isEmpty() || pin.isEmpty()) return;
        try {
            loginPass(token, pin, task);
        } catch (Exception ignored) {
        }
    }

    private boolean isPassSessionError(String message) {
        if (message == null) return false;
        String m = message.toLowerCase(Locale.US);
        return m.contains("captcha")
                || m.contains("pass")
                || m.contains("authenticated")
                || m.contains("authorized")
                || m.contains("not logged in");
    }
    
    @Override
    public void addPreferencesOnScreen(PreferenceGroup preferenceGroup) {
        addPasscodePreference(preferenceGroup);
        addPasswordPreference(preferenceGroup);
        addHttpsPreference(preferenceGroup, true);
        addProxyPreferences(preferenceGroup);
    }
    
    private HttpHeader[] getPostHeaders(String boardName, String origin) {
        return new HttpHeader[] {
            new HttpHeader("Origin", origin),
            new HttpHeader("Referer", origin + "/" + boardName + "/"),
            new HttpHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        };
    }

    private HttpHeader[] getAuthHeaders(String host) {
        String origin = "https://" + host;
        return new HttpHeader[] {
            new HttpHeader("Origin", origin),
            new HttpHeader("Referer", origin + "/auth"),
            new HttpHeader("Accept", "application/json, text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        };
    }

    private boolean useHttps() {
        return useHttps(true);
    }

    /** Package-visible for Chan4Captcha to trigger Cloudflare challenge handling */
    void handleCloudflareCheck(HttpWrongStatusCodeException e, String url) throws Exception {
        checkCloudflareError(e, url);
    }
    
    @Override
    public SimpleBoardModel[] getBoardsList(ProgressListener listener, CancellableTask task, SimpleBoardModel[] oldBoardsList) throws Exception {
        List<SimpleBoardModel> list = new ArrayList<SimpleBoardModel>();
        Map<String, BoardModel> newMap = new HashMap<String, BoardModel>();
        
        String url = (useHttps() ? "https://" : "http://") + "a.4cdn.org/boards.json";
        JSONObject boardsJson = downloadJSONObject(url, (oldBoardsList != null && boardsMap != null), listener, task);
        if (boardsJson == null) return oldBoardsList;
        JSONArray boards = boardsJson.getJSONArray("boards");
        
        for (int i=0, len=boards.length(); i<len; ++i) {
            BoardModel model = FourchanJsonMapper.mapBoardModel(boards.getJSONObject(i));
            newMap.put(model.boardName, model);
            list.add(new SimpleBoardModel(model));
        }
        
        boardsMap = newMap;
        return list.toArray(new SimpleBoardModel[list.size()]);
    }
    
    @Override
    public BoardModel getBoard(String shortName, ProgressListener listener, CancellableTask task) throws Exception {
        if (boardsMap == null) {
            try {
                getBoardsList(listener, task, null);
            } catch (Exception e) {}
        }
        if (boardsMap != null && boardsMap.containsKey(shortName)) return boardsMap.get(shortName);
        return FourchanJsonMapper.getDefaultBoardModel(shortName);
    }
    
    @Override
    public ThreadModel[] getCatalog(String boardName, int catalogType, ProgressListener listener, CancellableTask task, ThreadModel[] oldList)
            throws Exception {
        String url = (useHttps() ? "https://" : "http://") + "a.4cdn.org/" + boardName + "/catalog.json";
        JSONArray response = downloadJSONArray(url, oldList != null, listener, task);
        if (response == null) return oldList; //if not modified
        List<ThreadModel> threads = new ArrayList<>();
        for (int i=0, len=response.length(); i<len; ++i) {
            JSONArray curArray = response.getJSONObject(i).getJSONArray("threads");
            for (int j=0, clen=curArray.length(); j<clen; ++j) {
                JSONObject curThreadJson = curArray.getJSONObject(j);
                ThreadModel curThread = new ThreadModel();
                curThread.threadNumber = Long.toString(curThreadJson.getLong("no"));
                curThread.postsCount = curThreadJson.optInt("replies", -2) + 1;
                curThread.attachmentsCount = curThreadJson.optInt("images", -2) + 1;
                curThread.isSticky = curThreadJson.optInt("sticky") == 1;
                curThread.isClosed = curThreadJson.optInt("closed") == 1;
                curThread.posts = new PostModel[] { FourchanJsonMapper.mapPostModel(curThreadJson, boardName) };
                threads.add(curThread);
            }
        }
        return threads.toArray(new ThreadModel[threads.size()]);
    }
    
    @Override
    public ThreadModel[] getThreadsList(String boardName, int page, ProgressListener listener, CancellableTask task, ThreadModel[] oldList)
            throws Exception {
        String url = (useHttps() ? "https://" : "http://") + "a.4cdn.org/" + boardName + "/" + Integer.toString(page) + ".json";
        JSONObject response = downloadJSONObject(url, oldList != null, listener, task);
        if (response == null) return oldList; //if not modified
        JSONArray threads = response.getJSONArray("threads");
        ThreadModel[] result = new ThreadModel[threads.length()];
        for (int i=0, len=threads.length(); i<len; ++i) {
            JSONArray posts = threads.getJSONObject(i).getJSONArray("posts");
            JSONObject op = posts.getJSONObject(0);
            ThreadModel curThread = new ThreadModel();
            curThread.threadNumber = Long.toString(op.getLong("no"));
            curThread.postsCount = op.optInt("replies", -2) + 1;
            curThread.attachmentsCount = op.optInt("images", -2) + 1;
            curThread.isSticky = op.optInt("sticky") == 1;
            curThread.isClosed = op.optInt("closed") == 1;
            curThread.posts = new PostModel[posts.length()];
            for (int j=0, plen=posts.length(); j<plen; ++j) {
                curThread.posts[j] = FourchanJsonMapper.mapPostModel(posts.getJSONObject(j), boardName);
            }
            result[i] = curThread;
        }
        return result;
    }
    
    private String threadJsonUrl(String boardName, String threadNumber) {
        return (useHttps() ? "https://" : "http://") + "a.4cdn.org/" + boardName + "/thread/" + threadNumber + ".json";
    }

    @Override
    public void invalidateThreadPostsCache(String boardName, String threadNumber) {
        if (boardName == null || threadNumber == null) return;
        HttpStreamer.getInstance().removeFromModifiedMap(threadJsonUrl(boardName, threadNumber));
    }

    @Override
    public PostModel[] getPostsList(String boardName, String threadNumber, ProgressListener listener, CancellableTask task, PostModel[] oldList)
            throws Exception {
        String url = threadJsonUrl(boardName, threadNumber);
        JSONObject response = downloadJSONObject(url, oldList != null, listener, task);
        if (response == null) return oldList; //if not modified
        JSONArray posts = response.getJSONArray("posts");
        PostModel[] result = new PostModel[posts.length()];
        for (int i=0, len=posts.length(); i<len; ++i) {
            result[i] = FourchanJsonMapper.mapPostModel(posts.getJSONObject(i), boardName);
        }
        if (oldList != null) {
            result = ChanModels.mergePostsLists(Arrays.asList(oldList), Arrays.asList(result));
        }
        return result;
    }
    
    @Override
    public PostModel[] search(String boardName, String searchRequest, ProgressListener listener, CancellableTask task) throws Exception {
        throw new Exception("Open this page in the browser");
    }
    
    @Override
    public CaptchaModel getNewCaptcha(String boardName, String threadNumber, ProgressListener listener, CancellableTask task) throws Exception {
        ensurePassSession(task);
        if (usingPasscode) return null;
        if (Chan4CaptchaSolved.hasSolved()) return null;
        throw new Chan4Captcha(boardName, threadNumber);
    }
    
    @Override
    public String sendPost(SendPostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        ensurePassSession(task);
        String[] captchaPair = usingPasscode ? null : Chan4CaptchaSolved.pop();
        if (!usingPasscode && captchaPair == null) {
            throw new Chan4Captcha(model.boardName, model.threadNumber);
        }
        try {
            return doSendPost(model, captchaPair, listener, task);
        } catch (Exception e) {
            if (usingPasscode && isPassSessionError(e.getMessage())
                    && !getPassToken().isEmpty() && !getPassPin().isEmpty()) {
                setPasscodeCookie(null, true);
                String reauth = loginPass(getPassToken(), getPassPin(), task);
                if (reauth == null) {
                    return doSendPost(model, null, listener, task);
                }
            }
            throw e;
        }
    }

    private String doSendPost(SendPostModel model, String[] captchaPair, ProgressListener listener, CancellableTask task)
            throws Exception {
        String url = "https://sys.4chan.org/" + model.boardName + "/post";
        ExtendedMultipartBuilder postEntityBuilder = ExtendedMultipartBuilder.create().setDelegates(listener, task).
                addString("name", model.name).
                addString("email", model.sage ? "sage" : "").
                addString("sub", model.subject).
                addString("com", model.comment).
                addString("mode", "regist").
                addString("pwd", model.password);
        if (model.threadNumber != null) postEntityBuilder.addString("resto", model.threadNumber);
        if (!usingPasscode && captchaPair != null) {
            postEntityBuilder.addString("t-challenge", captchaPair[0]);
            postEntityBuilder.addString("t-response", captchaPair[1]);
        }
        if (model.attachments != null && model.attachments.length != 0) postEntityBuilder.addFile("upfile", model.attachments[0]);
        if (model.custommark) postEntityBuilder.addString("spoiler", "on");

        HttpRequestModel request = HttpRequestModel.builder().setPOST(postEntityBuilder.build())
                .setCustomHeaders(getPostHeaders(model.boardName, "https://boards.4chan.org")).build();
        String response;
        try {
            response = HttpStreamer.getInstance().getStringFromUrl(url, request, httpClient, listener, task, true);
        } catch (HttpWrongStatusCodeException e) {
            checkCloudflareError(e, "https://4chan.org");
            throw e;
        }
        logPostResponseForCapture(response);
        FourchanPostResponse postResponse = FourchanPostResponse.parse(response);
        if (postResponse.type() == FourchanPostResponse.Type.SERVER_ERROR) {
            throw new Exception(postResponse.message());
        }
        if (postResponse.type() == FourchanPostResponse.Type.SUCCESS) {
            UrlPageModel redirect = new UrlPageModel();
            redirect.chanName = CHAN_NAME;
            redirect.type = UrlPageModel.TYPE_THREADPAGE;
            redirect.boardName = model.boardName;
            redirect.threadNumber = postResponse.threadNumber();
            redirect.postNumber = postResponse.postNumber();
            if (redirect.threadNumber.equals("0")) redirect.threadNumber = redirect.postNumber;
            return buildUrl(redirect);
        }
        Logger.d(TAG, "Unexpected 4chan post response: " + postResponse.bodySnippet());
        throw new Exception(postResponse.message());
    }
    
    @Override
    public String deletePost(DeletePostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        String url = "https://sys.4chan.org/" + model.boardName + "/imgboard.php";
        ExtendedMultipartBuilder postEntityBuilder = ExtendedMultipartBuilder.create().setDelegates(listener, task).
                addString(model.postNumber, "delete");
        if (model.onlyFiles) postEntityBuilder.addString("onlyimgdel", "on");
        postEntityBuilder.addString("mode", "usrdel").addString("pwd", model.password);
        HttpRequestModel request = HttpRequestModel.builder().setPOST(postEntityBuilder.build())
                .setCustomHeaders(getPostHeaders(model.boardName, "https://boards.4chan.org")).build();
        String response = HttpStreamer.getInstance().getStringFromUrl(url, request, httpClient, listener, task, false);
        String errorMessage = FourchanPostResponse.errorMessage(response);
        if (errorMessage != null) {
            throw new Exception(errorMessage);
        }
        return null;
    }
    
    @Override
    public String reportPost(final DeletePostModel model, ProgressListener listener, final CancellableTask task) throws Exception {
        ensurePassSession(task);
        String[] captchaPair = null;
        if (!usingPasscode) {
            captchaPair = Chan4CaptchaSolved.pop();
            if (captchaPair == null) {
                throw new Chan4Captcha(model.boardName, null);
            }
        }
        String url = "https://sys.4chan.org/" + model.boardName + "/imgboard.php?mode=report&no=" + model.postNumber;
        ExtendedMultipartBuilder postEntityBuilder = ExtendedMultipartBuilder.create().setDelegates(listener, task).
                addString("cat", "vio").
                addString("board", model.boardName).
                addString("no", model.postNumber);
        if (captchaPair != null) {
            postEntityBuilder.addString("t-challenge", captchaPair[0]);
            postEntityBuilder.addString("t-response", captchaPair[1]);
        }
        HttpRequestModel request = HttpRequestModel.builder().setPOST(postEntityBuilder.build())
                .setCustomHeaders(getPostHeaders(model.boardName, "https://boards.4chan.org")).build();
        String response = HttpStreamer.getInstance().getStringFromUrl(url, request, httpClient, listener, task, false);
        if (response.contains("https://www.4chan.org/banned")) throw new Exception("You can't report posts because you are banned");
        if (response.contains("You seem to have mistyped the CAPTCHA")) throw new Exception("You seem to have mistyped the CAPTCHA");
        if (response.contains("That post doesn't exist anymore")) throw new Exception("That post doesn't exist anymore");
        if (response.contains("You forgot to solve the CAPTCHA")) throw new Exception("You forgot to solve the CAPTCHA");
        return null;
    }
    
    @Override
    public String buildUrl(UrlPageModel model) throws IllegalArgumentException {
        if (!model.chanName.equals(CHAN_NAME)) throw new IllegalArgumentException("wrong chan");
        if (model.boardName != null && !model.boardName.matches("\\w+")) throw new IllegalArgumentException("wrong board name");
        StringBuilder url = new StringBuilder(useHttps() ? "https://" : "http://");
        try {
            switch (model.type) {
                case UrlPageModel.TYPE_INDEXPAGE:
                    return url.append("www.4chan.org").toString();
                case UrlPageModel.TYPE_BOARDPAGE:
                    if (model.boardPage == UrlPageModel.DEFAULT_FIRST_PAGE || model.boardPage == 1)
                        return url.append("boards.4chan.org/").append(model.boardName).append('/').toString();
                    return url.append("boards.4chan.org/").append(model.boardName).append('/').append(model.boardPage).toString();
                case UrlPageModel.TYPE_CATALOGPAGE:
                    return url.append("boards.4chan.org/").append(model.boardName).append("/catalog").toString();
                case UrlPageModel.TYPE_THREADPAGE:
                    return url.append("boards.4chan.org/").append(model.boardName).append("/thread/").append(model.threadNumber).
                            append(model.postNumber == null || model.postNumber.length() == 0 ? "" : ("#p" + model.postNumber)).toString();
                case UrlPageModel.TYPE_SEARCHPAGE:
                    return url.append("boards.4chan.org/").append(model.boardName).append("/catalog#s=").
                            append(URLEncoder.encode(model.searchRequest, "UTF-8")).toString();
                case UrlPageModel.TYPE_OTHERPAGE:
                    return url.append(model.otherPath.startsWith("/") ? "boards.4chan.org" : "").append(model.otherPath).toString();
            }
        } catch (Exception e) {}
        throw new IllegalArgumentException("wrong page type");
    }
    
    @Override
    public UrlPageModel parseUrl(String url) throws IllegalArgumentException {
        String domain;
        String path = "";
        Matcher parseUrl = Pattern.compile("https?://(?:www\\.)?(.+)", Pattern.CASE_INSENSITIVE).matcher(url);
        if (!parseUrl.find()) throw new IllegalArgumentException("incorrect url");
        String urlPath = parseUrl.group(1);
        Matcher parsePath = Pattern.compile("(.+?)(?:/(.*))").matcher(urlPath);
        if (parsePath.find()) {
            domain = parsePath.group(1).toLowerCase(Locale.US);
            path = parsePath.group(2);
        } else {
            domain = parseUrl.group(1).toLowerCase(Locale.US);
        }
        
        if (domain.equals("4cdn.org") || domain.endsWith(".4cdn.org")) {
            UrlPageModel model = new UrlPageModel();
            model.chanName = CHAN_NAME;
            model.type = UrlPageModel.TYPE_OTHERPAGE;
            model.otherPath = urlPath;
            return model;
        }
        
        if (!domain.equals("4chan.org") && !domain.endsWith(".4chan.org")) throw new IllegalArgumentException("wrong chan");
        
        UrlPageModel model = new UrlPageModel();
        model.chanName = CHAN_NAME;
        
        if (path.length() == 0) {
            model.type = UrlPageModel.TYPE_INDEXPAGE;
            return model;
        }
        
        Matcher threadPage = Pattern.compile("([^/]+)/thread/(\\d+)[^#]*(?:#p(\\d+))?").matcher(path);
        if (threadPage.find()) {
            model.type = UrlPageModel.TYPE_THREADPAGE;
            model.boardName = threadPage.group(1);
            model.threadNumber = threadPage.group(2);
            model.postNumber = threadPage.group(3);
            return model;
        }
        
        Matcher pageCatalogSearch = Pattern.compile("([^/]+)/catalog(?:#s=(.+))?").matcher(path);
        if (pageCatalogSearch.find()) {
            model.boardName = pageCatalogSearch.group(1);
            String search = pageCatalogSearch.group(2);
            if (search != null) {
                model.type = UrlPageModel.TYPE_SEARCHPAGE;
                model.searchRequest = search;
                try {
                    model.searchRequest = URLDecoder.decode(model.searchRequest, "UTF-8");
                } catch (Exception e) {}
            } else {
                model.type = UrlPageModel.TYPE_CATALOGPAGE;
                model.catalogType = 0;
            }
            return model;
        }
        
        Matcher boardPage = Pattern.compile("([^/]+)(?:/(\\d+)?)?").matcher(path);
        if (boardPage.find()) {
            model.type = UrlPageModel.TYPE_BOARDPAGE;
            model.boardName = boardPage.group(1);
            String page = boardPage.group(2);
            model.boardPage = page == null ? 1 : Integer.parseInt(page);
            return model;
        }
        
        throw new IllegalArgumentException("fail to parse");
    }
    
}
