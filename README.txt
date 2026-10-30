UNCLE DOGS PARKING
Departments Parking Slot Management System
Java 17 | Built-in HttpServer | Single-page web application | Swing admin window

============================================================
1. RUN LOCALLY
============================================================
1. Install a Java 17 or newer JDK and make sure both java and javac work in Command Prompt.
2. Extract this entire UncleDogsParking folder.
3. Double-click run.bat on Windows. On macOS/Linux, run ./run.sh from a terminal.
4. Open the Network URL printed in the console on a device connected to the same non-guest Wi-Fi. Do not use localhost from your phone.
5. If the PC website works but the phone cannot connect, run allow-lan.bat once and approve the Windows administrator prompt. The rule is scoped to the local subnet. Follow LOCAL_NETWORK_SETUP.txt.
6. If the Supabase connection fails, run check-supabase.bat. It checks DNS and network ports without reading or printing the password.
7. This ZIP has ADMIN_KEY configured in config.txt as the key you requested. Keep config.txt private and do not upload it to GitHub. You can also set ADMIN_KEY as a private environment variable on the online host.
8. Keep the host PC and its server window running while testing. Local CSV files are created under data/.

The default port is 8080. If another program uses it, change PORT in config.txt or set the PORT environment variable before running. If Supabase mode is configured but the database cannot be reached, the app stops by default rather than saving a separate set of records in CSV. A clearly warned, opt-in local fallback is available only for temporary LAN testing.

LOGO
The resized black-and-white logo is included at assets/logo.jpg and is used by the website header, Swing admin window, and printable QR labels. If the file is removed, the website falls back to the dog emoji and text.

============================================================
2. TEST THE PARKING FLOW BEFORE PRINTING
============================================================
1. Open the website on your phone. Create an account or sign in.
2. Select an available slot. Choose Car or Motorcycle, enter the plate twice, choose Park now, and tap Prepare entry and scan ENTER QR.
3. Use Admin -> unlock -> View / Print QR for that slot. Display the ENTER QR on another screen or print it. On a phone, use the public HTTPS site for camera access; see the camera note below.
4. Tap Scan QR inside the website, or scan the slot code with Google Lens and tap its link. Enter only the vehicle plate number. The server verifies it against the reservation or active parking session, then shows “Entry Success” or “Leave Success”. The slot and audit database update only after the plate is accepted.

When a reservation is made, the driver enters and confirms their plate. At arrival and departure, the QR flow asks for the plate once and checks it against the database. The in-site scanner also requires the signed-in account to match the reservation/session owner.

============================================================
3. CAMERA / HTTPS IMPORTANT NOTE
============================================================
Browsers restrict phone camera access to secure contexts. On phones, an ordinary LAN address such as http://192.168.x.x:8080 usually cannot open the camera. Use the deployed HTTPS Vercel address or an HTTPS tunnel (Cloudflare Tunnel/ngrok) for the built-in scanner. This is a browser security rule, not a Java setting.

Pinned browser libraries:
- qrcodejs 1.0.0 via cdnjs: QR printing needs this script to load.
- html5-qrcode 2.3.8 via cdnjs: used as the camera scanner when available.
- When that scanner library is unavailable, the app tries the browser's native BarcodeDetector QR API, which is only supported by some browsers (commonly Chromium-based browsers). If neither scanner is available, use a supported browser and HTTPS.

The public website QR only opens the site. Version 1.8.3 ENTER/LEAVE QR labels encode full HTTPS-capable website links (/enter/N and /leave/N), so Google Lens can open them as links instead of searching plain text. Opening a slot QR shows a minimal plate form. A correct plate must match the eligible reservation for entry or the active session for exit. The in-site camera reads the same URLs and then shows a one-field plate verification dialog; it requires the user account to own the reservation/session. Link previews/prefetches are ignored. Exit closes the active session, frees the slot, and retains the audit visit in history.

============================================================
4. SUPABASE POSTGRESQL (SHARED ONLINE DATA)
============================================================
This package is preconfigured with the host and username of your existing Supabase project. On Windows, run.bat asks for the database password privately if DATABASE_PASSWORD is blank. The password is not saved by the prompt. Local CSV mode is only used when DATABASE_URL is deliberately blank. Version 1.8.3 creates the udp_feedback table and indexes automatically on PostgreSQL startup; local CSV mode writes feedback to data/feedback.csv.

1. Use your EXISTING Supabase project. Do not create another project.
2. In Supabase, open Connect and choose the Session pooler connection for PostgreSQL if the direct connection is not reachable from your host. Copy its exact host, port, and username. Use the JDBC URL format:
   jdbc:postgresql://HOST:PORT/postgres?sslmode=require
3. Set these variables locally in config.txt, or as environment variables on the cloud host:
   DATABASE_URL=jdbc:postgresql://HOST:PORT/postgres?sslmode=require
   DATABASE_USER=YOUR_SUPABASE_DATABASE_USER
   DATABASE_PASSWORD=YOUR_SUPABASE_DATABASE_PASSWORD
4. Instead of a JDBC URL, DATABASE_URL may be a standard postgresql://user:password@host:port/postgres?sslmode=require connection URI; the app extracts the credentials if DATABASE_USER and DATABASE_PASSWORD are left blank.
5. Start the app. The app creates its required udp_* tables and indexes automatically. Do not expose your database password in website JavaScript or a public Git repository.

Setting DATABASE_URL enables PostgreSQL mode. If the connection is misconfigured or blocked, the app stops with a clear message instead of silently switching to local CSV. This prevents users from thinking remote reservations were saved when they were not.

RESERVATION EXPIRY
- A reservation that has not been checked in is marked EXPIRED at or after 90 minutes past its reserved arrival time. It is not deleted, so the audit history remains intact.
- Local/always-on server maintenance checks every 30 seconds, and the app also checks on each relevant request.
- For Vercel, enable Supabase Cron in your EXISTING Supabase project, then run database/setup_reservation_expiry.sql once in its SQL Editor. The database job runs every minute, even while Vercel is scaled down. The slot becomes Available once no active session or other active reservation is blocking it.
- Confirm the job exists using the verification query included at the bottom of the SQL file.

============================================================
5. VERCEL DEPLOYMENT (PUBLIC HTTPS WEBSITE)
============================================================
This version includes Dockerfile.vercel for Vercel's container-based Functions. It runs the same Java 17 HttpServer, uses the PORT supplied by the platform (defaulting to 80), and stores all durable information in the EXISTING Supabase PostgreSQL project. Do not create another Supabase project.

Before deploying:
- Use the current v1.8.3 project files. The repository root must directly contain Dockerfile.vercel, src/, and assets/ (do not upload an extra parent folder containing the project).
- Keep the GitHub repository private for a school project. The .gitignore excludes config.txt, data CSVs/keys, local build output, and local environment files. Check GitHub Desktop's Changes list before committing: config.txt and data/*.csv/data/*.key must not be listed. If config.txt was already tracked in an older repository, remove it from tracking before pushing.
- Do not copy the local config.txt into the Vercel image. Dockerfile.vercel intentionally does not copy it.

Deploy:
1. Push the project files to a private GitHub repository. Then open https://vercel.com/new and import that repository.
2. In Project Settings / Environment Variables, add the following for Production (and Preview only if you want previews to connect to the database):
   DATABASE_URL       = the JDBC URL for your EXISTING Supabase database, for example jdbc:postgresql://YOUR-POOLER-HOST:5432/postgres?sslmode=require
   DATABASE_USER      = your existing Supabase database username
   DATABASE_PASSWORD  = your existing Supabase database password
   ADMIN_KEY          = your chosen private admin key (at least 16 characters)
   SHOW_SWING         = false
   OPEN_BROWSER       = false
3. Enable / expose Vercel System Environment Variables so the app can use VERCEL_PROJECT_PRODUCTION_URL for production QR links and VERCEL_URL for preview links. If those variables are unavailable, the app derives the current HTTPS hostname from the incoming request. After the first deployment, it is still recommended to set BASE_URL to the final public HTTPS production domain and redeploy before printing production QR labels.
4. Deploy. Vercel detects Dockerfile.vercel and builds the Java 17 container. Check the deployment logs; startup must identify Supabase/PostgreSQL storage. If DATABASE_URL or ADMIN_KEY are missing, this version deliberately stops with a clear configuration error instead of silently using temporary CSV data.
5. Open the exact public https://...vercel.app address from your phone using mobile data. Register or sign in, create a test reservation, verify it appears in your existing Supabase Table Editor, and test ENTER and LEAVE before printing labels.
6. Reprint website, ENTER, and LEAVE QRs from the final production domain. Do not use localhost or a private 192.168.x.x address for remote access. QR labels generated from a preview deployment should not be used for production.

Vercel-specific behavior:
- Vercel's Dockerfile-based Functions run stateless container instances. User and admin login cookies in v1.8.3 are HMAC-signed and stateless so they can validate across cold starts and different instances without relying on an in-memory session map. They expire after 12 hours and are signed with ADMIN_KEY; rotating ADMIN_KEY invalidates existing cookies.
- Parking records, users, bookings, sessions, entry intents, and feedback persist in Supabase, never in the container filesystem.
- Vercel uses HTTPS, which allows supported mobile browsers to request camera permission for the in-site QR scanner. Camera permission still needs to be granted.
- Keep DATABASE_PASSWORD and ADMIN_KEY in Vercel Environment Variables only. Never commit them to GitHub.

FREE PLAN NOTE
Vercel Hobby and Supabase Free can be used for a student demonstration within the current plan's usage limits, but free plans do not guarantee uninterrupted production availability. Vercel container functions scale down when idle and Supabase Free has usage and backup limitations. Check current plans and quotas before relying on the system for real campus operations:
https://vercel.com/docs/functions/limitations
https://vercel.com/docs/environment-variables/system-environment-variables
https://supabase.com/pricing

The local PC application is separate from the deployed website. For shared online reservations, all users must use the single public Vercel HTTPS URL and the same existing Supabase database. Local CSV mode is only for offline development/tests and does not sync with Supabase. Local LAN access is for devices on the same Wi-Fi; phone camera access requires HTTPS or localhost.

LOCAL NETWORK TROUBLESHOOTING
- Wait for the console banner “Uncle Dogs Parking is running” first. If startup stopped on a Supabase connection error, the website is not being served yet.
- Use the Network URL printed by the app, not localhost. The console lists alternative private IPv4 addresses when more than one adapter is active.
- If the PC works but the phone does not, run allow-lan.bat once. If it still fails, check Guest Wi-Fi/client isolation and compare the phone and PC network. Test http://<LAN-IP>:<PORT>/healthz from the phone; it should return {"status":"ok"}.

============================================================
6. ADMINISTRATION AND SECURITY
============================================================
- ADMIN_KEY is generated randomly for local runs when blank. In online hosting, define ADMIN_KEY as a private environment variable.
- User passwords are salted and hashed with PBKDF2WithHmacSHA256. Passwords are not stored as plain text.
- Users may cancel their own unused reservations. Only an administrator can manually check out another user's session or disable a slot.
- Public slot state does not expose plates. Plates are visible only to the signed-in owner and administrator views.
- User feedback requires a signed-in account. Only the unlocked web admin session or the host-PC Swing admin window can list, change feedback status, or write private admin notes. User-submitted messages are HTML-escaped before display.
- PostgreSQL uses the udp_feedback table; local testing uses data/feedback.csv. The admin note is internal and is not editable by normal users.
- The server ignores preview/prefetch scan requests. QR payloads never perform an action merely by opening a link.
- Reservations are checked transactionally in PostgreSQL, using per-slot database locks and a unique index preventing two active sessions for one slot.
- Reservation duration is used to detect schedule conflicts. It does not create parking fees and does not automatically remove a vehicle at the estimated end time.
- If a late driver would overlap the next reservation, entry is rejected and they must contact an administrator.
- For real campus use, add school identity/email verification and a formal privacy/retention policy before inviting everyone. This student implementation does not send verification emails.

============================================================
7. OOP PILLARS
============================================================
- Encapsulation: private model fields, validation in Vehicle and ParkingSlot, immutable records.
- Inheritance: Car and Motorcycle extend the abstract Vehicle class.
- Polymorphism: overridden getVehicleType() and RecordStorage implementations used through the same interface.
- Abstraction: Vehicle and RecordStorage define the types/contracts that the rest of the program uses.

============================================================
8. MAIN STRUCTURE
============================================================
Dockerfile.vercel (Vercel HTTP container)
Dockerfile (generic Java container)
src/Main.java
src/model/Vehicle.java, Car.java, Motorcycle.java, ParkingSlot.java, ParkingRecord.java, Reservation.java
src/service/ParkingLot.java, AppConfig.java, PasswordUtil.java
src/storage/ParkingStore.java, RecordStorage.java, FileRecordStorage.java, PostgresRecordStorage.java, CsvUtil.java
src/web/ParkingServer.java, PageBuilder.java
src/gui/AdminWindow.java
assets/logo.jpg (included, resized to 600x600)
config.txt
run.bat / run.sh
Dockerfile / Dockerfile.vercel

automated tests: tests/smoke-test.sh
Version 1.8.0 adds a database-backed feedback inbox, smooth loading and skeleton states, accessible responsive motion, and an asynchronous desktop feedback dashboard, while preserving the prior QR/reservation behavior. The 1.6.x series fixed login verification, QR routing/timing, status priority, and safe slot deletion.


VERSION 1.8.3 NOTES
- Added a signed-in user feedback form with categories, 1–5 rating, validation, and a reference number after submission.
- Added an admin-only feedback inbox with NEW / REVIEWING / RESOLVED / DISMISSED states and private admin notes. Access is checked server-side; the API requires an unlocked admin session.
- PostgreSQL startup safely creates udp_feedback and indexes on the existing database. CSV mode uses data/feedback.csv. No new Supabase project or manual table setup is required.
- Added shimmer skeletons for initial slot and feedback loading, smoother transitions, active navigation underline, reduced-motion support, and clear loading/connection status.
- Redesigned the Swing admin window with background workers for database refreshes, slot actions, and feedback loading/updates to keep the UI responsive. The desktop window includes Add Slot, Feedback Inbox, Open Website, Copy URL, safe slot management, and manual check-out.
- ENTER and LEAVE QR codes contain actual website links. Google Lens opens the link, and the in-site camera scans the same QR.
- Both flows ask for only the vehicle plate. Entry verifies the eligible reservation (or the signed-in user's prepared walk-in); leaving verifies the active session. Success messages are “Entry Success” and “Leave Success”.
- Entry/exit actions update udp_sessions and udp_entry_intents only after verification; successful leave sets the audit status to COMPLETED and frees the slot while retaining visit history.
- QR GET requests and preview/prefetch requests are side-effect-free. Public plate entry is rate-limited for repeated invalid attempts.
- Slot status priority is OCCUPIED, then UNAVAILABLE, RESERVED, AVAILABLE. An active session is shown as Occupied even if a future booking also exists.
- Use HTTPS for phone camera access. The pinned HTML5 QR scanner library is loaded from a CDN; device camera permission is required.
- Added an Admin-only “Export all visits · CSV” download. It includes every active and completed parking visit with user name/email, plate, vehicle type, slot, entry/exit timestamps, duration, status, and reservation reference. The on-screen history remains limited to the last 100; the CSV is not limited to 100. It requires an unlocked admin session.

VERSION 1.6 NOTES
- Retained the resized black-and-white logo in the web, Swing, website QR, and slot QR interfaces.
- Fixed login verification and retained safe deletion only for never-used slots.
- Added responsive animation, loading indicators, an animated navigation underline, and reduced-motion accessibility support.
- Arrival buttons include 30 minutes, 1 hour, 1 hour 30 minutes, 2 hours, and 3 hours, with a custom date/time option. Expected parking duration is used for scheduling only and does not create fees.
- Added scanner CDN fallback loading and clearer camera errors. Phone camera access requires HTTPS or localhost.

Supabase verification query for user feedback (run only in the authenticated Supabase SQL editor):
SELECT f.id, u.email, u.display_name, f.category, f.rating, f.message, f.status, f.admin_note, f.created_at, f.updated_at
FROM public.udp_feedback f JOIN public.udp_users u ON u.id=f.user_id
ORDER BY f.created_at DESC;

Supabase verification query for QR entry intent/audit:
SELECT i.user_id, u.email, u.display_name, i.slot_number, i.plate, i.vehicle_type, i.status, i.entered_at
FROM public.udp_entry_intents i JOIN public.udp_users u ON u.id=i.user_id
ORDER BY i.entered_at DESC NULLS LAST;

SUPABASE CONNECTION TROUBLESHOOTING (v1.8.3)
- The app uses the exact DATABASE_URL and DATABASE_USER from Supabase Connect. Do not guess the pooler hostname; copy it from the project's Connect dialog.
- For the shared session pooler use port 5432. If a network/SSL timeout occurs, this build automatically tries the shared transaction pooler on port 6543 for Supabase pooler hosts and disables server-side prepared statements for that fallback.
- This fallback cannot bypass an ISP/firewall that blocks both PostgreSQL ports. Run check-supabase.bat to test DNS and TCP access to the configured port and the alternate pooler port; the test never asks for or prints the database password.
- If TCP is reachable but SSL still times out, verify the endpoint in Supabase Connect, confirm the project is active, then try a different network such as a phone hotspot. Do not repeatedly reset the password for an SSL handshake timeout; password authentication has not happened yet.
- If you only need to test phone access while Supabase is unreachable, set ALLOW_LOCAL_CSV_FALLBACK=true in config.txt and restart. The website then uses local CSV files and shows a warning banner. This is TEMPORARY LOCAL TEST MODE: changes do not sync to Supabase. Set it back to false before using the shared database or deploying online.

PHONE ACCESS CHECKLIST (v1.8.3)
- The site only serves after startup completes and the console says "Uncle Dogs Parking is running". If database startup fails, fix the database connection or explicitly enable local test fallback as described above before testing the phone.
- Connect phone and PC to the same non-guest Wi-Fi. Use the exact Network URL printed in the console, not localhost and not a stale IP from a previous run.
- Run allow-lan.bat once if Windows Firewall blocks access. On the phone, open http://<LAN-IP>:<PORT>/healthz. If it fails, test Guest Wi-Fi/client isolation or a hotspot router; the Java app cannot override Wi-Fi client isolation.
- The website can load over local HTTP, but phone camera access normally requires HTTPS. Use the final public HTTPS deployment or a trusted HTTPS tunnel for in-site QR scanning from the phone.

