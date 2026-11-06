# Deploying Dugout XI for free

Four free services, about 30 minutes the first time:

| Part | Service | Why |
| --- | --- | --- |
| Database | **Supabase** (free) | Real PostgreSQL, 500 MB, no compute-hour limit. The jobs query it every 20 seconds, which would use up Neon's free hours. |
| Backend | **Render** (free web service, Docker) | Builds `Dockerfile` from `render.yaml`. 512 MB is enough: the app uses about 210 MB. |
| Frontend | **Vercel** (Hobby) | Serves the React build and forwards `/api/*` to Render (`frontend/vercel.json`), so the browser sees one site. |
| Keep-awake | **cron-job.org** (free) | Calls `/api/health` every 10 minutes. Render puts a free service to sleep after 15 idle minutes, and the scheduled jobs (locking matches, reminders) only run while it is awake. |

Nothing here costs money. Secrets are typed into each service's dashboard and are never committed.

---

## 1. Supabase: the database

1. **New project.** Name `dugout-xi`, region **South Asia (Mumbai)**, and choose a strong **database password**. Keep it somewhere safe; it is needed in step 2.
2. When the project is ready: **Connect** (top of the project page) → **Connection string** → **Session pooler**. It looks like
   `postgresql://postgres.<project-ref>:[YOUR-PASSWORD]@aws-0-ap-south-1.pooler.supabase.com:5432/postgres`
3. From it, note three values for Render:
   - `DB_URL` = `jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require` (the host from the string, with `jdbc:` in front and no user or password)
   - `DB_USER` = `postgres.<project-ref>`
   - `DB_PASSWORD` = the password from step 1

Use the **session** pooler (port 5432), not the transaction pooler (6543): the app and Flyway use prepared statements and
sessions. The tables are created by the app itself (Flyway) on its first start.

## 2. Render: the backend

1. **New → Blueprint** → connect GitHub → pick the **DugoutXI** repository. Render reads `render.yaml`.
2. It asks for the secret values:
   - `DB_URL`, `DB_USER`, `DB_PASSWORD`: from step 1
   - `JWT_SECRET`: a long random string (64+ characters). Changing it later signs everybody out.
   - `ADMIN_EMAILS`: your email, so your account is an admin as soon as you register
   - `CORS_ALLOWED_ORIGINS`: your Vercel address from step 3, e.g. `https://dugout-xi.vercel.app` (fill in a placeholder now and correct it after step 3)
3. **Apply.** The first build takes a few minutes, and the first start can take several more on the free CPU.
4. When it is live, open `https://<your-service>.onrender.com/api/health`. It should show `{"status":"UP"}`.
5. If Render gave the service an address other than `dugout-xi-api.onrender.com`, change the address in
   `frontend/vercel.json` to match, commit and push.

## 3. Vercel: the frontend

1. **Add New → Project** → import the **DugoutXI** repository.
2. **Root Directory: `frontend`.** Vercel then detects Vite: build `npm run build`, output `dist`. No environment
   variables are needed: `frontend/.env.production` makes the app call `/api` on its own site, and `vercel.json` forwards
   those calls to Render.
3. **Deploy.** Note the address, e.g. `https://dugout-xi.vercel.app`.
4. Back in Render → the service → **Environment**: set `CORS_ALLOWED_ORIGINS` to that exact address (no trailing slash)
   and save. Render redeploys.

## 4. cron-job.org: keep it awake

1. **Create cronjob.** URL `https://<your-service>.onrender.com/api/health`, every **10 minutes**.
2. Save. Its history should show `200` each time.

750 free Render hours a month cover one service running all month.

## 5. Check it

Open the Vercel address and:

- register with the email in `ADMIN_EMAILS` (the Admin tab appears)
- create a league, copy its invite link, open it in a private window, register a second player and ask to join
- accept them, add a match, make picks, then lock it with **Contests → (match) → Result** once it has started

## Day to day

- **Updating:** push to `main`. Render and Vercel both redeploy on their own.
- **First visit after a deploy** can take a few minutes while the backend starts. The page shows "Could not reach the
  server" until then; refresh.
- **Backups:** Supabase's free plan keeps no backups you can restore yourself. Every member can download Excel copies
  (League → Download Excel, Profile → Download Excel), and the organizer should do so after each match week.
- **Free plan limits change.** Everything runs in Docker and standard PostgreSQL, so moving to another host takes about an
  hour if one of these plans gets worse.
