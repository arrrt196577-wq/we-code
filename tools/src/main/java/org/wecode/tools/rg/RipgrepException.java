package org.wecode.tools.rg;

/**
 * ripgrep 调用失败：未找到可执行文件、超时、进程错误或输出异常。
 */
public final class RipgrepException extends RuntimeException {

    /**
     * @param message 错误说明
     */
    public RipgrepException(String message) {
        super(message);
    }

    /**
     * @param message 错误说明
     * @param cause   底层原因
     */
    public RipgrepException(String message, Throwable cause) {
        super(message, cause);
    }
}
