# Dugout XI

A prediction league for friends. You and your mates pick who will win each IPL match, and the app keeps the score
and the money straight. **No money ever moves through the app** — people pay each other directly, and the app
records who has paid, works out who is owed what, and lets the organizer tick people off as they settle.

Two kinds of game:

- **A league.** Everyone pays the whole season up front (amount per contest × number of contests). Each match is a
  contest, every confirmed member is in every contest automatically, and nothing is paid out until the league ends,
  when the whole thing is netted off into one figure per person.
- **A one-off contest.** A single match with its own entry amount. Anyone can be let in by the organizer, and it
  settles on its own as soon as the result is in.

The money rule is the same in both: the pool is the entry amount times the number of players, and whoever picked
the winning team shares it equally. If nobody picked the winner, or everybody did, every entry is refunded and
nobody is out of pocket. Someone who never made a pick still has their entry in the pool — they simply cannot win.

---

## What is in here

```
ipl-league/
├── pom.xml                      Spring Boot 3.3.5, Java 21
├── docker-compose.yml           PostgreSQL 16 for local development
├── src/main/resources/
│   ├── application.yml          Config, including the job schedule and the dev-tools switch
│   └── db/migration/            Flyway: V1 to V14, the whole schema and the IPL 2026 seed
├── src/main/java/com/sahilprojects/bettingApp/
│   ├── auth/                    JWT, the security filter chain, AccessGuard
│   ├── user/                    Profiles, and the admin account screens
│   ├── tournament/              Tournaments and teams (reference data)
│   ├── league/                  Leagues and league membership
│   ├── contest/                 Contests, participants, predictions, results
│   ├── participation/           The status workflow both kinds of membership share
│   ├── payout/                  One-off contest money, and the pool calculation
│   ├── settlement/              Ending a league and settling up
│   ├── notification/            The in-app inbox
│   ├── dashboard/               The league table and personal records (read-only)
│   ├── jobs/                    The scheduled half: locking, cutoffs, reminders
│   ├── audit/                   The append-only audit log
│   └── common/                  Errors, the clock, shared base entity
├── src/test/java/               322 tests, including a full-season end-to-end run
├── frontend/                    React 19 + TypeScript + Vite, 62 tests
├── postman/                     251 requests with assertions, in the order a season happens
└── docs/TEST_CASES.md           Every test, generated from the sources
```

---

## Running it

### 1. The database

```bash
docker compose up -d
```

PostgreSQL 16 on 5432, database `ipl_league`, user `ipl`, password `ipl`. If something else already uses 5432
(a local PostgreSQL install, say), run `DB_PORT=5433 docker compose up -d` and start the backend with
`DB_URL=jdbc:postgresql://localhost:5433/ipl_league`. Flyway runs every migration on startup
and seeds IPL 2026 with its ten teams. Hibernate is set to `validate`, so if an entity and the schema ever
disagree the app refuses to start rather than quietly changing a table.

### 2. The backend

```bash
mvn spring-boot:run
```

On <http://localhost:8080>. For trying things out by hand, start it with the dev-tools shortcuts on:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--app.dev-tools.enabled=true
```

That adds a few admin-only endpoints that pull a contest's start time forward and run the scheduled jobs on demand,
so you do not have to wait for a real start time to see a contest lock. They do not exist unless you ask for them.

### 3. The frontend

```bash
cd frontend
npm install
npm run dev
```

On <http://localhost:5173>, which is the origin the backend already allows. Point it elsewhere with
`VITE_API_URL` in `.env.local` (see `.env.example`).

### 4. Make yourself an admin

Admins are not seeded, because that would mean shipping a password. The easiest way is to start the backend with
`ADMIN_EMAILS=you@example.com` (comma separated for several): those accounts become admins when they register, and on
every startup if they already exist. Or register through the app, then:

```sql
update users set role = 'ADMIN' where email = 'you@example.com';
```

The role is read from the database on every request, so this takes effect immediately — no need to sign in again.
From then on, an admin makes other people admins (or players again) from **Admin → Accounts**, with a reason that
goes in the audit log. The same screen resets a forgotten password: the app has no email, so "Forgot password?" on
the sign-in page alerts the admins, who send back a temporary password that must be changed at the next sign-in.

---

## Tests

```bash
mvn test                  # 322 backend tests
cd frontend && npm test   # 62 frontend tests
```

The backend's integration tests need Docker: they start a real PostgreSQL 16 through Testcontainers and run every
migration against it, so the triggers, the generated columns and the views are all tested rather than mocked. The
unit tests (the pool calculation, the status workflow, JWT, the access rules) need nothing.

`src/test/java/.../EndToEndJourneyTest.java` is the one to read first. It walks a whole season through the real
API — three friends register, join a league, play two contests, settle up — and its last assertions check that
every rupee that went in comes back out.

`docs/TEST_CASES.md` lists all 308 tests, generated from the sources.

### Postman

`postman/ipl-league.postman_collection.json` — 251 requests across 14 folders, each with assertions, in the order
a real season happens. Import it, set `baseUrl` to `http://localhost:8080`, and use the collection runner from the
top. Folders 8 onwards build their own league and players, so they can be run on their own; they do need folder 2
to have created the admin, and folders 9, 10 and 13 need `app.dev-tools.enabled=true`.

---

## How it fits together

### The pool

`PayoutCalculator` is a pure function with no database, clock or Spring in it — give it the entry amount, the
winning team and the confirmed players' picks and it tells you what everyone gets. That is deliberate: the money
rules are the part most worth being able to test exhaustively, and they are, in `PayoutCalculatorTest`.

```
pool       = amount × number of confirmed players
per winner = floor(pool ÷ number of winners) to 2 decimal places
leftover   = pool − (per winner × number of winners)
```

The leftover is the paise a pool could not divide evenly. It is recorded on the contest rather than given to
somebody arbitrarily, and the settlement add-up check accounts for it, which is how the books balance exactly.

Everyone is refunded, with nothing spent, when there is no result, when nobody picked the winner, or when
everybody did. In each of those cases there is nothing meaningful to split.

### Declaring a result takes two steps

The organizer previews the result and sees exactly what every player would get. The preview hands back a token
derived from the result and from every pick that fed the sums; confirming sends it back, and the backend recomputes
it. If a pick or a player changed in between, the token no longer matches and the confirmation is refused rather
than settling figures the organizer never saw.

### Rules the database enforces, not just the code

The service layer checks everything, and then the database checks the things that would be expensive to get wrong:

- A trigger on `contest_participants` refuses a pick that is late, for a team not in the match, or from somebody
  who is not confirmed. The service checks the clock first; the trigger is the second lock.
- Composite foreign keys mean a contest's teams must belong to its tournament, and a league's contest must use the
  league's tournament.
- Check constraints tie the money to the result: a `WON` row must have winnings above zero, a `REFUNDED` row must
  have spent nothing.
- `audit_log` is append-only — a trigger raises on any update or delete.
- `v_visible_predictions` returns nothing until a contest has started, so even a mistake in the service could not
  leak somebody's pick early.

### The clock

Several things happen because a time arrives rather than because somebody clicked: joining opens, unpaid places are
cut off, a contest locks at its start time, and a league starts with its first contest. Those live in `jobs/`,
switched on by `app.jobs.enabled` (on by default, off in tests). Each job locks the rows it works on with
`FOR UPDATE SKIP LOCKED`, so running the app on more than one instance is safe without a distributed lock.

Locking a contest is the point of no return: the unpaid are taken out first so only money the organizer actually
holds is in the pool, and a contest left with too few players is cancelled with refunds rather than played.

### Money that has moved cannot be quietly changed

A result can be corrected, with a reason, but not once the money it would change has actually gone: a one-off
contest refuses while somebody else's payout is marked paid, and a league refuses while a member other than the
organizer has settled up. The organizer's own balance is always recalculated, because they hold the pot and their
figure is the mirror of everyone else's.

### Everything is recorded

`audit_log` keeps every meaningful change with who made it and why. An admin acting in an organizer's place is
refused without a written reason — enforced in `AuditService` and again by a check constraint. Changes that affect
members, such as a result correction or a rescheduled contest, are marked visible to them.

---

## Configuration

| Setting | Default | What it does |
| --- | --- | --- |
| `JWT_SECRET` | a development-only string | Signing key. Must be at least 32 bytes. **Set this in production.** |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | local Postgres | Database connection |
| `CONTEST_MINIMUM_PLAYERS` | `2` | Below this a contest is cancelled at its start time rather than played |
| `JOBS_ENABLED` | `true` | The scheduled jobs. Tests switch this off |
| `DEV_TOOLS_ENABLED` | `false` | The admin-only shortcuts. Never enable on anything real |
| `ADMIN_EMAILS` | empty | Emails made admins at registration and on every startup |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Where the browser app is served from |

---

## Known limits

These are deliberate choices for this version, not oversights:

- **Nobody joins a league late.** Requests still waiting when the first contest starts are withdrawn. Letting
  somebody in halfway would mean deciding what they owe for matches already played, and there is no fair answer.
- **No email or push.** Notifications are in-app only; the frontend polls the unread count.
- **The result is entered by hand.** There is no scorecard feed, so the organizer types it in — which is why it
  takes two steps and can be corrected.
- **No sign-in rate limiting.** Worth adding before this is exposed to the internet.
- **No OpenAPI document.** The Postman collection serves that purpose for now.
- **No distributed lock library.** Not needed: the jobs use `FOR UPDATE SKIP LOCKED`, which is enough for several
  instances of this app.
