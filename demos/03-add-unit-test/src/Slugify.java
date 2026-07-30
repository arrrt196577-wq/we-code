/**
 * Demo 03：另一组纯函数，便于 Grep「slug」或 Glob {@code **/*.java}。
 */
public final class Slugify {

    private Slugify() {
    }

    /**
     * 将标题转成 URL slug：小写、非字母数字换成 {@code -}，压缩连续短横线。
     *
     * @param title 标题；{@code null} 返回空串
     * @return slug 文本
     */
    public static String fromTitle(String title) {
        if (title == null) {
            return "";
        }
        String lower = title.trim().toLowerCase();
        StringBuilder sb = new StringBuilder();
        boolean lastWasDash = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            // 字母数字直接追加
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                lastWasDash = false;
                continue;
            }
            // 其它字符变成短横线，并压缩连续 -
            if (!lastWasDash && !sb.isEmpty()) {
                sb.append('-');
                lastWasDash = true;
            }
        }
        // 去掉末尾短横线
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '-') {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }
}
