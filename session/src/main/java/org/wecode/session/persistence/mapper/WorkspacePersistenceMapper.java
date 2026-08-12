package org.wecode.session.persistence.mapper;

import org.apache.ibatis.annotations.Param;
import org.wecode.session.persistence.entity.WorkspaceRecord;

/**
 * 已确认工作区的持久化语句。
 */
public interface WorkspacePersistenceMapper {

    /**
     * 创建一个工作区；根目录与信任信息创建后不可修改。
     *
     * @param workspace 待持久化的工作区
     * @return 成功插入的行数
     */
    int insert(WorkspaceRecord workspace);

    /**
     * 按工作区标识读取工作区。
     *
     * @param workspaceId 工作区唯一标识
     * @return 工作区不存在时为 {@code null}
     */
    WorkspaceRecord findById(@Param("workspaceId") String workspaceId);

    /**
     * 按规范化后的真实根路径读取工作区，用于判断当前目录是否已获用户确认。
     *
     * @param rootPath 规范化后的真实绝对路径
     * @return 工作区不存在时为 {@code null}
     */
    WorkspaceRecord findByRootPath(@Param("rootPath") String rootPath);
}
