package org.wecode.session;

import org.wecode.llm.model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Holds conversation history and runtime session state.
 */
public final class Session {

    private final List<Message> messages = new ArrayList<>();

    public void append(Message message) {
        messages.add(Objects.requireNonNull(message, "message"));
    }

    public List<Message> messages() {
        return List.copyOf(messages);
    }

    public String snapshot() {
        // TODO: return serializable history snapshot
        throw new UnsupportedOperationException("not implemented");
    }
}
