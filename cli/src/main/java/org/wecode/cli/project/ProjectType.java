package org.wecode.cli.project;

/**
 * 当前启动目录对应的项目类型。
 */
public enum ProjectType {

    /** 当前目录位于 Git 仓库内，项目根目录为 Git worktree 根目录。 */
    GIT_REPOSITORY,

    /** 当前目录不属于 Git 仓库，当前目录本身作为本地项目根目录。 */
    LOCAL_DIRECTORY
}
