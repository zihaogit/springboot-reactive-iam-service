# Spring Boot Reactive IAM Service (`springboot-reactive-iam-service`)

A production-ready reactive authentication and IAM microservice built with Spring Boot WebFlux, R2DBC (PostgreSQL), Redis, Apache Kafka, and FusionAuth integration.

## 🚀 Key Features

- **Reactive WebFlux Stack**: Non-blocking I/O throughout the request lifecycle.
- **Dual Persistence**:
  - **PostgreSQL + R2DBC**: Reactive persistence for user identities, roles, bulk job execution logs, and webhook audit events.
  - **Redis 7 (Reactive)**: High-speed in-memory store for OTPs, verification codes, and password reset tokens with automatic TTL expiration.
- **Apache Kafka & Distributed Bulk Architecture**:
  - **Asynchronous UUID Pattern**: Fast pre-validation with immediate `202 Accepted` returning a tracking Job UUID.
  - **Kafka Producer & Consumer**: Durable topic partitioning (`bulk-jobs`) with non-blocking retries via `@RetryableTopic` and Dead Letter Queue (`bulk-jobs-dead-letter`).
  - **Pluggable Engine**: Seamless fallback to in-memory reactive worker (`@Profile("!kafka")`) or full Apache Kafka cluster (`@Profile("kafka")`).
  - **Kafka-UI Web Console**: Included out of the box on port `8085`.
- **FusionAuth Integration & Webhooks**: Supports external identity providers and transactional webhook consumption with idempotency guarantees.
- **Containerized & Kubernetes-Ready**:
  - Multi-stage `Dockerfile` with distroless/alpine runtime.
  - Kubernetes `Deployment` & `Service` for high availability.
  - Kubernetes `CronJob` specifications for automated database pruning and maintenance (including bulk job sweeper/reaper).

---

## 📋 API Endpoints Overview

| Method | Endpoint | Description | Access |
| :--- | :--- | :--- | :--- |
| `POST` | `/auths/register` | Register new user account | Public |
| `POST` | `/auths/verify-email` | Verify registration OTP code | Public |
| `POST` | `/auths/login` | Authenticate with credentials | Public |
| `POST` | `/auths/refresh` | Refresh expired JWT access token | Public |
| `POST` | `/auths/fp` | Request forgot password OTP | Public |
| `POST` | `/auths/reset-password` | Reset password using verified OTP | Public |
| `POST` | `/auths/social/login` | Initiate social login provider redirect | Public |
| `POST` | `/auths/social/callback` | Handle OAuth2/OIDC social callback | Public |
| `POST` | `/webhooks/fusionauth` | Ingest FusionAuth event webhooks | Signature Verified |
| `GET` | `/users?userId={id}` | Get user by ID | `USER` or `ADMIN` |
| `PUT` | `/users/update?userId={id}` | Update user details | `USER` or `ADMIN` |
| `DELETE` | `/users?userId={id}` | Delete user | `ADMIN` |
| `POST` | `/api/users` | Single user load (synchronous / reactive) | Public / User |
| `POST` | `/api/users/bulk` | Bulk user load with envelope pattern & async UUID | `ADMIN` (`ROLE_ADMIN`) |
| `GET` | `/api/jobs/{jobId}` | Poll bulk job execution status & row-level error details | `ADMIN` (`ROLE_ADMIN`) |

---

## ⏱️ Scheduled CronJobs & Maintenance

Automated database maintenance and cleanup tasks are managed via **Kubernetes CronJobs** and monitored through **K9s**:

| CronJob Name | Schedule | Target | Description |
| :--- | :--- | :--- | :--- |
| `cleanup-expired-otps` | Daily at 02:00 AM | `verification_otp` | Deletes expired verification OTPs & tokens older than 24h |
| `purge-webhook-events` | Weekly (Sun 03:00 AM) | `processed_webhook_events` | Purges idempotency webhook records older than 30 days |
| `cleanup-unverified-users` | Weekly (Sun 04:00 AM) | `users` | Prunes abandoned unverified accounts without active OTPs |
| `cleanup-stuck-bulk-jobs` | Every 15 minutes | `bulk_jobs` | Sweeper/Reaper marking orphaned jobs stuck in `PROCESSING` (>30m) as `FAILED` |

---

## 🐳 Local Development Infrastructure

Start the full development and observability stack including PostgreSQL, Redis, FusionAuth, MailDev, Apache Kafka, Kafka-UI, Prometheus, Zipkin, and Grafana:

```powershell
docker compose up -d
```

### 🌐 Localhost Port Reference Table

| Service / Tool | Localhost URL / Port | Credentials / Notes | Purpose |
| :--- | :--- | :--- | :--- |
| **Spring Boot Application** | [http://localhost:8080](http://localhost:8080) | — | Reactive WebFlux IAM Microservice |
| **Swagger UI** | [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) | — | Interactive OpenAPI Documentation |
| **Actuator Metrics** | [http://localhost:8080/actuator/prometheus](http://localhost:8080/actuator/prometheus) | — | Prometheus Metrics Scrape Endpoint |
| **Grafana Dashboard** | [http://localhost:3000/d/iam-service-observability/iam-service-observability-and-errors](http://localhost:3000/d/iam-service-observability/iam-service-observability-and-errors) | `admin` / `admin` | Pre-built IAM Observability & Error Dashboard |
| **Zipkin Trace Console** | [http://localhost:9411/zipkin/?annotationQuery=error](http://localhost:9411/zipkin/?annotationQuery=error) | — | Trace & Error Code (`error.code`, `AUTH-XXXXXX`) Flamegraphs |
| **Prometheus Console** | [http://localhost:9090/graph?g0.expr=auth_errors_total&g0.tab=0](http://localhost:9090/graph?g0.expr=auth_errors_total&g0.tab=0) | — | Live Error Metric Counter & PromQL Engine |
| **Dozzle Container Logs** | [http://localhost:8888](http://localhost:8888) | — | Real-time Web Log Viewer for all Docker Containers |
| **Kafka-UI Console** | [http://localhost:8085](http://localhost:8085) | — | Kafka Topics & Bulk Job Consumer Monitor |
| **MailDev Web UI** | [http://localhost:1080](http://localhost:1080) | SMTP: `1025` | Mock Email Inbox for OTP Verification Emails |
| **FusionAuth Admin** | [http://localhost:9011](http://localhost:9011) | Set on initial setup | OAuth2 / OIDC Federated Identity Provider |
| **PostgreSQL Database** | `localhost:5432` | User: `postgres`, DB: `security_assignment` | Relational Persistence (R2DBC / Flyway) |
| **Redis Cache** | `localhost:6379` | Auth pass in `.env` | Reactive In-Memory Store (OTPs / Tokens) |
| **Apache Kafka Broker** | `localhost:9092` | Internal: `29092` | Event-driven Messaging for Bulk Jobs |

### 🔍 Error Monitoring Quick Search

| Search Target | Tool | How to Search / Direct Link |
| :--- | :--- | :--- |
| **By Trace ID** (`traceId`) | **Zipkin** | Direct URL: `http://localhost:9411/zipkin/traces/<traceId>` (views timing flamegraph & stack trace) |
| **By Error Code** (`AUTH-XXXXXX`) | **Zipkin** | [http://localhost:9411/zipkin/?annotationQuery=error.code%3DAUTH-000101](http://localhost:9411/zipkin/?annotationQuery=error.code%3DAUTH-000101) *(Click **RUN QUERY**)* |
| **All Error Traces** | **Zipkin** | [http://localhost:9411/zipkin/?annotationQuery=error](http://localhost:9411/zipkin/?annotationQuery=error) *(Click **RUN QUERY**)* |
| **Error Metrics & Charts** | **Grafana** | [IAM Service Dashboard](http://localhost:3000/d/iam-service-observability/iam-service-observability-and-errors) *(cumulative errors, donut distribution, total counters)* |
| **Error Counters (PromQL)** | **Prometheus** | [auth_errors_total Graph](http://localhost:9090/graph?g0.expr=auth_errors_total&g0.tab=0) *(scraped every 5s from `/actuator/prometheus`)* |
| **Live App & Error Logs** | **Dozzle** | [http://localhost:8888](http://localhost:8888) &rarr; select **`my_iam_service`** for live container console logs |


---

## 📮 Postman Collection & Smoke Testing Guide

The repository includes a ready-to-run Postman collection and local environment under the `postman/` directory:
- **Collection**: [Springboot-BasicAuth-Webflux.postman_collection.json](file:///C:/Users/Laggerbomb/OneDrive/Documents/Default%20Project/Springboot-BasicAuth-Webflux/postman/Springboot-BasicAuth-Webflux.postman_collection.json)
- **Environment**: [Local.postman_environment.json](file:///C:/Users/Laggerbomb/OneDrive/Documents/Default%20Project/Springboot-BasicAuth-Webflux/postman/Local.postman_environment.json)

### Collection Structure

1. **Auth**: User registration, OTP email verification, Basic Auth & JSON login, JWT refresh token, password reset, and logout.
2. **Users**: Admin list all users, get user by ID, update user profile, delete user.
3. **Social Login**: OAuth2 / OIDC federated login redirects (Google, Facebook, X/Twitter).
4. **Bulk Load (Kafka)** *(New)*:
   - `Single User Load`: `POST /api/users` (Synchronous / Reactive lane).
   - `Submit Bulk Job`: `POST /api/users/bulk` with `X-Idempotency-Key` and auto-saved `jobId`.
   - `Submit Bulk Job (no idempotency key)`: Standard submission without deduplication header.
   - `Submit Bulk Job (validation errors)`: Test error handling with invalid fields (`continueOnError: true`).
   - `Get Job Status`: `GET /api/jobs/{{jobId}}` polling endpoint (returns status, counts, and error array).
   - `Submit Bulk Job (Unauthorized - USER role)`: Verifies role enforcement (`401`/`403`).

### 🧪 Verification Steps (Single vs Bulk Lane)

#### 1. Single Load Verification (Synchronous Lane)
- **Request**: Send `Single User Load` (`POST /api/users`)
- **Expectation**: `201 Created` immediately with user details in response body.
- **DB Verification**:
  ```sql
  SELECT id, username, email, active FROM users WHERE username = 'alice';
  ```

#### 2. Bulk Load Verification (Asynchronous UUID + Kafka Lane)
- **Request**: Send `Submit Bulk Job` (`POST /api/users/bulk`) with Basic Auth (`admin` / `admin12345`).
- **Expectation**: `202 Accepted` with payload:
  ```json
  {
    "jobId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "status": "ACCEPTED"
  }
  ```
- **Kafka Verification**:
  - Open **Kafka-UI** at [http://localhost:8085](http://localhost:8085).
  - Open topic `bulk-jobs`: inspect message containing the payload envelope.
  - Consumer group `bulk-jobs-group` will process the records and commit offset.
- **Poll Status**: Send `Get Job Status` (`GET /api/jobs/{{jobId}}`):
  - Returns `status: "COMPLETED"`, `totalRecords: 3`, `successCount: 3`, `failCount: 0`.
- **DB Verification**:
  ```sql
  -- Verify job execution header:
  SELECT id, idempotency_key, status, total_records, success_count, fail_count, submitted_by
  FROM bulk_jobs WHERE id = 'YOUR-JOB-ID';

  -- Verify created user accounts:
  SELECT id, username, email FROM users WHERE username IN ('bulkuser1', 'bulkuser2', 'bulkuser3');
  ```

