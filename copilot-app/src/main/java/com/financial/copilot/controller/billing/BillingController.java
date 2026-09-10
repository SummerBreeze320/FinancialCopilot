package com.financial.copilot.controller.billing;

import com.financial.copilot.agent.core.billing.WalletBillingService;
import com.financial.copilot.common.result.ApiResult;
import com.financial.copilot.domain.billing.dto.RechargeOrderCreateDTO;
import com.financial.copilot.domain.billing.dto.UsageTrendPointDTO;
import com.financial.copilot.domain.billing.dto.WalletDTO;
import com.financial.copilot.domain.billing.entity.ModelPricing;
import com.financial.copilot.domain.billing.entity.RechargeOrder;
import com.financial.copilot.domain.billing.entity.RechargePackage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * <h1>商业化 Token 计量计费与账户收银台 REST 控制器 (Billing & Commercial Controller)</h1>
 * <p>
 * 职责：
 * 1. 提供用户钱包资产查询（可用算力点数、折合法币、预估报告份数）；
 * 2. 在线充值规格套餐清单查询与收银台订单创建；
 * 3. 支付回调确认与秒级到账；
 * 4. Token 消费对账明细与每日消耗走势图表数据源；
 * 5. 全平台公开透明的大模型阶梯计费矩阵。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final WalletBillingService billingService;

    public BillingController(WalletBillingService billingService) {
        this.billingService = billingService;
    }

    /**
     * 查询当前登录用户的算力钱包资产状态
     *
     * @param userId 用户 ID (可选，默认为 1)
     * @return 钱包展示对象
     */
    @GetMapping("/wallet")
    public ApiResult<WalletDTO> getWallet(@RequestParam(value = "userId", required = false) Long userId) {
        Long uid = userId != null ? userId : 1L;
        log.info("[HTTP-BILLING] 查询用户算力资产: userId={}", uid);
        return ApiResult.success(billingService.getWallet(uid));
    }

    /**
     * 获取当前所有已上架生效的充值规格套餐列表
     *
     * @return 套餐规格列表
     */
    @GetMapping("/packages")
    public ApiResult<List<RechargePackage>> listPackages() {
        log.info("[HTTP-BILLING] 查询在线充值规格套餐列表");
        return ApiResult.success(billingService.listPackages());
    }

    /**
     * 创建算力充值交易订单
     *
     * @param request 创建订单参数 (套餐 ID 与支付通道)
     * @param userId  用户 ID (可选，默认为 1)
     * @return 待支付交易订单详情
     */
    @PostMapping("/order/create")
    public ApiResult<RechargeOrder> createOrder(@RequestBody RechargeOrderCreateDTO request,
                                               @RequestParam(value = "userId", required = false) Long userId) {
        Long uid = userId != null ? userId : 1L;
        log.info("[HTTP-BILLING] 用户发起算力充值下单: userId={}, packageId={}, channel={}",
                uid, request.getPackageId(), request.getPayChannel());
        RechargeOrder order = billingService.createOrder(uid, request.getPackageId(), request.getPayChannel());
        return ApiResult.success(order);
    }

    /**
     * 支付网关异步回调接口（模拟或接收微信/支付宝支付成功通知）
     *
     * @param callbackPayload 回调请求载荷（含 orderNo 与 thirdPartyTradeNo）
     * @return 支付确认结果
     */
    @PostMapping("/order/pay-callback")
    public ApiResult<RechargeOrder> payCallback(@RequestBody Map<String, String> callbackPayload) {
        String orderNo = callbackPayload.get("orderNo");
        String tradeNo = callbackPayload.get("thirdPartyTradeNo");
        log.info("[HTTP-BILLING] 收到支付成功异步回调通知: orderNo={}, tradeNo={}", orderNo, tradeNo);
        RechargeOrder order = billingService.payCallback(orderNo, tradeNo);
        return ApiResult.success(order);
    }

    /**
     * 分页查询当前用户的 Token 消费明细对账流水
     *
     * @param userId   用户 ID
     * @param page     页码 (默认 1)
     * @param size     每页条数 (默认 10)
     * @param model    模型筛选 (可选)
     * @param taskType 步骤类型筛选 (可选)
     * @return 分页结果映射 (total, list, page, size)
     */
    @GetMapping("/ledger")
    public ApiResult<Map<String, Object>> getLedger(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "taskType", required = false) String taskType) {
        Long uid = userId != null ? userId : 1L;
        log.info("[HTTP-BILLING] 分页查询消费流水: userId={}, page={}, size={}, model={}", uid, page, size, model);
        return ApiResult.success(billingService.getLedger(uid, page, size, model, taskType));
    }

    /**
     * 获取过去指定天数 (7天 / 30天) 每日消耗走势时序数据
     *
     * @param userId 用户 ID
     * @param days   统计天数 (默认 7)
     * @return 每日时序数据点列表
     */
    @GetMapping("/stats/trend")
    public ApiResult<List<UsageTrendPointDTO>> getUsageTrend(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "days", defaultValue = "7") int days) {
        Long uid = userId != null ? userId : 1L;
        log.info("[HTTP-BILLING] 查询消费趋势走势: userId={}, days={}", uid, days);
        return ApiResult.success(billingService.getUsageTrend(uid, days));
    }

    /**
     * 查询平台多厂商大模型公开计费单价矩阵
     *
     * @return 定价规则列表
     */
    @GetMapping("/pricing")
    public ApiResult<List<ModelPricing>> getPricing() {
        log.info("[HTTP-BILLING] 查询大模型公开计价矩阵");
        return ApiResult.success(billingService.listPricing());
    }
}
