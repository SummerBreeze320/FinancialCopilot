package com.financial.copilot.domain.platform.conversation.exception;

public class ConversationNotFoundException extends RuntimeException {
    public ConversationNotFoundException() {
        super("Conversation or run not found");
    }
}
