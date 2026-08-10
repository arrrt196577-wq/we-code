-- 会话根表：保存会话边界、运行状态和消息序号的并发控制信息。
CREATE TABLE IF NOT EXISTS sessions (
    id                     TEXT PRIMARY KEY,
    project_root_path      TEXT NOT NULL,
    working_directory_path TEXT NOT NULL,
    status                 TEXT NOT NULL,
    last_sequence_no       INTEGER NOT NULL DEFAULT 0,
    version                INTEGER NOT NULL DEFAULT 0,
    created_at             INTEGER NOT NULL,
    updated_at             INTEGER NOT NULL,
    metadata_json          TEXT NOT NULL,

    CHECK (status IN ('IDLE', 'RUNNING', 'INTERRUPTED', 'ARCHIVED')),
    CHECK (last_sequence_no >= 0),
    CHECK (version >= 0),
    CHECK (created_at >= 0),
    CHECK (updated_at >= created_at),
    CHECK (json_valid(metadata_json))
);

-- 会话历史事件表：system、user、assistant 与 compaction 均只追加、不原地修改。
CREATE TABLE IF NOT EXISTS session_message (
    id                           TEXT PRIMARY KEY,
    session_id                   TEXT NOT NULL,
    sequence_no                  INTEGER NOT NULL,
    message_type                 TEXT NOT NULL,
    payload_version              INTEGER NOT NULL,
    payload_json                 TEXT NOT NULL,
    compacts_through_sequence    INTEGER,
    created_at                   INTEGER NOT NULL,

    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE,
    -- compaction 仅能覆盖本会话中已存在的、更早的一条消息。
    FOREIGN KEY (session_id, compacts_through_sequence)
        REFERENCES session_message(session_id, sequence_no)
        ON DELETE CASCADE,

    UNIQUE (session_id, sequence_no),
    CHECK (sequence_no > 0),
    CHECK (message_type IN ('SYSTEM', 'USER', 'ASSISTANT', 'COMPACTION')),
    CHECK (payload_version > 0),
    CHECK (json_valid(payload_json)),
    CHECK (created_at >= 0),
    CHECK (
        (message_type = 'COMPACTION'
            AND compacts_through_sequence IS NOT NULL
            AND compacts_through_sequence > 0
            AND compacts_through_sequence < sequence_no)
        OR
        (message_type <> 'COMPACTION' AND compacts_through_sequence IS NULL)
    )
);

-- 工具执行表：assistant 消息不可变，工具的可变执行状态独立维护。
CREATE TABLE IF NOT EXISTS tool_execution (
    id                     TEXT PRIMARY KEY,
    assistant_message_id   TEXT NOT NULL,
    call_id                TEXT NOT NULL,
    call_index             INTEGER NOT NULL,
    tool_name              TEXT NOT NULL,
    arguments_json         TEXT NOT NULL,
    status                 TEXT NOT NULL,
    result_json            TEXT,
    attempt_count          INTEGER NOT NULL DEFAULT 0,
    lease_token            TEXT,
    lease_until            INTEGER,
    revision               INTEGER NOT NULL DEFAULT 0,
    created_at             INTEGER NOT NULL,
    started_at             INTEGER,
    finished_at            INTEGER,
    updated_at             INTEGER NOT NULL,

    FOREIGN KEY (assistant_message_id) REFERENCES session_message(id) ON DELETE CASCADE,

    UNIQUE (assistant_message_id, call_id),
    UNIQUE (assistant_message_id, call_index),
    CHECK (call_id <> ''),
    CHECK (call_index >= 0),
    CHECK (tool_name <> ''),
    CHECK (json_valid(arguments_json)),
    CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCELLED')),
    CHECK (result_json IS NULL OR json_valid(result_json)),
    CHECK (attempt_count >= 0),
    CHECK (revision >= 0),
    CHECK (created_at >= 0),
    CHECK (updated_at >= created_at),
    CHECK (started_at IS NULL OR started_at >= created_at),
    CHECK (finished_at IS NULL OR (started_at IS NOT NULL AND finished_at >= started_at))
);

-- SQLite 外键只能确认父消息存在，触发器继续保证工具调用只能从 assistant 消息派生。
CREATE TRIGGER IF NOT EXISTS trg_tool_execution_requires_assistant_message
BEFORE INSERT ON tool_execution
FOR EACH ROW
WHEN NOT EXISTS (
    SELECT 1
    FROM session_message
    WHERE id = NEW.assistant_message_id
      AND message_type = 'ASSISTANT'
)
BEGIN
    SELECT RAISE(ABORT, 'tool_execution.assistant_message_id must reference an ASSISTANT message');
END;

-- 启动恢复时按状态扫描会话。
CREATE INDEX IF NOT EXISTS idx_sessions_status_updated_at
    ON sessions (status, updated_at);

-- 组装 Prompt 时按会话顺序读取历史，并快速定位最新压缩节点。
CREATE INDEX IF NOT EXISTS idx_session_message_session_type_compaction
    ON session_message (session_id, message_type, compacts_through_sequence DESC);

-- 工具调度器按可领取状态和租约扫描待处理调用。
CREATE INDEX IF NOT EXISTS idx_tool_execution_status_lease
    ON tool_execution (status, lease_until);
