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

import dev.esoc.esochan.R;
import dev.esoc.esochan.common.Logger;
import dev.esoc.esochan.common.MainApplication;
import dev.esoc.esochan.ui.FavoritesFragment;
import dev.esoc.esochan.ui.HistoryFragment;
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
    public Fragment currentFragment;
    
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
                    if (tabModel.forceUpdate && currentFragment != null && currentFragment instanceof BoardFragment) {
                        ((BoardFragment) currentFragment).update();
                        tabModel.forceUpdate = false;
                        MainApplication.getInstance().serializer.serializeTabsState(MainApplication.getInstance().tabsState);
                    }
                    return;
                }
            }
            if (MainApplication.getInstance().getChanModule(tabModel.pageModel.chanName) == null) {
                Logger.e(TAG, "chan module " + tabModel.pageModel.chanName + " not registered");
                return;
            }
            currentFragment = BoardFragment.newInstance(tabModel.id);
            currentId = tabModel.id;
            replace(fragmentManager, currentFragment);
        } catch (Exception e) {
            Logger.e(TAG, e);
        }
    }
    
    /**
     * Переключиться на скрытую вкладку (Избранное / История).
     * POSITION_NEWTAB is handled in MainActivity (opens 4chan index) and is not a fragment.
     * @param virtualPosition виртуальная позиция вкладки
     * (см. {@link TabModel#POSITION_FAVORITES}, {@link TabModel#POSITION_HISTORY})
     * @param fragmentManager менеджер фрагментов
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
            default:
                Logger.e(TAG, "unsupported virtual tab position: " + virtualPosition);
                return;
        }
        currentFragment = newFragment;
        currentId = (long) virtualPosition;
        replace(fragmentManager, newFragment);
    }
    
    private void replace(FragmentManager fragmentManager, Fragment newFragment) {
        try {
            fragmentManager.beginTransaction().setCustomAnimations(android.R.anim.slide_in_left, android.R.anim.slide_out_right).
                    replace(R.id.main_fragment_container, newFragment).commit();
        } catch (Exception e) {
            Logger.e(TAG, e);
            try {
                fragmentManager.beginTransaction().setCustomAnimations(android.R.anim.slide_in_left, android.R.anim.slide_out_right).
                        replace(R.id.main_fragment_container, newFragment).commitAllowingStateLoss();
            } catch (Exception e1) {
                Logger.e(TAG, e1);
            }
        }
    }
}
