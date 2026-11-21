# Chithira Print Hub (web application)

The shop's web application on the local network. Desktops running the Fiery Hot Folder app register here with an
API key, pick or quick-add customers, and push every job they send to the Fiery. Staff log in with their own
accounts to manage customers, enter printed and waste counts, and see dashboards. Every change is written to the audit log.

## Status

| Phase | What | State |
|---|---|---|
| 1 | Logins, roles, page access, desktops and API keys, customers (web and API), audit log | **done** |
| 2 | Desktop link: customer picker and quick add, job push with an offline outbox | **done** |
| 3 | Job tracking: Fiery status poll every 60 s, print-room printed and waste counts, paper catalog | **done** |
| 4 | Dashboards: overall, customer, paper, print, waste | **done** |
| 5 | Reports, bills, payment reminders, customer portal, paper inventory (stub packages and feature flags exist) | |

## Run it

**Trial or development:** an H2 file database in `./data`, with nothing to install.

```bash
mvn -f print-hub/pom.xml package
java -jar print-hub/target/print-hub.jar --spring.profiles.active=dev
```

**Shop host (Windows), no code or Java needed:** download the ready-made package.

1. On GitHub: **Actions → Build Windows EXE → Run workflow**, choose your branch, then download the
   **`PrintHub-windows`** artifact. It contains the Print Hub with its own Java, the Windows service wrapper (WinSW)
   and double-click scripts. The package's source files are in `packaging/windows/`.
2. Install **PostgreSQL 16** (the only other install) and unzip the package to `C:\PrintHub`.
3. Double-click **`setup-database.bat`**. It creates the `printhub` database and user, and saves the password in `config\application.properties`.
4. Right-click **`install-service.bat` → Run as administrator**. This installs the service (it starts with the PC
   and restarts itself after a failure), starts it, allows port 8080 on private networks, and opens the browser and
   `FIRST-LOGIN.txt` with the first admin password.

Settings are in `config\application.yml` (port, shop name, Fiery). Run `restart-service.bat` after changing them.
`README-WINDOWS.txt` in the package walks through everything, including updating and removing.

Flyway creates and upgrades the tables on start (`src/main/resources/db/migration`).

**First start:** a one-time password for `admin` is printed to the log and written to `FIRST-LOGIN.txt` in the working
folder. Log in at `http://<host>:8080` and choose your own password; the file is deleted then.

## Fiery connection (job status and paper catalog)

In `application.yml`, under `printhub.fiery`:

```yaml
printhub:
  fiery:
    enabled: true
    base-url: https://192.168.1.50
    api-key: ${PRINTHUB_FIERY_API_KEY}
    username: ${PRINTHUB_FIERY_USER}
    password: ${PRINTHUB_FIERY_PASSWORD}
```

Every `poll-seconds` (default **10**, minimum 5), the hub asks the Fiery for each job still on it (`GET /jobs/{id}`)
and moves the job on. A new job is also checked about **3 s after the desktop reports it**, so "Held" shows almost
at once. A job counts as deleted only after **two "not found" answers in a row** (`missing-checks-before-removed`),
so one network hiccup never closes a job. The second check comes **5 s** after the first miss rather than a whole
interval later. **Check now** on a job page asks the Fiery about that job immediately.

The job list, job page and print room **refresh themselves**: every 5 s they ask the hub whether a job on screen
changed (or a new job arrived) and reload if so. They never reload while someone is typing counts; they wait
until that's saved or the field is left. A small "● Live" note in the corner shows the last check.

Typical times with the mock Fiery: Sent → Held about 1–3 s, Print → Printed about 10 s, Delete → Awaiting counts about 10 s. The Paper Catalog is read every `paper-sync-hours` (default 6), and on
demand with **Sync from Fiery** on the Paper catalog page.

**Confirm on your Fiery once.** Hold a job, print it, and compare with `GET /live/api/v5/jobs/{id}`. Set
`status-path` (where the state is, default `data.item.state`) and add any state you see to `status-map`, for
example `printing: PRINTING`. Keys with spaces go in brackets: `"[done printing]": PRINTED`. Unknown states are
shown on the job (hover the status badge) but don't change it. The status line on the job and print-room pages
shows when the Fiery was last checked, or why it can't be reached.

## Job life cycle

```
Desktop reports ─▶ SENT ─▶ HELD ─▶ PRINTING ─▶ PRINTED ─▶ (deleted on Fiery) REMOVED ─▶ AWAITING_COUNTS ─▶ COUNTED
                   (driver jobs start at AWAITING_COUNTS · rejected sends are FAILED · staff can CANCEL an open job)
```

* Statuses only move forward (Printing → Held is allowed when a print is stopped). Each move is kept in the
  job's **timeline** with what caused it (desktop, poller or user) and in the audit log.
* **Print room page** (`/print-room`) has three lists:
  - **Waiting for counts:** oldest first. A job is flagged overdue after `printhub.print-room.counts-overdue-hours` (default 12).
  - **On the Fiery now.**
  - **Counted in the last 24 hours:** open a job there to correct its counts.

  Staff enter the **total printed** (good sheets) and the **waste** sheets. Waste needs a reason
  (`printhub.print-room.waste-reasons`). Counts can be entered from Printed onwards and corrected later; every
  correction keeps the old and new numbers in the audit log.
* The **customer page** shows the jobs, printed sheets, and waste sheets and %, and the **paper catalog page** shows usage per paper.

## Dashboards

| Page | Shows |
|---|---|
| **Overall** (start page `/`) | printed sheets (headline), waste and waste %, sheets sent, jobs waiting for counts, failed sends; printed and waste per day; waste % per day; top customers; papers |
| **Prints** `/dashboards/prints` | sheets sent per day, printed and waste per day, jobs by status, per desktop, per send method, per front-staff operator |
| **Papers** `/dashboards/papers` | printed and waste per paper, waste % per paper, sheets per day (one paper with the Paper filter) |
| **Waste** `/dashboards/waste` | waste sheets and %, top reason, waste % per day, waste by reason, paper, operator, customer and desktop, jobs with the most waste |
| **Customers** `/dashboards/customers` | ranking of customers; click one for their own dashboard (per day, papers used) |

* **Filters** (one row above the charts): quick ranges (today, 7 / 30 / 90 days, this or last month), from and to
  dates, paper, desktop, and customer (by clicking one). The default is the last 30 days, and the longest range is 400 days.
* **What counts:** printed and waste include only jobs whose counts the print room has entered. Sheets sent counts
  every job that reached the Fiery or the printer. Days are by the date the job was sent.
* **Colours:** printed sheets are always blue and waste is always orange, on every chart. Both colours were checked
  for colour-blind readers in light and dark mode. The pages follow the computer's light or dark setting. Every chart
  has a hover tooltip and a **Show as table** view. Chart.js is served from the jar, so no internet is needed.
* Each dashboard is its own page in **Admin → Page access**. By default Front staff and Printing room can view them all.

## Roles and access

| Role | Default access |
|---|---|
| Admin | Everything, always: users, page access, desktops and API keys, audit log |
| Front staff | Overall dashboard, customers (edit), print jobs (edit), waste and paper (view) |
| Printing room | Overall dashboard, customers (view), print jobs and print room: **enter and edit total printed and waste counts**, paper catalog (edit) |
| Customer | Nothing until the customer portal feature is switched on |

Every person gets their own account, and a role can have any number of users. Admin changes access per role in
**Admin → Page access** (edit includes view). Changes apply immediately and are audited.

## Desktop API (`/api/v1`, header `X-Api-Key`)

Register each PC in **Admin → Desktops & API keys**. The key (`chp_xxxxxxxx_…`) is shown only once. **New key**
replaces it, and **Revoke** stops it. A desktop also sends `X-Operator` (the Windows user), which appears in the audit log.

| Method | Path | |
|---|---|---|
| GET | `/ping` | Checks the key; returns the desktop name |
| GET | `/customers` | All active customers. `?q=` searches; `?updatedSince=<serverTime>` returns only changes |
| GET | `/customers/{id}` | One customer |
| POST | `/customers` | Quick add `{name, phone, email, company}`. Returns `409` with the existing customer if the phone is already used |
| POST | `/jobs` | Report a job after each send (see below). Returns `201` for a new job, or `200` with `"duplicate": true` for a retry |

**Job report** (`POST /api/v1/jobs`): `clientJobUuid` (made by the desktop, so retries are safe), `outcome`
(`SENT`/`FAILED`), `fieryJobId` (required for API sends), `customerId`, `sendMethod` (`API`, `DRIVER_API`,
`DRIVER`), `fileName`, `backFileName`, `jobName`, `paperId`, `paperName`, `imposed`, `quantity`, `piecesPerSheet`,
`sheets`, `duplex`, `instructions`, `operator`, `sentAt`, `error`.

Where a job starts: `SENT` (followed on the Fiery from phase 3), `AWAITING_COUNTS` for driver jobs (there is no
Fiery job id to follow), and `FAILED` for rejected sends. Every report gets a status-history row and an audit row.

Errors are JSON: `{"error": "...", "fields": {...}}`.

## Code layout (`com.chithira.printhub`)

| Package | Responsibility |
|---|---|
| `common` | Settings, `PageKey` (every access-controlled page), domain events, shared exceptions |
| `auth` | Users, roles, page-access matrix, login, security configuration, first-start data |
| `devices` | Desktop registration, API keys (only SHA-256 hashes stored), API-key filter |
| `customers` | Customer records, web pages, desktop API |
| `audit` | Append-only audit log and the audit page search |
| `web` | Page controllers, shared page model, template helpers, API error handling |
| `jobs` | Print jobs, status rules, counts, timeline, `/api/v1/jobs`, job list and print-room pages |
| `fiery` | Fiery API client, status mapping, 60-second poller |
| `papers` | Paper Catalog synced from the Fiery, paper page with usage |
| `dashboard` | Dashboard figures (one query per page, totals worked out in Java), chart specs, dashboard pages |
| `billing`, `inventory`, `reports`, `notifications`, `portal` | Planned modules (feature flags in `application.yml`) |

Rules: each module owns its entities, service, controllers and migrations. Modules talk through services or
domain events (`common.events`). A new page adds a `PageKey`, so Admin can grant it straight away. Every service
method that changes data calls `AuditService.record(...)` in the same transaction.

## Tests

`mvn -f print-hub/pom.xml test` runs unit tests and integration tests on an in-memory H2 database in PostgreSQL mode,
using the same migrations as production.
