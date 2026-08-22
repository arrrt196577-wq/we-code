package org.wecode.session;

import java.util.Objects;

/**
 * 会话标题的公共校验与首条消息兜底标题生成规则。
 */
public final class SessionTitle {

    /** 持久化标题允许的最大 Unicode 码点数量。 */
    public static final int MAX_CODE_POINTS = 200;

    /** 首条用户消息生成兜底标题时展示的最大 Unicode 码点数量。 */
    public static final int TEMPORARY_MAX_CODE_POINTS = 60;

    private SessionTitle() {
    }

    /**
     * 校验一个非空会话标题。
     *
     * @param title 待保存的标题
     * @return 保留原始文本的合法标题
     */
    public static String requireValid(String title) {
        Objects.requireNonNull(title, "title");
        // 空白标题没有展示语义，调用方应在没有标题时使用 null。
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        // 使用码点计数，避免把一个代理对字符误认为两个字符。
        if (title.codePointCount(0, title.length()) > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("title must contain at most " + MAX_CODE_POINTS + " Unicode code points");
        }
        return title;
    }

    /**
     * 根据首条用户消息生成立即可用的兜底标题。
     *
     * @param firstUserMessage 已校验的首条用户消息
     * @return 最多 60 个 Unicode 码点的标题；超长时以省略号结尾
     */
    public static String temporaryFromFirstUserMessage(String firstUserMessage) {
        Objects.requireNonNull(firstUserMessage, "firstUserMessage");
        String normalized = firstUserMessage.strip();
        // 首条消息为空白时不能创建会话，也不能生成有效标题。
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("firstUserMessage must not be blank");
        }
        int codePoints = normalized.codePointCount(0, normalized.length());
        // 未超过展示上限时直接使用首条消息。
        if (codePoints <= TEMPORARY_MAX_CODE_POINTS) {
            return requireValid(normalized);
        }
        int endIndex = normalized.offsetByCodePoints(0, TEMPORARY_MAX_CODE_POINTS - 1);
        return requireValid(normalized.substring(0, endIndex) + "…");
    }
}
