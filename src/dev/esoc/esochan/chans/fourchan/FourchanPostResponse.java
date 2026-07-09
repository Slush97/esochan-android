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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FourchanPostResponse {
    enum Type {
        SUCCESS,
        SERVER_ERROR,
        UNEXPECTED
    }

    private static final int MAX_SNIPPET_LENGTH = 500;
    private static final Pattern SUCCESS_POSTING = Pattern.compile("<!--\\s*thread:(\\d+),no:(\\d+)\\s*-->");

    private final Type type;
    private final String threadNumber;
    private final String postNumber;
    private final String message;
    private final String bodySnippet;

    private FourchanPostResponse(Type type, String threadNumber, String postNumber, String message, String bodySnippet) {
        this.type = type;
        this.threadNumber = threadNumber;
        this.postNumber = postNumber;
        this.message = message;
        this.bodySnippet = bodySnippet;
    }

    static FourchanPostResponse parse(String response) {
        String snippet = snippet(response);
        if (response == null || response.trim().isEmpty()) {
            return unexpected(snippet);
        }

        String errorMessage = errorMessage(response);
        if (errorMessage != null) {
            return new FourchanPostResponse(Type.SERVER_ERROR, null, null, errorMessage, snippet);
        }

        Matcher successMatcher = SUCCESS_POSTING.matcher(response);
        if (successMatcher.find()) {
            return new FourchanPostResponse(Type.SUCCESS, successMatcher.group(1), successMatcher.group(2), null, snippet);
        }

        return unexpected(snippet);
    }

    Type type() {
        return type;
    }

    String threadNumber() {
        return threadNumber;
    }

    String postNumber() {
        return postNumber;
    }

    String message() {
        return message;
    }

    String bodySnippet() {
        return bodySnippet;
    }

    private static FourchanPostResponse unexpected(String snippet) {
        return new FourchanPostResponse(
                Type.UNEXPECTED,
                null,
                null,
                "Unexpected response from 4chan while posting. Draft kept. Details: " + snippet,
                snippet);
    }

    static String errorMessage(String response) {
        if (response == null || response.trim().isEmpty()) return null;
        Document document = Jsoup.parse(response);
        Element error = document.selectFirst("span#errmsg");
        if (error == null) return null;
        String errorMessage = error.text().trim();
        if (errorMessage.isEmpty()) errorMessage = snippet(error.html());
        return errorMessage;
    }

    private static String snippet(String response) {
        if (response == null || response.trim().isEmpty()) return "(empty response)";
        String text = Jsoup.parse(response).text();
        if (text == null || text.trim().isEmpty()) text = response;
        text = text.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return "(empty response)";
        if (text.length() > MAX_SNIPPET_LENGTH) {
            return text.substring(0, MAX_SNIPPET_LENGTH) + "...";
        }
        return text;
    }
}
