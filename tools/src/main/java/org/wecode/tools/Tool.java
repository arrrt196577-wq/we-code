package org.wecode.tools;

/**
 * Common contract for agent tools. One implementation per tool file.
 */
public interface Tool {

    String name();

    String description();

    String execute(String input);
}
