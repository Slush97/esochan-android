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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import dev.esoc.esochan.common.IOUtils;

/** Utilities for selecting and importing files through Android's document APIs. */
public final class UriFileUtils {
    public static final long MAX_ATTACHMENT_BYTES = 32L * 1024L * 1024L;
    public static final long MAX_THEME_BYTES = 64L * 1024L;

    private static final int BUFFER_SIZE = 32 * 1024;
    private static final int MAX_FILE_NAME_LENGTH = 120;
    private static final String CACHE_DIRECTORY = "picked_files";

    private UriFileUtils() {}

    /** Creates a single-document picker restricted to the supplied extensions where possible. */
    public static Intent createOpenDocumentIntent(String[] extensions) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = getMimeTypes(extensions);
        if (mimeTypes.length > 0) intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        return intent;
    }

    /** Uses the system Photo Picker when available and the document picker on older devices. */
    public static Intent createImagePickerIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        return intent;
    }

    /**
     * Copies a file or content URI into the app cache without trusting its display name or size.
     * Callers must invoke this on an IO thread.
     */
    public static File copyToCache(Context context, Uri uri, long maxBytes) throws IOException {
        if (uri == null) throw new IOException("No file was selected");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");

        if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null) throw new IOException("Selected file has no path");
            File source = new File(path);
            if (!source.isFile()) throw new IOException("Selected file is unavailable");
            ensureWithinLimit(source.length(), maxBytes);
            return source;
        }
        if (!ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Unsupported file URI");
        }

        ContentResolver resolver = context.getContentResolver();
        Metadata metadata = queryMetadata(resolver, uri);
        ensureWithinLimit(metadata.size, maxBytes);

        String displayName = metadata.displayName;
        if (!hasExtension(displayName)) {
            String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri));
            if (extension != null && !extension.isEmpty()) displayName = displayName + "." + extension;
        }
        File destination = createDestination(context, sanitizeFileName(displayName));
        boolean success = false;
        InputStream input = null;
        FileOutputStream output = null;
        try {
            input = resolver.openInputStream(uri);
            if (input == null) throw new IOException("Could not open selected file");
            output = new FileOutputStream(destination);
            copyBounded(input, output, maxBytes);
            success = true;
            return destination;
        } finally {
            IOUtils.closeQuietly(input);
            IOUtils.closeQuietly(output);
            if (!success) destination.delete();
        }
    }

    /** Reads a small text document with the same hard limit used during streaming. */
    public static String readText(Context context, Uri uri, long maxBytes, Charset charset) throws IOException {
        if (uri == null) throw new IOException("No file was selected");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");

        InputStream input = null;
        try {
            if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath();
                if (path == null) throw new IOException("Selected file has no path");
                File file = new File(path);
                ensureWithinLimit(file.length(), maxBytes);
                input = new FileInputStream(file);
            } else if (ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme())) {
                ContentResolver resolver = context.getContentResolver();
                ensureWithinLimit(queryMetadata(resolver, uri).size, maxBytes);
                input = resolver.openInputStream(uri);
            } else {
                throw new IOException("Unsupported file URI");
            }
            if (input == null) throw new IOException("Could not open selected file");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            copyBounded(input, output, maxBytes);
            return output.toString(charset.name());
        } finally {
            IOUtils.closeQuietly(input);
        }
    }

    public static boolean hasAllowedExtension(File file, String[] allowedExtensions) {
        return file != null && hasAllowedExtension(file.getName(), allowedExtensions);
    }

    public static boolean hasAllowedDocument(Context context, Uri uri, String[] allowedExtensions) {
        if (allowedExtensions == null || allowedExtensions.length == 0) return true;
        if (hasAllowedExtension(getDisplayName(context, uri), allowedExtensions)) return true;
        String selectedType = context.getContentResolver().getType(uri);
        if (selectedType == null) return false;
        for (String allowedType : getMimeTypes(allowedExtensions)) {
            if (selectedType.equalsIgnoreCase(allowedType)) return true;
        }
        return false;
    }

    public static boolean hasAllowedExtension(String fileName, String[] allowedExtensions) {
        if (allowedExtensions == null || allowedExtensions.length == 0) return true;
        String extension = getExtension(fileName);
        for (String allowed : allowedExtensions) {
            if (allowed != null && extension.equals(normalizeExtension(allowed))) return true;
        }
        return false;
    }

    public static String getDisplayName(Context context, Uri uri) {
        if (uri == null) return "attachment";
        if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            return path == null ? "attachment" : new File(path).getName();
        }
        return queryMetadata(context.getContentResolver(), uri).displayName;
    }

    static String sanitizeFileName(String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        StringBuilder clean = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            clean.append(c < 32 || c == 127 || c == '/' || c == '\\' ? '_' : c);
        }
        name = clean.toString();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "attachment";
        if (name.length() <= MAX_FILE_NAME_LENGTH) return name;

        String extension = getExtension(name);
        String suffix = extension.isEmpty() ? "" : "." + extension;
        int baseLength = Math.max(1, MAX_FILE_NAME_LENGTH - suffix.length());
        return name.substring(0, baseLength) + suffix;
    }

    private static String[] getMimeTypes(String[] extensions) {
        if (extensions == null) return new String[0];
        Set<String> types = new LinkedHashSet<>();
        MimeTypeMap map = MimeTypeMap.getSingleton();
        for (String extension : extensions) {
            String normalized = normalizeExtension(extension);
            String type = map.getMimeTypeFromExtension(normalized);
            if (type != null) types.add(type);
        }
        return types.toArray(new String[0]);
    }

    private static Metadata queryMetadata(ContentResolver resolver, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri,
                    new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE },
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String displayName = cursor.isNull(0) ? "attachment" : cursor.getString(0);
                long size = cursor.isNull(1) ? -1 : cursor.getLong(1);
                return new Metadata(displayName, size);
            }
        } catch (RuntimeException ignored) {
            // Some document providers do not implement metadata queries. The stream is still bounded.
        } finally {
            if (cursor != null) cursor.close();
        }
        return new Metadata("attachment", -1);
    }

    private static File createDestination(Context context, String fileName) throws IOException {
        File directory = new File(context.getCacheDir(), CACHE_DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create attachment cache");
        }
        String extension = getExtension(fileName);
        String suffix = extension.isEmpty() ? "" : "." + extension;
        String base = suffix.isEmpty() ? fileName : fileName.substring(0, fileName.length() - suffix.length());
        for (int index = 0; index < 10_000; index++) {
            String candidateName = index == 0 ? fileName : base + "-" + index + suffix;
            File candidate = new File(directory, candidateName);
            if (candidate.createNewFile()) return candidate;
        }
        throw new IOException("Could not create attachment cache file");
    }

    private static void copyBounded(InputStream input, java.io.OutputStream output, long maxBytes) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long copied = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("File import was cancelled");
            copied += count;
            ensureWithinLimit(copied, maxBytes);
            output.write(buffer, 0, count);
        }
        output.flush();
    }

    private static void ensureWithinLimit(long size, long maxBytes) throws FileTooLargeException {
        if (size > maxBytes) throw new FileTooLargeException(maxBytes);
    }

    private static boolean hasExtension(String fileName) {
        return !getExtension(fileName).isEmpty();
    }

    private static String getExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return normalizeExtension(fileName.substring(dot + 1));
    }

    private static String normalizeExtension(String extension) {
        String normalized = extension == null ? "" : extension.trim().toLowerCase(Locale.US);
        while (normalized.startsWith(".")) normalized = normalized.substring(1);
        return normalized;
    }

    private static final class Metadata {
        final String displayName;
        final long size;

        Metadata(String displayName, long size) {
            this.displayName = displayName == null ? "attachment" : displayName;
            this.size = size;
        }
    }

    public static final class FileTooLargeException extends IOException {
        public final long maxBytes;

        FileTooLargeException(long maxBytes) {
            super("Selected file exceeds the import limit");
            this.maxBytes = maxBytes;
        }
    }
}
