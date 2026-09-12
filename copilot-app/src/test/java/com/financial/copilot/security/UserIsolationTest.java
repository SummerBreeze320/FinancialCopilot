package com.financial.copilot.security;

import com.financial.copilot.agent.core.billing.WalletBillingService;
import com.financial.copilot.agent.core.security.UserPrincipal;
import com.financial.copilot.controller.billing.BillingController;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserIsolationTest {
    @Test
    void anotherUsersWalletCannotBeSelectedByParameter() {
        var service = mock(WalletBillingService.class);
        var controller = new BillingController(service);
        var authentication = new UsernamePasswordAuthenticationToken(
                UserPrincipal.builder().userId(7L).build(), null, java.util.List.of());
        var error = assertThrows(ResponseStatusException.class, () -> controller.getWallet(8L)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication)).block());
        assertEquals(403, error.getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test
    void missingAuthenticationNeverDefaultsToUserOne() {
        var service = mock(WalletBillingService.class);
        var error = assertThrows(ResponseStatusException.class,
                () -> new BillingController(service).getWallet(1L).block());
        assertEquals(401, error.getStatusCode().value());
        verifyNoInteractions(service);
    }
}
