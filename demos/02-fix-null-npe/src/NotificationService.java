/**
 * Demo 02：空输入下会触发 NPE 的通知服务。
 * <p>
 * 正常路径：有 name/email 时拼接邮件正文。
 * 缺陷路径：{@code user == null} 或 {@code user.name() == null} 时会 NPE。
 */
public class NotificationService {

    /**
     * 为用户生成欢迎邮件正文。
     *
     * @param user 用户档案；当前实现未做空值防御
     * @return 邮件正文
     */
    public String buildWelcomeEmail(UserProfile user) {
        // 缺陷：user 为 null 时直接 NPE
        String displayName = user.name().trim();
        // 缺陷：email 为 null 时 toLowerCase 会 NPE
        String mailbox = user.email().toLowerCase();

        return "To: " + mailbox + "\n"
                + "Subject: Welcome\n"
                + "Body: Hello, " + displayName + "! Thanks for joining.";
    }

    /**
     * 根据用户名长度粗略评估「资料完整度」分。
     *
     * @param user 用户档案
     * @return 0–100 的分数
     */
    public int completenessScore(UserProfile user) {
        // 缺陷：user 或 name 为 null 时 NPE
        int nameLen = user.name().length();
        int emailLen = user.email().length();
        int raw = nameLen * 10 + emailLen;
        // 上限截断到 100
        if (raw > 100) {
            return 100;
        }
        return raw;
    }
}
