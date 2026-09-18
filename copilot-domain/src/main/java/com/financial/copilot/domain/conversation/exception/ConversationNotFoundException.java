package com.financial.copilot.domain.conversation.exception;

public class ConversationNotFoundException extends RuntimeException {
    public ConversationNotFoundException() {
        super("Conversation or run not found");
    }
}
