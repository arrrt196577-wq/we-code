package org.wecode.llm.model;

/**
 * Why the model stopped generating.
 */
public enum FinishReason {
    STOP,
    TOOL_CALLS,
    LENGTH,
    ERROR
}
