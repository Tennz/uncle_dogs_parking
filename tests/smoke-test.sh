#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
command -v curl >/dev/null || { echo "curl is required."; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required."; exit 1; }
[[ -d "$ROOT/out" ]] || { echo "Compile first: find src -name '*.java' -print > sources.txt && javac --release 17 -d out @sources.txt"; exit 1; }
PORT="${TEST_PORT:-18789}"
TMP="$(mktemp -d)"
KEY='SmokeTestAdminKey-2026-ChangeThis'
mkdir -p "$TMP/data" "$TMP/assets"
cp "$ROOT/assets/logo.jpg" "$TMP/assets/logo.jpg" 2>/dev/null || true
cat > "$TMP/config.txt" <<'CONFIG'
PORT=8080
BASE_URL=
ADMIN_KEY=
DATABASE_URL=
DATABASE_USER=
DATABASE_PASSWORD=
DATA_DIR=data
SHOW_SWING=false
OPEN_BROWSER=false
CONFIG
export PORT DATA_DIR="$TMP/data" ADMIN_KEY="$KEY" SHOW_SWING=false BASE_URL="http://127.0.0.1:$PORT" DATABASE_URL=
(cd "$TMP" && java -cp "$ROOT/out" Main) >"$TMP/server.log" 2>&1 &
PID=$!
cleanup(){ kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true; if [[ "${KEEP_TEST_FILES:-0}" == "1" ]]; then echo "Test files retained at $TMP"; else rm -rf "$TMP"; fi; }
trap cleanup EXIT
ready=0
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
if [[ "$ready" != "1" ]]; then cat "$TMP/server.log"; echo "FAIL: server did not start"; exit 1; fi
curl -fsS "http://127.0.0.1:$PORT/healthz" >/dev/null
# When a phone accesses the local site by a private IPv4 address, QR URLs must use that host.
LAN_HOST_STATE="$(curl -fsS -H "Host: 192.168.1.77:$PORT" "http://127.0.0.1:$PORT/api/state")"
echo "$LAN_HOST_STATE" | python3 -c "import json,sys; d=json.load(sys.stdin); assert d['baseUrl']=='http://192.168.1.77:$PORT', d.get('baseUrl')" || { echo "FAIL: LAN host did not flow into QR/base URL generation"; exit 1; }
LOGO_CODE="$(curl -sS -o "$TMP/logo-served.jpg" -w '%{http_code}' "http://127.0.0.1:$PORT/assets/logo.jpg")"
[[ "$LOGO_CODE" == "200" && -s "$TMP/logo-served.jpg" ]] || { echo "FAIL: logo asset route did not serve the logo"; exit 1; }
LOGGED_OUT_QR="$(curl -sS -w '\n%{http_code}' "http://127.0.0.1:$PORT/enter/1")"
echo "$LOGGED_OUT_QR" | grep -q 'Vehicle plate number' || { echo "FAIL: public QR should show plate verification form: $LOGGED_OUT_QR"; exit 1; }
echo "$LOGGED_OUT_QR" | grep -q '200$' || { echo "FAIL: public QR form status: $LOGGED_OUT_QR"; exit 1; }
EMAIL="smoke$(date +%s)@example.com"
EMAIL2="other$(date +%s)@example.com"
COOKIE1="$TMP/user1.cookies"; COOKIE2="$TMP/user2.cookies"; LOGINCOOKIE="$TMP/login.cookies"; ADMINCOOKIE="$TMP/admin.cookies"
post(){ curl -sS -b "$1" -c "$1" -w '\n%{http_code}' -X POST "http://127.0.0.1:$PORT$2" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode "$3" ${4:+--data-urlencode "$4"} ${5:+--data-urlencode "$5"} ${6:+--data-urlencode "$6"} ${7:+--data-urlencode "$7"} ${8:+--data-urlencode "$8"} ${9:+--data-urlencode "$9"}; }
REG1="$(post "$COOKIE1" /api/auth/register 'displayName=Smoke Test' "email=$EMAIL" 'password=SmokeTestPass123!')"
echo "$REG1" | grep -q '201' || { echo "FAIL: first registration: $REG1"; exit 1; }
REG2="$(post "$COOKIE2" /api/auth/register 'displayName=Second User' "email=$EMAIL2" 'password=OtherTestPass123!')"
echo "$REG2" | grep -q '201' || { echo "FAIL: second registration: $REG2"; exit 1; }
# Explicitly test login using a fresh cookie jar; this caught the v1.5 PBKDF2 verification defect.
LOGIN="$(curl -sS -b "$LOGINCOOKIE" -c "$LOGINCOOKIE" -w '\n%{http_code}' -X POST "http://127.0.0.1:$PORT/api/auth/login" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode "email=$EMAIL" --data-urlencode 'password=SmokeTestPass123!')"
echo "$LOGIN" | grep -q '200' || { echo "FAIL: login with valid credentials: $LOGIN"; exit 1; }
ME="$(curl -fsS -b "$LOGINCOOKIE" "http://127.0.0.1:$PORT/api/me")"
echo "$ME" | grep -qi "$EMAIL" || { echo "FAIL: login cookie not authenticated: $ME"; exit 1; }
BADLOGIN="$(curl -sS -w '\n%{http_code}' -X POST "http://127.0.0.1:$PORT/api/auth/login" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode "email=$EMAIL" --data-urlencode 'password=WrongPassword000!')"
echo "$BADLOGIN" | grep -q '401' || { echo "FAIL: wrong password should be rejected: $BADLOGIN"; exit 1; }
SLOTS="$(curl -fsS "http://127.0.0.1:$PORT/api/state" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len(d["slots"]))')"
[[ "$SLOTS" == "20" ]] || { echo "FAIL: expected default slot count 20, got $SLOTS"; exit 1; }
NOW_MINUS_ONE="$(python3 -c 'import datetime; print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(minutes=1)).strftime("%Y-%m-%dT%H:%M:%SZ"))')"
RES="$(post "$COOKIE1" /api/reserve 'slotNumber=1' 'plate=ABC 1234' 'plateConfirm=ABC 1234' 'vehicleType=CAR' "reservedAt=$NOW_MINUS_ONE" 'durationMinutes=60')"
echo "$RES" | grep -q '201' || { echo "FAIL: reservation creation: $RES"; exit 1; }
ENTER="$(post "$COOKIE1" /api/scan 'action=ENTER' 'slotNumber=1' 'plate=ABC 1234')"
echo "$ENTER" | grep -q '200' || { echo "FAIL: entry: $ENTER"; exit 1; }
STATE="$(curl -fsS "http://127.0.0.1:$PORT/api/state")"
echo "$STATE" | python3 -c 'import json,sys; s=next(x for x in json.load(sys.stdin)["slots"] if x["number"]==1); assert s["status"]=="OCCUPIED"' || { echo "FAIL: slot did not turn occupied"; exit 1; }
WRONG="$(post "$COOKIE2" /api/scan 'action=LEAVE' 'slotNumber=1' 'plate=ABC 1234')"
echo "$WRONG" | grep -q '401' || { echo "FAIL: another account was allowed to check out the slot: $WRONG"; exit 1; }
LEAVE="$(post "$COOKIE1" /api/scan 'action=LEAVE' 'slotNumber=1' 'plate=ABC 1234')"
echo "$LEAVE" | grep -q '200' || { echo "FAIL: leave: $LEAVE"; exit 1; }
# A booking can be entered using only the plate number from either the in-site scanner API or Google Lens form.
QR_RES="$(post "$COOKIE1" /api/reserve 'slotNumber=3' 'plate=QR12345' 'plateConfirm=QR12345' 'vehicleType=CAR' "reservedAt=$NOW_MINUS_ONE" 'durationMinutes=45')"
echo "$QR_RES" | grep -q '201' || { echo "FAIL: create QR reservation: $QR_RES"; exit 1; }
QR_FORM="$(curl -sS -w '\n%{http_code}' "http://127.0.0.1:$PORT/enter/3")"
echo "$QR_FORM" | grep -q 'Vehicle plate number' || { echo "FAIL: Google Lens ENTER URL did not show plate form: $QR_FORM"; exit 1; }
echo "$QR_FORM" | grep -q '200$' || { echo "FAIL: Google Lens form GET: $QR_FORM"; exit 1; }
QR_WRONG_PLATE="$(curl -sS -X POST "http://127.0.0.1:$PORT/enter/3" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'plate=BAD9999')"
echo "$QR_WRONG_PLATE" | grep -q 'does not match' || { echo "FAIL: wrong plate was not rejected for reserved entry: $QR_WRONG_PLATE"; exit 1; }
QR_ENTER="$(curl -sS -w '\n%{http_code}' -X POST "http://127.0.0.1:$PORT/enter/3" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'plate=QR12345')"
echo "$QR_ENTER" | grep -q 'Entry Success' || { echo "FAIL: direct ENTER QR plate verification: $QR_ENTER"; exit 1; }
echo "$QR_ENTER" | grep -q '200$' || { echo "FAIL: direct QR entry status: $QR_ENTER"; exit 1; }
STATE_ENTRY="$(curl -fsS "http://127.0.0.1:$PORT/api/state")"
echo "$STATE_ENTRY" | python3 -c 'import json,sys; s=next(x for x in json.load(sys.stdin)["slots"] if x["number"]==3); assert s["status"]=="OCCUPIED"' || { echo "FAIL: reserved slot did not become occupied after plate match"; exit 1; }
grep -q 'ENTERED' "$TMP/data/entry_intents.csv" || { echo "FAIL: entry intent does not retain ENTERED record"; cat "$TMP/data/entry_intents.csv"; exit 1; }
WRONG_PLATE="$(curl -sS -X POST "http://127.0.0.1:$PORT/leave/3" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'plate=NOT1234')"
echo "$WRONG_PLATE" | grep -q 'does not match' || { echo "FAIL: wrong plate was not rejected for leave: $WRONG_PLATE"; exit 1; }
QR_LEAVE="$(curl -sS -w '\n%{http_code}' -X POST "http://127.0.0.1:$PORT/leave/3" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'plate=QR12345')"
echo "$QR_LEAVE" | grep -q 'Leave Success' || { echo "FAIL: direct LEAVE QR plate verification: $QR_LEAVE"; exit 1; }
echo "$QR_LEAVE" | grep -q '200$' || { echo "FAIL: direct QR leave status: $QR_LEAVE"; exit 1; }
grep -q 'COMPLETED' "$TMP/data/entry_intents.csv" || { echo "FAIL: entry intent was not marked COMPLETED"; cat "$TMP/data/entry_intents.csv"; exit 1; }
# Slot state changes priority: active sessions must render as OCCUPIED even if there are reservation records.
FORM_RES="$(post "$COOKIE2" /api/reserve 'slotNumber=5' 'plate=FORM555' 'plateConfirm=FORM555' 'vehicleType=CAR' "reservedAt=$NOW_MINUS_ONE" 'durationMinutes=60')"
echo "$FORM_RES" | grep -q '201' || { echo "FAIL: slot 5 reservation: $FORM_RES"; exit 1; }
FORM_ENTER="$(curl -sS -X POST "http://127.0.0.1:$PORT/enter/5" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'plate=FORM555')"
echo "$FORM_ENTER" | grep -q 'Entry Success' || { echo "FAIL: direct reservation entry: $FORM_ENTER"; exit 1; }
FORM_STATE="$(curl -fsS "http://127.0.0.1:$PORT/api/state")"
echo "$FORM_STATE" | python3 -c 'import json,sys; s=next(x for x in json.load(sys.stdin)["slots"] if x["number"]==5); assert s["status"]=="OCCUPIED"' || { echo "FAIL: active slot did not have OCCUPIED priority"; exit 1; }
FORM_LEAVE="$(curl -sS -X POST "http://127.0.0.1:$PORT/leave/5" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'plate=FORM555')"
echo "$FORM_LEAVE" | grep -q 'Leave Success' || { echo "FAIL: direct QR leave did not complete: $FORM_LEAVE"; exit 1; }
# In-site scanner API also prompts for only the plate, while still supporting prepared walk-ins.
PREPARED_WALKIN="$(post "$COOKIE2" /api/prepare-entry 'slotNumber=6' 'plate=WALK666' 'plateConfirm=WALK666' 'vehicleType=MOTORCYCLE' 'durationMinutes=45')"
echo "$PREPARED_WALKIN" | grep -q '200' || { echo "FAIL: prepare walk-in: $PREPARED_WALKIN"; exit 1; }
PREPARED_WRONG="$(post "$COOKIE2" /api/scan 'action=ENTER' 'slotNumber=6' 'plate=WRONG666')"
echo "$PREPARED_WRONG" | grep -q '400' || { echo "FAIL: wrong plate should be rejected for prepared walk-in: $PREPARED_WRONG"; exit 1; }
PREPARED_ENTER="$(post "$COOKIE2" /api/scan 'action=ENTER' 'slotNumber=6' 'plate=WALK666')"
echo "$PREPARED_ENTER" | grep -q '200' || { echo "FAIL: valid prepared-walk-in plate was not accepted: $PREPARED_ENTER"; exit 1; }
PREPARED_LEAVE="$(post "$COOKIE2" /api/scan 'action=LEAVE' 'slotNumber=6' 'plate=WALK666')"
echo "$PREPARED_LEAVE" | grep -q '200' || { echo "FAIL: prepared-walk-in leave: $PREPARED_LEAVE"; exit 1; }
# A preview must never mutate a slot, even if a session cookie is present.
PREP_PREVIEW="$(post "$COOKIE2" /api/prepare-entry 'slotNumber=4' 'plate=PVW1234' 'plateConfirm=PVW1234' 'vehicleType=CAR' 'durationMinutes=45')"
echo "$PREP_PREVIEW" | grep -q '200' || { echo "FAIL: prepare preview test: $PREP_PREVIEW"; exit 1; }
PREVIEW_GET="$(curl -sS -b "$COOKIE2" -H 'Purpose: prefetch' -w '\n%{http_code}' "http://127.0.0.1:$PORT/enter/4")"
echo "$PREVIEW_GET" | grep -q 'Preview ignored' || { echo "FAIL: direct QR preview was not ignored: $PREVIEW_GET"; exit 1; }
STATE_PREVIEW="$(curl -fsS "http://127.0.0.1:$PORT/api/state")"
echo "$STATE_PREVIEW" | python3 -c 'import json,sys; s=next(x for x in json.load(sys.stdin)["slots"] if x["number"]==4); assert s["status"]=="AVAILABLE"' || { echo "FAIL: preview changed slot state"; exit 1; }
FUTURE="$(python3 -c 'import datetime; print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=3)).strftime("%Y-%m-%dT%H:%M:%SZ"))')"
FUTURE_OVERLAP="$(python3 -c 'import datetime; print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=3,minutes=30)).strftime("%Y-%m-%dT%H:%M:%SZ"))')"
FUTURE_RES="$(post "$COOKIE1" /api/reserve 'slotNumber=2' 'plate=ABC 1234' 'plateConfirm=ABC 1234' 'vehicleType=CAR' "reservedAt=$FUTURE" 'durationMinutes=60')"
echo "$FUTURE_RES" | grep -q '201' || { echo "FAIL: future reservation: $FUTURE_RES"; exit 1; }
FUTURE_STATE="$(curl -fsS "http://127.0.0.1:$PORT/api/state")"
echo "$FUTURE_STATE" | python3 -c 'import json,sys; s=next(x for x in json.load(sys.stdin)["slots"] if x["number"]==2); assert s["status"]=="RESERVED"' || { echo "FAIL: future reservation did not render as Reserved"; exit 1; }
OVERLAP="$(post "$COOKIE2" /api/reserve 'slotNumber=2' 'plate=XYZ 5678' 'plateConfirm=XYZ 5678' 'vehicleType=CAR' "reservedAt=$FUTURE_OVERLAP" 'durationMinutes=60')"
echo "$OVERLAP" | grep -q '409' || { echo "FAIL: overlapping reservation was not rejected: $OVERLAP"; exit 1; }
# Early QR entry must accept a correct reservation plate for all quick-arrival choices, including 2-3 hours ahead.
FUTURE_TWO_HOURS="$(python3 -c 'import datetime; print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%SZ"))')"
FUTURE_CHECKIN_RES="$(post "$COOKIE1" /api/reserve 'slotNumber=8' 'plate=FUTURE808' 'plateConfirm=FUTURE808' 'vehicleType=CAR' "reservedAt=$FUTURE_TWO_HOURS" 'durationMinutes=45')"
echo "$FUTURE_CHECKIN_RES" | grep -q '201' || { echo "FAIL: two-hour future reservation: $FUTURE_CHECKIN_RES"; exit 1; }
FUTURE_CHECKIN="$(curl -sS -w '\n%{http_code}' -X POST "http://127.0.0.1:$PORT/enter/8" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'plate=FUTURE808')"
echo "$FUTURE_CHECKIN" | grep -q 'Entry Success' || { echo "FAIL: correct plate for a two-hour future reservation was rejected: $FUTURE_CHECKIN"; exit 1; }
FUTURE_CHECKIN_LEAVE="$(curl -sS -w '\n%{http_code}' -X POST "http://127.0.0.1:$PORT/leave/8" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'plate=FUTURE808')"
echo "$FUTURE_CHECKIN_LEAVE" | grep -q 'Leave Success' || { echo "FAIL: leave after early check-in: $FUTURE_CHECKIN_LEAVE"; exit 1; }
# The in-site authenticated scanner must use the same future arrival window as the Google Lens form.
USER_FUTURE="$(python3 -c 'import datetime; print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%SZ"))')"
USER_FUTURE_RES="$(post "$COOKIE2" /api/reserve 'slotNumber=11' 'plate=USER1111' 'plateConfirm=USER1111' 'vehicleType=CAR' "reservedAt=$USER_FUTURE" 'durationMinutes=45')"
echo "$USER_FUTURE_RES" | grep -q '201' || { echo "FAIL: authenticated future reservation: $USER_FUTURE_RES"; exit 1; }
USER_FUTURE_ENTER="$(post "$COOKIE2" /api/scan 'action=ENTER' 'slotNumber=11' 'plate=USER1111')"
echo "$USER_FUTURE_ENTER" | grep -q '200' || { echo "FAIL: authenticated scan rejected matching future reservation: $USER_FUTURE_ENTER"; exit 1; }
USER_FUTURE_LEAVE="$(post "$COOKIE2" /api/scan 'action=LEAVE' 'slotNumber=11' 'plate=USER1111')"
echo "$USER_FUTURE_LEAVE" | grep -q '200' || { echo "FAIL: authenticated leave after early entry: $USER_FUTURE_LEAVE"; exit 1; }
FUTURE_TOO_EARLY="$(python3 -c 'import datetime; print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=4)).strftime("%Y-%m-%dT%H:%M:%SZ"))')"
TOO_EARLY_RES="$(post "$COOKIE1" /api/reserve 'slotNumber=10' 'plate=LATER1010' 'plateConfirm=LATER1010' 'vehicleType=CAR' "reservedAt=$FUTURE_TOO_EARLY" 'durationMinutes=45')"
echo "$TOO_EARLY_RES" | grep -q '201' || { echo "FAIL: four-hour future reservation: $TOO_EARLY_RES"; exit 1; }
TOO_EARLY_SCAN="$(curl -sS -X POST "http://127.0.0.1:$PORT/enter/10" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'plate=LATER1010')"
echo "$TOO_EARLY_SCAN" | grep -q 'entry opens up to 3 hours' || { echo "FAIL: too-early scan did not provide a clear timing message: $TOO_EARLY_SCAN"; exit 1; }
# The complete visit export must be denied before admin unlock.
UNAUTH_EXPORT="$(curl -sS -w '\n%{http_code}' "http://127.0.0.1:$PORT/api/admin/export/visits.csv")"
echo "$UNAUTH_EXPORT" | grep -q '401$' || { echo "FAIL: visit CSV export should require admin unlock: $UNAUTH_EXPORT"; exit 1; }
# Create an active visit so the export is verified for both active and completed sessions.
EXPORT_RES="$(post "$COOKIE1" /api/reserve 'slotNumber=12' 'plate=EXPORT1212' 'plateConfirm=EXPORT1212' 'vehicleType=CAR' "reservedAt=$NOW_MINUS_ONE" 'durationMinutes=45')"
echo "$EXPORT_RES" | grep -q '201' || { echo "FAIL: export test reservation: $EXPORT_RES"; exit 1; }
EXPORT_ENTER="$(post "$COOKIE1" /api/scan 'action=ENTER' 'slotNumber=12' 'plate=EXPORT1212')"
echo "$EXPORT_ENTER" | grep -q '200' || { echo "FAIL: export test entry: $EXPORT_ENTER"; exit 1; }
ADMIN="$(curl -sS -b "$ADMINCOOKIE" -c "$ADMINCOOKIE" -w '\n%{http_code}' -X POST "http://127.0.0.1:$PORT/api/admin/unlock" -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode "key=$KEY")"
echo "$ADMIN" | grep -q '200' || { echo "FAIL: admin unlock: $ADMIN"; exit 1; }
# The admin download includes all history, valid CSV columns, user identity, and active/completed session states.
EXPORT_CODE="$(curl -sS -b "$ADMINCOOKIE" -D "$TMP/visits.headers" -o "$TMP/visits.csv" -w '%{http_code}' "http://127.0.0.1:$PORT/api/admin/export/visits.csv")"
[[ "$EXPORT_CODE" == "200" ]] || { echo "FAIL: admin CSV export status $EXPORT_CODE"; cat "$TMP/visits.csv" 2>/dev/null || true; exit 1; }
grep -qi '^Content-Type: text/csv' "$TMP/visits.headers" || { echo "FAIL: CSV response content type missing"; cat "$TMP/visits.headers"; exit 1; }
grep -qi '^Content-Disposition: attachment;' "$TMP/visits.headers" || { echo "FAIL: CSV response is not an attachment"; cat "$TMP/visits.headers"; exit 1; }
python3 - "$TMP/visits.csv" "$EMAIL" <<'PYCSV'
import csv, sys
path, expected_email = sys.argv[1:]
with open(path, encoding='utf-8-sig', newline='') as f:
    reader = csv.DictReader(f)
    required = {'visit_id','slot_number','user_id','user_name','user_email','plate_number','vehicle_type','entry_time_utc','exit_time_utc','duration_seconds','duration_minutes','session_status','reservation_id'}
    assert set(reader.fieldnames or []) == required, f'Unexpected CSV headers: {reader.fieldnames}'
    rows = list(reader)
assert rows, 'CSV should include parking history'
assert any(r['user_email'] == expected_email and r['plate_number'] == 'ABC 1234' for r in rows), 'Missing known completed visit'
assert any(r['user_email'] == expected_email and r['plate_number'] == 'EXPORT1212' and r['session_status'] == 'ACTIVE' and not r['exit_time_utc'] for r in rows), 'Missing active visit row'
assert all(None not in r for r in rows), 'CSV rows have extra fields'
PYCSV
# User feedback is authenticated, persisted, and visible/updateable only inside the admin session.
FEEDBACK="$(post "$COOKIE1" /api/feedback 'category=Bug report' 'rating=2' 'message=The camera loading could be clearer.')"
echo "$FEEDBACK" | grep -q '201' || { echo "FAIL: feedback submission: $FEEDBACK"; exit 1; }
FEEDBACK_ID="$(echo "$FEEDBACK" | python3 -c 'import json,sys; print(json.loads(sys.stdin.read().splitlines()[0])["feedback"]["id"])')"
FEEDBACK_ADMIN="$(curl -fsS -b "$ADMINCOOKIE" "http://127.0.0.1:$PORT/api/admin/feedback")"
echo "$FEEDBACK_ADMIN" | grep -q 'The camera loading could be clearer.' || { echo "FAIL: admin feedback inbox did not return user feedback: $FEEDBACK_ADMIN"; exit 1; }
FEEDBACK_UPDATE="$(post "$ADMINCOOKIE" /api/admin/feedback/update "feedbackId=$FEEDBACK_ID" 'status=REVIEWING' 'adminNote=Review the scanner loading state.')"
echo "$FEEDBACK_UPDATE" | grep -q '200' || { echo "FAIL: admin feedback status update: $FEEDBACK_UPDATE"; exit 1; }
echo "$FEEDBACK_UPDATE" | grep -q 'REVIEWING' || { echo "FAIL: feedback status did not update: $FEEDBACK_UPDATE"; exit 1; }
grep -q 'The camera loading could be clearer.' "$TMP/data/feedback.csv" || { echo "FAIL: CSV feedback persistence missing"; cat "$TMP/data/feedback.csv"; exit 1; }
UNAUTH_FEEDBACK="$(curl -sS -w '\n%{http_code}' "http://127.0.0.1:$PORT/api/admin/feedback")"
echo "$UNAUTH_FEEDBACK" | grep -q '401$' || { echo "FAIL: admin feedback inbox should require admin unlock: $UNAUTH_FEEDBACK"; exit 1; }
ADD="$(post "$ADMINCOOKIE" /api/admin/add-slot 'compatibility=MOTORCYCLE')"
echo "$ADD" | grep -q '201' || { echo "FAIL: add slot: $ADD"; exit 1; }
NEW_SLOT="$(echo "$ADD" | python3 -c 'import json,sys; print(json.loads(sys.stdin.read().splitlines()[0])["slot"]["number"])')"
DISABLE="$(post "$ADMINCOOKIE" /api/admin/slot "slotNumber=$NEW_SLOT" 'enabled=false')"
echo "$DISABLE" | grep -q '200' || { echo "FAIL: disable new slot: $DISABLE"; exit 1; }
DISABLED_STATE="$(curl -fsS "http://127.0.0.1:$PORT/api/state")"
echo "$DISABLED_STATE" | python3 -c "import json,sys; s=next(x for x in json.load(sys.stdin)['slots'] if x['number']==$NEW_SLOT); assert s['status']=='DISABLED'" || { echo "FAIL: disabled slot status not shown as DISABLED"; exit 1; }
ENABLE="$(post "$ADMINCOOKIE" /api/admin/slot "slotNumber=$NEW_SLOT" 'enabled=true')"
echo "$ENABLE" | grep -q '200' || { echo "FAIL: re-enable new slot: $ENABLE"; exit 1; }
DEL="$(post "$ADMINCOOKIE" /api/admin/delete-slot "slotNumber=$NEW_SLOT")"
echo "$DEL" | grep -q '200' || { echo "FAIL: delete unused slot: $DEL"; exit 1; }
DEL_HISTORY="$(post "$ADMINCOOKIE" /api/admin/delete-slot 'slotNumber=1')"
echo "$DEL_HISTORY" | grep -q '409' || { echo "FAIL: deleting a slot with visit history should be blocked: $DEL_HISTORY"; exit 1; }
PREFETCH="$(curl -sS -X POST "http://127.0.0.1:$PORT/api/scan" -H 'Purpose: prefetch' -H 'Content-Type: application/x-www-form-urlencoded' --data 'action=LEAVE&slotNumber=1&plate=ABC1234')"
echo "$PREFETCH" | grep -q 'ignored' || { echo "FAIL: preview request was not ignored: $PREFETCH"; exit 1; }
grep -q 'id="liveToggle"' "$ROOT/src/web/PageBuilder.java" || { echo "FAIL: live-update opt-in control missing"; exit 1; }
grep -q 'setLiveUpdates(false)' "$ROOT/src/web/PageBuilder.java" || { echo "FAIL: live updates should default off"; exit 1; }
grep -q 'id=\"exportVisitsCsv\"' "$ROOT/src/web/PageBuilder.java" || { echo "FAIL: admin CSV export control missing from the website"; exit 1; }
grep -q '/api/admin/export/visits.csv' "$ROOT/src/web/ParkingServer.java" || { echo "FAIL: admin CSV export route missing"; exit 1; }
EXPORT_LEAVE="$(post "$COOKIE1" /api/scan 'action=LEAVE' 'slotNumber=12' 'plate=EXPORT1212')"
echo "$EXPORT_LEAVE" | grep -q '200' || { echo "FAIL: export test leave: $EXPORT_LEAVE"; exit 1; }
echo "PASS: CSV mode, register + login, session cookies, plate-confirmed booking, API entry/exit, plate-verified Google Lens QR entry/exit, wrong-plate rejection, prepared walk-in plate verification, entry-intent audit status, available/occupied/reserved/unavailable statuses, prefetch guard, user ownership, overlap guard, admin unlock/add/delete-slot, history protection, authenticated user feedback submission, admin-only feedback inbox, admin-only full visit CSV export (active and completed records, valid columns, user identity), feedback status/private-note update, manual/live refresh controls, early reservation check-in through Google Lens and the in-site scanner, and clear too-early QR feedback."
