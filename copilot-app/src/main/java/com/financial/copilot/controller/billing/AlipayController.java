package com.financial.copilot.controller.billing;

import com.financial.copilot.agent.core.billing.AlipayPaymentService;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.config.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/billing/alipay")
@RequiredArgsConstructor
public class AlipayController {
    private final AlipayPaymentService alipay;

    @PostMapping("/order/{orderNo}/pay")
    public Mono<ApiResult<String>> pay(@PathVariable("orderNo") String orderNo) {
        return SecurityUtils.requireCurrentUserId(null).flatMap(uid -> Mono.fromCallable(() ->
                ApiResult.success(alipay.paymentUrl(uid, orderNo))).subscribeOn(Schedulers.boundedElastic()))
                .onErrorMap(SecurityException.class, e -> new ResponseStatusException(HttpStatus.FORBIDDEN))
                .onErrorMap(IllegalStateException.class, e -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "支付未就绪"))
                .onErrorMap(IllegalArgumentException.class, e -> new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    @PostMapping(value = "/notify", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> notifyPayment(ServerWebExchange exchange) {
        return exchange.getFormData().flatMap(form -> Mono.fromCallable(() -> {
            if (form.values().stream().anyMatch(values -> values.size() != 1)) {
                throw new IllegalArgumentException("Duplicate notification fields");
            }
            alipay.acceptNotification(form.toSingleValueMap());
            return "success";
        }).subscribeOn(Schedulers.boundedElastic())).onErrorReturn("failure");
    }
}
