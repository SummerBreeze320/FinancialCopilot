package com.financial.copilot.controller;

import com.financial.copilot.agent.core.platform.billing.WalletBillingService;
import com.financial.copilot.agent.core.platform.conversation.ConversationService;
import com.financial.copilot.agent.core.infra.security.UserPrincipal;
import com.financial.copilot.agent.core.infra.workflow.FinancialResearchWorkflow;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResearchRunControllerTest {
    @Test
    void foreignOrMissingRunReturnsNotFound() {
        FinancialResearchWorkflow workflow = mock(FinancialResearchWorkflow.class);
        when(workflow.findCheckpoint(8L, "owned-by-7")).thenReturn(Optional.empty());
        ResearchRunController controller = new ResearchRunController(workflow, mock(WalletBillingService.class),
                mock(ConversationService.class));
        var auth = new UsernamePasswordAuthenticationToken(UserPrincipal.builder().userId(8L).build(), null, List.of());
        assertThatThrownBy(() -> controller.status("owned-by-7")
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)).block())
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }
}
