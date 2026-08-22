-- 会话标题允许为空；非空标题最多保存 200 个 Unicode 码点。
-- 空值表示尚未由后续的标题生成或用户重命名流程设置标题。
ALTER TABLE sessions
    ADD COLUMN title TEXT CHECK (title IS NULL OR length(title) BETWEEN 1 AND 200);
