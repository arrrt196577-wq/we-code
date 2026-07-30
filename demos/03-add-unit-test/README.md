# Demo 03: Add Unit Test

## Goal

为已有纯函数工具类补齐最小可运行的单元测试。

## Input

| 文件 | 说明 |
|------|------|
| `src/TextStats.java` | `countNonWhitespace` / `countWords` / `isPalindrome` |
| `src/Slugify.java` | `fromTitle` 转 URL slug |

当前**没有**测试类，适合练习「先读懂再写测试」。

## Expected outcome

- 新增至少 1 个通过的测试用例（具备 Edit 后）
- 不修改被测业务语义（除非发现明显缺陷）

## 试用（当前只读工具）

```powershell
mvn -q -pl cli exec:java "-Dexec.args=--workspace demos/03-add-unit-test 用 Glob 列出所有 Java 文件，Read TextStats，为三个方法各设计 2 个边界用例（用中文写出测试意图与期望返回值）"
```
