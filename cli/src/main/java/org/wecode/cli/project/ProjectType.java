package org.wecode.cli.project;

/**
 * 当前工作区目录类型。
 */
public enum ProjectType {

    /** 当前工作区目录位于 Git worktree 内；这不会改变工作区路径。 */
    GIT_REPOSITORY,

    /** 当前工作区目录不属于 Git worktree。 */
    LOCAL_DIRECTORY
}
