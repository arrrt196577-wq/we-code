package org.wecode.cli.interaction;

/**
 * 交互循环对自然语言和本地命令的业务分发契约。
 */
public interface InteractionHandler {

    /**
     * 处理一条非命令自然语言输入。
     *
     * @param userMessage 用户原始输入
     * @return 应输出到终端的 Agent 最终文本，可为空
     */
    String handleUserMessage(String userMessage);

    /**
     * 重命名当前活动会话。
     *
     * @param title 已解析出的标题参数
     */
    void renameActiveSession(String title);

    /**
     * 判断当前交互是否已绑定活动会话。
     *
     * @return 存在活动会话时返回 {@code true}
     */
    boolean hasActiveSession();
}
