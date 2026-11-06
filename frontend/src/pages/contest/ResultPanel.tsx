import { useState } from 'react';
import { contests } from '../../api/endpoints';
import type { Contest, ResultPreview } from '../../api/types';
import { Card, Empty, ErrorNote, InfoNote, Loader, Stat, Status } from '../../components/ui';
import { useAction, useLoad } from '../../hooks/useLoad';
import { money, sign, signedMoney } from '../../lib/format';

/**
 * Declaring the result, in two deliberate steps.
 *
 * The organizer asks for a preview first and sees exactly what every player would get. Confirming sends back a
 * token from that preview, which the backend recomputes: if a pick or a player changed in between, the token no
 * longer matches and the confirmation is refused rather than settling figures nobody saw.
 *
 * Correcting a declared result works the same way but always needs a reason, and the backend refuses it once the
 * money it would change has actually moved.
 */
export function ResultPanel({ contest, onChanged }: { contest: Contest; onChanged: () => void }) {
  const hasResult = contest.resultType !== null;
  const outcomes = useLoad(() => contests.outcomes(contest.id), [contest.id], { skip: !hasResult });

  return (
    <>
      {contest.organizer && contest.status === 'LOCKED' && (
        <DeclareResult contest={contest} onChanged={onChanged} />
      )}

      {contest.organizer && hasResult && contest.status !== 'CANCELLED' && (
        <CorrectResult contest={contest} onChanged={onChanged} />
      )}

      {hasResult && (
        <Card
          title="How it finished"
          subtitle={
            contest.resultType === 'NO_RESULT'
              ? 'No result, so every entry was refunded.'
              : `${contest.winningTeam?.name} won.`
          }
        >
          <Loader state={outcomes} empty={<Empty>Nobody was in this contest.</Empty>}>
            {(rows) => (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Player</th>
                      <th>Picked</th>
                      <th>Result</th>
                      <th className="num">Entry</th>
                      <th className="num">Won</th>
                      <th className="num">Net</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.participantId}>
                        <td>{row.displayName}</td>
                        <td>{row.selectedTeamShortCode ?? <span className="muted">no pick</span>}</td>
                        <td>
                          <Status value={row.result} />
                        </td>
                        <td className="num">{money(row.entrySpent)}</td>
                        <td className="num">{money(row.winnings)}</td>
                        <td className={`num tone-${sign(row.netPl)}`}>{signedMoney(row.netPl)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Loader>

          {Number(contest.leftoverAmount) > 0 && (
            <p className="muted small" style={{ margin: 0 }}>
              {money(contest.leftoverAmount)} could not be divided evenly between the winners and stays with the
              contest.
            </p>
          )}
        </Card>
      )}
    </>
  );
}

function DeclareResult({ contest, onChanged }: { contest: Contest; onChanged: () => void }) {
  const { run, busy, error } = useAction();
  const [choice, setChoice] = useState<number | 'NO_RESULT' | ''>('');
  const [preview, setPreview] = useState<ResultPreview | null>(null);

  const body = () =>
    choice === 'NO_RESULT' ? { noResult: true } : { winningTeamId: Number(choice) };

  const doPreview = async () => {
    const result = await run(() => contests.previewResult(contest.id, body()));
    if (result) setPreview(result);
  };

  const doConfirm = async () => {
    if (!preview) return;
    const result = await run(() =>
      contests.confirmResult(contest.id, { ...body(), previewToken: preview.previewToken }),
    );
    if (result) {
      setPreview(null);
      setChoice('');
      onChanged();
    }
  };

  return (
    <Card
      title="Enter the result"
      subtitle="You will see exactly what everyone gets before anything is saved."
    >
      <div className="picks">
        {[contest.teamA, contest.teamB].map((team) => (
          <button
            key={team.id}
            type="button"
            className={choice === team.id ? 'pick pick-chosen' : 'pick'}
            aria-pressed={choice === team.id}
            onClick={() => {
              setChoice(team.id);
              setPreview(null);
            }}
          >
            <span>{team.shortCode}</span>
            <span className="pick-name">{team.name} won</span>
          </button>
        ))}
      </div>

      <button
        type="button"
        className={choice === 'NO_RESULT' ? 'btn btn-primary' : 'btn'}
        onClick={() => {
          setChoice('NO_RESULT');
          setPreview(null);
        }}
      >
        No result (abandoned or washed out)
      </button>

      <div className="row gap">
        <button type="button" className="btn" disabled={busy || choice === ''} onClick={doPreview}>
          {busy && !preview ? 'Working…' : 'Work out the payouts'}
        </button>
        {preview && (
          <button type="button" className="btn btn-primary" disabled={busy} onClick={doConfirm}>
            {busy ? 'Saving…' : 'Confirm this result'}
          </button>
        )}
      </div>

      <ErrorNote>{error}</ErrorNote>
      {preview && <PreviewTable preview={preview} />}
    </Card>
  );
}

function CorrectResult({ contest, onChanged }: { contest: Contest; onChanged: () => void }) {
  const { run, busy, error } = useAction();
  const [open, setOpen] = useState(false);
  const [choice, setChoice] = useState<number | 'NO_RESULT' | ''>('');
  const [reason, setReason] = useState('');

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    const payload =
      choice === 'NO_RESULT'
        ? { noResult: true, reason: reason.trim() }
        : { winningTeamId: Number(choice), reason: reason.trim() };
    const result = await run(() => contests.correctResult(contest.id, payload));
    if (result) {
      setOpen(false);
      setChoice('');
      setReason('');
      onChanged();
    }
  };

  if (!open) {
    return (
      <Card title="Entered the wrong result?">
        <div className="row gap">
          <button type="button" className="btn" onClick={() => setOpen(true)}>
            Correct the result
          </button>
          <span className="muted small">
            Needs a reason, and is refused once money has actually changed hands.
          </span>
        </div>
      </Card>
    );
  }

  return (
    <Card title="Correct the result" subtitle="Everyone affected is told, and the reason is recorded.">
      <form className="col" onSubmit={submit}>
        <div className="picks">
          {[contest.teamA, contest.teamB].map((team) => (
            <button
              key={team.id}
              type="button"
              className={choice === team.id ? 'pick pick-chosen' : 'pick'}
              aria-pressed={choice === team.id}
              onClick={() => setChoice(team.id)}
            >
              <span>{team.shortCode}</span>
              <span className="pick-name">{team.name} won</span>
            </button>
          ))}
        </div>

        <button
          type="button"
          className={choice === 'NO_RESULT' ? 'btn btn-primary' : 'btn'}
          onClick={() => setChoice('NO_RESULT')}
        >
          No result
        </button>

        <label className="field">
          <span className="field-label">What went wrong?</span>
          <textarea
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            rows={3}
            maxLength={500}
            required
            placeholder="I read the scorecard wrong"
          />
        </label>

        <ErrorNote>{error}</ErrorNote>

        <div className="row gap end">
          <button type="button" className="btn btn-quiet" onClick={() => setOpen(false)}>
            Cancel
          </button>
          <button
            type="submit"
            className="btn btn-danger"
            disabled={busy || choice === '' || reason.trim() === ''}
          >
            {busy ? 'Saving…' : 'Correct it'}
          </button>
        </div>
      </form>
    </Card>
  );
}

/** The preview: the pool, who shares it, and what each player ends up with. Nothing has been saved at this point. */
function PreviewTable({ preview }: { preview: ResultPreview }) {
  return (
    <>
      <div className="grid-stats">
        <Stat label="Players in" value={preview.confirmedCount} hint={`${money(preview.entryAmount)} each`} />
        <Stat label="Pool" value={money(preview.pool)} />
        <Stat label="Winners" value={preview.winnerCount} />
        <Stat label="Each winner gets" value={money(preview.perWinner)} />
        {Number(preview.leftover) > 0 && (
          <Stat label="Leftover" value={money(preview.leftover)} hint="Cannot be divided evenly" />
        )}
      </div>

      {preview.everyoneRefunded && <InfoNote>{preview.refundReason}</InfoNote>}

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Player</th>
              <th>Picked</th>
              <th>Would be</th>
              <th className="num">Entry</th>
              <th className="num">Wins</th>
              <th className="num">Net</th>
            </tr>
          </thead>
          <tbody>
            {preview.outcomes.map((row) => (
              <tr key={row.participantId}>
                <td>{row.displayName}</td>
                <td>{row.selectedTeamShortCode ?? <span className="muted">no pick</span>}</td>
                <td>
                  <Status value={row.result} />
                </td>
                <td className="num">{money(row.entrySpent)}</td>
                <td className="num">{money(row.winnings)}</td>
                <td className={`num tone-${sign(row.netPl)}`}>{signedMoney(row.netPl)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <p className="muted small" style={{ margin: 0 }}>
        Nothing has been saved yet. Check the figures, then confirm.
      </p>
    </>
  );
}
