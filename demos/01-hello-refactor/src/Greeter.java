/**
 * Demo 01 辅助类：另一处可合并的问候逻辑。
 */
public class Greeter {

    /**
     * 按姓名生成问候语（与 {@link HelloApp} 中的拼接风格重复）。
     *
     * @param name 姓名；调用方应保证非空
     * @return 形如 {@code Hello, Alice!} 的字符串
     */
    public String sayHello(String name) {
        // 与 HelloApp.main 中的拼接模式相同，适合抽公共方法
        return "Hello, " + name + "!";
    }

    /**
     * 按姓名生成欢迎语。
     *
     * @param name 姓名
     * @return 形如 {@code Welcome, Alice to we-code} 的字符串
     */
    public String sayWelcome(String name) {
        return "Welcome, " + name + " to we-code";
    }
}
