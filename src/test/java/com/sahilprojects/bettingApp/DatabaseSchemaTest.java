package com.sahilprojects.bettingApp;

import com.sahilprojects.bettingApp.tournament.TeamRepository;
import com.sahilprojects.bettingApp.tournament.TournamentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs every Flyway migration on a real Postgres and lets Hibernate validate all 12 entities against the schema
 * (ddl-auto=validate). If an entity and a table disagree, the context fails to start.
 */
class DatabaseSchemaTest extends AbstractIntegrationTest {

    @Autowired TournamentRepository tournaments;
    @Autowired TeamRepository teams;
    @Autowired JdbcTemplate jdbc;

    @Test
    void migrationsRunAndSeedIsLoaded() {
        var ipl = tournaments.findAll().stream().filter(t -> t.getName().equals("IPL 2026")).findFirst().orElseThrow();
        assertThat(teams.findActiveByTournament(ipl.getId())).hasSize(10);
    }

    @Test
    void contestWithTeamOutsideItsTournamentIsRefused() {
        jdbc.update("insert into users (name, display_name, email, password_hash) values ('A','A','a@x.com','h')");
        Long userId = jdbc.queryForObject("select id from users where email = 'a@x.com'", Long.class);
        jdbc.update("insert into tournaments (name) values ('Empty Cup')");
        Long emptyCup = jdbc.queryForObject("select id from tournaments where name = 'Empty Cup'", Long.class);
        Long mi = jdbc.queryForObject("select id from teams where short_code = 'MI'", Long.class);
        Long csk = jdbc.queryForObject("select id from teams where short_code = 'CSK'", Long.class);

        // MI and CSK play in IPL 2026, not in Empty Cup: the composite foreign key must reject this
        assertThatThrownBy(() -> jdbc.update("""
                insert into contests (organizer_id, tournament_id, team_a_id, team_b_id, start_time,
                                      contest_amount, registration_deadline, payment_deadline)
                values (?, ?, ?, ?, now() + interval '2 days', 100, now(), now() + interval '1 day')
                """, userId, emptyCup, mi, csk))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void auditLogIsAppendOnly() {
        jdbc.update("insert into users (name, display_name, email, password_hash) values ('B','B','b@x.com','h')");
        Long userId = jdbc.queryForObject("select id from users where email = 'b@x.com'", Long.class);
        jdbc.update("insert into audit_log (entity_type, entity_id, action, actor_id, actor_type) "
                + "values ('LEAGUE', 1, 'START_TIME_CHANGED', ?, 'USER')", userId);

        assertThatThrownBy(() -> jdbc.update("update audit_log set action = 'X'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_log"))
                .hasMessageContaining("append-only");
    }

    @Test
    void adminAuditEntryNeedsAReason() {
        jdbc.update("insert into users (name, display_name, email, password_hash, role) values ('C','C','c@x.com','h','ADMIN')");
        Long adminId = jdbc.queryForObject("select id from users where email = 'c@x.com'", Long.class);

        assertThatThrownBy(() -> jdbc.update("insert into audit_log (entity_type, entity_id, action, actor_id, actor_type, reason) "
                + "values ('LEAGUE', 1, 'ADMIN_OVERRIDE', ?, 'ADMIN', '  ')", adminId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
