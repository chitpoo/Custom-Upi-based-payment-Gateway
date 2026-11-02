# UPI Payment Verification Gateway — Build Plan

> Solo hackathon. Merchant creates Rs.100 order -> system assigns Rs.100.37 (unique paise 0.01-0.99 not used by any PENDING order) -> UPI link `upi://pay?pa=<merchantUpi>&am=<amount>&tn=<orderId>` + QR -> customer pays direct to merchant UPI (we never hold money) -> merchant pastes SMS / uploads screenshot -> Muse Spark 1.3 via Meta Model API verifies -> match amount -> PENDING -> PAID if VERIFIED+credit. 5-min expiry (if not paid within 5 mins order becomes EXPIRED/FAILED and user must retry - create new order). Web page: create + QR + countdown, verify, status with expired banner. Footer: "Built with Muse Spark".

> Tech: Java 17, Spring Boot 3.x, Maven, Spring Web, Spring Data JPA, MySQL 8 (local install, `docker-compose.yml` as alternative), Spring Validation. Model: `muse-spark-1.3` on Meta Model API at `https://api.meta.ai/v1`, key from env `META_API_KEY`. All money `BigDecimal` (precision 10, scale 2). No login, no React, no microservices.

> Commands use `mvn` (there is no Maven wrapper in this repo; if you add one, swap `mvn` for `./mvnw`). Each step has a checkbox: mark `[x]` only after you confirm its test passed.
>
> Final scope for Nov 4 submission: Steps 1–10 are must-have, in order. Step 11 is optional — skip it if time is short. This plan is FINAL: build it as written.

---

## Step 1 — Project Skeleton (MySQL) [x]

**Files to create/change:**
- `pom.xml` — parent `spring-boot-starter-parent` 3.2.x (Java 17). Dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `com.mysql:mysql-connector-j` (runtime), `com.google.zxing:core` + `javase` 3.5.x (for Step 4). Build: `spring-boot-maven-plugin`.
- `src/main/java/com/example/upigateway/UpiGatewayApplication.java` — `@SpringBootApplication` + `@EnableScheduling`.
- `src/main/resources/application.properties`:
  ```
  server.port=8080
  spring.datasource.url=jdbc:mysql://localhost:3306/paymentgateway?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
  spring.datasource.username=root
  spring.datasource.password=9599
  spring.datasource.driverClassName=com.mysql.cj.jdbc.Driver
  spring.jpa.hibernate.ddl-auto=update
  spring.jpa.show-sql=true
  spring.jpa.properties.hibernate.format_sql=true
  logging.level.org.hibernate.SQL=DEBUG
  logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE
  meta.api.key=${META_API_KEY:}
  meta.api.baseUrl=https://api.meta.ai/v1
  meta.api.model=muse-spark-1.3
  app.expiry-minutes=5
  spring.servlet.multipart.max-file-size=5MB
  spring.servlet.multipart.max-request-size=5MB
  ```
  (Adjust username/password to your local MySQL. Do NOT set `spring.jpa.database-platform` — Hibernate 6 auto-detects it.)
- `docker-compose.yml` — `mysql:8.0`, container `upi-mysql`, `MYSQL_DATABASE: paymentgateway`, port 3306 (alternative if you prefer Docker over local MySQL).
- `.gitignore` — `target/`, `.idea/`, `.env` (plus existing entries).

**What it does:** Bootable app with Tomcat + JPA + MySQL. SQL is logged to console for learning. Proves tooling before domain code.

**How to test:**
```bash
mvn compile
mvn spring-boot:run
curl http://localhost:8080/ # 404 Whitelabel = Tomcat up
mysql -uroot -p paymentgateway -e "SHOW TABLES;" # enter your MySQL password
```
---

## Step 2 — Entities + Repositories [x]

**Files to create/change:**
- `src/main/java/com/example/upigateway/model/OrderStatus.java` — `PENDING, PAID, EXPIRED`.
- `src/main/java/com/example/upigateway/model/Order.java` — `@Entity @Table(name="orders")` with `@Id String id` (UUID, set by service — no `@GeneratedValue`), `@Column(precision=10,scale=2,nullable=false) BigDecimal baseAmount`, `uniqueAmount` same precision, `merchantUpiId` (`@NotBlank @Pattern` UPI regex), `@Enumerated(STRING) OrderStatus status`, `Instant createdAt`, `Instant paidAt` nullable, `@Version Long version` (optimistic lock).
- `src/main/java/com/example/upigateway/model/Payment.java` — `@Entity @Table(name="payments")` with `@Id String id` (UUID, set by service), `@ManyToOne(fetch=LAZY) Order order` nullable FK, `@Column(precision=10,scale=2) BigDecimal amount` nullable if NOT_A_PAYMENT, `@Column(unique=true,length=20) String utr` UNIQUE nullable, `sender`, `@Enumerated(STRING) Verdict verdict` (VERIFIED/SUSPICIOUS/NOT_A_PAYMENT), `@Column(length=1000) String reason` (AI text — update from default 255), `@Column(length=4000) String rawMessage`, `Instant receivedAt`. No `direction`/`timeText` columns — the parser folds them into `reason`/`rawJson`.
- `src/main/java/com/example/upigateway/model/Verdict.java` — `VERIFIED, SUSPICIOUS, NOT_A_PAYMENT`.
- `src/main/java/com/example/upigateway/repo/OrderRepository.java` — `JpaRepository<Order,String>` + `findByStatusAndCreatedAtBefore` + `existsByUniqueAmountAndStatus` + `findByUniqueAmountAndStatusOrderByCreatedAtAsc` (earliest-first `List<Order>` — see Step 7) + `findByStatus` (for Step 9 `?status` filter).
- `src/main/java/com/example/upigateway/repo/PaymentRepository.java` — `JpaRepository<Payment,String>` + `findByUtr` + `existsByUtr` + `findByOrder_Id(String orderId)` (for Step 9 `?orderId` filter) + `findAllByOrderByReceivedAtDesc()`.

**What it does:** Persistent domain with JPA constraints. `UNIQUE(utr)` + `@Version` are race guards for Step 7. UTR rule: normalize `""` to `null` before every save/query, and never call `existsByUtr(null)`.

**How to test:**
```bash
mvn compile
mvn spring-boot:run
mysql -uroot -p paymentgateway -e "SHOW COLUMNS FROM orders; SHOW COLUMNS FROM payments; SHOW INDEX FROM payments;"
# expect VERSION column and UNIQUE key on utr
```

---

## Step 3 — Create Order (Unique Amount + UPI Link) [x] (live-verified Nov 1: 3 distinct amounts, GET/404/400 checks passed)

**Files to create/change:**
- `src/main/java/com/example/upigateway/dto/CreateOrderRequest.java` — `BigDecimal baseAmount` (`@NotNull @DecimalMin("0.01") @Digits(integer=10,fraction=2)`), `String merchantUpiId` (`@NotBlank @Pattern` with the SAME regex as `Order.merchantUpiId`, so invalid IDs fail with 400 at the API, not late at JPA).
- `src/main/java/com/example/upigateway/dto/OrderResponse.java` — `id, baseAmount, uniqueAmount, merchantUpiId, status, createdAt, upiLink`. Controllers return DTOs, never entities.
- `src/main/java/com/example/upigateway/service/OrderService.java` — `synchronized createOrder(baseAmount, merchantUpiId)`: normalize `baseAmount.setScale(2,HALF_UP)`, validate >0; loop up to 100 tries: paise=1..99 random, candidate = baseAmount + paise/100 (`new BigDecimal(paise).movePointLeft(2)`), check `!orderRepository.existsByUniqueAmountAndStatus(candidate,PENDING)`; if all tries fail throw 409 "All paise variants in use — try again". Paise semantics: ADD (preserves unique >= base; documents rollover, e.g. base 100.99 -> unique 101.00–101.98). Set `id=UUID`, `status=PENDING`, `createdAt=now`; build `upiLink="upi://pay?pa="+encode(merchantUpiId)+"&am="+candidate.toPlainString()+"&tn="+encode(orderId)+"&cu=INR"` (`URLEncoder`, UTF-8); save. `synchronized` is the hackathon-level guard against duplicate-amount races on one instance — production would need a partial unique index. `getOrderById` rebuilds the `upiLink` for reads.
- `src/main/java/com/example/upigateway/controller/OrderController.java` — `@RestController @RequestMapping("/orders")`: `POST /orders` (`@Valid` body) -> 201 `OrderResponse`, `GET /orders/{id}` -> 200 `OrderResponse` / 404.

**What it does:** Concurrent Rs.100 orders get distinct paise via PENDING-only uniqueness.

**How to test:**
```bash
mvn spring-boot:run &
curl -X POST http://localhost:8080/orders -H "Content-Type: application/json" -d '{"baseAmount":"100.00","merchantUpiId":"shop@okaxis"}' | jq
# expect uniqueAmount like 100.37, upiLink with pa=shop@okaxis&am=100.37&tn=<id>
curl -X POST http://localhost:8080/orders -H "Content-Type: application/json" -d '{"baseAmount":"100.00","merchantUpiId":"shop@okaxis"}' | jq .uniqueAmount
curl -X POST http://localhost:8080/orders -H "Content-Type: application/json" -d '{"baseAmount":"100.00","merchantUpiId":"shop@okaxis"}' | jq .uniqueAmount
# two amounts !=
curl http://localhost:8080/orders/<id> | jq
curl http://localhost:8080/orders/unknown-id -i # 404
curl -X POST http://localhost:8080/orders -H "Content-Type: application/json" -d '{"baseAmount":"100.00","merchantUpiId":"not-a-upi"}' -i # 400
```

---

## Step 4 — QR Code for UPI Link [x] (live-verified Nov 1: PNG 220x220, 404 on unknown)

**Files to create/change:**
- `src/main/java/com/example/upigateway/service/QrService.java` — ZXing (3.5.x, already in `pom.xml`): `QRCodeWriter -> BitMatrix -> MatrixToImageWriter.writeToStream(..., "PNG", out)` 220x220. `WriterException` -> 500.
- `OrderController` add `GET /orders/{id}/qr` -> produces `image/png` bytes, 404 if order missing.

**What it does:** Merchant shows QR; phone scans UPI intent.

**How to test:**
```bash
ID=$(curl -s -X POST http://localhost:8080/orders -H "Content-Type: application/json" -d '{"baseAmount":"50.00","merchantUpiId":"test@upi"}' | jq -r .id)
curl -s http://localhost:8080/orders/$ID/qr --output /tmp/qr.png && file /tmp/qr.png
# expect PNG 220x220
curl -i http://localhost:8080/orders/$ID/qr | head # Content-Type: image/png 200
curl http://localhost:8080/orders/unknown-id/qr -i # 404
```

---

## Step 5 — Expiry Scheduler (5-min Reclaim) [x] (live-verified Nov 1: 1-min window → EXPIRED)

**Files to create/change:**
- `src/main/java/com/example/upigateway/service/ExpiryService.java` — `@Component @Scheduled(fixedDelayString="${app.expiry-interval-ms:60000}")` queries `findByStatusAndCreatedAtBefore(PENDING, now.minus(expiryMinutes,MINUTES))`, set EXPIRED, save, log count. `expiryMinutes` from `app.expiry-minutes=5`. Interval is a property (not a hardcoded constant) so tests can override without source edits.
- (Deliberately NOT here: expired-rejection at verify time lives in Step 7; the countdown banner UI lives in Step 10.)

**What it does:** Strict 5-min payment window. Frees paise: 100.37 PENDING >5m -> EXPIRED -> reusable.

**How to test:**
```bash
# fast run via property overrides (no source edit), then restart normally:
mvn spring-boot:run -Dspring-boot.run.arguments="--app.expiry-minutes=1 --app.expiry-interval-ms=10000" &
RESP=$(curl -s -X POST http://localhost:8080/orders -H "Content-Type: application/json" -d '{"baseAmount":"20.00","merchantUpiId":"test@upi"}')
ID=$(echo $RESP|jq -r .id); sleep 75; curl -s http://localhost:8080/orders/$ID | jq .status # expect EXPIRED
# reclaim: a new Rs.20 order may now reuse the freed paise
```

---

## Step 6 — AI Parser (Muse Spark 1.3, Text) + Debug Endpoint [x] (live-verified Nov 1: VERIFIED parse + OTP→NOT_A_PAYMENT on Meta Model API)

**Files to create/change:**
- `src/main/java/com/example/upigateway/model/ParsedResult.java` — amount(BigDecimal), utr, sender, timeText, direction, verdict, reason, rawJson.
- `src/main/java/com/example/upigateway/service/PaymentParser.java` — `@Service` reads `META_API_KEY`/baseUrl/model from props, uses HttpClient (connect + request timeout ~30s) + Jackson, method `ParsedResult parseSms(String smsText)` builds OpenAI-compatible `{model:"muse-spark-1.3",temperature:0,response_format:{type:"json_object"},messages:[{role:"system",content:"You are UPI SMS parser. Extract amount as a decimal number, utr (exactly 12 digits), sender (UPI/name), time, direction credit/debit, verdict VERIFIED|SUSPICIOUS|NOT_A_PAYMENT with reason. Judge only the message content; there is no expected amount. Return JSON only with keys amount,utr,sender,time,direction,verdict,reason." (No example amount in the prompt — the model once treated "100.37" as ground truth.)},{role:"user",content:smsText}]}` POST to `https://api.meta.ai/v1/chat/completions` with Bearer key. Parse only `message.content`: strip ``` fences, accept amount as string-or-number, UTR must match `\d{12}` else null. On IOException/non-2xx/missing key -> return SUSPICIOUS "Verification service unavailable". Never log the key.
- `src/main/java/com/example/upigateway/controller/DebugController.java` — `POST /debug/parse` takes `{smsText}` -> `ParsedResult` (dev only).

**What it does:** Isolates AI; strictly reads message.content; handles outages.

**Prerequisite (done Nov 1):** `export META_API_KEY=<your-key>; python3 test.py` lists `muse-spark-1.3`. Key verified working on Meta Model API (models + text + vision probes all passed). `response_format: json_object` is supported — keep it.

**How to test:**
```bash
export META_API_KEY=<your-key>
mvn spring-boot:run &
curl -X POST http://localhost:8080/debug/parse -H "Content-Type: application/json" -d '{"smsText":"Rs.100.37 credited to A/c XX1234 on 30-10-24 14:32 via UTR 123456789012 from RAHUL@okaxis"}' | jq
# expect VERIFIED + 100.37
curl -X POST http://localhost:8080/debug/parse -H "Content-Type: application/json" -d '{"smsText":"Your OTP is 482910"}' | jq .verdict # NOT_A_PAYMENT
```

---

## Step 7 — Verify + Match (POST /payments/verify, @Transactional Audit) [x] (live-verified Nov 1: PAID match, Duplicate UTR, expired rejection)

**Files to create/change:**
- `src/main/java/com/example/upigateway/dto/VerifySmsRequest.java` — `{ @NotBlank String smsText }`.
- `src/main/java/com/example/upigateway/dto/VerifyPaymentResponse.java` — `paymentId, verdict, reason, amount, utr, matchedOrderId, orderStatus` (nullable `matchedOrderId` when no order matched).
- `src/main/java/com/example/upigateway/service/PaymentService.java` — `@Service` core `@Transactional public VerifyPaymentResponse verifySms(String smsText)` delegating to private `matchAndSettle(ParsedResult parsed, String rawMessage)` (shared with Step 8):
  1. parsed = paymentParser.parseSms(smsText); normalize UTR `""`->null, amount `setScale(2,HALF_UP)` (match side; create side in Step 3 already stores scale 2).
  2. Create Payment always: id=UUID, amount, utr, sender, verdict, reason, rawMessage, receivedAt=now, order=null.
  3. If verdict!=VERIFIED or amount==null or (SMS and direction!=credit) -> save audit, return (201, matchedOrderId null). The credit gate applies to SMS only — screenshots skip it (a payer's success screen shows a debit).
  4. Else lookup PENDING orders by amount (earliest first). If none -> second lookup for EXPIRED by amount: if found, set verdict SUSPICIOUS, reason `"Order expired — 5 min window passed. Please create a new order and pay again."`, link the expired order, save, return with orderStatus EXPIRED (UI shows banner + Retry). If neither -> save audit (order null), return.
  5. If utr!=null && existsByUtr(utr) -> SUSPICIOUS "Duplicate UTR", save audit with UTR nulled (UNIQUE would reject the duplicate value; response still carries the parsed UTR), return. Same nulling guard in the EXPIRED branch for re-submits.
  6. Else order.setStatus(PAID); order.setPaidAt(now); payment.setOrder(order); save both in ONE @Transactional. Race Safety: catch `DataIntegrityViolationException`/`OptimisticLockingFailureException` OUTSIDE the tx boundary and re-save the losing attempt as a new SUSPICIOUS audit ("Payment already processed") in a fresh tx, so "every Payment saved" still holds.
- `src/main/java/com/example/upigateway/controller/PaymentController.java` — `@PostMapping("/payments/verify")` takes `@Valid VerifySmsRequest` -> always 201 with `VerifyPaymentResponse` (no 422; callers branch on `verdict`/`orderStatus`).
- `src/main/java/com/example/upigateway/config/GlobalExceptionHandler.java` — map `@Valid` failures -> 400 shape, missing resources -> 404 shape, exhaustion/races -> 409 shape.
- `OrderRepository`: change `findByUniqueAmountAndStatus` to return `List<Order>` ordered earliest-first (Step 7 needs "earliest PENDING wins", not Optional).
- `Payment.reason`: update to `@Column(length=1000)`.

**What it does:** All-or-nothing PAID + full audit; UTR unique + @Version prevent double-spend. Late SMS against an expired order is rejected as expired and the user is asked to retry with a new order.

**How to test:**
```bash
mvn spring-boot:run &
RESP=$(curl -s -X POST http://localhost:8080/orders -H "Content-Type: application/json" -d '{"baseAmount":"100.00","merchantUpiId":"shop@okicici"}')
AMT=$(echo $RESP|jq -r .uniqueAmount); OID=$(echo $RESP|jq -r .id)
curl -s -X POST http://localhost:8080/payments/verify -H "Content-Type: application/json" -d "{\"smsText\":\"Rs.$AMT credited via UTR 123456789012 from buyer@upi\"}" | jq
# expect VERIFIED, matchedOrderId==OID, orderStatus PAID
curl -s http://localhost:8080/orders/$OID | jq .status # PAID
# duplicate UTR:
RESP2=$(curl -s -X POST http://localhost:8080/orders -H "Content-Type: application/json" -d '{"baseAmount":"100.00","merchantUpiId":"shop@okicici"}')
AMT2=$(echo $RESP2|jq -r .uniqueAmount)
curl -s -X POST http://localhost:8080/payments/verify -H "Content-Type: application/json" -d "{\"smsText\":\"Rs.$AMT2 credited via UTR 123456789012 from buyer2@upi\"}" | jq .reason # Duplicate UTR
# expired order (run app with --app.expiry-minutes=1 for this check):
# verify correct-amount SMS after expiry -> SUSPICIOUS "Order expired..." + matchedOrderId set + orderStatus EXPIRED
```

---

## Step 8 — Screenshot Verification (Image to Muse) [x] (live-verified Nov 1: screenshot → VERIFIED + PAID)

**Pre-check (done Nov 1):** a real `image_url` vision call to `muse-spark-1.3` on Meta Model API succeeded — the model accepts and answers image inputs. (If vision ever fails on a future model, fallback: text-only verification + manual amount/UTR entry.)

**Files to create/change:**
- `PaymentParser` add `parseImage(byte[] bytes, String contentType)` — vision message [{type:"text",text:"Extract..."}, {type:"image_url",image_url:{url:"data:<type>;base64,<b64>"}}] same model, same failure->SUSPICIOUS.
- `PaymentService` add `@Transactional verifyImage(MultipartFile image)` — validate content-type jpeg/jpg/png (else 400) and non-empty (else 400), then the SAME `matchAndSettle` audit+match path as Step 7.
- `PaymentController` add `@PostMapping("/payments/verify-image", consumes=MULTIPART)` with `@RequestParam("image") MultipartFile image` -> 201 `VerifyPaymentResponse`. (`spring.servlet.multipart.max-file-size=5MB` already in props.)

**What it does:** Same guarantees for screenshots.

**How to test:**
```bash
curl -s -X POST http://localhost:8080/payments/verify-image -F "image=@/tmp/pay.jpg" | jq
# expect amount+utr if payment, NOT_A_PAYMENT if not
curl -s -X POST http://localhost:8080/payments/verify-image -F "image=@/tmp/selfie.jpg" | jq .verdict # NOT_A_PAYMENT
# payments are audited — list them via Step 9 GET /payments
```

---

## Step 9 — Read Endpoints [x] (live-verified Nov 1: lists + ?status/?orderId filters)

**Files to create/change:**
- `OrderController` add `GET /orders` -> list all, optional `?status=PENDING` (uses Step 2 `findByStatus`). `GET /orders/{id}` already exists from Step 3.
- `PaymentController` add `GET /payments` -> all desc by receivedAt (uses Step 2 `findAllByOrderByReceivedAtDesc`), optional filter `?orderId` (uses `findByOrder_Id`).
- `OrderRepository` add `findByStatus`; `PaymentRepository` add `findByOrder_Id` + `findAllByOrderByReceivedAtDesc` (if not already added in Step 7).

**What it does:** Full history for demo. Unbounded lists are fine at demo scale.

**How to test:**
```bash
curl -s http://localhost:8080/orders | jq
curl -s http://localhost:8080/orders/$OID | jq
curl -s http://localhost:8080/payments | jq
curl -s "http://localhost:8080/payments?orderId=$OID" | jq length
```

---

## Step 10 — Web Page (static HTML/JS) [x] (served live: index/app.js/footer; click-through in a real browser still yours to try)

**Files to create/change:**
- `src/main/resources/static/index.html` — Create form (baseAmount, merchantUpiId), result card (uniqueAmount, copyable upiLink `upi://` + copy button for desktop, QR `<img src=/orders/{id}/qr>` + 5-min countdown timer), Verify SMS (textarea + button), Verify Image (file + button), result panel (verdict chip green/amber/grey, reason, fields), Order list (poll `GET /orders` 5s, badges PENDING/PAID/EXPIRED), Payment history table (`GET /payments`), Expired banner + Retry button (pre-fills baseAmount/merchantUpiId). Footer Built with Muse Spark.
- `src/main/resources/static/app.js` — fetch create->QR+start 5-min timer from `createdAt + 5m`, verify JSON vs FormData, polls. Countdown is display-only: ALWAYS poll `GET /orders/{id}` for the true status (server expiry runs every ~60s, so EXPIRED can lag the timer). On `orderStatus==EXPIRED` show `"Payment failed — time expired. Please create a new order and pay again."` + Retry.
- `src/main/resources/static/style.css` — centered card.

**What it does:** End-to-end demo loop.

**How to test:**
```bash
mvn spring-boot:run
# Open http://localhost:8080
# 1) Enter 100 + shop@upi -> Create -> 100.xx + QR
# 2) Paste correct-amount SMS -> VERIFIED PAID
# 3) Paste OTP -> NOT_A_PAYMENT, history +1
# 4) Upload JPG -> VERIFIED via vision
curl -s http://localhost:8080/ | grep -i "Built with Muse Spark"
```

---

## Step 11 (Optional, skip for Nov 4) — Postgres Alternative [ ]

**Files to create/change:**
- `pom.xml` — add `org.postgresql:postgresql` runtime (keep MySQL default).
- No code change: run with `--spring.datasource.url=jdbc:postgresql://localhost:5432/upigw --spring.datasource.username=... --spring.datasource.password=... --spring.datasource.driverClassName=org.postgresql.Driver`.

**What it does:** Zero code change to another DB via property overrides.

**How to test:**
```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:postgresql://localhost:5432/upigw --spring.datasource.username=postgres --spring.datasource.password=... --spring.datasource.driverClassName=org.postgresql.Driver" &
curl -s http://localhost:8080/orders | jq
```

---

## Step 12 — Android SMS Webhook [x] (live-verified Nov 2: alias/malformed/plain bodies → 201, secret off/on incl. 401s)

**Files to create/change:**
- `src/main/java/com/example/upigateway/dto/SmsWebhookRequest.java` — `{from, text}` with `@JsonAlias` for forwarder variants (`sender`/`address`/`number`, `message`/`body`/`sms`) + static `lenientParse` (normal JSON first; fallback extracts text between the text-key and the final closing quote with newlines→spaces; last resort treats the whole body as the SMS; null only for a missing body; never throws).
- `PaymentController` add `POST /payments/sms-webhook` (raw `String` body + optional `X-Webhook-Secret` header) -> 401 on secret mismatch when `webhook.secret` is set, 400 only when no SMS text at all, else delegates to the same `verify()` flow (same 201 + race-loss handling). Envelope sender is informational; the AI extracts the real sender.
- `src/main/resources/application.properties` — `webhook.secret=${WEBHOOK_SECRET:}` (empty = open endpoint).
- `src/test/java/com/example/upigateway/dto/SmsWebhookRequestTest.java` — primary/alias deserialization + lenient cases (raw quotes/newlines/backslash, alias+malformed, plain text, missing body).

**What it does:** An Android forwarder app (MacroDroid: SMS Received → HTTP POST) pushes each SMS to `http://<laptop-ip>:8080/payments/sms-webhook` on the same network — no pasting, same AI + match path. Server listens on all interfaces (`*:8080`). MacroDroid inserts the SMS unescaped, so strict JSON binding would 400 on real bank SMS — hence the raw-body + lenient parse (never 400 for malformed JSON).

**How to test:**
```bash
curl -s -X POST http://localhost:8080/payments/sms-webhook -H "Content-Type: application/json" -d '{"from":"VM-HDFCBK","text":"Rs.<AMT> credited ... UTR <fresh12> ..."}' | jq
# expect VERIFIED + matchedOrderId + PAID, same as /payments/verify
```

---

## Build Order Summary

1. Skeleton -> 2. Entities+Repos -> 3. Create+UPI -> 4. QR -> 5. Expiry scheduler -> 6. AI parser (SMS) + debug endpoint -> 7. Verify+@Transactional+Audit+UTR+Version+expired-rejection (+repo tweaks) -> 8. Screenshot (after vision spike) -> 9. Read (+repo filters) -> 10. Web (-> 11. Postgres only if time)

> Rules: BigDecimal precision 10 scale 2 never double (setScale(2) on both write and match sides); @Transactional all-or-nothing; every Payment saved (UTR ""->null; re-save lost races as SUSPICIOUS in a fresh tx); UTR unique + @Version + synchronized createOrder for races; controllers return DTOs, never entities; footer present. No login/React/microservices.
