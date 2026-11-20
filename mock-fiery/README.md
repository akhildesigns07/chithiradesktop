# Mock Fiery Server

A stand-in for a Xerox Fiery, for testing the Chithira hot folder app without the real press.
See the main README section "Mock Fiery Server" for details.

| What | Where |
|---|---|
| Hold-queue web page | https://localhost:8443/ |
| Fiery API | https://localhost:8443/live/api/v5 (login, papercatalog, jobs) |
| Raw printing | localhost:9100 (Windows printer, Standard TCP/IP port, RAW) |
| Login | api key `MOCK-KEY`, user `test`, password `test` |
| Settings | `mock-fiery.properties` next to the exe (created on first start) |
| Received jobs | `mock-fiery-data/jobs` |
| Paper catalog | `mock-fiery-data/papercatalog.json` (editable, read on every request) |

Build: `build-exe.bat` (JDK 17+ and Maven), or `mvn package` then `java -jar target/mock-fiery-1.0.0-all.jar`.

PostScript page previews need Ghostscript installed (`gswin64c`); PDF and TIFF previews work without it.
The built-in certificate is a localhost test certificate only. Browsers will warn about it; that is expected.
