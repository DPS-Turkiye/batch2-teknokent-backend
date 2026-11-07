# Local Moodle for office hours

A Moodle 4.5 in Docker that stands in for ODTÜClass while office hours (LTI 1.3) are built. Local only.

## Start it

```bash
docker compose -f dev/moodle/compose.yml up -d
```

The first start installs Moodle and takes a few minutes. Then it is at http://localhost:8081.

## Register Menton and make a test course

```bash
docker compose -f dev/moodle/compose.yml cp dev/moodle/setup-office-hours.php moodle:/tmp/
docker compose -f dev/moodle/compose.yml exec -u daemon -e HTTP_HOST=localhost:8081 moodle php /tmp/setup-office-hours.php
```

The script registers "Menton ofis saatleri" as a site-wide LTI 1.3 tool (the way ODTÜ's information office
would), makes the course CENG 101 with a teacher and a student, and adds an "Ofis saatleri" activity to it. It
prints the `LTI_*` lines for the backend's `.env`; add them and restart the app (`docker compose up -d app`).

## Test accounts (local only)

| User | Password | Role in CENG 101 |
|---|---|---|
| `admin` | `Admin-local-1` | site admin |
| `teacher1` | `Teacher-local-1` | teacher → office hours as an instructor |
| `student1` | `Student-local-1` | student → office hours as a student |

## Try a launch

Sign in to Moodle as `teacher1` or `student1`, open CENG 101 and click "Ofis saatleri". It opens in a new
window (Menton refuses to be shown inside another site's frame) and lands on the course's office-hours page.

The backend fetches Moodle's keys at `host.docker.internal:8081`, and Moodle fetches the tool's keys at
`host.docker.internal:8080`, because each runs inside Docker.
