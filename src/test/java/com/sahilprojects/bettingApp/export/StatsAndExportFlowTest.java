package com.sahilprojects.bettingApp.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahilprojects.bettingApp.AbstractIntegrationTest;
import com.sahilprojects.bettingApp.TestAuth;
import com.sahilprojects.bettingApp.TestAuth.Registered;
import com.sahilprojects.bettingApp.TestContests;
import com.sahilprojects.bettingApp.TestLeagues;
import com.sahilprojects.bettingApp.TestLeagues.TestLeague;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The profile's stats by period, and the Excel copies of a season and of a player's own history. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class StatsAndExportFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void statsForAPeriodCountOnlyContestsThatStartedInIt() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        Registered player = TestAuth.register(mvc, mapper);
        TestContests.playStandalone(mvc, mapper, jdbc, organizer, player, 100, "CSK", "MI", "MI");
        long old = TestContests.playStandalone(mvc, mapper, jdbc, organizer, player, 100, "MI", "CSK", "MI");
        TestContests.setStartTime(jdbc, old, Instant.now().minus(70, ChronoUnit.DAYS));

        mvc.perform(get("/api/me/stats/period?range=ONE_MONTH").header("Authorization", player.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("ONE_MONTH"))
                .andExpect(jsonPath("$.contestsPlayed").value(1))
                .andExpect(jsonPath("$.contestsWon").value(1))
                .andExpect(jsonPath("$.amountInvested").value(100.00))
                .andExpect(jsonPath("$.netPl").value(100.00));

        mvc.perform(get("/api/me/stats/period?range=THREE_MONTHS").header("Authorization", player.bearer()))
                .andExpect(jsonPath("$.contestsPlayed").value(2))
                .andExpect(jsonPath("$.contestsLost").value(1))
                .andExpect(jsonPath("$.netPl").value(0.00))
                .andExpect(jsonPath("$.bestStreak").value(1));

        mvc.perform(get("/api/me/stats/period").header("Authorization", player.bearer()))
                .andExpect(jsonPath("$.range").value("ALL"))
                .andExpect(jsonPath("$.from").doesNotExist());
    }

    @Test
    void anUnknownRangeIsABadRequest() throws Exception {
        Registered player = TestAuth.register(mvc, mapper);
        mvc.perform(get("/api/me/stats/period?range=FOREVER").header("Authorization", player.bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void myExportHoldsMyContestsAndTheOnesIOrganise() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        Registered player = TestAuth.register(mvc, mapper);
        TestContests.playStandalone(mvc, mapper, jdbc, organizer, player, 100, "CSK", "MI", "MI");

        try (Workbook book = download("/api/me/export", player)) {
            assertThat(sheetNames(book)).containsExactly("Summary", "By month", "My contests", "My leagues",
                    "Payouts", "Contests I organise");
            Sheet contests = book.getSheet("My contests");
            assertThat(contests.getLastRowNum()).isEqualTo(1);
            assertThat(contests.getRow(1).getCell(6).getStringCellValue()).isEqualTo("MI");     // my pick
            assertThat(contests.getRow(1).getCell(8).getStringCellValue()).isEqualTo("Won");
            assertThat(contests.getRow(1).getCell(11).getNumericCellValue()).isEqualTo(100.0);   // net
            assertThat(book.getSheet("Payouts").getLastRowNum()).isEqualTo(1);
        }
        try (Workbook book = download("/api/me/export", organizer)) {
            assertThat(book.getSheet("Contests I organise").getLastRowNum()).isEqualTo(2);
        }
    }

    @Test
    void aLeagueExportIsForMembersAndHidesPicksBeforeTheStart() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 5);
        TestLeagues.open(mvc, league);
        Registered member = TestAuth.register(mvc, mapper);
        TestLeagues.joinConfirmed(mvc, mapper, league, member);
        long contest = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        TestContests.pick(mvc, member, contest, TestContests.team(jdbc, "MI"));
        Registered outsider = TestAuth.register(mvc, mapper);

        mvc.perform(get("/api/leagues/" + league.id() + "/export").header("Authorization", outsider.bearer()))
                .andExpect(status().isForbidden());

        try (Workbook book = download("/api/leagues/" + league.id() + "/export", member)) {
            assertThat(sheetNames(book)).containsExactly("League", "Table", "Matches", "Picks", "Members", "Settle up");
            assertThat(book.getSheet("Matches").getLastRowNum()).isEqualTo(1);
            assertThat(book.getSheet("Picks").getLastRowNum()).isZero();                 // not started yet
            assertThat(book.getSheet("Members").getLastRowNum()).isEqualTo(2);
        }

        TestContests.lock(jdbc, contest);
        try (Workbook book = download("/api/leagues/" + league.id() + "/export", member)) {
            assertThat(book.getSheet("Picks").getLastRowNum()).isEqualTo(2);
        }
    }

    private Workbook download(String path, Registered who) throws Exception {
        MockHttpServletResponse response = mvc.perform(get(path).header("Authorization", who.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentType()).startsWith("application/vnd.openxmlformats");
        assertThat(response.getHeader("Content-Disposition")).contains("attachment").contains(".xlsx");
        return new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()));
    }

    private static List<String> sheetNames(Workbook book) {
        List<String> names = new ArrayList<>();
        book.forEach(sheet -> names.add(sheet.getSheetName()));
        return names;
    }
}
