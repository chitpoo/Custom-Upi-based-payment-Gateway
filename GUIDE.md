# UPI Payment Verification Gateway — Guide

Merchant creates a Rs.100 order → system assigns a unique amount (e.g. Rs.100.37;
the paise 0.01–0.99 is unused by any `PENDING` order) → UPI link + QR → customer
pays direct to the merchant UPI (we never hold money) → the bank SMS reaches
the app (phone auto-forward, paste, or screenshot upload) → Muse Spark
verifies → amount match flips the order `PENDING` → `PAID`. Orders unpaid
after 5 minutes become `EXPIRED`.

## How verification works (the idea in 30 seconds)

- Every `PENDING` order owns a unique paise amount (Rs.100 → `100.37`), so an
  amount identifies exactly one order. The app never touches money — payment
  goes straight to the merchant's UPI ID.
- Muse Spark (`muse-spark-1.3`) reads each SMS/screenshot and returns amount,
  12-digit UTR, sender, direction, and a verdict (`VERIFIED` / `SUSPICIOUS` /
  `NOT_A_PAYMENT`) — including flagging fabricated-looking UTRs.
- A `VERIFIED` credit whose amount equals a `PENDING` order flips it to `PAID`;
  unknown amounts, duplicates (UTR already used), and late arrivals on
  `EXPIRED` orders are rejected with a reason. Every attempt is saved to the
  `payments` audit table, matched or not.

## Prerequisites

- Java 17+ (`java -version`) and Maven 3.9+ (`mvn -version`) — tested on JDK 23
- MySQL 8, running locally on port 3306 — or Docker Desktop for the
  `docker-compose.yml` alternative
- A Meta Model API key with access to `muse-spark-1.3`
- `curl` and `jq` for the demo script; any modern browser for the UI
- Node.js (only needed for the `node --check` syntax check on `app.js`)

## Setup

1. Start MySQL (pick one):

   ```bash
   # Option A — local MySQL 8 already installed: just make sure it is running
   mysql -uroot -p -e "SELECT 1;"   # enter your password, expect 1

   # Option B — Docker
   docker compose up -d
   docker logs upi-mysql            # wait until ready for connections
   ```

   The app URL contains `createDatabaseIfNotExist=true`, so the `paymentgateway`
   database is created automatically on first run.

2. If your MySQL user/password differ from `root` / `9599`, update them in
   `src/main/resources/application.properties`
   (`spring.datasource.username` / `spring.datasource.password`).

3. Get the API key value (provided with our submission — never committed to
   git) and sanity-check it:

   ```bash
   META_API_KEY=<key-from-us> python3 test.py   # should list muse-spark-1.3
   ```

## Run

```bash
mvn compile                                            # must pass with no errors
META_API_KEY=<key-from-us> mvn spring-boot:run        # app on http://localhost:8080
# + WEBHOOK_SECRET=<secret> prefix only if you use the Android macro
```

Open http://localhost:8080 for the demo UI
(`curl http://localhost:8080/ | grep -i "Built with Muse Spark"`).

One server serves everything: the UI and the API share port 8080 — no
separate frontend process, no CORS setup, one command to run.

Fast-expiry run (for testing the 5-minute window without waiting):

```bash
META_API_KEY=<key-from-us> mvn spring-boot:run -Dspring-boot.run.arguments="--app.expiry-minutes=1 --app.expiry-interval-ms=10000"
```

## Demo script (5 minutes)

### Judge demo — browser only (recommended)

You (the presenter) run Terminal 1 beforehand:
`META_API_KEY=<key-from-us> mvn spring-boot:run`.
Judges only touch http://localhost:8080 (no phone or macro setup needed —
paste/upload covers them):

1. **Create** — the form is prefilled (`100.00`, `shop@okaxis`); click Create
   Order. The result card shows the unique amount (e.g. `100.37`), the `upi://`
   link + Copy button, the QR code, status `PENDING`, and a 5:00 countdown.
2. **Pay** — scan the QR with any UPI app and pay the EXACT unique amount
   (or simulate — see below).
3. **Verify SMS** — paste the bank SMS into Verify SMS → Verify SMS. Green
   `VERIFIED` chip + amount/UTR/matched order; the order badge flips to `PAID`;
   both tables refresh within 5 s.
4. **Screenshot instead** — upload a payment screenshot (JPG/PNG ≤ 5 MB) for
   the same verdict panel.
5. **Expiry** — after 5 unpaid minutes the red *"Payment failed — time
   expired…"* banner appears with a Retry button that pre-fills the form.

### Auto SMS from Android (recommended for the video)

Your phone can push each SMS straight to the app — no pasting:

1. **Same network:** connect the phone to the same network as this laptop,
   then find the laptop's IP on that network — Mac: `ipconfig getifaddr en0`,
   Windows: `ipconfig`, Linux: `hostname -I`. Use YOUR OWN IP in step 4
   below, and re-check it if things ever stop working (IPs change).
2. **Install MacroDroid** (Play Store, free) and grant it SMS access when asked.
3. **Battery:** Android Settings → Apps → MacroDroid → Battery → Unrestricted,
   so Android can't kill it mid-demo.
4. **Create the macro:** Add Macro → Trigger `SMS Received` (any sender) →
   Action `HTTP Request`: URL
   `http://<laptop-ip>:8080/payments/sms-webhook` (your IP from step 1),
   method `POST`,
   content type `application/json`, body
   `{"from":"<SMS-number magic text>","text":"<SMS-message magic text>"}`
   (insert both via the magic-text button). No constraints needed.
5. **macOS firewall:** on the first phone attempt the laptop may ask to allow
   incoming connections for Java — click Allow.
6. **Test:** text yourself any message → a new row appears in Payment History
   (non-payments land as `NOT_A_PAYMENT`/`SUSPICIOUS`, never matched). For a
   real match, FIRST create the order in the UI, then pay its EXACT unique
   amount — the forwarded SMS must carry that amount, or it lands as an
   unmatched audit row. Watch the order flip to `PAID` untouched.
7. **Optional security:** to stop strangers on the network hitting your
   webhook, pick ANY value you like and put the SAME value in both places:
   prefix the run command with `WEBHOOK_SECRET=<your-value>` and add a header
   `X-Webhook-Secret: <your-value>` in MacroDroid's HTTP Request headers.
   The two just have to match each other — not anyone else's. Leave the env
   var unset and the endpoint is open (fine for judging, where no macro is
   used at all). The server never fails on malformed JSON: raw quotes, newlines, and
   backslashes in the SMS are handled, and even a plain-text body is treated
   as the SMS.

### Manual fallback (no phone setup)

- **Paste:** read the bank SMS on your phone, copy/type its text into the
  Verify SMS box, submit. That text goes straight to the AI parser, and the
  result is saved to the `payments` table.
- **Same-network browser:** open `http://<laptop-ip>:8080` in the phone's
  browser and paste the SMS directly — no retyping.
- **Screenshot:** screenshot the payment → transfer it to the laptop (AirDrop,
  message-to-self, USB) → upload it in Verify Screenshot.
- **Simulated SMS (no real payment):** paste
  `Rs.<uniqueAmount> credited to A/c XX1234 via UTR <random-12-digit> from buyer@okaxis`
  using the EXACT amount shown on screen and a fresh random-looking UTR each
  run. Avoid obviously fake UTRs (`123456789012`, `777788889999`, …) — the
  model flags sequential/repetitive patterns as fabricated.

### Terminal demo — curl (same flow, for API checking)

Terminal 1: `META_API_KEY=<key-from-us> mvn spring-boot:run`.
Terminal 2:

```bash
# 1. Create an order — note the uniqueAmount, e.g. 100.37
RESP=$(curl -s -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"baseAmount":"100.00","merchantUpiId":"shop@okaxis"}')
echo $RESP | jq
OID=$(echo $RESP | jq -r .id)
AMT=$(echo $RESP | jq -r .uniqueAmount)

# 2. Pay Rs.$AMT to shop@okaxis from your phone (use the QR in the UI),
#    then verify with the bank SMS the merchant received.
#    Use a fresh random UTR every run — reusing one returns "Duplicate UTR":
UTR=$(python3 -c 'import random; print(random.randrange(10**11, 10**12))')
curl -s -X POST http://localhost:8080/payments/verify \
  -H "Content-Type: application/json" \
  -d "{\"smsText\":\"Rs.$AMT credited to A/c XX1234 via UTR $UTR from buyer@okaxis\"}" | jq
# expect verdict VERIFIED, matchedOrderId == $OID, orderStatus PAID

curl -s http://localhost:8080/orders/$OID | jq .status   # PAID

# 3. Non-payment SMS is audited, never matched:
curl -s -X POST http://localhost:8080/payments/verify \
  -H "Content-Type: application/json" \
  -d '{"smsText":"Your OTP is 482910"}' | jq .verdict    # NOT_A_PAYMENT

# 4. Screenshot path (JPG/PNG, max 5MB):
curl -s -X POST http://localhost:8080/payments/verify-image \
  -F "image=@/tmp/pay.jpg" | jq

# 5. History:
curl -s http://localhost:8080/orders | jq length
curl -s "http://localhost:8080/payments?orderId=$OID" | jq length
```

In the browser: create → QR + 5-min countdown → verify SMS → green `VERIFIED`
chip; orders table and payment history refresh every 5 s. After expiry the UI
shows *"Payment failed — time expired…"* with a Retry button that pre-fills the
create form.

## Video script (3 minutes)

- **0:00–0:30 — Problem + idea:** small merchants can't cheaply confirm UPI
  payments. We assign a unique-paise amount per order and let AI read the
  bank SMS. Show the UI.
- **0:30–1:00 — Create:** create a Rs.100 order → unique amount + QR +
  countdown appear. Scan the QR with the phone (close-up).
- **1:00–2:00 — Pay + auto-verify (the money shot):** pay the exact amount →
  bank SMS arrives on the phone → MacroDroid pushes it → Payment History row
  appears → order flips `PENDING` → `PAID` live. No typing.
- **2:00–2:30 — Trust signals:** paste an OTP → `NOT_A_PAYMENT`, never
  matched; upload a screenshot → same verdict panel.
- **2:30–3:00 — How it works + close:** unique-paise matching + Muse Spark
  extraction + full audit trail; 5-min expiry with retry; "we never touch
  the money."

Recording tips: clear both tables first so history starts empty; do one full
dry run; keep the paste box ready as backup; mention expiry verbally instead
of waiting on camera; film the phone screen too when the SMS lands.

## API quick-ref

| Method & path | Body | Success |
|---|---|---|
| `POST /orders` | `{"baseAmount":"100.00","merchantUpiId":"shop@okaxis"}` | `201` `OrderResponse` (`id`, `uniqueAmount`, `upiLink`, …) |
| `GET /orders` | — (`?status=PENDING\|PAID\|EXPIRED` optional) | `200` list |
| `GET /orders/{id}` | — | `200` `OrderResponse`, `404` unknown |
| `GET /orders/{id}/qr` | — | `200` `image/png` (220×220), `404` unknown |
| `POST /payments/verify` | `{"smsText":"…"}` | always `201` `VerifyPaymentResponse` — branch on `verdict` / `orderStatus`, never on status code |
| `POST /payments/verify-image` | multipart `image` (JPEG/PNG ≤ 5 MB) | always `201` `VerifyPaymentResponse` |
| `POST /payments/sms-webhook` | `{"from":"…","text":"…"}` (aliases + tolerant parse of raw quotes/newlines; plain text also OK) | always `201` — Android forwarder target; optional `X-Webhook-Secret` header (`401` on mismatch when `WEBHOOK_SECRET` is set) |
| `GET /payments` | — (`?orderId=` optional) | `200` list, newest first |
| `POST /debug/parse` | `{"smsText":"…"}` | `200` raw `ParsedResult` (dev only) |

Error shape (all errors): `{"timestamp","status","error","message","path"}`.
`400` validation / bad param / bad image, `401` bad webhook secret, `404`
unknown order, `409` paise exhaustion or write race, `413` image over 5 MB.

Money is `BigDecimal` (scale 2) end to end; UTR is normalized `""` → `null` and
unique; every verify attempt is saved as a `payments` audit row, including
duplicates and races (re-saved as `SUSPICIOUS`).

## Troubleshooting

- **App won't start / access-denied for `root`**: MySQL isn't up or the
  password differs — check `docker ps` (Option B) or
  `mysql -uroot -p -e "SELECT 1;"`, then fix `spring.datasource.*`.
- **`Verification service unavailable` verdict**: `META_API_KEY` missing/invalid
  or Meta API unreachable. Re-check with
  `META_API_KEY=<key-from-us> python3 test.py`, then restart the app with the
  key inline (see Run above).
- **`Order expired — 5 min window passed`**: correct behavior — create a new
  order (new unique amount) and pay again. For tests, use the fast-expiry run
  above.
- **`Duplicate UTR`**: that UTR was already used for a matched payment; each
  payment needs its own UTR.
- **Test SMS came back `SUSPICIOUS` instead of `VERIFIED`**: your test UTR
  looks fabricated (sequential or repetitive digits) — use a fresh
  random-looking 12-digit UTR.
- **`413 File too large`**: screenshot over 5 MB — compress/resize and retry.
- **`409 All paise variants in use`**: 99+ `PENDING` orders share one base
  amount — wait for expiry or vary the base amount.
- **QR shows but phone won't open it**: the `upi://` link needs a UPI app
  (GPay/PhonePe/Paytm) on the scanning phone; on desktop use the Copy button.
- **Countdown hit 00:00 but status still `PENDING`**: the timer is display-only;
  the server expires every ~60 s — the UI polls and updates shortly after.
- **Port 8080 already in use**: start on another port —
  `mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"`,
  then open http://localhost:8081 instead.
- **SMS arrived but no payment row appeared**: phone and laptop must share one
  network — re-check the laptop IP (`ipconfig getifaddr en0`) and the macro
  URL; confirm MacroDroid battery is Unrestricted and the app is running with
  the key inline (see Run above).
- **macOS blocked the phone's connection**: allow incoming connections for
  Java when prompted (or Settings → Network → Firewall).
- **Row appears but the order stays `PENDING`**: read that row's verdict and
  reason — it names the exact cause (amount mismatch, flagged UTR, expired
  order). The SMS must carry the order's EXACT unique amount.
- **College/public WiFi blocks phone → laptop**: some networks isolate
  clients from each other. Fallback: turn on the phone's hotspot, join it
  from the laptop, and use the hotspot IP in the macro (USB tethering works
  the same way). Paste always works regardless of network.
- **Webhook returns `401`**: you set `WEBHOOK_SECRET` but MacroDroid's
  `X-Webhook-Secret` header is missing or different — make them match, or
  unset the env var and restart to leave the endpoint open.

## Project map

```
pom.xml                                  Spring Boot 3.2 / Java 17 / MySQL / ZXing
src/main/java/com/example/upigateway/
  UpiGatewayApplication.java             boot + @EnableScheduling
  model/      Order, Payment, OrderStatus, Verdict, ParsedResult
  repo/       OrderRepository, PaymentRepository (UTR unique, earliest-first match)
  dto/        CreateOrderRequest, OrderResponse, VerifySmsRequest, VerifyPaymentResponse,
              SmsWebhookRequest (lenient forwarder-body parse)
  service/    OrderService (unique paise + upi:// link), QrService (220px PNG),
              ExpiryService (5-min scheduler), PaymentParser (Muse Spark SMS+vision),
              PaymentService (@Transactional audit + match/settle)
  controller/ OrderController, PaymentController (race-loss fallback audits),
              DebugController (POST /debug/parse)
  config/     GlobalExceptionHandler (JSON error shape)
src/main/resources/
  application.properties                 DB, Meta API, expiry, 5MB upload cap
  static/     index.html, app.js, style.css (demo UI, no framework)
src/test/...   PaymentServiceTest, SmsWebhookRequestTest (no DB/AI, `mvn test`)
docker-compose.yml  MySQL 8 (Option B)    README_MYSQL.md  DB deep-dive
PLAN.md         build plan (source of truth)  test.py  API-key probe
```

Rules the code follows: `BigDecimal` money (never `double`), controllers return
DTOs (never entities), `@Transactional` all-or-nothing settle, every payment
audited, footer “Built with Muse Spark”.
