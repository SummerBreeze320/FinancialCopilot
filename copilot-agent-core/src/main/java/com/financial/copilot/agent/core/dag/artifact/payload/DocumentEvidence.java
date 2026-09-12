package com.financial.copilot.agent.core.dag.artifact.payload;

import java.time.Instant;

/**
 * <h1>研报与公告证据碎片载荷</h1>
 */
public record DocumentEvidence(
    String documentTitle,
    String docType,
    String excerpt,
    String sourceUri,
    Instant publishTime
) {}
