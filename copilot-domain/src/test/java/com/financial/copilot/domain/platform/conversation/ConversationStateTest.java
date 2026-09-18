package com.financial.copilot.domain.platform.conversation;
import com.financial.copilot.domain.platform.conversation.entity.*;
import com.financial.copilot.domain.platform.conversation.model.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConversationStateTest {
 @Test void terminalStatesAreFinal() {
  var message=ConversationMessage.runningAssistant(1L,UUID.randomUUID(),7L,UUID.randomUUID(),2L,Instant.now());
  assertEquals(MessageStatus.COMPLETED,message.complete("report",Map.of(),Instant.now()).status());
  assertThrows(IllegalStateException.class,()->message.complete("r",Map.of(),Instant.now()).fail("ERROR","failed",Instant.now()));
 }
 @Test void userIsCompletedAndSequencesMustBePositive() {
  assertEquals(MessageStatus.COMPLETED,ConversationMessage.user(1L,UUID.randomUUID(),7L,UUID.randomUUID(),1L,"p",Instant.now()).status());
  assertThrows(IllegalArgumentException.class,()->ConversationMessage.runningAssistant(1L,UUID.randomUUID(),7L,UUID.randomUUID(),0L,Instant.now()));
 }
}
