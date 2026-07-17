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

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import dev.esoc.esochan.R;
import dev.esoc.esochan.api.ChanModule;
import dev.esoc.esochan.common.Async;
import dev.esoc.esochan.common.MainApplication;
import dev.esoc.esochan.ui.BoardsListFragment;
import dev.esoc.esochan.ui.tabs.TabsTrackerService;
import dev.esoc.esochan.ui.tabs.UrlHandler;
import dev.esoc.esochan.ui.theme.ThemeUtils;

public class PreferencesFragment extends PreferenceFragmentCompat {
    private final ArrayDeque<PreferenceScreen> screenStack = new ArrayDeque<>();
    private SharedPreferences.OnSharedPreferenceChangeListener sharedPreferenceChangeListener;
    private SharedPreferences sharedPreferences;
    private boolean recreatePending;

    private static final int[] KEYS_AUTOUPDATE = new int[] {
            R.string.pref_key_enable_autoupdate,
            R.string.pref_key_autoupdate_delay,
            R.string.pref_key_autoupdate_notification,
            R.string.pref_key_autoupdate_background };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext().getApplicationContext());
        setupRootPreferences();
        restoreNavStackIfNeeded();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        applyPreferenceDividers();
    }

    private void applyPreferenceDividers() {
        // Material Preference styles suppress dividers via allowDividerAbove=false;
        // styles flip that, and we force a readable hairline here (listSeparator is often
        // too close to the page background on dark themes like Tomorrow).
        int fg = ThemeUtils.getThemeColor(requireContext().getTheme(),
                android.R.attr.textColorPrimary, 0xFF808080);
        int color = (fg & 0x00FFFFFF) | 0x38000000;
        setDivider(new ColorDrawable(color));
        setDividerHeight(Math.max(1, Math.round(getResources().getDisplayMetrics().density)));
    }

    void openScreen(PreferenceScreen screen) {
        PreferenceScreen current = getPreferenceScreen();
        if (current != null) {
            screenStack.push(current);
        }
        setPreferenceScreen(screen);
        if (screen.getTitle() != null) {
            requireActivity().setTitle(screen.getTitle());
        }
    }

    boolean popScreen() {
        if (screenStack.isEmpty()) {
            return false;
        }
        PreferenceScreen previous = screenStack.pop();
        setPreferenceScreen(previous);
        if (screenStack.isEmpty()) {
            requireActivity().setTitle(R.string.preferences);
        } else if (previous.getTitle() != null) {
            requireActivity().setTitle(previous.getTitle());
        }
        return true;
    }

    private void setupRootPreferences() {
        updateChansScreen((PreferenceScreen) findPreference(getString(R.string.pref_key_cat_chans)));
        updateListSummary(R.string.pref_key_theme);
        updateListSummary(R.string.pref_key_font_size);
        updateListSummary(R.string.pref_key_download_thumbs);
        updateListSummary(R.string.pref_key_download_format);

        final Preference clearSubscriptionsPreference = findPreference(getString(R.string.pref_key_clear_subscriptions));
        int subscriptionsCount = (int) MainApplication.getInstance().subscriptions.getCurrentCount();
        clearSubscriptionsPreference.setSummary(getResources().getQuantityString(R.plurals.pref_clear_subscriptions_summary,
                subscriptionsCount, subscriptionsCount));
        if (subscriptionsCount > 0) {
            clearSubscriptionsPreference.setOnPreferenceClickListener(preference -> {
                MainApplication.getInstance().subscriptions.reset();
                int count = (int) MainApplication.getInstance().subscriptions.getCurrentCount();
                clearSubscriptionsPreference.setSummary(getResources().getQuantityString(R.plurals.pref_clear_subscriptions_summary,
                        count, count));
                MainApplication.getInstance().settings.setSubscriptionsClear(true);
                return true;
            });
        }

        EditTextPreference downloadDirPref = findPreference(getString(R.string.pref_key_download_dir));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            downloadDirPref.setEnabled(false);
            downloadDirPref.setSummary(getString(R.string.pref_download_dir_summary) + " (Downloads)");
        } else {
            downloadDirPref.setOnBindEditTextListener(editText ->
                    editText.setHint(MainApplication.getInstance().settings.getDefaultDownloadDir().getAbsolutePath()));
        }

        final Preference clearCachePreference = findPreference(getString(R.string.pref_key_clear_cache));
        clearCachePreference.setSummary(getString(R.string.pref_clear_cache_summary, MainApplication.getInstance().fileCache.getCurrentSizeMB()));
        clearCachePreference.setOnPreferenceClickListener(preference -> {
            new MaterialAlertDialogBuilder(requireActivity())
                    .setMessage(R.string.pref_clear_cache_confirmation)
                    .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                        final AlertDialog progressDlg = SettingsProgress.show(requireActivity(),
                                getString(R.string.dialog_wait), null);
                        Async.runAsync(() -> {
                            MainApplication.getInstance().fileCache.clearCache();
                            requireActivity().runOnUiThread(() -> {
                                progressDlg.dismiss();
                                clearCachePreference.setSummary(getString(R.string.pref_clear_cache_summary,
                                        MainApplication.getInstance().fileCache.getCurrentSizeMB()));
                            });
                        });
                    })
                    .setNegativeButton(android.R.string.no, null)
                    .show();
            return true;
        });

        Preference aboutPreference = findPreference(getString(R.string.pref_key_about_version));
        try {
            String versionName = requireContext().getPackageManager().getPackageInfo(requireContext().getPackageName(), 0).versionName;
            aboutPreference.setSummary(versionName);
        } catch (Exception ignored) {}
        if (MainApplication.getInstance().settings.enableAppUpdateCheck()) {
            aboutPreference.setOnPreferenceClickListener(preference -> {
                AppUpdatesChecker.checkForUpdates(requireActivity());
                return true;
            });
        }

        Preference licensePreference = findPreference(getString(R.string.pref_key_about_license));
        licensePreference.setOnPreferenceClickListener(preference -> {
            UrlHandler.launchExternalBrowser(requireActivity(), "https://www.gnu.org/licenses/gpl-3.0.html");
            return true;
        });

        findPreference(getString(R.string.pref_key_autohide)).setOnPreferenceClickListener(preference -> {
            startActivity(new Intent(requireActivity(), AutohideActivity.class));
            return true;
        });

        findPreference(getString(R.string.pref_key_cache_maxsize)).setOnPreferenceChangeListener((preference, newValue) -> {
            int newSize;
            try {
                newSize = Integer.parseInt(newValue.toString());
            } catch (NumberFormatException e) {
                newSize = 50;
            }
            MainApplication.getInstance().fileCache.setMaxSize(newSize * 1024L * 1024L);
            clearCachePreference.setSummary(getString(R.string.pref_clear_cache_summary,
                    MainApplication.getInstance().fileCache.getCurrentSizeMB()));
            return true;
        });

        findPreference(getString(R.string.pref_key_autoupdate_delay)).setOnPreferenceChangeListener((preference, newValue) -> {
            String newValueStr = newValue.toString();
            if (newValueStr.length() == 0) return true;
            try {
                int intVal = Integer.parseInt(newValueStr);
                if (intVal < 30) throw new NumberFormatException();
                return true;
            } catch (NumberFormatException e) {
                Toast.makeText(requireActivity(), R.string.pref_autoupdate_delay_incorrect, Toast.LENGTH_LONG).show();
                return false;
            }
        });

        findPreference(getString(R.string.pref_key_theme)).setOnPreferenceChangeListener((preference, newValue) -> {
            if (getString(R.string.pref_theme_value_custom).equals(newValue)) {
                startActivity(new Intent(requireActivity(), CustomThemeListActivity.class));
                return false;
            }
            return true;
        });

        sharedPreferenceChangeListener = (prefs, key) -> {
            if (key == null) return;
            Preference preference = findPreference(key);
            if (preference instanceof ListPreference) {
                updateListSummary(key);
            } else {
                for (int autoupdateKey : KEYS_AUTOUPDATE) {
                    if (getString(autoupdateKey).equals(key)) {
                        TabsTrackerService.syncWithVisibleUi(requireActivity());
                    }
                }
                if (getString(R.string.pref_key_show_nsfw_boards).equals(key)) {
                    Fragment currentFragment = MainApplication.getInstance().tabsSwitcher.getCurrentFragment();
                    if (currentFragment instanceof BoardsListFragment) {
                        ((BoardsListFragment) currentFragment).updateList();
                    }
                }
            }
            if (getString(R.string.pref_key_theme).equals(key)
                    || getString(R.string.pref_key_font_size).equals(key)) {
                recreateActivityForTheme();
            }
        };

        if (!MainApplication.getInstance().settings.isRealTablet()) {
            Preference pHide = findPreference(getString(R.string.pref_key_sidepanel_hide));
            Preference pWidth = findPreference(getString(R.string.pref_key_sidepanel_width));
            Preference pRight = findPreference(getString(R.string.pref_key_sidepanel_right));
            PreferenceGroup appearanceGroup = findPreference(getString(R.string.pref_key_cat_appearance));
            appearanceGroup.removePreference(pHide);
            appearanceGroup.removePreference(pWidth);
            appearanceGroup.removePreference(pRight);
        } else {
            updateListSummary(R.string.pref_key_sidepanel_width);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (sharedPreferences != null && sharedPreferenceChangeListener != null) {
            sharedPreferences.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
        }
        if (PreferencesActivity.needUpdateChansScreen) {
            updateChansScreen((PreferenceScreen) findPreference(getString(R.string.pref_key_cat_chans)));
        }

        ListPreference themePreference = findPreference(getString(R.string.pref_key_theme));
        if (themePreference != null && sharedPreferences != null) {
            String currentValue = sharedPreferences.getString(getString(R.string.pref_key_theme), "");
            if (!currentValue.equals("") && !currentValue.equals(themePreference.getValue())) {
                themePreference.setValue(currentValue);
                updateListSummary(R.string.pref_key_theme);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (sharedPreferences != null && sharedPreferenceChangeListener != null) {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
        }
    }

    private void updateListSummary(int prefKeyId) {
        updateListSummary(getString(prefKeyId));
    }

    private void updateListSummary(String prefKey) {
        ListPreference preference = findPreference(prefKey);
        if (preference != null) {
            preference.setSummary(preference.getEntry());
        }
    }

    private void updateChansScreen(final PreferenceScreen chansScreen) {
        if (chansScreen == null) return;
        PreferencesActivity.needUpdateChansScreen = false;
        chansScreen.removeAll();

        ChanModule chan = MainApplication.getInstance().getChanModule();
        if (MainApplication.getInstance().settings.isUnlockedChan(chan.getChanName())) {
            chan.addPreferencesOnScreen(chansScreen);
        }
    }

    void prepareRecreateForTheme() {
        ArrayList<String> navKeys = new ArrayList<>();
        for (PreferenceScreen screen : screenStack) {
            if (screen.getKey() != null) navKeys.add(screen.getKey());
        }
        PreferenceScreen current = getPreferenceScreen();
        if (current != null && current.getKey() != null) {
            navKeys.add(current.getKey());
        }
        requireActivity().getIntent().putStringArrayListExtra(
                PreferencesActivity.EXTRA_PREF_NAV_STACK, navKeys);
    }

    private void recreateActivityForTheme() {
        if (recreatePending || getActivity() == null) return;
        recreatePending = true;
        prepareRecreateForTheme();
        requireActivity().recreate();
    }

    private void restoreNavStackIfNeeded() {
        Intent intent = requireActivity().getIntent();
        ArrayList<String> navKeys = intent.getStringArrayListExtra(PreferencesActivity.EXTRA_PREF_NAV_STACK);
        if (navKeys == null || navKeys.isEmpty()) return;
        intent.removeExtra(PreferencesActivity.EXTRA_PREF_NAV_STACK);
        for (String key : navKeys) {
            Preference preference = findPreference(key);
            if (preference instanceof PreferenceScreen) {
                openScreen((PreferenceScreen) preference);
            }
        }
    }
}
