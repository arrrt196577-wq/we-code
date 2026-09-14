package org.wecode.hook;

import java.util.Objects;

/**
 * 标识一个可以注册 Hook Handler 的类型化控制位置。
 *
 * <p>类型参数只在编译期约束该位置接收的只读上下文和类型化决策；实例本身不保存 Handler，
 * 也不负责执行生命周期、数据变换或 Agent 状态迁移。HookPoint 使用对象身份进行区分，
 * 名称仅用于日志和诊断。</p>
 *
 * @param <C> 触发 Hook 时传入的上下文类型
 * @param <R> Hook Handler 返回的类型化决策
 */
public final class HookPoint<C, R> {

    private final String name;

    /**
     * @param name 用于日志和诊断的 Hook 名称
     */
    private HookPoint(String name) {
        this.name = Objects.requireNonNull(name, "name");
        // 空白名称无法在注册冲突和运行日志中准确定位 Hook。
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        // 不自动修剪名称，避免错误配置被静默修改后产生难以发现的名称冲突。
        if (!name.equals(name.strip())) {
            throw new IllegalArgumentException("name must not contain leading or trailing whitespace");
        }
    }

    /**
     * 创建一个具有独立对象身份的 HookPoint。
     *
     * @param name 用于日志和诊断的 Hook 名称
     * @param <C>  触发 Hook 时传入的上下文类型
     * @param <R>  Hook Pipeline 返回的结果类型
     * @return 新创建的 HookPoint；即使名称相同，也不会与其他实例视为同一个 HookPoint
     */
    public static <C, R> HookPoint<C, R> named(String name) {
        // 每次创建独立实例，由声明方发布并复用该标准实例。
        return new HookPoint<>(name);
    }

    /**
     * 获取用于日志和诊断的名称。
     *
     * @return Hook 名称
     */
    public String name() {
        // 返回构造时已经完成校验的不可变名称。
        return name;
    }

    /**
     * 返回便于日志定位的文本，不参与 HookPoint 身份判断。
     *
     * @return 包含 Hook 名称的诊断文本
     */
    @Override
    public String toString() {
        // 显式输出类型和名称，便于排查注册及分发问题。
        return "HookPoint[name=" + name + "]";
    }
}
