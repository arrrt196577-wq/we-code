package org.wecode.llm.provider;

import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.ToolSpec;

import java.util.List;

/**
 * Placeholder OpenAI-compatible provider adapter.
 */
public final class OpenAiProvider implements LlmProvider {

    @Override
    public LlmResponse chat(List<Message> messages, List<ToolSpec> tools) {
        // TODO: call OpenAI-compatible API
        throw new UnsupportedOperationException("not implemented");
    }
}
