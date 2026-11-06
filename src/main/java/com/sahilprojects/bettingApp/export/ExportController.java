package com.sahilprojects.bettingApp.export;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.sahilprojects.bettingApp.auth.AuthenticatedUser;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** Excel downloads. See ExportService for what each workbook holds. */
@Tag(name = "Exports")
@RestController
@RequestMapping("/api")
public class ExportController {

    static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ExportService exports;

    public ExportController(ExportService exports) {
        this.exports = exports;
    }

    @Operation(summary = "Download a league season (Excel)",
            description = "Members only. Sheets: League, Table, Matches, Picks (started matches only), Members, Settle up.")
    @GetMapping("/leagues/{leagueId}/export")
    public ResponseEntity<byte[]> league(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable Long leagueId) {
        return download(exports.league(actor, leagueId));
    }

    @Operation(summary = "Download your own history (Excel)",
            description = "Summary, by month, every contest with your pick, your leagues, payouts, and the one-off contests you organise.")
    @GetMapping("/me/export")
    public ResponseEntity<byte[]> mine(@AuthenticationPrincipal AuthenticatedUser actor) {
        return download(exports.mine(actor));
    }

    private static ResponseEntity<byte[]> download(ExportService.Export export) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.fileName(), StandardCharsets.UTF_8).build().toString())
                // Lets the browser read the file name on a cross-origin request.
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
                .body(export.content());
    }
}
