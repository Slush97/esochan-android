/*
 * esochan (Meta Imageboard Client)
 * Copyright (C) 2024-2026  esoc <https://github.com/esoc-dev>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.esoc.esochan.lib;

import java.util.Locale;

import dev.esoc.esochan.R;

/** Selects a fallback attachment icon when an image thumbnail cannot be decoded. */
public final class AttachmentIconUtils {
    private static final String[] IMAGE_EXTENSIONS = { "jpg", "jpeg", "png", "gif", "svg", "svgz" };
    private static final String[] VIDEO_EXTENSIONS = { "webm", "mp4", "avi", "mov", "mkv", "wmv", "flv" };
    private static final String[] AUDIO_EXTENSIONS = { "mp3", "ogg", "flac", "wav" };
    private static final String[] SAVED_THREAD_EXTENSIONS = { "html", "mhtml", "zip" };

    private AttachmentIconUtils() {}

    public static int getDefaultIconResId(String fileName) {
        String normalized = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if (endsWithAny(normalized, IMAGE_EXTENSIONS)) return R.drawable.filedialog_file_image;
        if (endsWithAny(normalized, VIDEO_EXTENSIONS)) return R.drawable.filedialog_file_video;
        if (endsWithAny(normalized, AUDIO_EXTENSIONS)) return R.drawable.filedialog_file_audio;
        if (endsWithAny(normalized, SAVED_THREAD_EXTENSIONS)) return R.drawable.filedialog_file_html;
        return R.drawable.filedialog_file;
    }

    private static boolean endsWithAny(String fileName, String[] extensions) {
        for (String extension : extensions) {
            if (fileName.endsWith("." + extension)) return true;
        }
        return false;
    }
}
