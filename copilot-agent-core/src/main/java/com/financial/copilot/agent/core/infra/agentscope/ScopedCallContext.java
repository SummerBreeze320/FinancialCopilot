package com.financial.copilot.agent.core.infra.agentscope;

/**
 * <h1>单例 AgentScope 运行时上下文调用持有器 (ScopedCallContext)</h1>
 * <p>
 * 通过安全的作用域绑定（基于 ThreadLocal），确保单例 {@link io.agentscope.core.ReActAgent}
 * 以及单例模型代理在并发调用时，能准确隔离并获取当前线程/请求的作用域数据。
 * 支持 try-with-resources 自动释放，严防内存泄露。
 * </p>
 *
 * @author FinancialCopilot
 */
public final class ScopedCallContext {

    private static final ThreadLocal<CallContext> HOLDER = new ThreadLocal<>();

    private ScopedCallContext() {}

    /**
     * 打开当前调用的作用域上下文，返回可自动关闭的句柄
     *
     * @param context 当前调用的瞬态上下文
     * @return 自动清理资源句柄
     */
    public static AutoCloseable open(CallContext context) {
        CallContext previous = HOLDER.get();
        HOLDER.set(context);
        return () -> {
            if (previous != null) {
                HOLDER.set(previous);
            } else {
                HOLDER.remove();
            }
        };
    }

    /**
     * 获取当前调用绑定的作用域上下文
     *
     * @return 当前上下文，若脱离作用域则返回 null
     */
    public static CallContext current() {
        return HOLDER.get();
    }
}
