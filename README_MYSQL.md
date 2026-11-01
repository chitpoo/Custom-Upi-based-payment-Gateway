# MySQL Setup for UPI Gateway

This project uses MySQL so you can see real SQL queries. DB name is `paymentgateway`
(adjust `application.properties` if your local user/password differ from root/9599).

## 1. Option A — local MySQL (used for development)

Make sure your local MySQL 8 server is running on port 3306. The app URL has
`createDatabaseIfNotExist=true`, so the `paymentgateway` database is created
automatically on first run.

```bash
mysql -uroot -p -e "SELECT 1;"  # enter your password, expect 1
```

## 1. Option B — MySQL with Docker

Install Docker Desktop from https://www.docker.com/products/docker-desktop/
Then run:

```bash
docker compose up -d
docker ps  # should show upi-mysql
```

Check logs:
```bash
docker logs upi-mysql
```

## 2. Verify DB exists
```bash
mysql -uroot -p -e "SHOW DATABASES; USE paymentgateway; SHOW TABLES;"
```

Or use MySQL Workbench: host=localhost port=3306 user=root db=paymentgateway

## 3. Run the app
```bash
mvn spring-boot:run
```

You will see SQL in console like:
```
Hibernate: insert into orders (base_amount, ...) values (?, ?, ...)
TRACE - binding parameter [1] as [NUMERIC] - [100.37]
```

## 4. Query manually to learn SQL
```bash
mysql -uroot -p paymentgateway -e "SELECT id, base_amount, unique_amount, status, merchant_upi_id FROM orders;"
mysql -uroot -p paymentgateway -e "SELECT id, amount, utr, verdict, reason FROM payments;"
```

## 5. Without Docker (local MySQL installed)
Nothing to create manually — `createDatabaseIfNotExist=true` handles it.
Update `src/main/resources/application.properties` if your user/pass differs.

## 6. What changed from H2
- `pom.xml`: `com.h2database:h2` → `com.mysql:mysql-connector-j`
- `application.properties`: `jdbc:h2:file:./data/upidb` → `jdbc:mysql://localhost:3306/paymentgateway...`
- Added:
  spring.jpa.show-sql=true
  spring.jpa.properties.hibernate.format_sql=true
  logging.level.org.hibernate.SQL=DEBUG
  logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE
- Added `docker-compose.yml` for one-command MySQL
