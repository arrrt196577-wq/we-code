/**
 * 用户档案：姓名与邮箱。
 *
 * @param name  显示名；可能为 null（上游未校验）
 * @param email 邮箱；可能为 null
 */
public record UserProfile(String name, String email) {
}
