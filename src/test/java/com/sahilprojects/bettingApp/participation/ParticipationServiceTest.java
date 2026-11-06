package com.sahilprojects.bettingApp.participation;

import com.sahilprojects.bettingApp.audit.ActorType;
import com.sahilprojects.bettingApp.audit.AuditAction;
import com.sahilprojects.bettingApp.audit.AuditEntry;
import com.sahilprojects.bettingApp.audit.AuditService;
import com.sahilprojects.bettingApp.common.BadRequestException;
import com.sahilprojects.bettingApp.common.ConflictException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ParticipationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private final AuditService audit = mock(AuditService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ParticipationService service =
            new ParticipationService(audit, events, Clock.fixed(NOW, ZoneOffset.UTC));
    private final ParticipationActor organizer = ParticipationActor.user(1L);

    private AuditEntry auditedEntry() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }

    private ParticipationConfirmationChanged publishedEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        return (ParticipationConfirmationChanged) captor.getValue();
    }

    @Test
    void acceptPaidConfirmsMarksPaidAndPublishesAGainEvent() {
        FakeParticipation p = new FakeParticipation("PENDING_APPROVAL");

        service.acceptPaid(p, organizer);

        assertThat(p.status).isEqualTo("CONFIRMED");
        assertThat(p.paid).isTrue();
        assertThat(p.paidAt).isEqualTo(NOW);
        assertThat(p.confirmedAt).isEqualTo(NOW);
        AuditEntry entry = auditedEntry();
        assertThat(entry.action()).isEqualTo(AuditAction.MEMBER_ACCEPTED);
        assertThat(entry.entityType()).isEqualTo("LEAGUE_MEMBER");
        assertThat(entry.leagueId()).isEqualTo(5L);
        assertThat(entry.actorType()).isEqualTo(ActorType.USER);
        ParticipationConfirmationChanged event = publishedEvent();
        assertThat(event.confirmed()).isTrue();
        assertThat(event.userId()).isEqualTo(3L);
    }

    @Test
    void acceptUnpaidDoesNotConfirmAndPublishesNoEvent() {
        FakeParticipation p = new FakeParticipation("PENDING_APPROVAL");

        service.acceptUnpaid(p, organizer);

        assertThat(p.status).isEqualTo("ACCEPTED_UNPAID");
        assertThat(p.paid).isFalse();
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void confirmPaymentMovesAcceptedUnpaidToConfirmed() {
        FakeParticipation p = new FakeParticipation("ACCEPTED_UNPAID");

        service.confirmPayment(p, organizer);

        assertThat(p.status).isEqualTo("CONFIRMED");
        assertThat(p.paid).isTrue();
        assertThat(auditedEntry().action()).isEqualTo(AuditAction.PAYMENT_STATUS_CHANGED);
        assertThat(publishedEvent().confirmed()).isTrue();
    }

    @Test
    void confirmPaymentBeforeAcceptingIsRefused() {
        FakeParticipation p = new FakeParticipation("PENDING_APPROVAL");

        assertThatThrownBy(() -> service.confirmPayment(p, organizer)).isInstanceOf(ConflictException.class);
        assertThat(p.status).isEqualTo("PENDING_APPROVAL");
        verifyNoInteractions(audit);
    }

    @Test
    void acceptingOnlyWorksForAPendingRequest() {
        FakeParticipation accepted = new FakeParticipation("ACCEPTED_UNPAID");
        FakeParticipation confirmed = new FakeParticipation("CONFIRMED");

        assertThatThrownBy(() -> service.acceptPaid(accepted, organizer)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.acceptUnpaid(accepted, organizer)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.acceptPaid(confirmed, organizer)).isInstanceOf(ConflictException.class);
        assertThat(accepted.status).isEqualTo("ACCEPTED_UNPAID");
        verifyNoInteractions(audit);
    }

    @Test
    void reversingOnlyWorksForAConfirmedParticipation() {
        FakeParticipation unpaid = new FakeParticipation("ACCEPTED_UNPAID");
        FakeParticipation pending = new FakeParticipation("PENDING_APPROVAL");

        // the move table would allow ACCEPTED_UNPAID to REMOVED, but a reversal is only for confirmed participations
        assertThatThrownBy(() -> service.reverseConfirmed(unpaid, organizer, "reason", ReverseTarget.REMOVED, false))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.reverseConfirmed(pending, organizer, "reason", ReverseTarget.ACCEPTED_UNPAID, false))
                .isInstanceOf(ConflictException.class);
        assertThat(unpaid.status).isEqualTo("ACCEPTED_UNPAID");
        verifyNoInteractions(audit);
    }

    @Test
    void rejectKeepsTheReason() {
        FakeParticipation p = new FakeParticipation("PENDING_APPROVAL");

        service.reject(p, organizer, "  Group is full  ");

        assertThat(p.status).isEqualTo("REJECTED");
        assertThat(p.reason).isEqualTo("Group is full");
        assertThat(auditedEntry().action()).isEqualTo(AuditAction.MEMBER_REJECTED);
    }

    @Test
    void removeWorksForAcceptedUnpaidButNotForConfirmed() {
        FakeParticipation unpaid = new FakeParticipation("ACCEPTED_UNPAID");
        service.remove(unpaid, organizer, "Never paid");
        assertThat(unpaid.status).isEqualTo("REMOVED");
        assertThat(unpaid.removedAt).isEqualTo(NOW);

        FakeParticipation confirmed = new FakeParticipation("CONFIRMED");
        assertThatThrownBy(() -> service.remove(confirmed, organizer, "reason"))
                .isInstanceOf(BadRequestException.class);
        assertThat(confirmed.status).isEqualTo("CONFIRMED");
    }

    @Test
    void removeNeedsAReason() {
        FakeParticipation p = new FakeParticipation("ACCEPTED_UNPAID");
        assertThatThrownBy(() -> service.remove(p, organizer, " ")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void reverseToUnpaidResetsPaymentAndConfirmationAndPublishesALossEvent() {
        FakeParticipation p = new FakeParticipation("CONFIRMED");
        p.paid = true;
        p.paidAt = NOW.minusSeconds(3600);
        p.confirmedAt = NOW.minusSeconds(3600);

        service.reverseConfirmed(p, organizer, "Paid by mistake, money returned", ReverseTarget.ACCEPTED_UNPAID, false);

        assertThat(p.status).isEqualTo("ACCEPTED_UNPAID");
        assertThat(p.paid).isFalse();
        assertThat(p.paidAt).isNull();
        assertThat(p.confirmedAt).isNull();
        assertThat(auditedEntry().action()).isEqualTo(AuditAction.MEMBER_REVERSED);
        assertThat(publishedEvent().confirmed()).isFalse();
    }

    @Test
    void reverseToRemovedBeforeStartReturnsTheMoney() {
        FakeParticipation p = new FakeParticipation("CONFIRMED");
        p.paid = true;

        service.reverseConfirmed(p, organizer, "Left the group, money returned", ReverseTarget.REMOVED, false);

        assertThat(p.status).isEqualTo("REMOVED");
        assertThat(p.paid).isFalse();
        assertThat(p.removedAt).isEqualTo(NOW);
    }

    @Test
    void reverseToRemovedAfterStartKeepsThePaymentForSettlement() {
        FakeParticipation p = new FakeParticipation("CONFIRMED");
        p.paid = true;
        p.paidAt = NOW.minusSeconds(86400);

        service.reverseConfirmed(p, ParticipationActor.admin(9L, "Organizer asked to drop a member"),
                "Member left mid-league", ReverseTarget.REMOVED, true);

        assertThat(p.status).isEqualTo("REMOVED");
        assertThat(p.paid).isTrue();                   // the money is still with the organizer, so settlement returns it
        assertThat(auditedEntry().actorType()).isEqualTo(ActorType.ADMIN);
    }

    @Test
    void reverseToUnpaidCannotKeepThePayment() {
        FakeParticipation p = new FakeParticipation("CONFIRMED");
        assertThatThrownBy(() -> service.reverseConfirmed(p, organizer, "reason", ReverseTarget.ACCEPTED_UNPAID, true))
                .isInstanceOf(BadRequestException.class);
        assertThat(p.status).isEqualTo("CONFIRMED");
    }

    @Test
    void reverseNeedsAReason() {
        FakeParticipation p = new FakeParticipation("CONFIRMED");
        assertThatThrownBy(() -> service.reverseConfirmed(p, organizer, "", ReverseTarget.REMOVED, false))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aConfirmedUserCannotWithdraw() {
        FakeParticipation p = new FakeParticipation("CONFIRMED");
        assertThatThrownBy(() -> service.cancelRequest(p)).isInstanceOf(ConflictException.class);
        assertThat(p.status).isEqualTo("CONFIRMED");
    }

    @Test
    void aPendingOrAcceptedUserCanWithdraw() {
        FakeParticipation pending = new FakeParticipation("PENDING_APPROVAL");
        FakeParticipation accepted = new FakeParticipation("ACCEPTED_UNPAID");
        service.cancelRequest(pending);
        service.cancelRequest(accepted);
        assertThat(pending.status).isEqualTo("CANCELLED");
        assertThat(accepted.status).isEqualTo("CANCELLED");
    }

    @Test
    void requestAgainWorksAfterRejectionOrWithdrawalButNotAfterRemoval() {
        FakeParticipation rejected = new FakeParticipation("REJECTED");
        rejected.reason = "Group is full";
        service.requestAgain(rejected);
        assertThat(rejected.status).isEqualTo("PENDING_APPROVAL");
        assertThat(rejected.reason).isNull();

        FakeParticipation removed = new FakeParticipation("REMOVED");
        assertThatThrownBy(() -> service.requestAgain(removed)).isInstanceOf(ConflictException.class);
    }

    @Test
    void cutoffRemovesAsSystemWithoutAnActor() {
        FakeParticipation p = new FakeParticipation("ACCEPTED_UNPAID");

        service.cutoffUnpaid(p);

        assertThat(p.status).isEqualTo("REMOVED");
        assertThat(p.reason).isEqualTo("Payment deadline passed");
        AuditEntry entry = auditedEntry();
        assertThat(entry.actorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(entry.actorId()).isNull();
        verify(events, never()).publishEvent(any(Object.class));   // an unpaid member was never confirmed
    }

    @Test
    void cutoffRefusesAnythingButAcceptedUnpaid() {
        FakeParticipation confirmed = new FakeParticipation("CONFIRMED");
        confirmed.paid = true;

        assertThatThrownBy(() -> service.cutoffUnpaid(confirmed)).isInstanceOf(ConflictException.class);
        assertThat(confirmed.status).isEqualTo("CONFIRMED");
        verifyNoInteractions(audit);
    }

    @Test
    void adminActionPassesItsReasonToTheAuditEntry() {
        FakeParticipation p = new FakeParticipation("PENDING_APPROVAL");

        service.acceptPaid(p, ParticipationActor.admin(9L, "Organizer is unreachable"));

        AuditEntry entry = auditedEntry();
        assertThat(entry.actorType()).isEqualTo(ActorType.ADMIN);
        assertThat(entry.reason()).isEqualTo("Organizer is unreachable");
    }

    @Test
    void contestParticipantsAreAuditedWithTheContestId() {
        FakeParticipation p = new FakeParticipation("PENDING_APPROVAL");
        p.kind = ParticipationKind.CONTEST_PARTICIPANT;

        service.acceptUnpaid(p, organizer);

        AuditEntry entry = auditedEntry();
        assertThat(entry.entityType()).isEqualTo("CONTEST_PARTICIPANT");
        assertThat(entry.contestId()).isEqualTo(8L);
        assertThat(entry.leagueId()).isNull();
    }
}
