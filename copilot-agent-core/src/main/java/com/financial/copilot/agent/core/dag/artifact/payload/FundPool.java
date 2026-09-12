package com.financial.copilot.agent.core.dag.artifact.payload;

import com.financial.copilot.domain.fund.entity.FundInfo;

import java.util.List;

/**
 * <h1>基金初筛标的池业务载荷</h1>
 */
public record FundPool(
    List<String> fundCodes,
    List<FundInfo> funds,
    String screeningReason,
    int totalCount
) {
    public FundPool {
        fundCodes = fundCodes != null ? List.copyOf(fundCodes) : List.of();
        funds = funds != null ? List.copyOf(funds) : List.of();
    }

    public FundPool(List<String> fundCodes, String screeningReason, int totalCount) {
        this(fundCodes, List.of(), screeningReason, totalCount);
    }

    public static FundPool of(List<FundInfo> funds, String screeningReason) {
        List<String> codes = funds != null ? funds.stream().map(FundInfo::getFundCode).toList() : List.of();
        return new FundPool(codes, funds != null ? funds : List.of(), screeningReason, codes.size());
    }

    public static FundPool ofCodes(List<String> fundCodes, String screeningReason) {
        return new FundPool(fundCodes, List.of(), screeningReason, fundCodes != null ? fundCodes.size() : 0);
    }

    public boolean isEmpty() {
        return (funds == null || funds.isEmpty()) && (fundCodes == null || fundCodes.isEmpty());
    }
}
