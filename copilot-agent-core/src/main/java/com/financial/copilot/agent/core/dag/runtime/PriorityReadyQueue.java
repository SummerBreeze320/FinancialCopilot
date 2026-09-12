package com.financial.copilot.agent.core.dag.runtime;

import com.financial.copilot.agent.core.dag.model.GraphNode;
import com.financial.copilot.agent.core.dag.runtime.resource.NodePriority;

import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * <h1>基于优先级的就绪节点调度队列 (PriorityReadyQueue)</h1>
 * 按 NodePriority 权重从高到低排序 (HIGH:10 > NORMAL:5 > LOW:1)，确保核心路径任务优先获取有限物理配额。
 */
public class PriorityReadyQueue {

    private final PriorityBlockingQueue<GraphNode> queue;

    public PriorityReadyQueue() {
        this.queue = new PriorityBlockingQueue<>(
                16,
                Comparator.comparingInt((GraphNode n) -> n.getPriority() != null ? n.getPriority().getWeight() : NodePriority.NORMAL.getWeight())
                        .reversed()
        );
    }

    public boolean offer(GraphNode node) {
        if (node == null) return false;
        return queue.offer(node);
    }

    public GraphNode poll() {
        return queue.poll();
    }

    public GraphNode peek() {
        return queue.peek();
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public boolean remove(GraphNode node) {
        return queue.remove(node);
    }

    public void clear() {
        queue.clear();
    }
}
