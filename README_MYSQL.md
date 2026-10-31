# MySQL Setup for UPI Gateway

This project now uses MySQL instead of H2 so you can see real SQL queries.

## 1. Start MySQL with Docker

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
docker exec -it upi-mysql mysql -uroot -proot -e "SHOW DATABASES; USE upidb; SHOW TABLES;"
```

Or use MySQL Workbench: host=localhost port=3306 user=root pass=root db=upidb

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
docker exec -it upi-mysql mysql -uroot -proot upidb -e "SELECT id, base_amount, unique_amount, status, merchant_upi_id FROM orders;"
docker exec -it upi-mysql mysql -uroot -proot upidb -e "SELECT id, amount, utr, verdict, reason FROM payments;"
```

## 5. Without Docker (local MySQL installed)
Create DB manually:
```sql
CREATE DATABASE upidb;
```
Update `src/main/resources/application.properties` if your user/pass differs from root/root.

## 6. What changed from H2
- `pom.xml`: `com.h2database:h2` → `com.mysql:mysql-connector-j`
- `application.properties`: `jdbc:h2:file:./data/upidb` → `jdbc:mysql://localhost:3306/upidb...`
- Added:
  spring.jpa.show-sql=true
  spring.jpa.properties.hibernate.format_sql=true
  logging.level.org.hibernate.SQL=DEBUG
  logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE
- Added `docker-compose.yml` for one-command MySQL
