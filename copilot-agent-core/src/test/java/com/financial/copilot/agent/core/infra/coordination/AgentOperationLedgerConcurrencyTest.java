package com.financial.copilot.agent.core.infra.coordination;

import com.financial.copilot.agent.core.infra.agent.AgentRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOperationLedgerConcurrencyTest {

    @Test
    void executesSuppliedFunctionExactlyOnceForSameIdempotencyKey() throws Exception {
        AgentOperationLedger ledger = new InMemoryAgentOperationLedger();
        String idempotencyKey = "run-1:fund:getFundProfile:hash123";
        AtomicInteger executionCounter = new AtomicInteger(0);

        int concurrency = 100;
        List<Callable<String>> tasks = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            tasks.add(() -> ledger.executeOnce(
                    idempotencyKey,
                    "run-1",
                    "agent-1",
                    AgentRole.FUND_RESEARCH,
                    "getFundProfile",
                    () -> {
                        executionCounter.incrementAndGet();
                        try {
                            Thread.sleep(10); // simulate deterministic computation
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "{\"status\":\"SUCCESS\",\"code\":\"005827\"}";
                    }
            ));
        }

        try (var executor = Executors.newFixedThreadPool(16)) {
            List<Future<String>> futures = executor.invokeAll(tasks);
            for (Future<String> f : futures) {
                String result = f.get();
                assertThat(result).isEqualTo("{\"status\":\"SUCCESS\",\"code\":\"005827\"}");
            }
        }

        // Must execute exactly once
        assertThat(executionCounter.get()).isEqualTo(1);

        // Ledger record must be in SUCCEEDED status
        var op = ledger.getOperation(idempotencyKey);
        assertThat(op).isPresent();
        assertThat(op.get().status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(op.get().resultReference()).contains("005827");
    }

    @Test
    void executesDifferentKeysIndependently() {
        AgentOperationLedger ledger = new InMemoryAgentOperationLedger();
        AtomicInteger counter = new AtomicInteger(0);

        String res1 = ledger.executeOnce(
                "key-1",
                "run-1",
                "agent-1",
                AgentRole.FUND_RESEARCH,
                "tool1",
                () -> {
                    counter.incrementAndGet();
                    return "res-1";
                }
        );

        String res2 = ledger.executeOnce(
                "key-2",
                "run-1",
                "agent-1",
                AgentRole.FUND_RESEARCH,
                "tool2",
                () -> {
                    counter.incrementAndGet();
                    return "res-2";
                }
        );

        assertThat(counter.get()).isEqualTo(2);
        assertThat(res1).isEqualTo("res-1");
        assertThat(res2).isEqualTo("res-2");
    }
}
