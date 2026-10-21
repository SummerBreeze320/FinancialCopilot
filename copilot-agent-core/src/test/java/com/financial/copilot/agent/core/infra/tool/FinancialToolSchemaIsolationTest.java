package com.financial.copilot.agent.core.infra.tool;

import com.financial.copilot.agent.core.infra.tool.fund.FundResearchTools;
import com.financial.copilot.agent.core.infra.tool.market.MarketResearchTools;
import com.financial.copilot.agent.core.infra.tool.stock.StockResearchTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialToolSchemaIsolationTest {

    private static final Set<String> FORBIDDEN_MODEL_INPUT_FIELDS = Set.of(
            "userid",
            "conversationid",
            "workflowrunid",
            "agentrunid",
            "sessionkey",
            "tenantid"
    );

    @Test
    void ensuresNoForbiddenIdentityFieldsInModelVisibleToolSignatures() {
        List<Class<?>> toolClasses = List.of(
                FundResearchTools.class,
                StockResearchTools.class,
                MarketResearchTools.class,
                ResearchEvidenceTools.class
        );

        for (Class<?> toolCls : toolClasses) {
            for (Method method : toolCls.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    for (Parameter param : method.getParameters()) {
                        // ToolContext is hidden and strictly managed by the framework
                        if (param.getType().equals(ToolContext.class)) {
                            continue;
                        }

                        String paramName = param.getName().toLowerCase();
                        for (String forbidden : FORBIDDEN_MODEL_INPUT_FIELDS) {
                            assertThat(paramName)
                                    .withFailMessage("Tool method %s in %s contains forbidden identity parameter %s",
                                            method.getName(), toolCls.getSimpleName(), paramName)
                                    .doesNotContain(forbidden);
                        }

                        // Inspect complex parameter types if any (e.g. DTO)
                        if (!param.getType().isPrimitive() && !param.getType().equals(String.class) && !Number.class.isAssignableFrom(param.getType())) {
                            for (var field : param.getType().getDeclaredFields()) {
                                String fieldName = field.getName().toLowerCase();
                                for (String forbidden : FORBIDDEN_MODEL_INPUT_FIELDS) {
                                    assertThat(fieldName)
                                            .withFailMessage("DTO %s for method %s contains forbidden identity field %s",
                                                    param.getType().getSimpleName(), method.getName(), fieldName)
                                            .doesNotContain(forbidden);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
