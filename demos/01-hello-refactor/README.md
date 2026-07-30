# Demo 01: Hello Refactor

## Goal

将重复的问候字符串拼接重构为可复用方法，不改变对外行为。

## Input

| 文件 | 说明 |
|------|------|
| `src/HelloApp.java` | `main` 里三次 `Hello` / 两次 `Welcome` 手写拼接 |
| `src/Greeter.java` | 同类拼接逻辑，适合与上面抽公共方法 |

## Expected outcome

- 抽出公共方法（如 `formatHello(name)`）
- 行为保持不变
- 改动范围可控、可 diff 复查

## 试用（当前只读工具）

在仓库根目录：

```powershell
mvn -q -pl cli exec:java "-Dexec.args=--workspace demos/01-hello-refactor 用 Glob 找 **/*.java，再用 Grep 搜索 Hello，最后 Read 相关文件，用中文说明哪里适合重构"
```
