package com.sahilprojects.bettingApp.audit;

/** Actions marked (members) are visible to league members. The rest are admin only. */
public enum AuditAction {
    START_TIME_CHANGED(true), RESULT_CONFIRMED(true), RESULT_CORRECTED(true),
    CONTEST_LOCKED(true), LEAGUE_STARTED(true),
    CONTEST_CANCELLED(true), LEAGUE_CANCELLED(true), LEAGUE_CREATED(false), CONTEST_CREATED(false), LEAGUE_UPDATED(false), LEAGUE_OPENED(false), LEAGUE_ENDED(true), LEAGUE_COMPLETED(true),
    MEMBER_ACCEPTED(false), MEMBER_REJECTED(false), MEMBER_REMOVED(false), MEMBER_REVERSED(false),
    TOURNAMENT_ACTIVE_CHANGED(false), TEAM_ACTIVE_CHANGED(false), TOURNAMENT_TEAM_REMOVED(false),
    PAYMENT_STATUS_CHANGED(false), PAYOUT_STATUS_CHANGED(false),
    USER_ACTIVATED(false), USER_DEACTIVATED(false), USER_ROLE_CHANGED(false), USER_PASSWORD_RESET(false), USER_PASSWORD_CHANGED(false),
    SETTLEMENT_MARKED(false), SETTLEMENT_WRITTEN_OFF(false), FORCE_COMPLETE(false), ADMIN_OVERRIDE(false);

    private final boolean visibleToMembers;

    AuditAction(boolean visibleToMembers) {
        this.visibleToMembers = visibleToMembers;
    }

    public boolean isVisibleToMembers() {
        return visibleToMembers;
    }
}
