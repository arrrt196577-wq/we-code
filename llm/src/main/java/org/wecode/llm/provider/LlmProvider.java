package org.wecode.llm.provider;

import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.ToolSpec;

import java.util.List;

/**
 * Abstraction over LLM providers.
 */
public interface LlmProvider {

    /**
     * Run one chat completion turn with optional tools.
     *
     * @param messages conversation so far (system / user / assistant / tool)
     * @param tools    tool specs advertised to the model; may be empty
     * @return model output including optional tool calls
     */
    LlmResponse chat(List<Message> messages, List<ToolSpec> tools);
}
