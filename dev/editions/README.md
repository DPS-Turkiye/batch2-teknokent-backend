# Both editions side by side (local)

One codebase, two deployments: **TEKNOKENT** (startup mentoring, the default) and **ODTU** (office hours + İŞKUR).
`APP_EDITION` picks the edition; each one answers 404 for the other's endpoints and pages. This runs both on one
laptop, each with its own database and Redis, so nothing crosses between them.

| | TEKNOKENT | ODTU |
|---|---|---|
| Backend | http://localhost:8080 | http://localhost:8090 |
| Postgres / Redis (host ports) | 5435 / 6381 | 5434 / 6380 |
| Frontend | http://localhost:3000 | http://127.0.0.1:3100 |
| Sign-in | your usual accounts | the persona picker at `/login/odtu/dev` |
| Compose project | `menton-teknokent` | `menton-odtu` |

Open the ODTU frontend on `127.0.0.1`, not `localhost`: cookies are kept per host, not per port, so on one host
the two editions would overwrite each other's session cookie.

Neither stack touches your usual `docker compose up` stack (project `teamtnt`, ports 8080/5433/6379): stop its
`app` first if it holds :8080.

## Backend

```bash
dev/editions/up.sh            # builds once, starts both, waits for /api/health
dev/editions/down.sh          # stops both; add --volumes to drop both databases
```

`up.sh` writes `.env.odtu` from your `.env` plus [`odtu.env`](odtu.env): `APP_EDITION=ODTU`, the dev login stub
on, OTG off, no S3 bucket (storage unconfigured until MinIO), no meeting bot, pgvector matching off, and origins
for :3100. Both databases start empty and run every migration, so the ODTU one also holds the Teknokent demo seeds
until the (b) seed cleanup exists. Locally both stacks share your Firebase project; the real (b) gets its own.

## Frontend

The ODTU frontend needs its own checkout, because two `next dev` servers cannot share one `.next` folder:

```bash
cd ../Frontend
git worktree add --detach ../odtu-frontend main        # or the branch you are testing
ln -s "$PWD/node_modules" ../odtu-frontend/node_modules
cp .env ../odtu-frontend/.env
```

In `../odtu-frontend/.env` set `APP_EDITION=ODTU`, `APP_URL=http://127.0.0.1:3100`,
`BACKEND_URL` and `NEXT_PUBLIC_BACKEND_URL` to `http://localhost:8090`,
`NEXT_PUBLIC_REALTIME_URL=ws://localhost:8090/ws`, `NEXT_PUBLIC_ODTU_DEV_LOGIN=true`, and a new
`SESSION_PASSWORD`. Then:

```bash
npm run dev                                         # :3000, TEKNOKENT (APP_EDITION unset)
(cd ../odtu-frontend && npx next dev --webpack -p 3100)   # Turbopack cannot follow the symlinked node_modules
```

## Quick check

| Request | :8080 / :3000 | :8090 / :3100 |
|---|---|---|
| `GET /api/peer-mentoring/categories`, `GET /api/lti/jwks` | 404 | 401 / 200 |
| `GET /api/assistant/bots`, `POST /api/auth/register/mentor` | 401 | 404 |
| `GET /api/programs/names` (service login) | Teknokent programs only | ODTÜ programs only |
| `/student`, `/academician`, `/login/odtu/dev` | 404 | sign-in / persona picker |
| `/m/login`, `/assistant` | phone login / sign-in | 404 |
