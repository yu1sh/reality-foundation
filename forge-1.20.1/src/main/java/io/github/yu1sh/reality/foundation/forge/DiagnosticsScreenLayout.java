package io.github.yu1sh.reality.foundation.forge;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Pure screen layout rules that can be tested without starting a client. */
final class DiagnosticsScreenLayout {
    private DiagnosticsScreenLayout() {
    }

    static int pageCount(int itemCount, int pageSize) {
        if (itemCount < 0 || pageSize <= 0) {
            throw new IllegalArgumentException("layout bounds");
        }
        return Math.max(1, (itemCount + pageSize - 1) / pageSize);
    }

    static int centeredTop(int screenHeight, int imageHeight) {
        if (screenHeight < 0 || imageHeight <= 0) {
            throw new IllegalArgumentException("screen bounds");
        }
        return (screenHeight - imageHeight) / 2;
    }

    static List<String> page(List<String> lines, int page, int pageSize) {
        Objects.requireNonNull(lines, "lines");
        if (page < 0 || pageSize <= 0 || page >= pageCount(lines.size(), pageSize)) {
            throw new IllegalArgumentException("page bounds");
        }
        int start = page * pageSize;
        int end = Math.min(lines.size(), start + pageSize);
        return List.copyOf(lines.subList(start, end));
    }

    static String ellipsize(String value, int maxWidth, ToIntFunction<String> width) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(width, "width");
        if (maxWidth <= 0) {
            throw new IllegalArgumentException("maxWidth");
        }
        if (width.applyAsInt(value) <= maxWidth) {
            return value;
        }
        String ellipsis = "…";
        int end = value.length();
        while (end > 0 && width.applyAsInt(value.substring(0, end) + ellipsis) > maxWidth) {
            end--;
        }
        return value.substring(0, end) + ellipsis;
    }

    /**
     * Wraps every character into measured-width lines without dropping a
     * suffix. The screen uses this for the keyboard-accessible detail view;
     * ellipsis is reserved for the compact row only.
     */
    static List<String> wrap(String value, int maxWidth, ToIntFunction<String> width) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(width, "width");
        if (maxWidth <= 0) {
            throw new IllegalArgumentException("maxWidth");
        }
        if (value.isEmpty()) {
            return List.of("");
        }
        List<String> lines = new ArrayList<>();
        int lineStart = 0;
        int cursor = 0;
        while (cursor < value.length()) {
            int next = value.offsetByCodePoints(cursor, 1);
            String candidate = value.substring(lineStart, next);
            if (width.applyAsInt(candidate) > maxWidth && cursor > lineStart) {
                lines.add(value.substring(lineStart, cursor));
                lineStart = cursor;
            } else {
                cursor = next;
            }
        }
        if (lineStart < value.length()) {
            lines.add(value.substring(lineStart));
        }
        return List.copyOf(lines);
    }

    record DetailHitRect(int x, int y, int width, int height) {
        DetailHitRect {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("detail hit bounds");
            }
        }
    }

    static int hitIndex(List<DetailHitRect> hits, int x, int y) {
        Objects.requireNonNull(hits, "hits");
        for (int index = 0; index < hits.size(); index++) {
            DetailHitRect hit = hits.get(index);
            if (x >= hit.x() && x < hit.x() + hit.width()
                    && y >= hit.y() && y < hit.y() + hit.height()) {
                return index;
            }
        }
        return -1;
    }
}
