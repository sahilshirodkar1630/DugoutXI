package com.sahilprojects.bettingApp.settlement.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The whole league's closing position, with the add-up check.
 *
 * balanced is the safety net: every rupee that went in has to come out, apart from the leftover paise the pool
 * could not divide. If it is ever false, something is wrong with the sums and the league must not be completed.
 */
public record SettlementSummaryResponse(
        long leagueId,
        String leagueName,
        String leagueStatus,
        int memberCount,
        BigDecimal totalPaidIn,
        BigDecimal totalFinalBalance,
        BigDecimal totalLeftover,
        boolean balanced,
        int pendingCount,
        int settledCount,
        int writtenOffCount,
        List<SettlementRowResponse> owedMoney,
        List<SettlementRowResponse> oweMoney,
        List<SettlementRowResponse> rows) {
}
