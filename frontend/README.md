# Dugout XI — frontend

React 19, TypeScript and Vite. Talks to the Spring Boot backend in the folder above.

```bash
npm install
npm run dev        # http://localhost:5173
npm run share      # the same, reachable from phones on your Wi-Fi; the API goes through it at /api (API_TARGET, default 8080)
npm test           # 67 tests
npm run typecheck  # tsc, no emit
npm run build      # production bundle into dist/
```

The dev server runs on 5173, which the backend already allows as an origin. To point at a different API, copy
`.env.example` to `.env.local` and set `VITE_API_URL`.

## How it is put together

```
src/
├── api/
│   ├── types.ts        One interface per backend record, so a changed endpoint is a type error
│   ├── client.ts        The only place that calls fetch: the token, the errors, the 401 handling
│   └── endpoints.ts     Every call, named after what it does rather than its URL
├── auth/               Who is signed in, and the stored token
├── hooks/
│   ├── useLoad.ts      Loading, errors and abort-on-unmount for a GET; useAction for a change
│   └── usePrompt.tsx   A confirmation modal that asks for a reason where the backend needs one
├── components/         The shared pieces: cards, badges, tables, tabs, the layout
├── lib/format.ts       Money, dates and statuses
└── pages/              One file per screen, with the bigger ones split into panels
```

Three things are worth knowing before changing anything:

**Money is never a number.** Amounts arrive as decimal strings because the backend works in `BigDecimal`. They are
formatted for display and compared, but never added up in JavaScript — a rupee total assembled from floats is how
a ledger ends up a paisa out.

**The API client is the only thing that calls `fetch`.** The token, the error shape and signing out on a 401 are
handled there once. Component tests stub `fetch` rather than the endpoint functions, so the real client runs and
those behaviours are exercised exactly as they are in the browser.

**The backend decides, the frontend explains.** Every rule — who may declare a result, whether a league can be
closed, whether a reason is required — is enforced server-side. The screens hide buttons that would be refused and
ask for a reason up front, so the user is not sent into an error they could have avoided, but nothing here is the
actual protection.

## Screens

| Route | What it is for |
| --- | --- |
| `/sign-in` | Sign in or register; registering signs you straight in |
| `/` | Your record, money coming back to you, and what is waiting on you |
| `/join/:code` | An invite link: what the league costs, and a button to ask for a place. Works signed out too: sign-in brings you back here |
| `/leagues` | Your leagues; start one, or open one with an invite code |
| `/leagues/:id` | One league: the table, its contests, its players, and settling up |
| `/contests` | One-off contests; create one, or open one shared with you |
| `/contests/:id` | One contest: your pick, the players, the result, and the money |
| `/notifications` | The in-app inbox |
| `/profile` | Your details, including the UPI id your organizer pays to |
| `/admin` | Tournaments, teams, accounts, and running the scheduled jobs by hand |
