/**
 * Demo 02 入口：演示正常路径与会 NPE 的路径。
 * <p>
 * 直接运行 {@code main}：先打印正常邮件，再在 null 用户上炸掉。
 */
public class DemoRunner {

    /**
     * @param args 未使用
     */
    public static void main(String[] args) {
        NotificationService service = new NotificationService();

        // 正常路径：应成功打印
        UserProfile alice = new UserProfile("Alice", "Alice@Example.COM");
        System.out.println(service.buildWelcomeEmail(alice));
        System.out.println("score=" + service.completenessScore(alice));

        // 缺陷路径：null 用户 → NPE（修复后应优雅失败或跳过）
        UserProfile missing = null;
        System.out.println(service.buildWelcomeEmail(missing));
    }
}
