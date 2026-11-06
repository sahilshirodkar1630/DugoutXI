import { useState } from 'react';
import { admin } from '../api/endpoints';
import type { TemporaryPassword } from '../api/types';
import { useAuth } from '../auth/useAuth';
import { Card, Empty, ErrorNote, Field, InfoNote, Loader, Status, Tabs } from '../components/ui';
import { useAction, useLoad } from '../hooks/useLoad';
import { usePrompt } from '../hooks/usePrompt';
import { shareOnWhatsApp } from '../lib/share';

type TabId = 'tournaments' | 'users' | 'jobs';

/**
 * The admin screens.
 *
 * Tournaments and teams are reference data that leagues and contests are built on, so they live here rather than
 * with the leagues. Every change an admin makes on behalf of somebody else needs a written reason, which the
 * backend enforces and the audit log keeps for good.
 */
export function AdminPage() {
  const [tab, setTab] = useState<TabId>('tournaments');

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Admin</h1>
          <p className="muted small">
            Reference data and accounts. Everything you do here is recorded with the reason you give.
          </p>
        </div>
      </div>

      <Tabs<TabId>
        active={tab}
        onChange={setTab}
        tabs={[
          { id: 'tournaments', label: 'Tournaments & teams' },
          { id: 'users', label: 'Accounts' },
          { id: 'jobs', label: 'Scheduled jobs' },
        ]}
      />

      {tab === 'tournaments' && <TournamentsTab />}
      {tab === 'users' && <UsersTab />}
      {tab === 'jobs' && <JobsTab />}
    </>
  );
}

function TournamentsTab() {
  const list = useLoad(() => admin.allTournaments(), []);
  const allTeams = useLoad(() => admin.allTeams(), []);
  const [selected, setSelected] = useState<number | null>(null);
  const linked = useLoad(() => admin.tournamentTeams(Number(selected)), [selected], { skip: selected === null });
  const reloadAll = () => {
    list.reload();
    allTeams.reload();
    if (selected !== null) linked.reload();
  };
  const prompt = usePrompt(reloadAll);
  const { run, busy, error } = useAction();
  const [tournamentName, setTournamentName] = useState('');
  const [team, setTeam] = useState({ name: '', shortCode: '' });
  const [toLink, setToLink] = useState('');

  const selectedName = (list.data ?? []).find((t) => t.id === selected)?.name;
  const linkedIds = new Set((linked.data ?? []).map((t) => t.id));
  const linkable = (allTeams.data ?? []).filter((t) => t.active && !linkedIds.has(t.id));

  return (
    <>
      {prompt.element}

      <Card title="Tournaments" subtitle="A league or contest is always tied to one of these.">
        <form
          className="row gap"
          onSubmit={async (event) => {
            event.preventDefault();
            const created = await run(() => admin.createTournament({ name: tournamentName.trim() }));
            if (created) {
              setTournamentName('');
              setSelected(created.id);
              list.reload();
            }
          }}
        >
          <input
            value={tournamentName}
            onChange={(event) => setTournamentName(event.target.value)}
            placeholder="IPL 2027"
            style={{ maxWidth: 260 }}
            maxLength={100}
            required
          />
          <button type="submit" className="btn btn-primary" disabled={busy}>
            Add tournament
          </button>
        </form>
        <ErrorNote>{error}</ErrorNote>

        <Loader state={list} empty={<Empty>No tournaments yet.</Empty>}>
          {(rows) => (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Tournament</th>
                    <th>Status</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((tournament) => (
                    <tr key={tournament.id} className={tournament.id === selected ? 'is-me' : undefined}>
                      <td>{tournament.name}</td>
                      <td>
                        <Status value={tournament.active ? 'ACTIVE' : 'CANCELLED'} />
                      </td>
                      <td>
                        <div className="row gap">
                          <button
                            type="button"
                            className="btn btn-small"
                            onClick={() => setSelected(tournament.id)}
                          >
                            Teams
                          </button>
                          <button
                            type="button"
                            className="btn btn-small"
                            onClick={() =>
                              prompt.ask(
                                {
                                  title: `${tournament.active ? 'Retire' : 'Bring back'} ${tournament.name}?`,
                                  message: tournament.active
                                    ? 'No new league or contest can be created on a retired tournament. Existing ones carry on.'
                                    : 'It becomes available again for new leagues.',
                                  confirmLabel: tournament.active ? 'Retire it' : 'Bring it back',
                                  reason: 'required',
                                },
                                (reason) =>
                                  admin.setTournamentActive(tournament.id, !tournament.active, reason ?? ''),
                              )
                            }
                          >
                            {tournament.active ? 'Retire' : 'Bring back'}
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Loader>
      </Card>

      <Card
        title={selectedName ? `Teams in ${selectedName}` : 'Teams in a tournament'}
        subtitle="Only teams linked here can be picked for its leagues and contests."
      >
        {selected === null ? (
          <Empty>Pick a tournament above to see and change the teams playing in it.</Empty>
        ) : (
          <>
            <form
              className="row gap"
              onSubmit={async (event) => {
                event.preventDefault();
                const done = await run(() => admin.addTeamToTournament(selected, Number(toLink)));
                if (done) {
                  setToLink('');
                  linked.reload();
                }
              }}
            >
              <select value={toLink} onChange={(event) => setToLink(event.target.value)} required>
                <option value="">Link a team…</option>
                {linkable.map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.shortCode} — {t.name}
                  </option>
                ))}
              </select>
              <button type="submit" className="btn" disabled={busy || toLink === ''}>
                Add to tournament
              </button>
            </form>

            <Loader state={linked} empty={<Empty>No teams linked to this tournament yet.</Empty>}>
              {(rows) => (
                <div className="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>Code</th>
                        <th>Team</th>
                        <th>Status</th>
                        <th />
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map((row) => (
                        <tr key={row.id}>
                          <td className="mono">{row.shortCode}</td>
                          <td>{row.name}</td>
                          <td>
                            <Status value={row.active ? 'ACTIVE' : 'CANCELLED'} />
                          </td>
                          <td>
                            <button
                              type="button"
                              className="btn btn-small"
                              onClick={() =>
                                prompt.ask(
                                  {
                                    title: `Remove ${row.shortCode} from ${selectedName ?? 'this tournament'}?`,
                                    message:
                                      'Refused if any contest in this tournament already uses the team. The team itself is kept.',
                                    confirmLabel: 'Remove',
                                    reason: 'required',
                                    danger: true,
                                  },
                                  (reason) => admin.removeTeamFromTournament(selected, row.id, reason ?? ''),
                                )
                              }
                            >
                              Remove from tournament
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Loader>
          </>
        )}
      </Card>

      <Card title="All teams" subtitle="A team is stored once and linked to each tournament it plays in.">
        <form
          className="row gap"
          onSubmit={async (event) => {
            event.preventDefault();
            const created = await run(() =>
              admin.createTeam({ name: team.name.trim(), shortCode: team.shortCode.trim().toUpperCase() }),
            );
            if (created) {
              setTeam({ name: '', shortCode: '' });
              allTeams.reload();
            }
          }}
        >
          <Field label="Name">
            <input
              value={team.name}
              onChange={(event) => setTeam((current) => ({ ...current, name: event.target.value }))}
              maxLength={100}
              required
            />
          </Field>
          <Field label="Short code" hint="2 to 6 letters or digits.">
            <input
              value={team.shortCode}
              onChange={(event) => setTeam((current) => ({ ...current, shortCode: event.target.value }))}
              minLength={2}
              maxLength={6}
              pattern="[A-Za-z0-9]{2,6}"
              required
              className="mono"
            />
          </Field>
          <button type="submit" className="btn" disabled={busy}>
            Add team
          </button>
        </form>

        <Loader state={allTeams} empty={<Empty>No teams yet.</Empty>}>
          {(rows) => (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Code</th>
                    <th>Team</th>
                    <th>Status</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id}>
                      <td className="mono">{row.shortCode}</td>
                      <td>{row.name}</td>
                      <td>
                        <Status value={row.active ? 'ACTIVE' : 'CANCELLED'} />
                      </td>
                      <td>
                        <button
                          type="button"
                          className="btn btn-small"
                          onClick={() =>
                            prompt.ask(
                              {
                                title: `${row.active ? 'Deactivate' : 'Reactivate'} ${row.shortCode}?`,
                                message: row.active
                                  ? 'It disappears from the team pickers. Contests that already use it carry on.'
                                  : 'It can be picked again for new contests.',
                                confirmLabel: row.active ? 'Deactivate' : 'Reactivate',
                                reason: 'required',
                              },
                              (reason) => admin.setTeamActive(row.id, !row.active, reason ?? ''),
                            )
                          }
                        >
                          {row.active ? 'Deactivate' : 'Reactivate'}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Loader>
      </Card>
    </>
  );
}

function UsersTab() {
  const [search, setSearch] = useState('');
  const [applied, setApplied] = useState('');
  const list = useLoad(() => admin.users(applied || undefined), [applied]);
  const prompt = usePrompt(list.reload);
  const { user: me } = useAuth();
  const [issued, setIssued] = useState<TemporaryPassword | null>(null);

  return (
    <>
      {prompt.element}

      {issued && <TemporaryPasswordCard issued={issued} onClose={() => setIssued(null)} />}

      <Card
        title="Accounts"
        subtitle="Switching an account off stops it immediately, on its very next request. Make admin gives someone these screens; the first admins can also be set on the server with ADMIN_EMAILS."
      >
        <form
          className="row gap"
          onSubmit={(event) => {
            event.preventDefault();
            setApplied(search.trim());
          }}
        >
          <input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search by name or email"
            style={{ maxWidth: 280 }}
          />
          <button type="submit" className="btn">
            Search
          </button>
        </form>

        <Loader state={list} empty={<Empty>No accounts match.</Empty>}>
          {(rows) => (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Name</th>
                    <th>Email</th>
                    <th>Role</th>
                    <th>Status</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((person) => (
                    <tr key={person.id}>
                      <td>
                        {person.displayName}
                        <div className="muted small">{person.name}</div>
                      </td>
                      <td className="small">{person.email}</td>
                      <td>
                        {person.role === 'ADMIN' ? (
                          <span className="badge badge-info">Admin</span>
                        ) : (
                          <span className="muted">Player</span>
                        )}
                      </td>
                      <td>
                        <Status value={person.active ? 'ACTIVE' : 'CANCELLED'} />
                      </td>
                      <td>
                        <div className="row gap">
                          <button
                            type="button"
                            className="btn btn-small"
                            onClick={() =>
                              prompt.ask(
                                {
                                  title: `${person.active ? 'Switch off' : 'Switch on'} ${person.displayName}?`,
                                  message: person.active
                                    ? 'They are signed out at once and cannot sign in again.'
                                    : 'They can sign in again.',
                                  confirmLabel: person.active ? 'Switch off' : 'Switch on',
                                  reason: 'required',
                                  danger: person.active,
                                },
                                (reason) => admin.setUserActive(person.id, !person.active, reason ?? ''),
                              )
                            }
                          >
                            {person.active ? 'Switch off' : 'Switch on'}
                          </button>
                          <button
                            type="button"
                            className="btn btn-small"
                            onClick={() =>
                              prompt.ask(
                                {
                                  title: `Make ${person.displayName} ${
                                    person.role === 'ADMIN' ? 'a player' : 'an admin'
                                  }?`,
                                  message:
                                    person.role === 'ADMIN'
                                      ? 'They lose admin access on their next request.'
                                      : 'An admin can override any organizer, with a reason.',
                                  confirmLabel: 'Change role',
                                  reason: 'required',
                                },
                                (reason) =>
                                  admin.setUserRole(
                                    person.id,
                                    person.role === 'ADMIN' ? 'USER' : 'ADMIN',
                                    reason ?? '',
                                  ),
                              )
                            }
                          >
                            {person.role === 'ADMIN' ? 'Make player' : 'Make admin'}
                          </button>
                          {person.id !== me?.id && (
                            <button
                              type="button"
                              className="btn btn-small"
                              onClick={() =>
                                prompt.ask(
                                  {
                                    title: `Reset ${person.displayName}'s password?`,
                                    message:
                                      'They are signed out everywhere and get a temporary password, shown to you once. ' +
                                      'They must choose their own when they next sign in.',
                                    confirmLabel: 'Reset password',
                                    reason: 'required',
                                  },
                                  async (reason) => {
                                    const issued = await admin.resetPassword(person.id, reason ?? '');
                                    setIssued(issued);
                                    return issued;
                                  },
                                )
                              }
                            >
                              Reset password
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Loader>
      </Card>
    </>
  );
}

/**
 * The temporary password, shown once. The admin sends it on (WhatsApp is the usual way); it is not stored anywhere
 * readable, so closing this card loses it and a fresh reset would be needed.
 */
function TemporaryPasswordCard({ issued, onClose }: { issued: TemporaryPassword; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  const message =
    `Hi ${issued.displayName}, your Dugout XI password was reset. Sign in at ${window.location.origin} with ` +
    `${issued.email} and the temporary password ${issued.temporaryPassword} — you will be asked to choose your own.`;

  return (
    <Card title={`Temporary password for ${issued.displayName}`} subtitle="Shown once. Send it to them now.">
      <strong className="stat-value mono" style={{ letterSpacing: '0.08em' }}>
        {issued.temporaryPassword}
      </strong>
      <div className="row gap">
        <button type="button" className="btn btn-primary btn-small" onClick={() => shareOnWhatsApp(message)}>
          Send on WhatsApp
        </button>
        <button
          type="button"
          className="btn btn-small"
          onClick={async () => {
            try {
              await navigator.clipboard.writeText(issued.temporaryPassword);
              setCopied(true);
            } catch {
              // Clipboard access can be refused; the password is on screen.
            }
          }}
        >
          {copied ? 'Copied' : 'Copy password'}
        </button>
        <button type="button" className="btn btn-quiet btn-small" onClick={onClose}>
          Done
        </button>
      </div>
    </Card>
  );
}

/**
 * The developer shortcuts.
 *
 * Several things only happen when a time arrives — a contest locks at its start, a league starts with its first
 * contest — and waiting for them while trying the app out is tedious. These run the jobs on demand, and only work
 * when the backend was started with app.dev-tools.enabled=true.
 */
function JobsTab() {
  const { run, busy, error } = useAction();
  const [result, setResult] = useState<Record<string, number> | null>(null);
  const [contestId, setContestId] = useState('');

  return (
    <>
      <Card
        title="Run the scheduled jobs now"
        subtitle="Opens due leagues, cuts off unpaid places, locks contests whose time has come, and sends queued alerts."
      >
        <div className="row gap">
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy}
            onClick={async () => {
              const outcome = await run(() => admin.runJobs());
              if (outcome) setResult(outcome);
            }}
          >
            {busy ? 'Running…' : 'Run all jobs'}
          </button>
        </div>

        {result && (
          <div className="grid-stats">
            {Object.entries(result).map(([key, value]) => (
              <div key={key} className="stat">
                <span className="stat-label">{key.replace(/([A-Z])/g, ' $1').toLowerCase()}</span>
                <strong className="stat-value">{value}</strong>
              </div>
            ))}
          </div>
        )}

        <ErrorNote>{error}</ErrorNote>
        <InfoNote>
          These only work when the backend runs with <span className="mono">app.dev-tools.enabled=true</span>. On a
          real deployment they are not there at all.
        </InfoNote>
      </Card>

      <Card title="Start a contest now" subtitle="Pulls its start time back and locks it, so you can enter a result.">
        <form
          className="row gap"
          onSubmit={async (event) => {
            event.preventDefault();
            const outcome = await run(() => admin.startContestNow(Number(contestId)));
            if (outcome) setResult({ [`contest ${outcome.contestId}`]: 1 });
          }}
        >
          <input
            value={contestId}
            onChange={(event) => setContestId(event.target.value)}
            inputMode="numeric"
            placeholder="Contest number"
            style={{ maxWidth: 200 }}
            required
          />
          <button type="submit" className="btn" disabled={busy}>
            Start it now
          </button>
        </form>
      </Card>
    </>
  );
}
