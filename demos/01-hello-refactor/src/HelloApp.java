/**
 * Demo 01：含重复拼接逻辑，适合练习「抽出公共方法」重构。
 * <p>
 * 当前 Agent 若只读不改：可用 Glob/Grep/Read 定位并说明问题。
 * 具备 Edit 后：可将 greeting 拼装抽成私有方法，行为保持不变。
 */
public class HelloApp {

    /**
     * 程序入口：打印几条问候语。
     *
     * @param args 命令行参数（未使用）
     */
    public static void main(String[] args) {
        // 重复拼接：同样的前缀/后缀写了三遍
        System.out.println("Hello, " + "Alice" + "!");
        System.out.println("Hello, " + "Bob" + "!");
        System.out.println("Hello, " + "Charlie" + "!");

        // 另一处重复：欢迎语也是手写拼接
        System.out.println("Welcome, " + "Alice" + " to we-code");
        System.out.println("Welcome, " + "Bob" + " to we-code");
    }

    /**
     * 返回固定标语（故意简单，便于观察重构范围）。
     *
     * @return 标语文本
     */
    public static String banner() {
        return "=== we-code demo 01 ===";
    }
}
