package com.financial.copilot.agent.tools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tool 参数元数据定义。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolParameterDefinition {

    /** 参数名称，如 windCodes, reportDate, startDate, endDate */
    private String name;

    /** 参数类型，如 string, array[string], number, integer */
    private String type;

    /** 是否必填 */
    private boolean required;

    /** 默认值 */
    private Object defaultValue;

    /** 参数描述说明 */
    private String description;
}
