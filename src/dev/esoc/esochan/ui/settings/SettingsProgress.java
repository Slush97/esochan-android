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

import android.content.Context;
import android.content.DialogInterface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class SettingsProgress {
    private SettingsProgress() {}

    public static AlertDialog show(Context context, CharSequence message, DialogInterface.OnCancelListener onCancel) {
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f,
                context.getResources().getDisplayMetrics());
        int gap = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f,
                context.getResources().getDisplayMetrics());

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(pad, pad, pad, pad);

        ProgressBar bar = new ProgressBar(context);
        layout.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView text = new TextView(context);
        text.setText(message);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMarginStart(gap);
        layout.addView(text, textParams);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setView(layout)
                .setCancelable(onCancel != null)
                .setOnCancelListener(onCancel)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        return dialog;
    }
}
