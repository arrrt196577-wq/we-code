-- 标题来源用于区分首条消息的临时标题、模型标题与用户手动重命名，避免迟到的模型结果覆盖用户输入。
-- 旧的 V2 数据库可能已经写入 title，因此新增列先允许空值，再显式回填历史标题来源。
ALTER TABLE sessions
    ADD COLUMN title_source TEXT
    CHECK (title_source IS NULL OR title_source IN ('TEMPORARY', 'MODEL', 'USER'));

-- V2 无法区分历史标题来源；为保持可恢复性，将已有非空标题保守视为首条消息兜底标题。
UPDATE sessions
SET title_source = 'TEMPORARY'
WHERE title IS NOT NULL;
