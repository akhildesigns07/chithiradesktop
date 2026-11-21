# Chithira Printers – Fiery printing system

Three separate projects live side by side in this repository. Each one builds, tests and ships on its own.

| Project | What it is | Runs on | Build | Windows download |
|---|---|---|---|---|
| [`fiery-hotfolder/`](fiery-hotfolder/README.md) | **Desktop app.** Watches a hot folder and sends files to the Fiery: imposition, front/back, actual size, customer picker | Each front-desk PC | `cd fiery-hotfolder && mvn package` | Actions → **Build Fiery Hot Folder** → `FieryHotFolder-windows` |
| [`print-hub/`](print-hub/README.md) | **Web application.** Customers, job tracking from the Fiery, print-room printed and waste counts, dashboards, users and audit log | One host PC (Windows service + PostgreSQL) | `cd print-hub && mvn package` | Actions → **Build Print Hub** → `PrintHub-windows` |
| [`mock-fiery/`](mock-fiery/README.md) | **Test server** that behaves like a Fiery (API, Hold queue, Print and Delete buttons) | Any PC, for testing only | `cd mock-fiery && mvn package` | Actions → **Build Mock Fiery** → `MockFiery-windows` |

**How they connect:** desktops send jobs to the **Fiery** (or the mock) and report each job to the **Print Hub**,
using the hub URL and API key in `fiery-hotfolder.properties`. The Print Hub follows the jobs on the Fiery. There
are no code dependencies between the projects; they only talk over the network.

**On GitHub:** open **Actions**, pick the project's workflow, click **Run workflow** and choose your branch. Each
workflow builds only its own download. Pushing a tag such as `print-hub-v1.2.0` also builds that project.

**In IntelliJ:** open this folder, then in the Maven tool window click **+** and add `fiery-hotfolder/pom.xml`,
`print-hub/pom.xml` and `mock-fiery/pom.xml`.
