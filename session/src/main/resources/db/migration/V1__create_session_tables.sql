-- 会话持久化表。SQLite 的外键约束必须在每个连接创建后开启。
CREATE TABLE IF NOT EXISTS sessions (
    id                     TEXT PRIMARY KEY,
    project_root_path      TEXT NOT NULL,
    working_directory_path TEXT NOT NULL,
    status                 TEXT NOT NULL,
    last_seq               INTEGER NOT NULL DEFAULT 0,
    version                INTEGER NOT NULL DEFAULT 0,
    created_at             INTEGER NOT NULL,
    updated_at             INTEGER NOT NULL,
    metadata_json          TEXT NOT NULL,

    CHECK (status IN ('IDLE', 'RUNNING', 'INTERRUPTED', 'ARCHIVED')),
    CHECK (last_seq >= 0),
    CHECK (version >= 0),
    CHECK (created_at >= 0),
    CHECK (updated_at >= created_at)
);

-- 对话消息只追加、不原地覆盖；(session_id, seq) 保证会话内的唯一顺序。
CREATE TABLE IF NOT EXISTS messages (
    session_id     TEXT NOT NULL,
    seq            INTEGER NOT NULL,
    agent_round_no INTEGER,
    role           TEXT NOT NULL,
    content        TEXT,
    tool_call_id   TEXT,
    created_at     INTEGER NOT NULL,
    payload_json   TEXT NOT NULL,

    PRIMARY KEY (session_id, seq),
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE,

    CHECK (seq > 0),
    CHECK (agent_round_no IS NULL OR agent_round_no > 0),
    CHECK (created_at >= 0),
    CHECK (
        (role IN ('SYSTEM', 'USER')
            AND agent_round_no IS NULL
            AND content IS NOT NULL AND length(trim(content)) > 0
            AND tool_call_id IS NULL)
        OR
        (role = 'ASSISTANT'
            AND agent_round_no IS NOT NULL
            AND tool_call_id IS NULL)
        OR
        (role = 'TOOL'
            AND agent_round_no IS NOT NULL
            AND content IS NOT NULL
            AND tool_call_id IS NOT NULL AND length(trim(tool_call_id)) > 0)
    )
);

-- 启动恢复时按状态扫描会话。
CREATE INDEX IF NOT EXISTS idx_sessions_status_updated_at
    ON sessions (status, updated_at);

-- 从完整历史中按 Agent 调用轮次截取上下文或定位工具结果。
CREATE INDEX IF NOT EXISTS idx_messages_session_round
    ON messages (session_id, agent_round_no);
