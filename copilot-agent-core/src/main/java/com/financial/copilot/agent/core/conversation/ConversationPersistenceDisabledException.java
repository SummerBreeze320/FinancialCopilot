package com.financial.copilot.agent.core.conversation;

public class ConversationPersistenceDisabledException extends RuntimeException {
    public ConversationPersistenceDisabledException() {
        super("Conversation persistence is disabled");
    }
}
