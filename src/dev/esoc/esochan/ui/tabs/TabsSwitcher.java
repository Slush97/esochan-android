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

package dev.esoc.esochan.ui.tabs;

import java.lang.ref.WeakReference;

import dev.esoc.esochan.R;
import dev.esoc.esochan.common.Logger;
import dev.esoc.esochan.common.MainApplication;
import dev.esoc.esochan.ui.ActivityFragment;
import dev.esoc.esochan.ui.FavoritesFragment;
import dev.esoc.esochan.ui.HistoryFragment;
import dev.esoc.esochan.ui.SavedThreadsFragment;
import dev.esoc.esochan.ui.presentation.BoardFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

/**
 * Переключение вкладок (фрагментов)
 * @author miku-nyan
 *
 */
public class TabsSwitcher {
    private static final String TAG = "TabsSwitcher";
    /** текущий ID или виртуальная позиция скрытой вкладки */
    public Long currentId = null;
    private volatile WeakReference<Fragment> currentFragmentRef = new WeakReference<>(null);

    public Fragment getCurrentFragment() {
        return currentFragmentRef.get();
    }

    public void clearCurrentFragment() {
        currentFragmentRef.clear();
    }

    private void setCurrentFragment(Fragment fragment) {
        currentFragmentRef = new WeakReference<>(fragment);
    }
    
    /**
     * Переключиться на вкладку (обычную) tabModel
     * @param tabModel вкладка
     * @param fragmentManager менеджер фрагментов
     */
    public void switchTo(TabModel tabModel, FragmentManager fragmentManager) {
        switchTo(tabModel, fragmentManager, false);
    }
    
    /**
     * Переключиться на вкладку (обычную) tabModel
     * @param tabModel вкладка
     * @param fragmentManager менеджер фрагментов
     * @param force если true, перезагрузить вкладку, если в данный момент открыта эта же вкладка
     */
    public void switchTo(TabModel tabModel, FragmentManager fragmentManager, boolean force) {
        try {
            if (!force) {
                if (currentId != null && currentId.equals(Long.valueOf(tabModel.id))) {
                    Fragment currentFragment = getCurrentFragment();
                    if (currentFragment != null) {
                        if (tabModel.forceUpdate && currentFragment instanceof BoardFragment) {
                            String expectPost = tabModel.startItemNumber;
                            ((BoardFragment) currentFragment).update(expectPost);
                            tabModel.forceUpdate = false;
                            MainApplication.getInstance().serializer.serializeTabsState(MainApplication.getInstance().tabsState);
                        }
                        return;
                    }
                }
            }
            if (MainApplication.getInstance().getChanModule(tabModel.pageModel.chanName) == null) {
                Logger.e(TAG, "chan module " + tabModel.pageModel.chanName + " not registered");
                return;
            }
            Fragment currentFragment = BoardFragment.newInstance(tabModel.id);
            setCurrentFragment(currentFragment);
            currentId = tabModel.id;
            replace(fragmentManager, currentFragment);
        } catch (Exception e) {
            Logger.e(TAG, e);
        }
    }
    
    /**
     * Switch to a virtual tab (Favorites / History / Saved / Activity).
     * POSITION_NEWTAB is handled in MainActivity (opens 4chan index) and is not a fragment.
     */
    public void switchTo(int virtualPosition, FragmentManager fragmentManager) {
        if (currentId != null && currentId.equals(Long.valueOf(virtualPosition))) return;
        Fragment newFragment;
        switch (virtualPosition) {
            case TabModel.POSITION_HISTORY:
                newFragment = new HistoryFragment();
                break;
            case TabModel.POSITION_FAVORITES:
                newFragment = new FavoritesFragment();
                break;
            case TabModel.POSITION_SAVED:
                newFragment = new SavedThreadsFragment();
                break;
            case TabModel.POSITION_ACTIVITY:
                newFragment = new ActivityFragment();
                break;
            default:
                Logger.e(TAG, "unsupported virtual tab position: " + virtualPosition);
                return;
        }
        setCurrentFragment(newFragment);
        currentId = (long) virtualPosition;
        replace(fragmentManager, newFragment);
    }
    
    private void replace(FragmentManager fragmentManager, Fragment newFragment) {
        try {
            fragmentManager.beginTransaction()
                    .replace(R.id.main_fragment_container, newFragment).commit();
        } catch (Exception e) {
            Logger.e(TAG, e);
            try {
                fragmentManager.beginTransaction()
                        .replace(R.id.main_fragment_container, newFragment).commitAllowingStateLoss();
            } catch (Exception e1) {
                Logger.e(TAG, e1);
            }
        }
    }
}
