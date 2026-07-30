package org.wecode.tools.rg;

import java.util.Objects;

/**
 * ripgrep 内容搜索的一条命中。
 *
 * @param path 相对搜索根的路径（正斜杠）
 * @param line 1-based 行号
 * @param text 该行文本（已去尾部换行）
 */
public record RipgrepMatchHit(String path, int line, String text) {

    public RipgrepMatchHit {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(text, "text");
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (line < 1) {
            throw new IllegalArgumentException("line must be >= 1");
        }
    }
}
