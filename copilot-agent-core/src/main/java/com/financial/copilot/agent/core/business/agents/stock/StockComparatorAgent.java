package com.financial.copilot.agent.core.business.agents.stock;

import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.artifact.ArtifactMetadata;
import com.financial.copilot.agent.core.infra.dag.artifact.EvidenceContract;
import com.financial.copilot.agent.core.infra.dag.model.GraphNode;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.infra.dag.runtime.NodeInput;
import com.financial.copilot.agent.tools.stock.StockQuantAnalysisTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** AgentScope ReAct role for symmetric stock comparison. */
@Component
public class StockComparatorAgent {
    private final AgentScopeAgentFactory factory;
    private final StockQuantAnalysisTool quant;

    public StockComparatorAgent(AgentScopeAgentFactory factory, StockQuantAnalysisTool quant) {
        this.factory = factory;
        this.quant = quant;
    }

    public Artifact<String> execute(GraphNode node, NodeInput input, NodeExecutionContext context) {
        List<String> codes = stockCodes(node, input);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ComparisonTools(quant));
        var run = factory.invokeWithTrace(new AgentScopeAgentFactory.AgentDefinition(
                        "StockComparatorAgent", "股票横向对标",
                        "你是股票对标 ReAct Agent。必须调用 compare_stock_metrics 对称查询两个代码，观察真实结果后生成 Markdown 结论。禁止模型心算。",
                        toolkit, 6),
                "用户目标=" + context.request().prompt() + "\n对标代码=" + codes, context);
        run.requireLastText("compare_stock_metrics");
        return new Artifact<>("art-stock-comp-" + UUID.randomUUID().toString().substring(0, 8),
                node.getOutputType(), node.getNodeId(), run.reply().getTextContent(),
                ArtifactMetadata.standard("StockComparatorAgent"), EvidenceContract.sufficient(
                "AgentScope 股票对标工具已执行", codes.stream().map(code -> "stock://" + code).toList()));
    }

    private static List<String> stockCodes(GraphNode node, NodeInput input) {
        List<String> result = new ArrayList<>();
        addCodes(result, node.getParams().get("targetCodes"));
        addCodes(result, node.getParams().get("stockCodes"));
        addCodes(result, node.getParams().get("targetCode"));
        if (result.size() < 2) {
            input.artifacts().values().stream().map(Artifact::payload)
                    .filter(String.class::isInstance).map(String.class::cast)
                    .flatMap(text -> Arrays.stream(text.split("[^A-Za-z0-9.]+")))
                    .filter(code -> code.matches("(?i)(?:[036]\\d{5}(?:\\.(?:SH|SZ))?|[A-Z]{1,5})"))
                    .forEach(result::add);
        }
        List<String> distinct = result.stream().filter(code -> code != null && !code.isBlank()).distinct().limit(2).toList();
        if (distinct.isEmpty()) throw new IllegalStateException("StockComparatorAgent requires stock codes");
        return distinct.size() == 1 ? List.of(distinct.get(0), distinct.get(0)) : distinct;
    }

    private static void addCodes(List<String> target, Object value) {
        if (value instanceof Iterable<?> values) values.forEach(item -> target.add(String.valueOf(item)));
        else if (value != null) target.addAll(Arrays.asList(String.valueOf(value).split("[,，\\s]+")));
    }

    static final class ComparisonTools {
        private final StockQuantAnalysisTool tool;

        ComparisonTools(StockQuantAnalysisTool tool) {
            this.tool = tool;
        }

        @Tool(name = "compare_stock_metrics", description = "对称查询两只股票的估值和财务量化指标", readOnly = true)
        public String compare(
                @ToolParam(name = "code_a", description = "股票A代码") String codeA,
                @ToolParam(name = "code_b", description = "股票B代码") String codeB) {
            return "A=" + tool.getStockMetrics(codeA) + "\nB=" + tool.getStockMetrics(codeB);
        }
    }
}
