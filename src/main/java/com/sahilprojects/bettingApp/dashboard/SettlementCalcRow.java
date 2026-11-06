package com.sahilprojects.bettingApp.dashboard;

import java.math.BigDecimal;

/** Input for one settlement row, calculated when a league ends. */
public record SettlementCalcRow(long leagueMemberId, long userId, BigDecimal joiningAmountPaid, BigDecimal netPl) {

    public BigDecimal finalBalance() {
        return joiningAmountPaid.add(netPl);
    }
}
