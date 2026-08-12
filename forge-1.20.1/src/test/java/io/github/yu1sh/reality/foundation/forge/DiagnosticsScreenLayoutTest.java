package io.github.yu1sh.reality.foundation.forge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsScreenLayoutTest {
    @Test
    void allSixtyFourServicesAreReachableByBoundedPages() {
        assertEquals(1, DiagnosticsScreenLayout.pageCount(0, 7));
        assertEquals(10, DiagnosticsScreenLayout.pageCount(64, 7));
    }

    @Test
    void longLocalizedTextIsBoundedButRemainsAvailableAsTooltipSource() {
        String longText = "あ".repeat(512);
        String visible = DiagnosticsScreenLayout.ellipsize(longText, 24, String::length);
        assertTrue(visible.endsWith("…"));
        assertTrue(visible.length() <= 24);
        assertTrue(longText.length() > visible.length(),
                "the full value is retained by the caller for hover details");
    }

    @Test
    void twoTruncatedRowsHaveDistinctKeyboardAndMouseDetailTargets() {
        List<DiagnosticsScreenLayout.DetailHitRect> hits = List.of(
                new DiagnosticsScreenLayout.DetailHitRect(16, 36, 120, 10),
                new DiagnosticsScreenLayout.DetailHitRect(16, 52, 120, 10));
        assertEquals(0, DiagnosticsScreenLayout.hitIndex(hits, 20, 40));
        assertEquals(1, DiagnosticsScreenLayout.hitIndex(hits, 20, 56));
        assertEquals(-1, DiagnosticsScreenLayout.hitIndex(hits, 20, 48));
    }

    @Test
    void keyboardDetailWrappingRetainsEveryLocalizedCharacter() {
        String longText = "ja-日本語-".repeat(80);
        List<String> lines = DiagnosticsScreenLayout.wrap(longText, 12, String::length);
        assertEquals(longText, String.join("", lines));
        assertTrue(lines.size() > 2);
    }

    @Test
    void boundedScreenFitsSmallScaledHeightAndKeepsContentAwayFromWidgets() {
        int screenHeight = 240;
        int top = DiagnosticsScreenLayout.centeredTop(screenHeight, DiagnosticsScreen.IMAGE_HEIGHT);
        assertTrue(top >= 0);
        assertTrue(DiagnosticsScreen.WIDGET_BOTTOM <= DiagnosticsScreen.IMAGE_HEIGHT);
        assertTrue(DiagnosticsScreen.CONTENT_BOTTOM <= DiagnosticsScreen.LIST_WIDGET_TOP);
        assertTrue(top + DiagnosticsScreen.IMAGE_HEIGHT <= screenHeight);
    }

    @Test
    void detailPagesBoundEachPageAndReachEveryWrappedLine() {
        List<String> lines = List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        assertEquals(2, DiagnosticsScreenLayout.pageCount(lines.size(), 10));
        assertEquals(List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"),
                DiagnosticsScreenLayout.page(lines, 0, 10));
        assertEquals(List.of("10"), DiagnosticsScreenLayout.page(lines, 1, 10));
    }
}
