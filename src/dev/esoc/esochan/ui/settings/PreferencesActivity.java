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

import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import dev.esoc.esochan.R;
import dev.esoc.esochan.common.MainApplication;

public class PreferencesActivity extends AppCompatActivity
        implements PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    public static boolean needUpdateChansScreen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        MainApplication.getInstance().settings.getTheme().setToPreferencesActivity(this);
        getTheme().applyStyle(R.style.Theme_Preferences_NoActionBar, true);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preferences);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.preferences);
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.preferences_container, new PreferencesFragment())
                    .commit();
        }
    }

    @Override
    public boolean onPreferenceStartScreen(PreferenceFragmentCompat caller, PreferenceScreen pref) {
        if (caller instanceof PreferencesFragment) {
            ((PreferencesFragment) caller).openScreen(pref);
            return true;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.preferences_container);
        if (current instanceof PreferencesFragment && ((PreferencesFragment) current).popScreen()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            Fragment current = getSupportFragmentManager().findFragmentById(R.id.preferences_container);
            if (current instanceof PreferencesFragment && ((PreferencesFragment) current).popScreen()) {
                return true;
            }
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.preferences_container);
        if (current instanceof PreferencesFragment && ((PreferencesFragment) current).popScreen()) {
            return true;
        }
        finish();
        return true;
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(title);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(title);
        }
    }

    @Override
    public void setTitle(int titleId) {
        super.setTitle(titleId);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(titleId);
        }
    }
}
