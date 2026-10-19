package com.financial.copilot;

import com.financial.copilot.agent.core.platform.billing.WalletBillingService;
import com.financial.copilot.common.exception.WalletInsufficientException;
import com.financial.copilot.domain.platform.billing.entity.PointReservation;
import com.financial.copilot.domain.platform.billing.entity.RechargeOrder;
import com.financial.copilot.domain.platform.billing.entity.ReservationStatus;
import com.financial.copilot.domain.platform.billing.port.BillingPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfSystemProperty(named = "copilot.integration", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BillingPersistenceTest {
    @Autowired WalletBillingService billing;
    @Autowired BillingPort port;
    @Autowired JdbcTemplate jdbc;

    @Test
    void concurrentPaymentNotificationsCreditExactlyOnceAndRejectAmountMismatch() throws Exception {
        long uid = ThreadLocalRandom.current().nextLong(100000000000L, 999999999999L);
        String orderNo = "TEST-" + UUID.randomUUID();
        String trade = "TRADE-" + UUID.randomUUID();
        var executor = Executors.newFixedThreadPool(2);
        try {
            port.getOrCreateWallet(uid, null);
            port.saveOrder(RechargeOrder.builder().orderNo(orderNo).userId(uid).packageId(1L)
                    .payAmountCny(new BigDecimal("49.00")).targetPoints(500L).payChannel("ALIPAY")
                    .orderStatus("PENDING").createdAt(LocalDateTime.now()).build());
            assertThrows(IllegalArgumentException.class, () -> billing.payCallback(orderNo, trade, BigDecimal.ONE));
            assertEquals(0L, port.getOrCreateWallet(uid, null).getBalancePoints());
            var start = new CountDownLatch(1);
            Callable<Void> callback = () -> {
                start.await();
                billing.payCallback(orderNo, trade, new BigDecimal("49.00"));
                return null;
            };
            var first = executor.submit(callback);
            var second = executor.submit(callback);
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertEquals(500L, port.getOrCreateWallet(uid, null).getBalancePoints());
            assertEquals("PAID", port.getOrderByNo(orderNo).orElseThrow().getOrderStatus());
            assertThrows(IllegalArgumentException.class,
                    () -> billing.payCallback(orderNo, "OTHER-TRADE", new BigDecimal("49.00")));
        } finally {
            executor.shutdownNow();
            jdbc.update("DELETE FROM sys_recharge_order WHERE order_no = ?", orderNo);
            jdbc.update("DELETE FROM sys_user_wallet WHERE user_id = ?", uid);
        }
    }

    @Test
    void ledgerFailureRollsBackDebitAndSuccessfulDebitRecordsActualUsage() {
        long uid = ThreadLocalRandom.current().nextLong(100000000000L, 999999999999L);
        String model = "test-" + UUID.randomUUID();
        try {
            port.getOrCreateWallet(uid, null);
            port.addRechargePoints(uid, 1000);
            jdbc.update("INSERT INTO llm_model_pricing (provider_type, model_name, input_price_per_k, output_price_per_k, is_active) VALUES ('DEEPSEEK', ?, 10, 20, true)", model);
            // PostgreSQL rejects task_type > 50 characters after the debit statement has run.
            assertThrows(RuntimeException.class, () -> billing.deductTokenPoints(uid, "test-session",
                    "x".repeat(51), "DEEPSEEK", model, 1000, 2000, 12));
            assertEquals(1000L, port.getOrCreateWallet(uid, null).getBalancePoints());
            assertEquals(0L, port.countLedger(uid, null, null));
            billing.deductTokenPoints(uid, "test-session", "LLM_CALL", "DEEPSEEK", model, 1000, 2000, 12);
            assertEquals(950L, port.getOrCreateWallet(uid, null).getBalancePoints());
            var ledger = port.queryLedger(uid, 0, 10, null, null).get(0);
            assertEquals(1000, ledger.getPromptTokens());
            assertEquals(2000, ledger.getCompletionTokens());
            assertEquals(50L, ledger.getConsumedPoints());
        } finally {
            jdbc.update("DELETE FROM llm_token_usage_ledger WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM sys_user_wallet WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM llm_model_pricing WHERE model_name = ?", model);
        }
    }

    @Test
    void concurrentRunsCannotReserveMoreThanAvailableBalance() throws Exception {
        long uid = ThreadLocalRandom.current().nextLong(100000000000L, 999999999999L);
        String runA = "run-a-" + UUID.randomUUID();
        String runB = "run-b-" + UUID.randomUUID();
        var executor = Executors.newFixedThreadPool(2);
        try {
            port.getOrCreateWallet(uid, null);
            port.addRechargePoints(uid, 1000L);

            var start = new CountDownLatch(1);
            Callable<PointReservation> reserveA = () -> {
                start.await();
                return billing.reserve(uid, runA, 800L);
            };
            Callable<PointReservation> reserveB = () -> {
                start.await();
                return billing.reserve(uid, runB, 800L);
            };

            var fA = executor.submit(reserveA);
            var fB = executor.submit(reserveB);
            start.countDown();

            int successes = 0;
            int failures = 0;
            try {
                PointReservation resA = fA.get(10, TimeUnit.SECONDS);
                assertEquals(ReservationStatus.ACTIVE, resA.status());
                successes++;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof WalletInsufficientException) {
                    failures++;
                } else {
                    throw e;
                }
            }

            try {
                PointReservation resB = fB.get(10, TimeUnit.SECONDS);
                assertEquals(ReservationStatus.ACTIVE, resB.status());
                successes++;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof WalletInsufficientException) {
                    failures++;
                } else {
                    throw e;
                }
            }

            assertEquals(1, successes);
            assertEquals(1, failures);
            assertEquals(800L, port.getOrCreateWallet(uid, null).getFrozenPoints());
            assertEquals(1000L, port.getOrCreateWallet(uid, null).getBalancePoints());
        } finally {
            executor.shutdownNow();
            jdbc.update("DELETE FROM sys_point_reservation WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM sys_user_wallet WHERE user_id = ?", uid);
        }
    }

    @Test
    void duplicateSettlementDoesNotDoubleCharge() {
        long uid = ThreadLocalRandom.current().nextLong(100000000000L, 999999999999L);
        String runId = "run-" + UUID.randomUUID();
        try {
            port.getOrCreateWallet(uid, null);
            port.addRechargePoints(uid, 1000L);

            billing.reserve(uid, runId, 500L);
            assertEquals(500L, port.getOrCreateWallet(uid, null).getFrozenPoints());

            billing.settle(uid, runId, 420L);
            billing.settle(uid, runId, 420L);

            var wallet = port.getOrCreateWallet(uid, null);
            assertEquals(420L, wallet.getTotalConsumedPoints());
            assertEquals(580L, wallet.getBalancePoints());
            assertEquals(0L, wallet.getFrozenPoints());
        } finally {
            jdbc.update("DELETE FROM sys_point_reservation WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM sys_user_wallet WHERE user_id = ?", uid);
        }
    }

    @Test
    void releaseUnusedReservationRestoresBalance() {
        long uid = ThreadLocalRandom.current().nextLong(100000000000L, 999999999999L);
        String runId = "run-" + UUID.randomUUID();
        try {
            port.getOrCreateWallet(uid, null);
            port.addRechargePoints(uid, 1000L);

            billing.reserve(uid, runId, 600L);
            assertEquals(600L, port.getOrCreateWallet(uid, null).getFrozenPoints());

            billing.release(uid, runId);

            var wallet = port.getOrCreateWallet(uid, null);
            assertEquals(0L, wallet.getFrozenPoints());
            assertEquals(1000L, wallet.getBalancePoints());
            assertEquals(0L, wallet.getTotalConsumedPoints());
        } finally {
            jdbc.update("DELETE FROM sys_point_reservation WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM sys_user_wallet WHERE user_id = ?", uid);
        }
    }
}
