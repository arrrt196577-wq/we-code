package org.wecode.agent.compaction;

import java.util.List;
import java.util.Objects;

/**
 * 校验摘要模型返回的固定 Markdown 骨架，防止不符合协议的文本进入长期会话记忆。
 */
final class SummaryFormatValidator {

    /** 摘要模板中必须按顺序出现的标题。 */
    private static final List<String> REQUIRED_HEADINGS = List.of(
            "## Objective",
            "## Important Details",
            "## Work State",
            "### Completed",
            "### Active",
            "### Blocked",
            "## Next Move",
            "## Relevant Files"
    );

    /**
     * 校验摘要具有可恢复的固定章节和下一步编号。
     *
     * @param summary 模型返回、尚未写入检查点的摘要文本
     */
    void validate(String summary) {
        Objects.requireNonNull(summary, "summary");
        String[] lines = summary.replace("\r\n", "\n").split("\n", -1);
        int previousHeadingIndex = -1;

        for (String heading : REQUIRED_HEADINGS) {
            int headingIndex = findHeadingAfter(lines, heading, previousHeadingIndex);
            // 缺失章节或章节倒序时，不能将摘要作为长期记忆持久化。
            if (headingIndex < 0) {
                throw new IllegalStateException("Compaction summary is missing or reorders required heading: " + heading);
            }
            // 每个章节至少要有一个非空内容行，空章节无法承载模板要求的事实或 “(none)” 标记。
            // Work State 的正文由三个三级子章节组成，因此不要求其标题后直接出现列表正文。
            if (!heading.equals("## Work State") && !hasSectionContent(lines, headingIndex)) {
                throw new IllegalStateException("Compaction summary section must not be empty: " + heading);
            }
            previousHeadingIndex = headingIndex;
        }

        int nextMoveIndex = findExactHeading(lines, "## Next Move");
        // Next Move 必须保留两个编号位置，保证后续摘要与既有模板一致。
        if (!containsNumberedItem(lines, nextMoveIndex, "1.")
                || !containsNumberedItem(lines, nextMoveIndex, "2.")) {
            throw new IllegalStateException("Compaction summary Next Move must contain items 1 and 2");
        }
    }

    /** 从指定位置之后查找完全匹配的 Markdown 标题。 */
    private static int findHeadingAfter(String[] lines, String heading, int afterIndex) {
        for (int index = afterIndex + 1; index < lines.length; index++) {
            // 标题只能独占一行，避免正文中提到标题文本时被误判。
            if (lines[index].strip().equals(heading)) {
                return index;
            }
        }
        return -1;
    }

    /** 在全部行中查找一个完全匹配的标题。 */
    private static int findExactHeading(String[] lines, String heading) {
        return findHeadingAfter(lines, heading, -1);
    }

    /** 判断当前标题到下一个标题之间是否存在非空正文。 */
    private static boolean hasSectionContent(String[] lines, int headingIndex) {
        for (int index = headingIndex + 1; index < lines.length; index++) {
            String line = lines[index].strip();
            // 下一个 Markdown 标题表示当前章节结束。
            if (line.startsWith("#")) {
                return false;
            }
            // 任意非空文本、列表或编号均视为有效章节内容。
            if (!line.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 判断 Next Move 章节中是否包含指定编号项。 */
    private static boolean containsNumberedItem(String[] lines, int headingIndex, String itemPrefix) {
        for (int index = headingIndex + 1; index < lines.length; index++) {
            String line = lines[index].strip();
            // 遇到下一章节时不再接受其内容作为 Next Move 的编号项。
            if (line.startsWith("#")) {
                return false;
            }
            if (line.startsWith(itemPrefix)) {
                return true;
            }
        }
        return false;
    }
}
