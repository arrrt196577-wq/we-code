/**
 * Demo 03：待补单测的纯函数工具类（无外部依赖）。
 * <p>
 * 当前目录下尚无测试类；具备 Edit 后可在同目录或 {@code test/} 下新增 JUnit 用例。
 */
public final class TextStats {

    private TextStats() {
    }

    /**
     * 统计非空白字符数（空格、换行、制表符不计）。
     *
     * @param text 输入文本；{@code null} 视为 0
     * @return 非空白字符个数
     */
    public static int countNonWhitespace(String text) {
        // null 安全：直接返回 0
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 跳过常见空白
            if (Character.isWhitespace(c)) {
                continue;
            }
            count++;
        }
        return count;
    }

    /**
     * 按空白切分后返回「单词」个数；连续空白视为一个分隔。
     *
     * @param text 输入文本；{@code null} 或全空白视为 0
     * @return 单词数
     */
    public static int countWords(String text) {
        if (text == null) {
            return 0;
        }
        String trimmed = text.trim();
        // 全空白
        if (trimmed.isEmpty()) {
            return 0;
        }
        return trimmed.split("\\s+").length;
    }

    /**
     * 判断文本是否为回文（忽略大小写与空白）。
     *
     * @param text 输入；{@code null} 视为 false
     * @return 是否回文
     */
    public static boolean isPalindrome(String text) {
        if (text == null) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // 只保留字母数字，并统一小写
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        String normalized = sb.toString();
        int left = 0;
        int right = normalized.length() - 1;
        while (left < right) {
            // 两端字符不一致则非回文
            if (normalized.charAt(left) != normalized.charAt(right)) {
                return false;
            }
            left++;
            right--;
        }
        return true;
    }
}
