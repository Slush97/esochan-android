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

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.preference.ListPreference;

import dev.esoc.esochan.R;

public class ThemePreference extends ListPreference {
    private final float density;

    public ThemePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
    }

    public ThemePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = context.getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onClick() {
        final CharSequence[] entries = getEntries();
        final CharSequence[] values = getEntryValues();
        if (entries == null || values == null || entries.length == 0) {
            return;
        }
        int selected = findIndexOfValue(getValue());
        new AlertDialog.Builder(getContext())
                .setTitle(getDialogTitle())
                .setSingleChoiceItems(new PreviewAdapter(entries, values, selected), selected,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                String value = values[which].toString();
                                if (callChangeListener(value)) setValue(value);
                                dialog.dismiss();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int dp(float value) {
        return (int) (density * value + 0.5f);
    }

    private int themeStyleForValue(String value) {
        Context c = getContext();
        if (value.equals(c.getString(R.string.pref_theme_value_futaba))) return R.style.Theme_Futaba;
        if (value.equals(c.getString(R.string.pref_theme_value_photon))) return R.style.Theme_Photon;
        if (value.equals(c.getString(R.string.pref_theme_value_paper))) return R.style.Theme_Paper;
        if (value.equals(c.getString(R.string.pref_theme_value_neutron))) return R.style.Theme_Neutron;
        if (value.equals(c.getString(R.string.pref_theme_value_tomorrow))) return R.style.Theme_Tomorrow;
        if (value.equals(c.getString(R.string.pref_theme_value_midnight))) return R.style.Theme_Midnight;
        return 0;
    }

    private static final int[] PREVIEW_ATTRS = new int[] {
            R.attr.activityRootBackground,
            R.attr.postBackground,
            R.attr.postForeground,
            R.attr.postNameForeground,
            R.attr.postNumberForeground,
            R.attr.postQuoteForeground };

    private class PreviewAdapter extends BaseAdapter {
        private final CharSequence[] entries;
        private final CharSequence[] values;
        private final int selected;

        PreviewAdapter(CharSequence[] entries, CharSequence[] values, int selected) {
            this.entries = entries;
            this.values = values;
            this.selected = selected;
        }

        @Override public int getCount() { return entries.length; }
        @Override public Object getItem(int position) { return entries[position]; }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Context context = getContext();
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));

            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            RadioButton radio = new RadioButton(context);
            radio.setChecked(position == selected);
            radio.setClickable(false);
            radio.setFocusable(false);
            header.addView(radio, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView name = new TextView(context);
            name.setText(entries[position]);
            name.setTextSize(16);
            name.setPadding(dp(6), 0, 0, 0);
            header.addView(name);
            row.addView(header);

            int style = themeStyleForValue(values[position].toString());
            if (style != 0) {
                row.addView(buildPreview(context, style), previewParams());
            }
            return row;
        }

        private LinearLayout.LayoutParams previewParams() {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.setMargins(dp(34), dp(6), 0, 0);
            return p;
        }

        private View buildPreview(Context context, int style) {
            ContextThemeWrapper themed = new ContextThemeWrapper(context, style);
            TypedArray a = themed.getTheme().obtainStyledAttributes(PREVIEW_ATTRS);
            int rootBg = a.getColor(0, 0);
            int postBg = a.getColor(1, 0);
            int fg = a.getColor(2, 0);
            int nameFg = a.getColor(3, fg);
            int numberFg = a.getColor(4, fg);
            int quoteFg = a.getColor(5, fg);
            a.recycle();

            LinearLayout page = new LinearLayout(context);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setBackgroundColor(rootBg);
            page.setPadding(dp(8), dp(8), dp(8), dp(8));

            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(postBg);
            cardBg.setCornerRadius(dp(4));
            card.setBackground(cardBg);
            card.setPadding(dp(8), dp(6), dp(8), dp(6));

            LinearLayout meta = new LinearLayout(context);
            meta.setOrientation(LinearLayout.HORIZONTAL);
            meta.addView(previewText(context, "Anonymous", nameFg, true));
            meta.addView(previewText(context, "  No.7654321", numberFg, false));
            card.addView(meta);
            card.addView(previewText(context, "Sample post preview text", fg, false));
            card.addView(previewText(context, ">looks pretty comfy", quoteFg, false));

            page.addView(card);
            return page;
        }

        private TextView previewText(Context context, String text, int color, boolean bold) {
            TextView tv = new TextView(context);
            tv.setText(text);
            tv.setTextColor(color);
            tv.setTextSize(13);
            if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
            return tv;
        }
    }
}
