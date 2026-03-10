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
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import androidx.core.content.res.ResourcesCompat;
import android.text.Html;
import android.text.InputType;
import android.webkit.WebView;
import android.widget.Toast;
import dev.esoc.esochan.R;
import dev.esoc.esochan.api.CloudflareChanModule;
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
import dev.esoc.esochan.common.SecurePreferences;
import dev.esoc.esochan.http.ExtendedMultipartBuilder;
import dev.esoc.esochan.http.streamer.HttpRequestModel;
import dev.esoc.esochan.http.streamer.HttpStreamer;
import dev.esoc.esochan.http.streamer.HttpWrongStatusCodeException;
import dev.esoc.esochan.lib.org_json.JSONArray;
import dev.esoc.esochan.lib.org_json.JSONObject;

public class FourchanModule extends CloudflareChanModule {
    
    static final String CHAN_NAME = "4chan.org";
    
    private static final String PREF_KEY_PASS_TOKEN = "PREF_KEY_PASS_TOKEN";
    private static final String PREF_KEY_PASS_PIN = "PREF_KEY_PASS_PIN";
    private static final String PREF_KEY_PASS_COOKIE = "PREF_KEY_PASS_COOKIE";
    
    private boolean usingPasscode = false;

    private Map<String, BoardModel> boardsMap = null;
    
    private static final Pattern ERROR_POSTING = Pattern.compile("<span id=\"errmsg\"(?:[^>]*)>(.*?)(?:</span>|<br)");
    private static final Pattern SUCCESS_POSTING = Pattern.compile("<!-- thread:(\\d+),no:(\\d+) -->");
    
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

    private void addPasscodePreference(PreferenceGroup preferenceGroup) {
        final Context context = preferenceGroup.getContext();
        PreferenceScreen passScreen = preferenceGroup.getPreferenceManager().createPreferenceScreen(context);
        passScreen.setTitle("4chan pass");
        Preference passTokenPreference = new Preference(context);
        passTokenPreference.setTitle("Token");
        passTokenPreference.setSummary(maskCredential(SecurePreferences.INSTANCE.get(getSharedKey(PREF_KEY_PASS_TOKEN))));
        passTokenPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                final android.widget.EditText input = new android.widget.EditText(context);
                input.setSingleLine();
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                input.setText(SecurePreferences.INSTANCE.get(getSharedKey(PREF_KEY_PASS_TOKEN)));
                new AlertDialog.Builder(context)
                    .setTitle("Token")
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            SecurePreferences.INSTANCE.put(getSharedKey(PREF_KEY_PASS_TOKEN), input.getText().toString());
                            preference.setSummary(maskCredential(input.getText().toString()));
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return true;
            }
        });
        Preference passPINPreference = new Preference(context);
        passPINPreference.setTitle("PIN");
        passPINPreference.setSummary(maskCredential(SecurePreferences.INSTANCE.get(getSharedKey(PREF_KEY_PASS_PIN))));
        passPINPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                final android.widget.EditText input = new android.widget.EditText(context);
                input.setSingleLine();
                input.setInputType(InputType.TYPE_CLASS_NUMBER);
                input.setText(SecurePreferences.INSTANCE.get(getSharedKey(PREF_KEY_PASS_PIN)));
                new AlertDialog.Builder(context)
                    .setTitle("PIN")
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            SecurePreferences.INSTANCE.put(getSharedKey(PREF_KEY_PASS_PIN), input.getText().toString());
                            preference.setSummary(maskCredential(input.getText().toString()));
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return true;
            }
        });
        Preference passLoginPreference = new Preference(context);
        Preference passClearPreference = new Preference(context);
        passLoginPreference.setTitle("Log In");
        passLoginPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                if (!useHttps()) Toast.makeText(context, "Using HTTPS even if HTTP is selected", Toast.LENGTH_SHORT).show();
                final String token = SecurePreferences.INSTANCE.get(getSharedKey(PREF_KEY_PASS_TOKEN));
                final String pin = SecurePreferences.INSTANCE.get(getSharedKey(PREF_KEY_PASS_PIN));
                final String authUrl = "https://sys.4chan.org/auth"; //only https
                final CancellableTask passAuthTask = new CancellableTask.BaseCancellableTask();
                final ProgressDialog passAuthProgressDialog = new ProgressDialog(context);
                passAuthProgressDialog.setMessage("Logging in");
                passAuthProgressDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        passAuthTask.cancel();
                    }
                });
                passAuthProgressDialog.setCanceledOnTouchOutside(false);
                passAuthProgressDialog.show();
                Async.runAsync(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (passAuthTask.isCancelled()) return;
                            setPasscodeCookie(null, true);
                            okhttp3.FormBody.Builder formBuilder = new okhttp3.FormBody.Builder();
                            formBuilder.add("act", "do_login");
                            formBuilder.add("id", token);
                            formBuilder.add("pin", pin);
                            HttpRequestModel request = HttpRequestModel.builder().setPOST(formBuilder.build())
                                    .setCustomHeaders(getAuthHeaders()).build();
                            String response = HttpStreamer.getInstance().getStringFromUrl(authUrl, request, httpClient, null, passAuthTask, false);
                            if (passAuthTask.isCancelled()) return;
                            if (response.contains("Your device is now authorized")) {
                                String passId = null;
                                for (HttpCookie cookie : httpClient.getCookieStore().getCookies()) {
                                    if (cookie.getName().equals("pass_id")) {
                                        String value = cookie.getValue();
                                        if (!value.equals("0")) {
                                            passId = value;
                                            break;
                                        }
                                    }
                                }
                                if (passId == null) {
                                    showToast("Could not get pass id");
                                } else {
                                    setPasscodeCookie(passId, true);
                                    showToast("Success! Your device is now authorized.");
                                }
                            } else if (response.contains("Your Token must be exactly 10 characters")) {
                                showToast("Incorrect token");
                            } else if (response.contains("You have left one or more fields blank")) {
                                showToast("You have left one or more fields blank");
                            } else if (response.contains("Incorrect Token or PIN")) {
                                showToast("Incorrect Token or PIN");
                            } else {
                                Matcher m = Pattern.compile("<strong style=\"color: red; font-size: larger;\">(.*?)</strong>").matcher(response);
                                if (m.find()) {
                                    showToast(m.group(1));
                                } else {
                                    showWebView(response);
                                }
                            }
                        } catch (Exception e) {
                            showToast(e.getMessage() == null ? resources.getString(R.string.error_unknown) : e.getMessage());
                        } finally {
                            passAuthProgressDialog.dismiss();
                        }
                    }
                    private void showToast(final String message) {
                        if (context instanceof Activity) {
                            ((Activity) context).runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    }
                    private void showWebView(final String html) {
                        if (context instanceof Activity) {
                            ((Activity) context).runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    WebView webView = new WebView(context);
                                    webView.getSettings().setSupportZoom(true);
                                    webView.loadData(html, "text/html", null);
                                    new AlertDialog.Builder(context).setView(webView).setNeutralButton(android.R.string.ok, null).show();
                                }
                            });
                        }
                    }
                });
                return true;
            }
        });
        passClearPreference.setTitle("Reset pass cookie");
        passClearPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                setPasscodeCookie(null, true);
                Toast.makeText(context, "Cookie is reset", Toast.LENGTH_LONG).show();
                return true;
            }
        });
        passScreen.addPreference(passTokenPreference);
        passScreen.addPreference(passPINPreference);
        passScreen.addPreference(passLoginPreference);
        passScreen.addPreference(passClearPreference);
        preferenceGroup.addPreference(passScreen);
    }
    
    private void setPasscodeCookie(String cookie, boolean saveToPreferences) {
        if (cookie == null || cookie.equals("0")) cookie = "";
        if (saveToPreferences) SecurePreferences.INSTANCE.put(getSharedKey(PREF_KEY_PASS_COOKIE), cookie);
        if (cookie.length() > 0) {
            usingPasscode = true;
            HttpCookie c1 = new HttpCookie("pass_id", cookie);
            c1.setDomain(".4chan.org");
            c1.setPath("/");
            httpClient.getCookieStore().addCookie(c1);
            HttpCookie c2 = new HttpCookie("pass_enabled", "1");
            c2.setDomain(".4chan.org");
            c2.setPath("/");
            httpClient.getCookieStore().addCookie(c2);
        } else {
            usingPasscode = false;
            HttpCookie c = new HttpCookie("pass_id", "0");
            c.setDomain(".4chan.org");
            c.setPath("/");
            httpClient.getCookieStore().addCookie(c);
            HttpCookie c2 = new HttpCookie("pass_enabled", "0");
            c2.setDomain(".4chan.org");
            c2.setPath("/");
            httpClient.getCookieStore().addCookie(c2);
        }
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

    private HttpHeader[] getAuthHeaders() {
        return new HttpHeader[] {
            new HttpHeader("Origin", "https://sys.4chan.org"),
            new HttpHeader("Referer", "https://sys.4chan.org/auth"),
            new HttpHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
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
    
    @Override
    public PostModel[] getPostsList(String boardName, String threadNumber, ProgressListener listener, CancellableTask task, PostModel[] oldList)
            throws Exception {
        String url = (useHttps() ? "https://" : "http://") + "a.4cdn.org/" + boardName + "/thread/" + threadNumber + ".json";
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
        if (usingPasscode) return null;
        if (Chan4CaptchaSolved.hasSolved()) return null;
        throw new Chan4Captcha(boardName, threadNumber);
    }
    
    @Override
    public String sendPost(SendPostModel model, ProgressListener listener, CancellableTask task) throws Exception {
        String[] captchaPair = usingPasscode ? null : Chan4CaptchaSolved.pop();
        if (!usingPasscode && captchaPair == null) {
            throw new Chan4Captcha(model.boardName, model.threadNumber);
        }
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
        Matcher errorMatcher = ERROR_POSTING.matcher(response);
        if (errorMatcher.find()) {
            throw new Exception(Html.fromHtml(errorMatcher.group(1)).toString());
        }
        Matcher successMatcher = SUCCESS_POSTING.matcher(response);
        if (successMatcher.find()) {
            UrlPageModel redirect = new UrlPageModel();
            redirect.chanName = CHAN_NAME;
            redirect.type = UrlPageModel.TYPE_THREADPAGE;
            redirect.boardName = model.boardName;
            redirect.threadNumber = successMatcher.group(1);
            redirect.postNumber = successMatcher.group(2);
            if (redirect.threadNumber.equals("0")) redirect.threadNumber = redirect.postNumber;
            return buildUrl(redirect);
        }
        return null;
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
        Matcher errorMatcher = ERROR_POSTING.matcher(response);
        if (errorMatcher.find()) {
            throw new Exception(Html.fromHtml(errorMatcher.group(1)).toString());
        }
        return null;
    }
    
    @Override
    public String reportPost(final DeletePostModel model, ProgressListener listener, final CancellableTask task) throws Exception {
        String[] captchaPair = Chan4CaptchaSolved.pop();
        if (captchaPair == null) {
            throw new Chan4Captcha(model.boardName, null);
        }
        String url = "https://sys.4chan.org/" + model.boardName + "/imgboard.php?mode=report&no=" + model.postNumber;
        ExtendedMultipartBuilder postEntityBuilder = ExtendedMultipartBuilder.create().setDelegates(listener, task).
                addString("cat", "vio").
                addString("t-challenge", captchaPair[0]).
                addString("t-response", captchaPair[1]).
                addString("board", model.boardName).
                addString("no", model.postNumber);
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
