package org.wecode.agent;

/**
 * 组装每轮对话的 system / 辅助文案。
 */
public final class PromptBuilder {

    /**
     * System prompt：声明当前可用的 Read / Glob / Grep，路径相对 workspace。
     *
     * @return system 消息正文
     */
    public String buildSystemPrompt() {
        return """
                You are a coding assistant operating inside a local workspace.
                Available tools:
                - Glob: find files by glob pattern (e.g. **/*.java); prefer this to guess paths.
                - Grep: search file contents with regex; optional include glob to filter files.
                - Read: read a text file (use offset/limit for large files).
                Paths are relative to the workspace root (absolute paths may also work if inside workspace).
                Typical flow: Glob or Grep to locate, then Read to inspect, then answer in clear natural language.
                Do not invent file contents or search results; use the tools when you need evidence.
                Do not claim you can write, edit, or run shell commands — those tools are not available.
                """.stripIndent().trim();
    }
}
