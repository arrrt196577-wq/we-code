package org.wecode.hook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookPointTest {

    /** 验证合法名称可以创建具有明确上下文和结果泛型的 HookPoint。 */
    @Test
    void createsNamedHookPoint() {
        HookPoint<String, Integer> hookPoint = HookPoint.named("agent.before-model");

        assertEquals("agent.before-model", hookPoint.name());
    }

    /** 验证空引用名称会在创建阶段被拒绝。 */
    @Test
    void rejectsNullName() {
        assertThrows(NullPointerException.class, () -> HookPoint.named(null));
    }

    /** 验证空字符串和全空白字符串不能作为诊断名称。 */
    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> HookPoint.named(""));
        assertThrows(IllegalArgumentException.class, () -> HookPoint.named("   "));
    }

    /** 验证名称首尾空白不会被静默修剪。 */
    @Test
    void rejectsLeadingOrTrailingWhitespace() {
        assertThrows(IllegalArgumentException.class, () -> HookPoint.named(" agent.before-model"));
        assertThrows(IllegalArgumentException.class, () -> HookPoint.named("agent.before-model "));
    }

    /** 验证同名 HookPoint 仍保持独立的对象身份。 */
    @Test
    void sameNameDoesNotCreateEqualHookPoints() {
        HookPoint<String, Integer> first = HookPoint.named("agent.before-model");
        HookPoint<String, Integer> second = HookPoint.named("agent.before-model");

        assertNotEquals(first, second);
    }

    /** 验证诊断文本包含 Hook 名称。 */
    @Test
    void toStringContainsHookName() {
        HookPoint<String, Integer> hookPoint = HookPoint.named("agent.before-model");

        assertTrue(hookPoint.toString().contains("agent.before-model"));
    }
}
