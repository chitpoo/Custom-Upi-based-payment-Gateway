# UPI Payment Verification Gateway — Guide

Merchant creates a Rs.100 order → system assigns a unique amount (e.g. Rs.100.37;
the paise 0.01–0.99 is unused by any `PENDING` order) → UPI link + QR → customer
pays direct to the merchant UPI (we never hold money) → merchant pastes the bank
SMS / uploads a screenshot → Muse Spark verifies → amount match flips the order
`PENDING` → `PAID`. Orders unpaid after 5 minutes become `EXPIRED`.

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

3. Export your API key (required for SMS/screenshot verification):

   ```bash
   export META_API_KEY=<your-key>
   ```

   Sanity-check the key first: `python3 test.py` should list `muse-spark-1.3`.

## Run

```bash
mvn compile            # must pass with no errors
mvn spring-boot:run    # app on http://localhost:8080
```

Open http://localhost:8080 for the demo UI
(`curl http://localhost:8080/ | grep -i "Built with Muse Spark"`).

Fast-expiry run (for testing the 5-minute window without waiting):

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--app.expiry-minutes=1 --app.expiry-interval-ms=10000"
```

## Demo script (5 minutes)

Terminal 1: `export META_API_KEY=<key>` then `mvn spring-boot:run`.
Terminal 2 (and a browser on http://localhost:8080):

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

## API quick-ref

| Method & path | Body | Success |
|---|---|---|
| `POST /orders` | `{"baseAmount":"100.00","merchantUpiId":"shop@okaxis"}` | `201` `OrderResponse` (`id`, `uniqueAmount`, `upiLink`, …) |
| `GET /orders` | — (`?status=PENDING\|PAID\|EXPIRED` optional) | `200` list |
| `GET /orders/{id}` | — | `200` `OrderResponse`, `404` unknown |
| `GET /orders/{id}/qr` | — | `200` `image/png` (220×220), `404` unknown |
| `POST /payments/verify` | `{"smsText":"…"}` | always `201` `VerifyPaymentResponse` — branch on `verdict` / `orderStatus`, never on status code |
| `POST /payments/verify-image` | multipart `image` (JPEG/PNG ≤ 5 MB) | always `201` `VerifyPaymentResponse` |
| `GET /payments` | — (`?orderId=` optional) | `200` list, newest first |
| `POST /debug/parse` | `{"smsText":"…"}` | `200` raw `ParsedResult` (dev only) |

Error shape (all errors): `{"timestamp","status","error","message","path"}`.
`400` validation / bad param / bad image, `404` unknown order, `409` paise
exhaustion or write race, `413` image over 5 MB.

Money is `BigDecimal` (scale 2) end to end; UTR is normalized `""` → `null` and
unique; every verify attempt is saved as a `payments` audit row, including
duplicates and races (re-saved as `SUSPICIOUS`).

## Troubleshooting

- **App won't start / access-denied for `root`**: MySQL isn't up or the
  password differs — check `docker ps` (Option B) or
  `mysql -uroot -p -e "SELECT 1;"`, then fix `spring.datasource.*`.
- **`Verification service unavailable` verdict**: `META_API_KEY` missing/invalid
  or Meta API unreachable. `export META_API_KEY=<key>`, re-check with
  `python3 test.py`, restart the app.
- **`Order expired — 5 min window passed`**: correct behavior — create a new
  order (new unique amount) and pay again. For tests, use the fast-expiry run
  above.
- **`Duplicate UTR`**: that UTR was already used for a matched payment; each
  payment needs its own UTR.
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

## Project map

```
pom.xml                                  Spring Boot 3.2 / Java 17 / MySQL / ZXing
src/main/java/com/example/upigateway/
  UpiGatewayApplication.java             boot + @EnableScheduling
  model/      Order, Payment, OrderStatus, Verdict, ParsedResult
  repo/       OrderRepository, PaymentRepository (UTR unique, earliest-first match)
  dto/        CreateOrderRequest, OrderResponse, VerifySmsRequest, VerifyPaymentResponse
  service/    OrderService (unique paise + upi:// link), QrService (220px PNG),
              ExpiryService (5-min scheduler), PaymentParser (Muse Spark SMS+vision),
              PaymentService (@Transactional audit + match/settle)
  controller/ OrderController, PaymentController (race-loss fallback audits),
              DebugController (POST /debug/parse)
  config/     GlobalExceptionHandler (JSON error shape)
src/main/resources/
  application.properties                 DB, Meta API, expiry, 5MB upload cap
  static/     index.html, app.js, style.css (demo UI, no framework)
src/test/.../service/PaymentServiceTest.java   fake-parser unit tests (no DB/AI)
docker-compose.yml  MySQL 8 (Option B)    README_MYSQL.md  DB deep-dive
PLAN.md         build plan (source of truth)  test.py  API-key probe
```

Rules the code follows: `BigDecimal` money (never `double`), controllers return
DTOs (never entities), `@Transactional` all-or-nothing settle, every payment
audited, footer “Built with Muse Spark”.
