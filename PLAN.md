# UPI Payment Verification Gateway — Build Plan

> Solo hackathon. Merchant creates Rs.100 order -> system assigns Rs.100.37 (unique paise 0.01-0.99 not used by any PENDING order) -> UPI link `upi://pay?pa=<merchantUpi>&am=<amount>&tn=<orderId>` + QR -> customer pays direct to merchant UPI (we never hold money) -> merchant pastes SMS / uploads screenshot -> Muse Spark Open-1026 via Baseten OpenAI-compatible API verifies -> match amount -> PENDING -> PAID if VERIFIED+credit. 10-min expiry. Web page: create + QR, verify, status. Footer: "Built with Muse Spark Open".

> Tech: Java 17, Spring Boot 3.x, Maven, Spring Web, Spring Data JPA, H2 file mode, Spring Validation. Model: `meta-models/muse-spark-open-1026` at `https://inference.baseten.co/v1`, key from env `BASETEN_API_KEY`. All money `BigDecimal` (precision 10, scale 2). No login, no React, no microservices.

---

## Step 1 — Project Skeleton

**Files to create/change:**
- `pom.xml` — parent `spring-boot-starter-parent` 3.2.x (Java 17). Dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `com.h2database:h2`, `com.google.zxing:core` + `javase` 0.7.x (for Step 4). Build: `spring-boot-maven-plugin`.
- `src/main/java/com/example/upigateway/UpiGatewayApplication.java` — `@SpringBootApplication` + `@EnableScheduling`.
- `src/main/resources/application.properties`:
  ```
  server.port=8080
  spring.datasource.url=jdbc:h2:file:./data/upidb
  spring.datasource.driverClassName=org.h2.Driver
  spring.jpa.hibernate.ddl-auto=update
  spring.jpa.show-sql=false
  spring.h2.console.enabled=true
  spring.h2.console.path=/h2-console
  baseten.api.key=${BASETEN_API_KEY:}
  baseten.api.baseUrl=https://inference.baseten.co/v1
  baseten.api.model=meta-models/muse-spark-open-1026
  app.expiry-minutes=10
  ```
- `.gitignore` — `/data/`, `target/`, `.idea/`.

**What it does:** Bootable app with Tomcat + JPA + H2 file DB at `./data/upidb.mv.db`. Proves tooling before domain code.

**How to test:**
```bash
mvn compile
mvn spring-boot:run
curl http://localhost:8080/ # 404 Whitelabel = Tomcat up
curl http://localhost:8080/h2-console # HTML login page
ls -lh ./data/upidb.mv.db # file created
```

---

## Step 2 — Entities + Repositories

**Files to create/change:**
- `src/main/java/com/example/upigateway/model/OrderStatus.java` — `PENDING, PAID, EXPIRED`.
- `src/main/java/com/example/upigateway/model/Order.java` — `@Entity @Table(name="orders")` with `@Id String id` (UUID), `@Column(precision=10,scale=2,nullable=false) BigDecimal baseAmount`, `uniqueAmount` same precision, `merchantUpiId` (`@NotBlank @Pattern` UPI regex), `@Enumerated(STRING) OrderStatus status`, `Instant createdAt`, `Instant paidAt` nullable, `@Version Long version` (optimistic lock).
- `src/main/java/com/example/upigateway/model/Payment.java` — `@Entity @Table(name="payments")` with `@Id String id` (UUID), `@ManyToOne(fetch=LAZY) Order order` nullable FK, `@Column(precision=10,scale=2) BigDecimal amount` nullable if NOT_A_PAYMENT, `@Column(unique=true,length=20) String utr` UNIQUE nullable, `sender`, `@Enumerated(STRING) Verdict verdict` (VERIFIED/SUSPICIOUS/NOT_A_PAYMENT), `reason`, `@Column(length=4000) String rawMessage`, `Instant receivedAt`.
- `src/main/java/com/example/upigateway/model/Verdict.java` — `VERIFIED, SUSPICIOUS, NOT_A_PAYMENT`.
- `src/main/java/com/example/upigateway/repo/OrderRepository.java` — `JpaRepository<Order,String>` + `findByStatusAndCreatedAtBefore(PENDING, cutoff)` + `existsByUniqueAmountAndStatus` + `findByUniqueAmountAndStatus`.
- `src/main/java/com/example/upigateway/repo/PaymentRepository.java` — `JpaRepository<Payment,String>` + `findByUtr` + `existsByUtr`.

**What it does:** Persistent domain with JPA constraints. `@Version` + `UNIQUE(utr)` are race guards for Step 7.

**How to test:**
```bash
mvn compile
mvn spring-boot:run
# In h2-console http://localhost:8080/h2-console (JDBC URL jdbc:h2:file:./data/upidb, user sa):
# SHOW COLUMNS FROM ORDERS; SHOW COLUMNS FROM PAYMENTS; -> see VERSION and UNIQUE on UTR
```

---

## Step 3 — Create Order (Unique Amount + UPI Link)

**Files to create/change:**
- `src/main/java/com/example/upigateway/dto/CreateOrderRequest.java` — `BigDecimal baseAmount` (`@NotNull @DecimalMin("0.01") @Digits(integer=10,fraction=2)`), `String merchantUpiId` (`@NotBlank`).
- `src/main/java/com/example/upigateway/service/OrderService.java` — `createOrder(baseAmount, merchantUpiId)`: normalize `baseAmount.setScale(2,HALF_UP)`, validate >0; loop up to 100 tries: paise=1..99 random, candidate = baseAmount.add(new BigDecimal(paise).movePointLeft(2)), check `!orderRepository.existsByUniqueAmountAndStatus(candidate,PENDING)`; build `upiLink="upi://pay?pa="+encode(merchantUpiId)+"&am="+candidate.toPlainString()+"&tn="+orderId+"&cu=INR"`; save Order PENDING.
- `src/main/java/com/example/upigateway/controller/OrderController.java` — `@RestController @RequestMapping("/orders")`: `POST /orders` -> 201 {id, baseAmount, uniqueAmount, merchantUpiId, status, createdAt, upiLink}, `GET /orders/{id}` -> 200/404.

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
```

---

## Step 4 — QR Code for UPI Link

**Files to create/change:**
- `src/main/java/com/example/upigateway/service/QrService.java` — ZXing: `QRCodeWriter -> BitMatrix -> MatrixToImageWriter.writeToStream(..., "PNG", out)` 220x220.
- `OrderController` add `GET /orders/{id}/qr` -> produces `image/png` bytes.

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

## Step 5 — Expiry (10-min Reclaim)

**Files to create/change:**
- `src/main/java/com/example/upigateway/service/ExpiryService.java` — `@Component @Scheduled(fixedRate=60_000)` queries `findByStatusAndCreatedAtBefore(PENDING, now.minus(expiryMinutes,MINUTES))`, set EXPIRED, save, log count.

**What it does:** Frees paise: 100.37 PENDING >10m -> EXPIRED -> reusable.

**How to test:**
```bash
# fast: set app.expiry-minutes=1, fixedRate=10_000, restart:
mvn spring-boot:run &
RESP=$(curl -s -X POST http://localhost:8080/orders -H "Content-Type: application/json" -d '{"baseAmount":"20.00","merchantUpiId":"test@upi"}')
ID=$(echo $RESP|jq -r .id); sleep 70; curl -s http://localhost:8080/orders/$ID | jq .status # expect EXPIRED
# restore 10 before next
```

---

## Step 6 — AI Parser (Muse Spark Open-1026, Text)

**Files to create/change:**
- `src/main/java/com/example/upigateway/service/PaymentParser.java` — `@Service` reads BASETEN_API_KEY/baseUrl/model from props, uses HttpClient+Jackson, method `ParsedResult parseSms(String smsText)` builds OpenAI-compatible {model:"meta-models/muse-spark-open-1026",temperature:0,messages:[{role:"system",content:"You are UPI SMS parser. Extract amount (\"100.37\"), utr (12-digit), sender (UPI/name), time, direction credit/debit, verdict VERIFIED|SUSPICIOUS|NOT_A_PAYMENT with reason. Return JSON only."},{role:"user",content:smsText}]} POST to https://inference.baseten.co/v1/chat/completions with Bearer key. Parse only message.content -> ParsedResult. On IOException/non-2xx/missing key -> return SUSPICIOUS "Verification service unavailable".
- `src/main/java/com/example/upigateway/model/ParsedResult.java` — amount(BigDecimal), utr, sender, timeText, direction, verdict, reason, rawJson.

**What it does:** Isolates AI; strictly reads message.content; handles outages.

**How to test:**
```bash
export BASETEN_API_KEY=sk-...
mvn spring-boot:run &
curl -X POST http://localhost:8080/debug/parse -H "Content-Type: application/json" -d '{"smsText":"Rs.100.37 credited to A/c XX1234 on 30-10-24 14:32 via UTR 123456789012 from RAHUL@okaxis"}' | jq
# expect VERIFIED + 100.37
curl -X POST http://localhost:8080/debug/parse -H "Content-Type: application/json" -d '{"smsText":"Your OTP is 482910"}' | jq .verdict # NOT_A_PAYMENT
```


---

## Step 7 — Verify + Match (POST /payments/verify, @Transactional Audit)

**Files to create/change:**
- `src/main/java/com/example/upigateway/service/PaymentService.java` — `@Service` core `@Transactional public Payment verifySms(String smsText)`:
  1. parsed = paymentParser.parseSms(smsText)
  2. Create Payment always: id=UUID, amount, utr, sender, verdict, reason, rawMessage=smsText, receivedAt=now, order=null
  3. If verdict!=VERIFIED or amount==null or direction!=credit -> save audit, return
  4. Else lookup findByUniqueAmountAndStatus(parsed.amount.setScale(2,HALF_UP),PENDING)
  5. If none -> save audit (order null), return
  6. If utr!=null && existsByUtr(utr) -> SUSPICIOUS "Duplicate UTR", save audit, return
  7. Else order.setStatus(PAID); order.setPaidAt(now); payment.setOrder(order); paymentRepository.save(payment); orderRepository.save(order); both in ONE @Transactional (catch DataIntegrityViolationException->duplicate race, OptimisticLockingFailureException->409)
- `src/main/java/com/example/upigateway/controller/PaymentController.java` — `@PostMapping("/payments/verify")` takes VerifySmsRequest { @NotBlank String smsText } -> delegates, returns 201 Payment.
- `src/main/java/com/example/upigateway/config/GlobalExceptionHandler.java` — map 409s.

**What it does:** All-or-nothing PAID + full audit; UTR unique + @Version prevent double-spend.

**How to test:**
```bash
mvn spring-boot:run &
RESP=$(curl -s -X POST http://localhost:8080/orders -H "Content-Type: application/json" -d '{"baseAmount":"100.00","merchantUpiId":"shop@okicici"}')
AMT=$(echo $RESP|jq -r .uniqueAmount); OID=$(echo $RESP|jq -r .id)
curl -s -X POST http://localhost:8080/payments/verify -H "Content-Type: application/json" -d "{\"smsText\":\"Rs.$AMT credited via UTR 123456789012 from buyer@upi\"}" | jq
# expect VERIFIED, matchedOrderId==OID
curl -s http://localhost:8080/orders/$OID | jq .status # PAID
# duplicate UTR:
RESP2=$(curl -s -X POST http://localhost:8080/orders -H "Content-Type: application/json" -d '{"baseAmount":"100.00","merchantUpiId":"shop@okicici"}')
AMT2=$(echo $RESP2|jq -r .uniqueAmount)
curl -s -X POST http://localhost:8080/payments/verify -H "Content-Type: application/json" -d "{\"smsText\":\"Rs.$AMT2 credited via UTR 123456789012 from buyer2@upi\"}" | jq .reason # Duplicate UTR
```


---

## Step 8 — Screenshot Verification (Image to Muse)

**Files to create/change:**
- `PaymentParser` add `parseImage(byte[] bytes, String contentType)` — vision message [{type:"text",text:"Extract..."}, {type:"image_url",image_url:{url:"data:<type>;base64,<b64>"}}] same model, same failure->SUSPICIOUS.
- `PaymentService` add `@Transactional verifyImage(MultipartFile image)` -> same audit+match.
- `PaymentController` add `@PostMapping("/payments/verify-image", consumes=MULTIPART)` with `@RequestParam("image") MultipartFile image` -> 201. `spring.servlet.multipart.max-file-size=5MB`.

**What it does:** Same guarantees for screenshots.

**How to test:**
```bash
curl -s -X POST http://localhost:8080/payments/verify-image -F "image=@/tmp/pay.jpg" | jq
# expect amount+utr if payment, NOT_A_PAYMENT if not
curl -s -X POST http://localhost:8080/payments/verify-image -F "image=@/tmp/selfie.jpg" | jq .verdict # NOT_A_PAYMENT
curl -s http://localhost:8080/payments | jq length # audited
```

---

## Step 9 — Read Endpoints

**Files to create/change:**
- `OrderController` add `GET /orders` -> list all (optional ?status=PENDING) + GET /orders/{id} already.
- `PaymentController` add `GET /payments` -> all desc by receivedAt, filter ?orderId.

**What it does:** Full history for demo.

**How to test:**
```bash
curl -s http://localhost:8080/orders | jq
curl -s http://localhost:8080/orders/$OID | jq
curl -s http://localhost:8080/payments | jq
curl -s "http://localhost:8080/payments?orderId=$OID" | jq length
```

---

## Step 10 — Web Page (static HTML/JS)

**Files to create/change:**
- `src/main/resources/static/index.html` — Create form (baseAmount, merchantUpiId), result card (uniqueAmount, upiLink upi://, QR <img src=/orders/{id}/qr>), Verify SMS (textarea + button), Verify Image (file + button), result panel (verdict chip green/amber/grey, reason, fields), Order list (poll GET /orders 5s, badges), Payment history table (GET /payments). Footer Built with Muse Spark Open.
- `src/main/resources/static/app.js` — fetch create->QR, verify JSON vs FormData, polls.
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
curl -s http://localhost:8080/ | grep -i "Muse Spark Open"
```

---

## Step 11 (Optional) — Switch H2 to PostgreSQL

**Files to create/change:**
- `pom.xml` — add `org.postgresql:postgresql` runtime (keep H2 default).
- `application.properties` — env overrides: `spring.datasource.url=${DB_URL:jdbc:h2:file:./data/upidb}` `spring.datasource.username=${DB_USER:sa}` etc. Or `application-postgres.properties` with postgres URL/driver.

**What it does:** Zero code change to prod DB.

**How to test:**
```bash
ls ./data/upidb.mv.db # H2 default
mvn spring-boot:run -Dspring-boot.run.profiles=postgres -Dspring.datasource.url=jdbc:postgresql://localhost:5432/upigw &
curl -s http://localhost:8080/orders | jq
```

---

## Build Order Summary

1. Skeleton -> 2. Entities+Repos -> 3. Create+UPI -> 4. QR -> 5. Expiry -> 6. AI parser (SMS) -> 7. Verify+@Transactional+Audit+UTR+Version -> 8. Screenshot -> 9. Read -> 10. Web -> 11. Postgres

> Rules: BigDecimal precision 10 scale 2 never double; @Transactional all-or-nothing; every Payment saved; UTR unique + @Version for races; footer present. No login/React/microservices.
