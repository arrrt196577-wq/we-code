package org.wecode.cli.project;

/**
 * WeCode 启动时的工作区确认结果。
 */
public enum WorkspaceStartupResult {

    /** 当前目录已存在工作区记录。 */
    EXISTS,

    /** 用户确认后已创建工作区记录。 */
    CREATED,

    /** 用户拒绝、未确认或未提供输入，程序应退出。 */
    CANCELLED
}
