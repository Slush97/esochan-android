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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FourchanPostResponseTest {
    @Test
    public void parsesSuccessComment() {
        FourchanPostResponse response = FourchanPostResponse.parse(
                "<html><body><!-- thread:123456,no:123789 --></body></html>");

        assertEquals(FourchanPostResponse.Type.SUCCESS, response.type());
        assertEquals("123456", response.threadNumber());
        assertEquals("123789", response.postNumber());
    }

    @Test
    public void parsesThreadZeroSuccessComment() {
        FourchanPostResponse response = FourchanPostResponse.parse("<!-- thread:0,no:555 -->");

        assertEquals(FourchanPostResponse.Type.SUCCESS, response.type());
        assertEquals("0", response.threadNumber());
        assertEquals("555", response.postNumber());
    }

    @Test
    public void parsesErrmsgSpanAsServerError() {
        FourchanPostResponse response = FourchanPostResponse.parse(
                "<span id=\"errmsg\" class=\"warning\">You forgot to solve the CAPTCHA.<br></span>");

        assertEquals(FourchanPostResponse.Type.SERVER_ERROR, response.type());
        assertEquals("You forgot to solve the CAPTCHA.", response.message());
    }

    @Test
    public void treatsBlankBodyAsUnexpected() {
        FourchanPostResponse response = FourchanPostResponse.parse("   ");

        assertEquals(FourchanPostResponse.Type.UNEXPECTED, response.type());
        assertTrue(response.message().contains("empty response"));
    }

    @Test
    public void treatsCloudflareInterstitialAsUnexpected() {
        FourchanPostResponse response = FourchanPostResponse.parse(
                "<!doctype html><title>Just a moment...</title>"
                        + "<div class=\"cf-browser-verification\">Checking your browser before accessing 4chan</div>");

        assertEquals(FourchanPostResponse.Type.UNEXPECTED, response.type());
        assertTrue(response.bodySnippet().contains("Just a moment"));
    }

    @Test
    public void treatsRandomHtmlAsUnexpected() {
        FourchanPostResponse response = FourchanPostResponse.parse(
                "<html><body><h1>Maintenance</h1><p>Try again later.</p></body></html>");

        assertEquals(FourchanPostResponse.Type.UNEXPECTED, response.type());
        assertTrue(response.message().contains("Maintenance"));
    }
}
