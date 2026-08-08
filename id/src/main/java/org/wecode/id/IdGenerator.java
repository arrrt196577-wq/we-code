package org.wecode.id;

/**
 * 生成跨业务可复用的原始文本标识。
 * <p>
 * 本接口不承担业务类型、持久化去重或权限校验职责；业务模块应将返回值包装为自身的值对象。
 */
public interface IdGenerator {

    /**
     * 生成一个全局唯一的原始文本标识。
     *
     * @return 非空的 ID 文本
     */
    String nextId();
}
