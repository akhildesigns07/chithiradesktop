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
| 4 | Dashboards: overall, customer, paper, print, waste | next |
| 5 | Reports, bills, payment reminders, customer portal, paper inventory (stub packages and feature flags exist) | |

## Run it

**Trial or development:** an H2 file database in `./data`, with nothing to install.

```bash
mvn -f print-hub/pom.xml package
java -jar print-hub/target/print-hub.jar --spring.profiles.active=dev
```

**Shop host (Windows):** uses PostgreSQL.

1. Install PostgreSQL 16, then create the database and user: `CREATE USER printhub PASSWORD '…'; CREATE DATABASE printhub OWNER printhub;`
2. Put `print-hub.jar` and a copy of `src/main/resources/application.yml` in, for example, `C:\PrintHub\`. Set the database password, preferably with the environment variable `PRINTHUB_DB_PASSWORD`.
3. Run `java -jar print-hub.jar`, or install it as a Windows service with WinSW. Allow port 8080 in the Windows firewall.

Flyway creates and upgrades the tables on start (`src/main/resources/db/migration`).

**First start:** the log prints a one-time password for `admin`. Log in at `http://<host>:8080`, and you will be asked to choose a new password.

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

Every `poll-seconds` (default 60), the hub asks the Fiery for each job still on it (`GET /jobs/{id}`) and moves the
job on. A job counts as deleted only after **two "not found" answers in a row** (`missing-checks-before-removed`),
so one network hiccup never closes a job. The Paper Catalog is read every `paper-sync-hours` (default 6), and on
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
| `dashboard` | Phase 4 |
| `billing`, `inventory`, `reports`, `notifications`, `portal` | Planned modules (feature flags in `application.yml`) |

Rules: each module owns its entities, service, controllers and migrations. Modules talk through services or
domain events (`common.events`). A new page adds a `PageKey`, so Admin can grant it straight away. Every service
method that changes data calls `AuditService.record(...)` in the same transaction.

## Tests

`mvn -f print-hub/pom.xml test` runs unit tests and integration tests on an in-memory H2 database in PostgreSQL mode,
using the same migrations as production.
