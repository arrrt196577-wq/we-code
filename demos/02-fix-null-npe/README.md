# Demo 02: Fix Null NPE

## Goal

定位并修复一段会触发 `NullPointerException` 的代码。

## Input

| 文件 | 说明 |
|------|------|
| `src/UserProfile.java` | 姓名/邮箱 record，字段可能为 null |
| `src/NotificationService.java` | `buildWelcomeEmail` / `completenessScore` 未做空值防御 |
| `src/DemoRunner.java` | 先跑正常路径，再对 `null` 用户调用（会 NPE） |

## Expected outcome

- 空值得到防御性处理或明确报错
- 原有正常路径行为不变

## 试用（当前只读工具）

```powershell
mvn -q -pl cli exec:java "-Dexec.args=--workspace demos/02-fix-null-npe 用 Grep 找 trim 或 toLowerCase，Read NotificationService 和 DemoRunner，指出哪些调用会 NPE，并给出修复建议（先不要改文件）"
```
