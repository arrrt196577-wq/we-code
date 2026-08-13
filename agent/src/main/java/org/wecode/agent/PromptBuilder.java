package org.wecode.agent;

/**
 * 组装每轮对话的 system / 辅助文案。
 */
public final class PromptBuilder {

    /**
     * System prompt：声明当前可用的 Read / Glob / Grep / Edit，路径相对工作区目录。
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
                - Edit: exact string replacement in a file. By default old_string must match once;
                  set replace_all=true to replace every match. To create a new file, pass empty old_string.
                Paths are relative to the workspace path (absolute paths may also work if inside the workspace).
                Typical flow: Glob or Grep to locate, Read to inspect, Edit to change, then answer in clear natural language.
                Do not invent file contents or search results; use the tools when you need evidence.
                Before Edit, Read and copy an exact unique snippet into old_string.
                Do not claim you can run shell commands — bash is not available.
                """.stripIndent().trim();
    }
}
