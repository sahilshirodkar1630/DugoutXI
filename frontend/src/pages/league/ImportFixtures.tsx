import { useState } from 'react';
import { leagues } from '../../api/endpoints';
import type { FixtureImportResult, FixtureRow, League } from '../../api/types';
import { Card, ErrorNote, InfoNote } from '../../components/ui';
import { useAction } from '../../hooks/useLoad';
import { FIXTURE_TEMPLATE, readFixtures, type ParsedFixtures } from '../../lib/fixtures';
import { when } from '../../lib/format';

/**
 * Add a season's matches from a spreadsheet instead of one at a time.
 *
 * Three steps, so nothing surprising happens: read the file here (problems are shown by line), check it with the
 * backend without creating anything, then import. The import is all or nothing: one bad row and nothing is added.
 */
export function ImportFixtures({ league, onImported }: { league: League; onImported: () => void }) {
  const [parsed, setParsed] = useState<ParsedFixtures | null>(null);
  const [fileName, setFileName] = useState('');
  const [check, setCheck] = useState<FixtureImportResult | null>(null);
  const [pasted, setPasted] = useState('');
  const { run, busy, error } = useAction();
  const remaining = league.totalContests - league.contestsCreated;

  const load = (text: string, name: string) => {
    setParsed(readFixtures(text));
    setFileName(name);
    setCheck(null);
  };

  const send = async (rows: FixtureRow[], dryRun: boolean) => {
    const result = await run(() => leagues.importFixtures(league.id, rows, dryRun));
    if (!result) return;
    if (!dryRun && result.ok) {
      onImported();
    } else {
      setCheck(result);
    }
  };

  return (
    <Card
      title="Import matches from a spreadsheet"
      subtitle={`Save the fixture list as CSV from Excel or Google Sheets. ${remaining} more match${
        remaining === 1 ? '' : 'es'
      } fit in this league.`}
    >
      <p className="muted small" style={{ margin: 0 }}>
        Headings on the first line: <strong>date, time, team a, team b</strong>, and optionally <strong>name</strong>{' '}
        and <strong>match</strong> (its number). Teams can be short codes like MI or full names. Times are in your
        device&apos;s time zone.{' '}
        <button type="button" className="btn btn-quiet btn-small" onClick={downloadTemplate}>
          Download a template
        </button>
      </p>

      <div className="row gap">
        <label className="btn">
          Choose CSV file
          <input
            type="file"
            accept=".csv,text/csv,text/plain"
            style={{ display: 'none' }}
            onChange={async (event) => {
              const file = event.target.files?.[0];
              if (file) load(await file.text(), file.name);
              event.target.value = '';
            }}
          />
        </label>
        {fileName && <span className="muted small">{fileName}</span>}
      </div>

      <details>
        <summary className="muted small">Or paste the rows</summary>
        <textarea
          rows={5}
          value={pasted}
          onChange={(event) => setPasted(event.target.value)}
          placeholder={FIXTURE_TEMPLATE}
        />
        <button
          type="button"
          className="btn btn-small"
          disabled={!pasted.trim()}
          onClick={() => load(pasted, 'Pasted rows')}
        >
          Read these rows
        </button>
      </details>

      {parsed && parsed.problems.length > 0 && (
        <ErrorNote>
          {parsed.problems.map((p) => (
            <span key={p.line} style={{ display: 'block' }}>
              Line {p.line}: {p.message}
            </span>
          ))}
        </ErrorNote>
      )}

      {parsed && parsed.rows.length > 0 && !check && (
        <div className="row gap">
          <span className="small">{parsed.rows.length} matches read.</span>
          <button type="button" className="btn btn-primary" disabled={busy} onClick={() => send(parsed.rows, true)}>
            {busy ? 'Checking…' : 'Check them'}
          </button>
        </div>
      )}

      <ErrorNote>{error}</ErrorNote>

      {check && (
        <>
          {check.ok ? (
            <InfoNote>Every match is fine. Nothing has been added yet.</InfoNote>
          ) : (
            <ErrorNote>
              {check.rows.filter((r) => r.error).length} of {check.rows.length} rows need fixing. Nothing has been
              added — correct the file and choose it again.
            </ErrorNote>
          )}
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Row</th>
                  <th>Match</th>
                  <th>Starts</th>
                  <th>No.</th>
                  <th>Check</th>
                </tr>
              </thead>
              <tbody>
                {check.rows.map((r) => (
                  <tr key={r.row}>
                    <td>{r.row}</td>
                    <td>
                      {r.teamA?.shortCode ?? '?'} v {r.teamB?.shortCode ?? '?'}
                      {r.name && <div className="muted small">{r.name}</div>}
                    </td>
                    <td>{when(r.startTime)}</td>
                    <td>{r.contestNumber ?? '—'}</td>
                    <td className={r.error ? 'tone-down small' : 'tone-up small'}>{r.error ?? 'OK'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {check.ok && parsed && (
            <div className="row gap">
              <button type="button" className="btn btn-primary" disabled={busy} onClick={() => send(parsed.rows, false)}>
                {busy ? 'Importing…' : `Import ${parsed.rows.length} matches`}
              </button>
              <button type="button" className="btn btn-quiet" onClick={() => setCheck(null)}>
                Back
              </button>
            </div>
          )}
        </>
      )}
    </Card>
  );
}

function downloadTemplate() {
  const url = URL.createObjectURL(new Blob([FIXTURE_TEMPLATE], { type: 'text/csv' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = 'fixtures-template.csv';
  link.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
