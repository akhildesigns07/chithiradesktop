# Chithira Print Hub (web application)

The shop's web application on the local network. Desktops running the Fiery Hot Folder app register here with an
API key, pick or quick-add customers, and push every job they send to the Fiery. Staff log in with their own
accounts to manage customers, enter printed and waste counts, and see dashboards. Every change is written to the audit log.

## Status

| Phase | What | State |
|---|---|---|
| 1 | Logins, roles, page access, desktops and API keys, customers (web and API), audit log | **done** |
| 2 | Desktop link: customer picker and quick add, job push with an offline outbox | next |
| 3 | Job tracking: Fiery status poll every 60 s, print-room printed and waste counts, paper catalog | |
| 4 | Dashboards: overall, customer, paper, print, waste | |
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
| `jobs`, `papers`, `fiery`, `dashboard` | Phases 3–4 |
| `billing`, `inventory`, `reports`, `notifications`, `portal` | Planned modules (feature flags in `application.yml`) |

Rules: each module owns its entities, service, controllers and migrations. Modules talk through services or
domain events (`common.events`). A new page adds a `PageKey`, so Admin can grant it straight away. Every service
method that changes data calls `AuditService.record(...)` in the same transaction.

## Tests

`mvn -f print-hub/pom.xml test` runs unit tests and integration tests on an in-memory H2 database in PostgreSQL mode,
using the same migrations as production.
