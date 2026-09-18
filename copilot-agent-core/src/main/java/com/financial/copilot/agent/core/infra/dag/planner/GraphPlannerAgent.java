package com.financial.copilot.agent.core.infra.dag.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.infra.agentscope.AgentScopeAgentFactory;
import com.financial.copilot.agent.core.business.agents.AgentRoleCatalog;
import com.financial.copilot.agent.core.infra.dag.artifact.Artifact;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraph;
import com.financial.copilot.agent.core.infra.dag.model.ExecutionGraphSnapshot;
import com.financial.copilot.agent.core.infra.dag.model.patch.GraphPatch;
import com.financial.copilot.agent.core.infra.dag.planner.tool.*;
import com.financial.copilot.agent.core.infra.dag.runtime.*;
import com.financial.copilot.agent.core.infra.dag.runtime.context.CancellationToken;
import com.financial.copilot.common.enums.AssetCategory;
import com.financial.copilot.agent.core.infra.prompt.GraphPlannerPrompt;
import io.agentscope.core.tool.*;
import org.springframework.stereotype.Component;

import java.util.Map;

/** AgentScope-native ReAct planner for initial graphs and runtime graph patches. */
@Component
public class GraphPlannerAgent implements RePlanAdvisor {
    private final AgentScopeAgentFactory factory; private final MetricRAGTool metrics;
    private final SkillRegistryTool skills; private final CapabilityRegistryTool capabilities;
    private final MarketMemoryTool memory; private final FinancialDocumentSearchTool documents;
    private final ObjectMapper mapper;

    public GraphPlannerAgent(AgentScopeAgentFactory factory, MetricRAGTool metrics, SkillRegistryTool skills,
                             CapabilityRegistryTool capabilities, MarketMemoryTool memory,
                             FinancialDocumentSearchTool documents, ObjectMapper mapper) {
        this.factory=factory;this.metrics=metrics;this.skills=skills;this.capabilities=capabilities;
        this.memory=memory;this.documents=documents;this.mapper=mapper;
    }

    public ExecutionGraph plan(GraphPlanningRequest request) {
        Toolkit toolkit=new Toolkit();toolkit.registerTool(new PlanningTools(metrics,skills,capabilities,memory,documents));
        NodeExecutionContext context=context(request,"__planner__");
        String userPrompt = GraphPlannerPrompt.buildPlanSpec(
                request.prompt(), request.sessionKey(), request.profile()).renderUserPrompt();
        var run=factory.invokeWithTrace(new AgentScopeAgentFactory.AgentDefinition(
                "GraphPlannerAgent","动态图规划", GraphPlannerPrompt.PLAN_SYSTEM_PROMPT, toolkit,8),
                userPrompt, context);
        try {
            if(run.observations().isEmpty())throw new IllegalStateException("Planner finished without capability discovery");
            GraphPlan plan=mapper.readValue(stripFence(run.reply().getTextContent()),GraphPlan.class);
            ExecutionGraph graph=plan.restore();
            if(graph.getNodes().isEmpty()||graph.hasCycle())throw new IllegalStateException("Planner produced an invalid graph");
            graph.getNodes().values().forEach(node -> {
                if (!AgentRoleCatalog.supports(node.getTaskType())) {
                    throw new IllegalArgumentException("No AgentScope role for task type: " + node.getTaskType());
                }
                if (!graph.getUpstream(node.getNodeId()).isEmpty() && node.getInputBindings().isEmpty()) {
                    throw new IllegalArgumentException("Non-root node requires explicit inputBindings: " + node.getNodeId());
                }
            });
            return graph;
        } catch(Exception e){throw new IllegalStateException("GraphPlannerAgent returned invalid GraphPlan",e);}
    }

    @Override public GraphPatch planPatch(ExecutionGraph graph,String completedNodeId,Artifact<?> result){
        return planPatch(graph, completedNodeId, result, null);
    }

    @Override public GraphPatch planPatch(ExecutionGraph graph,String completedNodeId,Artifact<?> result, GraphRunRequest owner){
        Toolkit toolkit=new Toolkit();toolkit.registerTool(new PlanningTools(metrics,skills,capabilities,memory,documents));
        GraphPlanningRequest request=new GraphPlanningRequest("runtime replan","replan-"+graph.getGraphId(),null,ignored->{},false,owner);
        try {
            String prompt="graph="+mapper.writeValueAsString(ExecutionGraphSnapshot.from(graph))+"\ncompletedNode="+completedNodeId
                    +"\nartifact="+mapper.writeValueAsString(result);
            var run=factory.invokeWithTrace(new AgentScopeAgentFactory.AgentDefinition("GraphPlannerAgent","动态图重规划",GraphPlannerPrompt.PATCH_SYSTEM_PROMPT,toolkit,5),prompt,context(request,"__replanner__"));
            String reply=stripFence(run.reply().getTextContent());
            if("NO_PATCH".equalsIgnoreCase(reply))return null;
            GraphPatch patch = mapper.readValue(reply,GraphPatch.class);
            patch.operations().forEach(operation -> {
                if (operation.op() == com.financial.copilot.agent.core.infra.dag.model.patch.PatchOp.ADD_NODE
                        && (operation.node() == null || !AgentRoleCatalog.supports(operation.node().getTaskType()))) {
                    String taskType = operation.node() == null ? null : operation.node().getTaskType();
                    throw new IllegalArgumentException("No AgentScope role for task type: " + taskType);
                }
                if (operation.op() == com.financial.copilot.agent.core.infra.dag.model.patch.PatchOp.UPDATE_NODE
                        && operation.params() != null && operation.params().containsKey("taskType")
                        && !AgentRoleCatalog.supports(String.valueOf(operation.params().get("taskType")))) {
                    throw new IllegalArgumentException("No AgentScope role for task type: " + operation.params().get("taskType"));
                }
            });
            return patch;
        }catch(Exception e){throw new IllegalStateException("GraphPlannerAgent returned invalid GraphPatch",e);}
    }

    private NodeExecutionContext context(GraphPlanningRequest request, String nodeId){
        GraphRunRequest run=request.runRequest()!=null?request.runRequest():new GraphRunRequest("plan-"+java.util.UUID.randomUUID(),0L,java.util.UUID.randomUUID(),null,
                request.sessionKey()==null?"planning":request.sessionKey(),request.prompt()==null?"":request.prompt(),
                request.enableThinking(),request.profile(),request.usageConsumer(),RunMode.SYNC);
        return new NodeExecutionContext(run,nodeId,new com.financial.copilot.agent.core.infra.dag.artifact.ArtifactStore(),new CancellationToken(run.runId()));
    }

    private static String stripFence(String text){return text==null?"":text.trim().replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$","");}

    static final class PlanningTools{
        private final MetricRAGTool m;private final SkillRegistryTool s;private final CapabilityRegistryTool c;
        private final MarketMemoryTool memory;private final FinancialDocumentSearchTool docs;
        PlanningTools(MetricRAGTool m,SkillRegistryTool s,CapabilityRegistryTool c,MarketMemoryTool memory,FinancialDocumentSearchTool docs){this.m=m;this.s=s;this.c=c;this.memory=memory;this.docs=docs;}
        @Tool(name="search_metrics",description="检索适用金融指标和阈值",readOnly=true) public Object metrics(@ToolParam(name="query",description="研究目标")String q){return m.searchMetrics(q);}
        @Tool(name="list_skills",description="列出可用投研技能",readOnly=true) public Object skills(){return s.listSkills();}
        @Tool(name="check_capability",description="检查资产类别的数据能力",readOnly=true) public boolean capability(@ToolParam(name="assetCategory",description="FUND 或 STOCK")String category){return c.isAssetCategorySupported(AssetCategory.valueOf(category));}
        @Tool(name="search_documents",description="检索金融文档",readOnly=true) public Object documents(@ToolParam(name="query",description="检索词")String q){return docs.search(q);}
        @Tool(name="read_memory",description="读取会话市场记忆",readOnly=true) public Object memory(@ToolParam(name="sessionKey",description="会话ID")String id,@ToolParam(name="query",description="检索词")String q){return memory.retrieveMemory(id,q,5);}
    }
}
