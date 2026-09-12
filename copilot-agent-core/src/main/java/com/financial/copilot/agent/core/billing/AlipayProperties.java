package com.financial.copilot.agent.core.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "copilot.alipay")
public class AlipayProperties {
    private boolean enabled;
    private boolean sandbox = true;
    private String appId;
    private String sellerId;
    private String privateKey;
    private String publicKey;
    private String notifyUrl;
}
