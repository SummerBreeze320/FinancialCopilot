package com.financial.copilot.agent.core.business.agents.a2a;

import io.a2a.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * <h1>A2A 智能体注册中心与能力发现引擎 (A2aAgentRegistry)</h1>
 * <p>
 * 管理系统中已注册的 {@link A2aExecutableAgent} 及其标准 {@link AgentCard}，
 * 支持按 TaskType 与 AssetCategory 进行能力发现与精准路由。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class A2aAgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(A2aAgentRegistry.class);

    private final List<A2aExecutableAgent> agents = new CopyOnWriteArrayList<>();

    @Autowired
    public A2aAgentRegistry(List<A2aExecutableAgent> initialAgents) {
        if (initialAgents != null) {
            initialAgents.forEach(this::register);
        }
    }

    public A2aAgentRegistry() {
    }

    public synchronized void register(A2aExecutableAgent agent) {
        if (agent == null) {
            return;
        }
        AgentCard card = agent.agentCard();
        String name = card != null ? card.name() : agent.getClass().getSimpleName();
        agents.removeIf(a -> a.agentCard() != null && Objects.equals(a.agentCard().name(), name));
        agents.add(agent);
        log.info("Registered A2A Agent: name={}, skills={}", name,
                card != null && card.skills() != null ? card.skills().size() : 0);
    }

    public Optional<A2aExecutableAgent> findAgent(String taskType, String assetCategory) {
        if (taskType == null) {
            return Optional.empty();
        }
        return agents.stream()
                .filter(a -> a.supports(taskType, assetCategory))
                .findFirst();
    }

    public A2aExecutableAgent getRequiredAgent(String taskType, String assetCategory) {
        return findAgent(taskType, assetCategory)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No A2A Agent registered for taskType=" + taskType + ", assetCategory=" + assetCategory));
    }

    public List<AgentCard> getAllAgentCards() {
        return agents.stream()
                .map(A2aExecutableAgent::agentCard)
                .filter(Objects::nonNull)
                .toList();
    }

    public Optional<AgentCard> findCardByName(String agentName) {
        if (agentName == null) {
            return Optional.empty();
        }
        return agents.stream()
                .map(A2aExecutableAgent::agentCard)
                .filter(c -> c != null && agentName.equalsIgnoreCase(c.name()))
                .findFirst();
    }

    public List<A2aExecutableAgent> getAgents() {
        return Collections.unmodifiableList(agents);
    }
}
