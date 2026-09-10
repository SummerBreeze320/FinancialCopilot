package com.financial.copilot.controller.admin;

import com.financial.copilot.agent.core.llm.config.LlmConfigManager;
import com.financial.copilot.agent.core.llm.dto.LlmConnectionTestRequest;
import com.financial.copilot.agent.core.llm.dto.LlmConnectionTestResult;
import com.financial.copilot.agent.core.llm.dto.LlmSettingsDTO;
import com.financial.copilot.agent.core.llm.provider.LlmProviderMetadata;
import com.financial.copilot.agent.core.llm.provider.LlmProviderRegistry;
import com.financial.copilot.agent.core.llm.service.LlmService;
import com.financial.copilot.common.result.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <h1>多厂商大模型研发测试与后台管理控制器 (LLM Admin & Dev Controller)</h1>
 * <p>
 * 职责：专供算法研发、测试评测人员与平台管理员使用（不向前端金融普通客户开放）。
 * 提供已接入厂商元数据查询、平台活动主选模型热切换、跨厂商网络握手与连通性实时探测。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/llm")
public class LlmAdminConfigController {

    private final LlmProviderRegistry providerRegistry;
    private final LlmConfigManager configManager;
    private final LlmService llmService;

    public LlmAdminConfigController(LlmProviderRegistry providerRegistry,
                                    LlmConfigManager configManager,
                                    LlmService llmService) {
        this.providerRegistry = providerRegistry;
        this.configManager = configManager;
        this.llmService = llmService;
    }

    /**
     * 查询平台已注册支持的所有厂商与可用模型规格矩阵
     *
     * @return 厂商元数据字典列表
     */
    @GetMapping("/providers")
    public ApiResult<List<LlmProviderMetadata>> listProviders() {
        log.info("[ADMIN-LLM] 研发管理端查询大模型厂商规格矩阵");
        return ApiResult.success(providerRegistry.getAllProviders());
    }

    /**
     * 获取当前系统全局活动生效的大模型配置
     *
     * @return 当前生效配置 DTO
     */
    @GetMapping("/config")
    public ApiResult<LlmSettingsDTO> getActiveConfig() {
        return ApiResult.success(configManager.getActiveSettings());
    }

    /**
     * 动态热更新系统生效的大模型厂商、模型标识与端点（研发测试/管理员专用）
     *
     * @param settings 待更新的设置载荷
     * @return 更新后的生效配置 DTO
     */
    @PostMapping("/config")
    public ApiResult<LlmSettingsDTO> updateActiveConfig(@RequestBody LlmSettingsDTO settings) {
        log.info("[ADMIN-LLM] 研发管理端发起系统主选模型动态热更新: provider={}, model={}",
                settings.getProvider(), settings.getModel());
        LlmSettingsDTO updated = configManager.updateActiveSettings(settings);
        return ApiResult.success(updated);
    }

    /**
     * 对指定厂商与端点执行网络握手与连通性即时探测
     *
     * @param testRequest 探测参数（含厂商、模型、自定义 Base URL 与 API Key）
     * @return 探测响应结果（包含是否成功、往返延迟毫秒数与模型回复样本）
     */
    @PostMapping("/test")
    public ApiResult<LlmConnectionTestResult> testConnection(@RequestBody LlmConnectionTestRequest testRequest) {
        log.info("[ADMIN-LLM] 研发管理端发起厂商连通性测试: provider={}, model={}",
                testRequest.getProvider(), testRequest.getModel());
        LlmConnectionTestResult result = llmService.testConnection(testRequest);
        return ApiResult.success(result);
    }
}
