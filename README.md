# Chithira Fiery Hot Folder (desktop app)

Three ways to send files, chosen per batch in the "Files waiting" window:

| Method | How it works | Fiery job id | Paper |
|---|---|---|---|
| **Fiery API → Hold queue** | Images wrapped in PDF (pixels unchanged); PDF/PS/TIFF uploaded as is | Yes | Paper Catalog |
| **Fiery driver → PostScript → API** | The Fiery driver converts the file exactly like Ctrl+P but **to a file**; that PostScript is uploaded with the API | Yes | Paper Catalog |
| **Printer driver – exactly like Ctrl+P** | Printed through the Fiery driver to its printer queue | No | Driver paper sizes / print dialog |

With the API methods the job keeps its **original file name and extension**
(e.g. `Wedding_Invite_Front.jpg`) in Command WorkStation, just like Ctrl+P, even
though the content is PDF or PostScript.

```
Startup: login → GET /live/api/v5/papercatalog → paper list (saved copy used if Fiery is offline)

Files copied into "Fiery HotFolder"
      │  (waits until each copy has finished)
      ▼
"Files waiting for the Fiery" window
   ☑ file list with type, size and quality on the chosen paper
   preview of the selected file · double-click / "Open file" to view it
   paper (from catalog) · image sizing · copies · instructions
      │  Send ticked                      │  Skip ticked
      ▼                                   ▼
PDF / PS / TIFF → unchanged          moved to Skipped/
PNG / JPG / … → PDF on the paper size (or unchanged: image.mode in the config file)
      ▼
POST /live/api/v5/jobs   queue=hold, pcmid=<paper id>, attributes={num copies, instructions}
      ▼
Response written to the log → file deleted (or moved to Sent/)
Failure → file moved to Failed/ with an .error.txt, failure logged
```

## Imposition and front/back (no Photoshop needed)

A customer sends one JPG or PNG. Staff tick **Impose**, type the **quantity**, and
click **Impose & Send**. The app fills the sheet, adds crop marks and sends it.

```
Customer's card.jpg ─▶ app works out the piece size ─▶ fits as many as possible on the paper
                    ─▶ centred grid, crop marks, info line ─▶ PDF sheet ─▶ Fiery (Hold, copies = sheets)
```

**What the app decides by itself**

| | How |
|---|---|
| Piece size | The DPI stored in the image (150–1200 DPI); otherwise the standard size whose shape matches (visiting card, A6, 4×6 …), choosing the one closest to 300 DPI; otherwise the image at 300 DPI. Staff can pick another size or type a custom one. |
| Rows × columns | As many as fit, trying pieces upright and turned 90°. Override with Columns / Rows (0 = automatic). |
| Doesn't fit? | Never an error: a piece bigger than the paper, or more rows × columns than fit, is **shrunk (keeping its proportions) until it fits**, centred. |
| Sheet | **Portrait** or **Landscape**: the paper is turned this way whatever the shape of the file (remembered for the next job) |
| Layout option | **Fit rows × columns to the paper** (default: set Columns, Rows, both or neither; pieces are resized up or down, keeping their proportions, to fill the paper) or **Piece size** (real size, shrink only if needed) |
| Artwork | Always fitted **inside** each piece: never cropped, stretched or over the edge (`impose.crop-to-fill=true` to crop instead) |
| Alignment | Grid centred; margin, gap and bleed adjustable in the window (all start at 0; crop-mark space is always kept) |
| Crop marks | **Always** on the front sheet, at every cut line; **never** on the back |
| Sheets | Quantity ÷ pieces per sheet, rounded up, sent as the number of copies. Quantity starts at 0 and must be entered before sending |
| Quality | Shows the DPI at the piece size and blocks blurry jobs (unless "Allow low resolution") |

**Front and back**

* Name the files `card_front.jpg` / `card_back.jpg` (also `_F`/`_B`, `side1`/`side2`) and the app
  **pairs them automatically**. Or tick two files and click **Pair as Front / Back**.
  **⇄ Swap** and **Unpair** fix mistakes.
* A front/back job is **always made into one 2-page PDF**: page 1 = front sheet, page 2 = back sheet,
  sent two-sided (turning on the long edge).
* The back is mirrored left↔right so every back lands behind its front. Pieces turned 90° on the
  front are turned the other way on the back, so both sides read upright.
* If your press needs it, nudge the back with `impose.back-offset-x-mm` / `-y-mm`.
* API method: the job gets the attribute `duplex=true`. Check the exact name your Fiery uses
  (set duplex on a held job in Command WorkStation, then `GET /jobs/{id}`) and set
  `api.duplex-attribute-name` / `-value`.

**Works with all three send methods.** The **Fiery API** method is the best choice for imposition:
the image is stored once in a small PDF (e.g. 60 KB for 25 cards). The two driver methods redraw
the image for every piece, so their files can be 50–200 MB per sheet.

**Settings (all optional, with these defaults):**

```properties
impose.presets=Visiting card:89x55mm;Visiting card (US):3.5x2in;ID card (CR80):86x54mm;...
impose.default-paper-id=5598
impose.margin-mm=0
impose.gap-mm=0
impose.bleed-mm=0
impose.orientation=PORTRAIT
impose.allow-rotation=true
impose.crop-to-fill=false
impose.info-line=true
impose.back-offset-x-mm=0
impose.back-offset-y-mm=0
frontback.auto-pair=true
api.duplex-attribute-name=duplex
api.duplex-attribute-value=true
```

## Print Hub (customers and job tracking)

When the Print Hub web application (`print-hub/`) runs on the shop network, each desktop can pick the
**customer** for a batch and report **every job** it sends, so the hub can track prints and waste per customer.

1. In the hub, open **Admin → Desktops & API keys**, register this PC, and copy the key. It is shown only once.
2. In `fiery-hotfolder.properties`:
   ```properties
   hub.enabled=true
   hub.base-url=http://192.168.1.10:8080
   hub.api-key=chp_...
   # true = a customer must be chosen before Send
   hub.require-customer=true
   ```
3. Click **Reload config**, then **Test hub**. The main window shows the hub status: connected, offline, or key rejected.

**In the "Files waiting" window:** the **Customer** row searches the hub's customers by name, phone, company or
code (C-0042). The list is cached on this PC, so it also works while the hub is offline. **+ New** adds a customer
to the hub straight away. If the phone number already belongs to a customer, that customer is chosen instead.
After each send, the customer is cleared so the next batch can't be booked to the wrong person.

**After each send:** the desktop reports the job (file, customer, paper, Fiery job id, copies or sheets, imposed
quantity, front/back, Windows user) to the hub. Driver-method prints are reported too, without a Fiery job id. Failed
sends are reported with the error. Reports are first written to `<user home>/.chithira-fiery/outbox/`, then
delivered in the background. If the hub is down, they wait there and are sent automatically when it is back
(checked every minute), and printing never waits. Reports the hub refuses move to `outbox/rejected/` with the reason.

## Mock Fiery Server (test without the real press)

`mock-fiery/` is a small separate program that behaves like your Fiery, so the
whole app can be tested on a laptop with no risk to real jobs.

* **Fiery API** at `https://localhost:8443/live/api/v5`: login (wrong key → 401), paper catalog,
  job upload. PNG/JPG are rejected with "unsupported PDL" like the real Fiery; the paper id and
  attributes are checked. Self-signed HTTPS certificate, sessions that expire.
* **Raw printing on port 9100.** Add a Windows printer with a PostScript driver on a
  *Standard TCP/IP port → localhost → RAW 9100*, and the two driver methods can be tested too.
* **Print and Delete buttons** on each job: **Print** sets it to Printing, then Printed after 5 seconds. **Delete** removes it, and the API answers 404, like a job deleted in Command WorkStation. These let you test the Print Hub's status tracking.
* **Hold-queue page** at `https://localhost:8443/`: every job with title, paper, copies, two-sided,
  attributes, **page previews** (check imposition and front/back by eye) and a download link.
* **Test switches:** go offline, slow responses, reject the next job, server error, expire sessions.
* Received files are saved in `mock-fiery-data\jobs` under their **original name and extension**
  (e.g. `1004_card_front.jpg`; the content is the PDF/PostScript that was sent). Papers are in `mock-fiery-data\papercatalog.json`
  (edit it to add your real papers).

**Run it:** build with `mock-fiery\build-exe.bat` (or get `MockFiery-windows` from GitHub Actions)
and double-click **Mock Fiery.exe**. Then point the app at it in `fiery-hotfolder.properties`:

```properties
fiery.base-url=https://localhost:8443
fiery.api-key=MOCK-KEY
fiery.username=test
fiery.password=test
fiery.trust-self-signed-certificate=true
```

Its own settings (port, login, session length) are in `mock-fiery.properties` next to `Mock Fiery.exe`.

## Windows .exe (recommended)

`build-exe.bat` builds a ready-to-run folder with **Java bundled inside**, so the
shop PC needs nothing installed:

```
dist\Fiery Hot Folder\
    Fiery Hot Folder.exe          <- double-click to start
    fiery-hotfolder.properties    <- the external config, next to the exe
    app\  runtime\                <- the app and its own Java (don't touch)
```

**Option A: build on any Windows PC (once)**

1. Install **JDK 21** (e.g. Eclipse Temurin, tick "Add to PATH") and **Apache Maven** (add its `bin` to PATH).
2. Unzip this project and double-click **`build-exe.bat`**.
3. Edit `dist\Fiery Hot Folder\fiery-hotfolder.properties`, then copy the whole
   `Fiery Hot Folder` folder to the shop PC (e.g. `C:\Program Files\Fiery Hot Folder`).

`build-exe.bat installer` also creates a normal **Setup .exe** with Start-menu and
desktop shortcuts. This needs the free WiX Toolset 3 on the build PC.

**Option B: let GitHub build it (nothing to install)**

Push the project to a GitHub repository, open **Actions → Build Windows EXE → Run
workflow**, and download the `FieryHotFolder-windows` zip (and `MockFiery-windows`, the test
server) when it finishes.

To start the app with Windows, press Win+R, type `shell:startup`, and put a
shortcut to `Fiery Hot Folder.exe` there.

## Build and run as a jar (developers)

```bash
mvn -q package
java -jar target/fiery-hotfolder-1.0.0-all.jar
```

## Fiery driver → PostScript → API

```
Image / PDF ─▶ Java draws the pages ─▶ Fiery driver ─▶ PostScript written to a temp file
            ─▶ POST /live/api/v5/jobs   file = PostScript, filename = original name (photo.jpg),
                                        queue = hold, pcmid = paper, num copies = N
            ─▶ Fiery job id ─▶ log ─▶ file deleted
```

* **Convert with:** your Fiery printer, so the conversion is the same as Ctrl+P. The
  other choice, *Java built-in PostScript*, needs no driver but produces generic
  PostScript without Fiery driver features.
* The page size comes from the Paper Catalog entry (`Dimension`), and the driver
  snaps it to its own matching paper.
* The driver writes 1 copy; the API sends the number of copies.
* The driver also writes its default media settings into the PostScript. Test one
  job to confirm the Paper Catalog choice (`pcmid`) wins on your Fiery.
* PostScript files are uploaded unchanged.

## Printing through the driver (exactly like Ctrl+P)

### What happens

```
Image file ─▶ Java decodes it and draws it on the page (fit / fill / actual size, auto-rotate)
           ─▶ Windows spooler ─▶ Fiery PostScript driver (adds paper, copies, driver settings)
           ─▶ Fiery printer queue (e.g. Hold) ─▶ the job appears in Command WorkStation
```

This is the same as pressing Ctrl+P in a photo viewer: the Fiery never receives
the PNG/JPG itself, it receives the driver's PostScript. Any format Java can read
works: JPG (including CMYK), PNG, TIFF, BMP, GIF, WebP, PSD. PDFs are printed page
by page through the same driver. PostScript files can't be drawn, so send those
with the API method.

### One-time setup on the shop PC

1. Install the **Fiery printer driver** for your Iridesse from Fiery WebTools
   (`https://<fiery-ip>` → Downloads) or Xerox's website.
2. Add the Fiery as a Windows printer that points to its **Hold** queue, and name it
   clearly, e.g. `Iridesse Fiery Hold`.
3. In `fiery-hotfolder.properties`, set `driver.printer-name=Iridesse Fiery Hold`
   (exactly as shown in Settings → Printers) and, optionally, `send.method=DRIVER`.

### In the "Files waiting" window

* **Send via:** Printer driver.
* **Printer and Paper size:** the lists come from the driver, the same ones the Ctrl+P dialog shows.
* **Combine ticked images into one job:** all ticked images become pages of one job.
  Untick it to get one job per file.
* **Show Windows print dialog:** shows the normal Ctrl+P dialog once per batch. Its
  **Properties** button opens the Fiery driver, where you set media type, paper
  catalog entry, tray, finishing and so on. Those choices apply to every file in the batch.
* **Image sizing and Copies:** as before.

After the driver accepts each job, the file is deleted (or moved to Sent/), and a
`PRINTED (driver)` entry goes into the log. A job handed to the Windows spooler
has no Fiery job id; check Command WorkStation's Hold queue for it.

### Media type and weight without the dialog

Java can only set the paper **size** directly. For media type and weight
(e.g. 300 gsm Glossy), choose one of these:

* tick **Show Windows print dialog** and pick them under Properties, or
* set them as the printer's defaults (Settings → Printers → Printing preferences), or
* add one Windows printer per Fiery **Virtual Printer** (each Virtual Printer has its
  own media preset) and choose that printer in the window.

### Large images

Very large images need memory while they are drawn. If you print big posters,
start the app with more memory: `java -Xmx2g -jar fiery-hotfolder-1.0.0-all.jar`.

## External configuration: `fiery-hotfolder.properties`

The hot folder and every Fiery API setting live in this plain-text file, **outside
the jar**, so they can be changed without rebuilding.

**Where the app looks for it (first match wins):**

1. `java -Dfiery.config=D:/config/fiery-hotfolder.properties -jar ...`
2. Environment variable `FIERY_HOTFOLDER_CONFIG`
3. Next to `Fiery Hot Folder.exe`
4. Next to the jar
5. The current working folder

If the app can't find the file, it creates a commented template next to the exe (or jar)
and asks you to fill it in.

**To change a setting:** click **Edit config** (opens it in Notepad), save, then
click **Reload config**. You don't need to restart, except after changing
`fiery.trust-self-signed-certificate`. The main window shows which file is in use.

| Key | Default | Meaning |
|---|---|---|
| `hotfolder.path` | `C:/FieryHotFolder` | Folder to watch (forward slashes recommended; backslashes also work) |
| `hotfolder.process-existing-files` | `true` | Offer files already in the folder at startup |
| `hotfolder.after-send` | `DELETE` | `DELETE` or `MOVE_TO_SENT` after the Fiery accepts a job |
| `fiery.base-url` | `https://192.168.1.50` | Fiery address |
| `fiery.api-key` / `fiery.username` / `fiery.password` | — | Credentials. `${ENV_VAR}` placeholders allowed, e.g. `fiery.password=${FIERY_PASSWORD}` |
| `fiery.trust-self-signed-certificate` | `true` | Accept the Fiery's self-signed certificate |
| `fiery.api.base-path` | `/live/api/v5` | API root |
| `fiery.api.login` / `logout` / `jobs` / `papercatalog` | `/login` … | Endpoint paths, joined to base-url + base-path |
| `fiery.timeout.connect-seconds` / `request-seconds` / `upload-seconds` | 15 / 60 / 300 | Timeouts |
| `image.mode` | `CONVERT_TO_PDF` | or `SEND_ORIGINAL` (test whether your Fiery accepts JPG) |
| `quality.min-dpi` / `quality.warn-dpi` | 200 / 300 | Block / warn thresholds for images |
| `send.method` | `API` | Default method in the window: `API`, `DRIVER_API` or `DRIVER` (staff can switch per batch) |
| `api.use-original-filename` | `true` | Upload with the original file name and extension (e.g. `photo.jpg`) |
| `driver.printer-name` | — | Windows printer for the driver methods, e.g. `Iridesse Fiery Hold` (also the default for "Convert with") |
| `driver.paper` | — | Default paper size name from the driver list (e.g. `iso-a4`); blank = driver default |
| `driver.combine-into-one-job` | `false` | Default for "Combine ticked images into one job" |
| `driver.show-print-dialog` | `false` | Default for "Show Windows print dialog" |

The queue is always **hold** and isn't configurable.
Mistyped values (e.g. `upload-seconds=abc`) are reported in a popup, and their defaults are used.

The app also remembers the last paper and sizing in
`C:\Users\<you>\.chithira-fiery\state.properties`. That file only holds those
two choices and is safe to delete.

## The paper list

* Loaded from `GET /live/api/v5/papercatalog` when the app starts, then cached in
  `C:\Users\<you>\.chithira-fiery\papercatalog.json`.
* If the Fiery is off when the app starts, the cached copy is used.
* After you add papers in Command WorkStation, click **Refresh papers** (or ↻ in the window).
* The paper's `id` is sent as `pcmid`. Its `Dimension` (points, e.g. `936.0 1368.0`
  = 13 × 19 in) is the page size used when converting images.

## Image sizing (images only)

| Option | Result |
|---|---|
| Fit to paper | Whole image visible; white margins if proportions differ |
| Fill paper | Covers the sheet; edges cropped |
| Actual size @300 DPI | Page = image size; the Fiery places it on the chosen paper |

Landscape images are rotated automatically to match portrait paper.
The quality column turns red below the "Block below DPI" setting (default 200);
those files can only be sent with "Allow low resolution" ticked.

## Log file

`C:\Users\<you>\.chithira-fiery\logs\fiery-jobs-YYYY-MM.log` (one file per month,
**Open log file** button). Example:

```
2026-11-20 07:33:08 | SENT | Brochure.pdf (2.1 MB)
  job name: Brochure | paper: 13x19 300 GSM new [5598] | sizing: FIT | copies: 3 | queue: hold | instructions: cut to size
  submitted as: Brochure.pdf
  fiery job id: 9001
  file: deleted from hot folder
  response: {"data":{"item":{"id":"9001", ...}}}
```

The file is deleted only **after** the Fiery returns success. Failed files are
never deleted: they go to `Failed/` with the error.

## Things to confirm on your Fiery

* **Job id:** the app takes the first `"id"` in the upload response. Compare with Command WorkStation for the first few jobs.
* **Gold/silver:** images carry only colour. Metallic jobs need a designer-made PDF with spot-colour swatches; PDFs are sent unchanged.
* **Password:** use `fiery.password=${FIERY_PASSWORD}` with a Windows environment variable, or keep the properties file in a folder only staff can read. Use an operator account rather than admin.
