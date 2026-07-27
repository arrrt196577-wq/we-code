package org.wecode.permission;

/**
 * Decides whether a tool action requires confirmation / is allowed.
 */
public interface PermissionPolicy {

    boolean allow(String toolName, String input);
}
