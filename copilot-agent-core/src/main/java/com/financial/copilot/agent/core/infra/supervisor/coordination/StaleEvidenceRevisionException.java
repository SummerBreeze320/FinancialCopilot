package com.financial.copilot.agent.core.infra.supervisor.coordination;

/**
 * <h1>证据版本过期异常 (StaleEvidenceRevisionException)</h1>
 * <p>
 * 当 Supervisor 或协调器尝试使用过期的 Evidence 快照版本进行归并与推导时抛出，
 * 强制重新读取最新全量证据后再行决策，禁止脏写与陈旧证据覆盖。
 * </p>
 */
public class StaleEvidenceRevisionException extends RuntimeException {

    public StaleEvidenceRevisionException(String message) {
        super(message);
    }
}
