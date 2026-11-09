# Local Moodle for office hours

A Moodle 4.5 that stands in for ODTÜClass while office hours (LTI 1.3) are built. Local only, and off unless
you ask for it.

## Start it

```bash
docker compose --profile moodle up -d
```

This starts the usual services plus Moodle at http://localhost:8081. Moodle keeps its own `moodle` database in
the same postgres service (the `moodle-db` service creates it). The first start installs Moodle and takes a few
minutes.

Once Moodle is up, the `moodle-setup` service runs `scripts/setup-office-hours.php` once: it registers
"Menton ofis saatleri" as a site-wide LTI 1.3 tool (the way ODTÜ's information office would), makes the course
CENG 101 with a teacher and a student, and adds an "Ofis saatleri" activity to it. It seeds Moodle's own database
through Moodle's API, so it is a script rather than a Flyway migration of this backend. Read its output for the
`LTI_*` lines, add them to `.env`, and restart the app:

```bash
docker compose logs moodle-setup
docker compose up -d app
```

## Test accounts (local only)

| User | Password | Role in CENG 101 |
|---|---|---|
| `admin` | `Admin-local-1` | site admin |
| `teacher1` | `Teacher-local-1` | teacher → office hours as an instructor |
| `student1` | `Student-local-1` | student → office hours as a student |

## Try a launch

Sign in to Moodle as `teacher1` or `student1`, open CENG 101 and click "Ofis saatleri". It opens in a new
window (Menton refuses to be shown inside another site's frame) and lands on the course's office-hours page.

Moodle and the backend reach each other on the compose network: the backend reads Moodle's keys at
`http://moodle:8080/mod/lti/certs.php`, and Moodle reads the tool's keys at `http://app:8080/api/lti/jwks`.
