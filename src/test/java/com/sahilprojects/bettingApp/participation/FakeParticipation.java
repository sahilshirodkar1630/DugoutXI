package com.sahilprojects.bettingApp.participation;

import java.time.Instant;

/** A plain in-memory Participation for testing the workflow without a database. */
class FakeParticipation implements Participation {

    Long id = 11L;
    ParticipationKind kind = ParticipationKind.LEAGUE_MEMBER;
    String status;
    boolean paid;
    Instant confirmedAt;
    Instant removedAt;
    Instant paidAt;
    String reason;

    FakeParticipation(String status) {
        this.status = status;
    }

    @Override public Long getId() { return id; }
    @Override public ParticipationKind kind() { return kind; }
    @Override public Long leagueIdForAudit() { return kind == ParticipationKind.LEAGUE_MEMBER ? 5L : null; }
    @Override public Long contestIdForAudit() { return kind == ParticipationKind.CONTEST_PARTICIPANT ? 8L : null; }
    @Override public Long participantUserId() { return 3L; }
    @Override public String currentStatus() { return status; }
    @Override public void changeStatus(String newStatus) { this.status = newStatus; }
    @Override public boolean isPaid() { return paid; }
    @Override public void recordPaid(Instant at) { paid = true; paidAt = at; }
    @Override public void recordUnpaid() { paid = false; paidAt = null; }
    @Override public void setConfirmedAt(Instant at) { confirmedAt = at; }
    @Override public void setRemovedAt(Instant at) { removedAt = at; }
    @Override public void setStatusReason(String reason) { this.reason = reason; }
}
