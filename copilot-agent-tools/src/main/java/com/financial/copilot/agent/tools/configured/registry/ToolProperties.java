package com.financial.copilot.agent.tools.configured.registry;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tool 模块配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "financial.copilot.tools")
public class ToolProperties {

    /** 拆分后的 tool 配置文件路径模式，递归扫描 analysis 与 comparison 子目录 */
    private String configPattern = "classpath*:config/tools/**/*.json";

    /** 远程 HTTP 调用超时时间(ms)，默认 15000 */
    private int httpTimeoutMs = 15000;

    /** 是否开启内存数据蒸馏 (方案 A) */
    private boolean enableDistillation = true;

    /** Wind 组件按需调用服务基地址 (如 WIND_FUNDRESEARCH_SERVICE_URL) */
    private String componentInvokeServiceUrl;
}
