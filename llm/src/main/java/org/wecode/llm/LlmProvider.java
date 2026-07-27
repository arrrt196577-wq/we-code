package org.wecode.llm;

/**
 * Abstraction over LLM providers.
 */
public interface LlmProvider {

    String complete(String prompt);
}
