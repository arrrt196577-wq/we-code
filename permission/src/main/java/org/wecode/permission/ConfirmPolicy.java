package org.wecode.permission;

/**
 * Default confirmation strategy (ask / allowlist / deny).
 */
public final class ConfirmPolicy implements PermissionPolicy {

    @Override
    public boolean allow(String toolName, String input) {
        // TODO: apply confirmation rules
        throw new UnsupportedOperationException("not implemented");
    }
}
