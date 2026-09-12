package com.financial.copilot.agent.tools.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;

/**
 * 基金信息数据传输对象。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FundInfoDTO {
    private String code;
    private String name;
    private BigDecimal nav;
}
