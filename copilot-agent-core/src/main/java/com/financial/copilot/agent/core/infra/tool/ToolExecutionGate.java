package com.financial.copilot.agent.core.infra.tool;

import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * <h1>工具执行前置网关与参数校验器 (ToolExecutionGate)</h1>
 * <p>基于 {@link ToolConstraint} 实现工具参数刚性同源校验与类型转换，在反射执行前阻断非法输入并触发自省反馈。</p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class ToolExecutionGate {

    /**
     * 校验并转换工具调用参数
     *
     * @param method 目标工具方法
     * @param rawArguments Agent 传入的原始参数字典
     * @return 转换后的入参数组
     * @throws InvalidToolArgumentException 当任何参数违反约束或类型不匹配时抛出
     */
    public Object[] validateAndConvert(Method method, Map<String, Object> rawArguments) {
        Objects.requireNonNull(method, "method must not be null");
        Map<String, Object> safeArgs = rawArguments != null ? rawArguments : Map.of();

        Parameter[] parameters = method.getParameters();
        Object[] convertedArgs = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            String paramName = resolveParamName(param);
            Object rawValue = findRawValue(safeArgs, paramName);
            convertedArgs[i] = validateAndConvertSingle(param, paramName, rawValue);
        }

        return convertedArgs;
    }

    private String resolveParamName(Parameter param) {
        ToolConstraint constraint = param.getAnnotation(ToolConstraint.class);
        if (constraint != null && !constraint.name().isBlank()) {
            return constraint.name();
        }
        ToolParam toolParam = param.getAnnotation(ToolParam.class);
        if (toolParam != null && !toolParam.name().isBlank()) {
            return toolParam.name();
        }
        return param.getName();
    }

    private Object findRawValue(Map<String, Object> args, String name) {
        if (args.containsKey(name)) {
            return args.get(name);
        }
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Object validateAndConvertSingle(Parameter param, String paramName, Object rawValue) {
        ToolConstraint constraint = param.getAnnotation(ToolConstraint.class);
        Class<?> targetType = param.getType();

        // 1. 必填性校验
        if (rawValue == null || (rawValue instanceof String s && s.isBlank())) {
            if (constraint != null && constraint.required()) {
                throw new InvalidToolArgumentException(paramName, rawValue,
                        "参数 [" + paramName + "] 为必填项，当前缺失或为空");
            }
            return targetType.isPrimitive() ? getPrimitiveDefault(targetType) : null;
        }

        String strVal = String.valueOf(rawValue).trim();

        // 2. 约束注解校验
        if (constraint != null) {
            // 枚举范围
            if (constraint.allowedValues().length > 0) {
                boolean match = Arrays.asList(constraint.allowedValues()).contains(strVal);
                if (!match) {
                    throw new InvalidToolArgumentException(paramName, rawValue,
                            "参数 [" + paramName + "] 值 '" + rawValue + "' 不在允许的枚举范围 "
                                    + Arrays.toString(constraint.allowedValues()) + " 内");
                }
            }

            // 正则规则
            if (!constraint.regexPattern().isEmpty()) {
                if (!Pattern.matches(constraint.regexPattern(), strVal)) {
                    throw new InvalidToolArgumentException(paramName, rawValue,
                            "参数 [" + paramName + "] 值 '" + rawValue + "' 不符合正则规则 " + constraint.regexPattern());
                }
            }

            // 数值范围校验
            if (isNumeric(targetType) || isNumericValue(strVal)) {
                try {
                    double num = Double.parseDouble(strVal);
                    if (num < constraint.minValue() || num > constraint.maxValue()) {
                        throw new InvalidToolArgumentException(paramName, rawValue,
                                "参数 [" + paramName + "] 值 " + rawValue + " 超出范围 ["
                                        + constraint.minValue() + ", " + constraint.maxValue() + "]");
                    }
                } catch (NumberFormatException e) {
                    throw new InvalidToolArgumentException(paramName, rawValue,
                            "参数 [" + paramName + "] 期望数值类型，无法解析值: " + rawValue);
                }
            }
        }

        // 3. 类型安全转换
        return convertType(rawValue, targetType, paramName);
    }

    private Object convertType(Object val, Class<?> targetType, String paramName) {
        if (targetType.isInstance(val)) {
            return val;
        }
        String s = String.valueOf(val).trim();
        try {
            if (targetType == String.class) {
                return s;
            }
            if (targetType == int.class || targetType == Integer.class) {
                return (int) Double.parseDouble(s);
            }
            if (targetType == double.class || targetType == Double.class) {
                return Double.parseDouble(s);
            }
            if (targetType == long.class || targetType == Long.class) {
                return (long) Double.parseDouble(s);
            }
            if (targetType == boolean.class || targetType == Boolean.class) {
                return Boolean.parseBoolean(s);
            }
        } catch (Exception e) {
            throw new InvalidToolArgumentException(paramName, val,
                    "参数 [" + paramName + "] 转换至目标类型 " + targetType.getSimpleName() + " 失败: " + e.getMessage());
        }
        return val;
    }

    private boolean isNumeric(Class<?> type) {
        return Number.class.isAssignableFrom(type)
                || type == int.class || type == double.class || type == long.class || type == float.class;
    }

    private boolean isNumericValue(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Object getPrimitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == double.class) return 0.0;
        if (type == long.class) return 0L;
        return null;
    }
}
