package com.financial.copilot.gateway.governance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h1>GatewayCapacityLimiterTest</h1>
 * <p>响应式信号量并发限流、排队等待与许可证释放测试。</p>
 */
@DisplayName("GatewayCapacityLimiter 响应式并发限流器测试")
class GatewayCapacityLimiterTest {

    @Test
    @DisplayName("初始容量充足时能够立即获取许可证")
    void shouldAcquireImmediatelyWhenPermitsAvailable() {
        GatewayCapacityLimiter limiter = new GatewayCapacityLimiter(2);
        assertThat(limiter.availablePermits()).isEqualTo(2);

        GatewayCapacityLimiter.CapacityPermit permit1 = limiter.acquire().block(Duration.ofSeconds(1));
        assertThat(permit1).isNotNull();
        assertThat(limiter.availablePermits()).isEqualTo(1);

        GatewayCapacityLimiter.CapacityPermit permit2 = limiter.acquire().block(Duration.ofSeconds(1));
        assertThat(permit2).isNotNull();
        assertThat(limiter.availablePermits()).isEqualTo(0);

        permit1.release();
        assertThat(limiter.availablePermits()).isEqualTo(1);

        permit2.close();
        assertThat(limiter.availablePermits()).isEqualTo(2);
    }

    @Test
    @DisplayName("容量耗尽时排队等待并在释放后自动恢复")
    void shouldQueueAndResumeWhenCapacityReleased() {
        GatewayCapacityLimiter limiter = new GatewayCapacityLimiter(1);
        GatewayCapacityLimiter.CapacityPermit permit = limiter.acquire().block();

        assertThat(limiter.availablePermits()).isEqualTo(0);

        // 异步获取许可证
        Mono<GatewayCapacityLimiter.CapacityPermit> queuedAcquire = limiter.acquire();

        // 验证在释放前保持阻塞/订阅未完成
        StepVerifier.create(queuedAcquire)
                .then(() -> {
                    assertThat(limiter.availablePermits()).isEqualTo(0);
                    permit.release();
                })
                .assertNext(p -> {
                    assertThat(p).isNotNull();
                    p.release();
                })
                .verifyComplete();

        assertThat(limiter.availablePermits()).isEqualTo(1);
    }
}
