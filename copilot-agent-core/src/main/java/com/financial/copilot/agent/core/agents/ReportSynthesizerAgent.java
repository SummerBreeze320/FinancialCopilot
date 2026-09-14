package com.financial.copilot.agent.core.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.dag.artifact.*;
import com.financial.copilot.agent.core.dag.artifact.payload.FinalSynthesisReport;
import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.runtime.NodeExecutionContext;
import com.financial.copilot.agent.core.dag.runtime.NodeInput;
import io.agentscope.core.tool.*;
import org.springframework.stereotype.Component;

import java.util.*;

/** AgentScope ReAct report editor that must inspect bound artifacts before answering. */
@Component
public class ReportSynthesizerAgent {
    private final AgentScopeAgentFactory factory; private final ObjectMapper mapper;
    public ReportSynthesizerAgent(AgentScopeAgentFactory factory,ObjectMapper mapper){this.factory=factory;this.mapper=mapper;}
    public Artifact<FinalSynthesisReport> execute(GraphNode node, NodeInput input, NodeExecutionContext context){
        Toolkit toolkit=new Toolkit(); toolkit.registerTool(new Tools(input,mapper));
        String userPrompt = com.financial.copilot.agent.core.prompt.ReportSynthesizerPrompt.buildSpec(
                context.request().prompt(), context.request().profile()).renderUserPrompt();
        var run=factory.invokeWithTrace(new AgentScopeAgentFactory.AgentDefinition("ReportSynthesizerAgent","投研报告终审",
                com.financial.copilot.agent.core.prompt.ReportSynthesizerPrompt.SYSTEM_PROMPT,toolkit,5),
                userPrompt,context);
        run.requireLastText("read_research_artifacts");
        List<String> evidence=input.artifacts().values().stream().map(Artifact::evidenceContract)
                .filter(Objects::nonNull).flatMap(contract->contract.evidenceUris().stream()).distinct().toList();
        List<String> funds=evidence.stream().filter(uri->uri.startsWith("fund://")).map(uri->uri.substring(7)).toList();
        FinalSynthesisReport payload=FinalSynthesisReport.of("专业投资研究报告","AgentScope ReAct 基于全链路工具证据合成",
                run.reply().getTextContent(),Map.of(),funds);
        return new Artifact<>("art-report-"+UUID.randomUUID().toString().substring(0,8),ArtifactType.FINAL_REPORT,
                node.getNodeId(),payload,ArtifactMetadata.standard("ReportSynthesizerAgent"),
                EvidenceContract.sufficient("报告已引用绑定产物",evidence));
    }
    static final class Tools{private final NodeInput input;private final ObjectMapper mapper;Tools(NodeInput i,ObjectMapper m){input=i;mapper=m;}
        @Tool(name="read_research_artifacts",description="读取当前节点绑定的全部强类型研究产物",readOnly=true)
        public String read(){try{return mapper.writeValueAsString(input.artifacts());}catch(Exception e){throw new IllegalStateException(e);}}}
}
