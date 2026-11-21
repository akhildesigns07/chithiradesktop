CHITHIRA PRINT HUB - INSTALLING ON THE SHOP PC
==============================================

The Print Hub runs on one PC (the "host"). Everyone else opens it in a web browser.
Java is included - you only need to install PostgreSQL (the database) once.

1. INSTALL POSTGRESQL (once)
   - Download PostgreSQL 16 for Windows: https://www.postgresql.org/download/windows/
   - Run the installer with the default options. When it asks for a password for the
     "postgres" user, choose one and WRITE IT DOWN. Port 5432 (default).
   - You can skip "Stack Builder" at the end.

2. COPY THE PRINT HUB
   - Unzip this folder to C:\PrintHub   (keep it out of "Program Files").

3. SET UP THE DATABASE (once)
   - Double-click setup-database.bat
   - Choose a password for the Print Hub (letters and digits, at least 8),
     then type the "postgres" password from step 1.

4. INSTALL THE SERVICE (once)
   - Right-click install-service.bat > Run as administrator.
   - It starts the Print Hub, opens the browser and shows FIRST-LOGIN.txt with the
     first password for the user "admin". You will be asked to choose your own.
   - From now on the Print Hub starts by itself whenever the PC starts.

5. USE IT FROM OTHER PCs
   - Find this PC's address: open Command Prompt, type  ipconfig  , look for "IPv4 Address",
     e.g. 192.168.1.10. In your router, reserve that address for this PC so it never changes.
   - On the other PCs open  http://192.168.1.10:8080
   - Windows must treat the shop network as "Private" (Settings > Network > Properties).
   - Desktops (Fiery Hot Folder): set hub.base-url=http://192.168.1.10:8080 in
     fiery-hotfolder.properties and the API key from Admin > Desktops & API keys.

SETTINGS
   - config\application.yml : port, shop name, Fiery connection. Edit with Notepad,
     then right-click restart-service.bat > Run as administrator.
   - config\application.properties : the database password (written by setup-database.bat).

EVERYDAY
   - Start / stop: Windows "Services" app > "Chithira Print Hub", or restart-service.bat.
   - Logs: the logs folder (send these if something goes wrong).
   - Backups: back up the PostgreSQL database "printhub" (e.g. pgAdmin > Backup).

UPDATING TO A NEW VERSION
   1. Right-click uninstall-service.bat > Run as administrator  (your data stays in PostgreSQL).
   2. Unzip the new version over C:\PrintHub, but KEEP your config folder.
   3. Right-click install-service.bat > Run as administrator.
   The database is upgraded automatically on start.

REMOVING
   Right-click uninstall-service.bat > Run as administrator. Uninstall PostgreSQL separately
   if you also want to delete the data.
