package com.sahilprojects.bettingApp.contest.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * What declaring this result would do, worked out but not saved. The organizer sees exactly who gets what before
 * committing. previewToken must be sent back on confirm; it changes if any pick or participant changes in between.
 */
public record ResultPreviewResponse(
        long contestId,
        String contestLabel,
        Long winningTeamId,
        String winningTeamShortCode,
        boolean noResult,
        BigDecimal entryAmount,
        int confirmedCount,
        BigDecimal pool,
        int winnerCount,
        BigDecimal perWinner,
        BigDecimal leftover,
        boolean everyoneRefunded,
        String refundReason,
        List<ParticipantOutcomeResponse> outcomes,
        String previewToken) {
}
