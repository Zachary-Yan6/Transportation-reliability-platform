# Transportation Reliability Platform

[![Backend quality gate](https://github.com/Zachary-Yan6/Transportation-reliability-platform/actions/workflows/backend-ci.yml/badge.svg)](https://github.com/Zachary-Yan6/Transportation-reliability-platform/actions/workflows/backend-ci.yml)

A Spring Boot backend for collecting Irish National Transport Authority (NTA) GTFS-Realtime data, measuring transport reliability, presenting live vehicle state, and producing delay estimates with safe fallbacks.

Frontend repository: [Transportation-reliability-platform-frontend](https://github.com/Zachary-Yan6/Transportation-reliability-platform-frontend)

## What the platform does

- Imports and versions static GTFS data: routes, stops, trips, stop times, calendars, and calendar-date exceptions.
- Polls NTA GTFS-Realtime trip updates, vehicle positions, and service alerts.
- Publishes normalized real-time events to Kafka so ingestion and downstream processing are decoupled.
- Stores historical stop-delay observations in PostgreSQL and projects current live state into Redis.
- Calculates route reliability, delay hot spots, historical trends, and route anomaly signals.
- Provides live vehicle positions and service-alert data to the frontend.
- Uses a trained Python/scikit-learn delay model where an approved model has adequate data; otherwise returns the current live or historical estimate as a fallback.
- Secures the API with JWT authentication and `USER` / `ADMIN` authorization.

## Architecture

```text
NTA static GTFS + GTFS-Realtime
             |
             v
  Spring Boot collectors / parsers
             |
             v
 Kafka topics (trip updates, vehicle positions)
       |                         |
       v                         v
PostgreSQL history          Redis live state
       |                         |
       +----------+--------------+
                  v
     Reliability, alerts and AI estimate services
                  |
          REST API + WebSocket invalidations
                  |
           React dashboard
```

The WebSocket endpoint sends lightweight invalidation events. After receiving one, the frontend refreshes the relevant REST resource; the WebSocket does not carry the full data set itself.

## Technology

| Area | Technology |
| --- | --- |
| Backend | Java 21, Spring Boot 3.5, Spring MVC, Spring Security |
| Persistence | PostgreSQL 16, MyBatis-Plus, Flyway |
| Streaming and live cache | Apache Kafka, Redis |
| Security | JWT bearer tokens, BCrypt password hashes |
| AI | Python, pandas, scikit-learn, joblib |
| Verification | JUnit 5, Mockito, MockMvc, Testcontainers, JaCoCo, SpotBugs |
| Frontend | React/Vite (maintained in the separate frontend repository) |

## Prerequisites

- JDK 21
- Maven Wrapper or Maven 3.9+
- Docker Desktop
- An NTA GTFS-Realtime subscription key
- Python 3.11+ only when training or serving the local ML model

## Quick start

### 1. Start PostgreSQL, Kafka, and Redis

The Compose file is intentionally stored with the backend source package. From the repository root, run:

```powershell
docker compose -f src/main/java/com/zachary/transportation_reliability_platform/compose.yaml up -d
```

This starts:

| Service | Host port |
| --- | --- |
| PostgreSQL | `5433` |
| Kafka | `9093` |
| Redis | `6379` |

### 2. Configure local environment variables

Set these values in the terminal used to start the application, or configure them in your IDE run configuration. Do not commit real keys or passwords.

```powershell
$env:NTA_API_KEY = "replace-with-your-NTA-key"
$env:NTA_VEHICLES_URL = "replace-with-the-NTA-vehicles-endpoint"
$env:NTA_ALERTS_URL = "replace-with-the-NTA-alerts-endpoint"

# Use a long random Base64 value outside local development.
$env:APP_JWT_SECRET = "replace-with-a-long-random-base64-secret"
$env:APP_BOOTSTRAP_ADMIN_EMAIL = "admin@example.com"
$env:APP_BOOTSTRAP_ADMIN_PASSWORD = "replace-with-a-strong-password"
$env:APP_FRONTEND_ORIGIN = "http://localhost:5173"
```

The trip-update URL and the default NTA header name (`x-api-key`) are configured in `src/main/resources/application.yml`. Vehicle and alert polling are opt-in:

```powershell
$env:NTA_VEHICLE_POLLING_ENABLED = "true"
$env:NTA_ALERT_POLLING_ENABLED = "true"
```

### 3. Start the backend

```powershell
.\mvnw.cmd spring-boot:run
```

The API starts at `http://localhost:8080`. Flyway applies database migrations automatically.

### 4. Create a user and sign in

Public registration creates a `USER` account. The bootstrap environment variables above create the initial `ADMIN` account at startup.

```powershell
curl.exe -X POST http://localhost:8080/api/v1/auth/register `
  -H "Content-Type: application/json" `
  -d '{"email":"user@example.com","password":"ChangeThisPassword123!"}'

curl.exe -X POST http://localhost:8080/api/v1/auth/login `
  -H "Content-Type: application/json" `
  -d '{"email":"user@example.com","password":"ChangeThisPassword123!"}'
```

Use the returned access token in API calls:

```text
Authorization: Bearer <access-token>
```

## Authentication and authorization

| Access | Endpoints |
| --- | --- |
| Public | `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, health endpoints, `/ws/**` |
| `USER` or `ADMIN` | Dashboard, routes, stops, trips, live vehicles, reliability and delay-estimate reads |
| `ADMIN` | NTA raw/preview/publish endpoints, GTFS import and refresh, event tooling, AI training-data endpoints, and alert creation |

`/ws/live` is public because a browser's native WebSocket handshake cannot attach an `Authorization` header. It emits only refresh notifications, not protected business data; protected REST endpoints still enforce JWT authorization.

## Core API map

The application has many detailed endpoints. The most useful user-facing reads are:

| Purpose | Endpoint |
| --- | --- |
| Dashboard summary | `GET /api/v1/dashboard/summary` |
| Route catalogue and route detail | `GET /api/v1/routes`, `GET /api/v1/routes/{routeId}` |
| Reliability ranking | `GET /api/v1/routes/reliability/worst?hours=24&limit=50` |
| Route reliability | `GET /api/v1/routes/{routeId}/reliability?hours=24` |
| Stop delay history | `GET /api/v1/stops/{stopId}/delays` |
| Live vehicle locations | `GET /api/v1/vehicles/live?routeId={routeId}` |
| Trip schedule and live delays | `GET /api/v1/trips/{tripId}/schedule`, `GET /api/v1/trips/{tripId}/live-delays` |
| A single stop delay estimate | `GET /api/v1/trips/{tripId}/stops/{stopId}/delay-estimate` |
| Batch trip estimates | `GET /api/v1/trips/{tripId}/delay-estimates` |
| Live update notifications | `ws://localhost:8080/ws/live` |

Admin development endpoints under `/api/v1/nta/**` support inspecting raw NTA feeds, previewing normalized content, manually publishing controlled snapshots, and reading polling runs. They are deliberately restricted to administrators.

Runnable request examples are available in:

- `src/main/java/com/zachary/transportation_reliability_platform/api-tests.http`
- `src/main/java/com/zachary/transportation_reliability_platform/nta-realtime.http`
- `src/main/java/com/zachary/transportation_reliability_platform/trip-update.http`
- `src/main/java/com/zachary/transportation_reliability_platform/trip-delays.http`

## Data ingestion and live-state design

1. Static GTFS must be imported before real-time trip updates can be matched to routes, trips, stops, and scheduled times.
2. The scheduler fetches NTA trip updates every five minutes by default. It is configured to process all routes unless a route filter is explicitly set.
3. Events are sent to Kafka; consumers write idempotent historical observations to PostgreSQL and current state to Redis.
4. Vehicle positions are stored as the latest value per vehicle in Redis. Historical vehicle trajectory storage is intentionally not enabled yet.
5. Alerts are persisted with their active/resolved state.

Event identifiers and database uniqueness constraints make trip-delay persistence idempotent. Redis live-state writes use timestamp-aware atomic updates so an older message cannot overwrite a newer state. Live WebSocket broadcasts use versioned debouncing to avoid a stale scheduled notification winning a later update.

## AI delay estimates

The application supports a gradual path from a baseline estimate to a trained model:

1. It first attempts a route/stop trained model when trained-model inference is enabled and a suitable artifact exists.
2. If the artifact is missing, model coverage is insufficient, inference fails, or the model is not approved, it falls back to the current live delay or historical baseline.
3. The response identifies the source so the client can distinguish a model prediction from a fallback estimate.

Training-data readiness endpoints are admin-only:

```text
GET /api/v1/ai/routes/{routeId}/training-data-status
GET /api/v1/ai/routes/ready-for-training
GET /api/v1/ai/routes/{routeId}/training-samples.csv
```

Install the training dependencies when needed:

```powershell
python -m pip install -r ml/requirements.txt
```

The `ml/` directory contains the route training, all-ready-route training, and inference scripts. Generated model artifacts under `ml/artifacts/` are environment outputs and should be managed outside source control or via an artifact store in production.

## Tests, coverage, and static analysis

Run the complete backend quality gate:

```powershell
.\mvnw.cmd verify
```

This executes unit tests, web-layer tests, integration tests with Testcontainers (PostgreSQL, Kafka, and Redis), JaCoCo coverage checks, and SpotBugs. The current JaCoCo gate applies to service and controller code:

- at least **90% line coverage**
- at least **85% branch coverage**

Useful outputs after a test run:

| Output | Path |
| --- | --- |
| JaCoCo HTML report | `target/site/jacoco/index.html` |
| SpotBugs HTML report | `target/site/spotbugs.html` |
| Unit-test reports | `target/surefire-reports/` |
| Integration-test reports | `target/failsafe-reports/` |

For fast unit tests only:

```powershell
.\mvnw.cmd test
```

GitHub Actions runs the backend quality gate on pushes and pull requests. The workflow is located at `.github/workflows/backend-ci.yml`.

## Configuration reference

| Configuration | Purpose |
| --- | --- |
| `NTA_API_KEY` | NTA GTFS-Realtime subscription key |
| `NTA_VEHICLES_URL` | NTA vehicle-position endpoint |
| `NTA_ALERTS_URL` | NTA service-alert endpoint |
| `NTA_REALTIME_POLLING_ENABLED` | Enables trip-update polling (default `true`) |
| `NTA_REALTIME_POLLING_INTERVAL_MILLIS` | Trip-update poll interval (default five minutes) |
| `NTA_VEHICLE_POLLING_ENABLED` | Enables vehicle-position polling |
| `NTA_ALERT_POLLING_ENABLED` | Enables service-alert polling |
| `APP_JWT_SECRET` | Base64 JWT signing secret |
| `APP_JWT_EXPIRATION_SECONDS` | Access-token lifetime |
| `APP_BOOTSTRAP_ADMIN_EMAIL` | Initial administrator email |
| `APP_BOOTSTRAP_ADMIN_PASSWORD` | Initial administrator password |
| `APP_FRONTEND_ORIGIN` | Allowed frontend CORS origin |
| `AI_TRAINED_MODEL_ENABLED` | Enables local Python trained-model inference |
| `AI_PYTHON_COMMAND` | Python executable used for inference |

See `src/main/resources/application.yml` for the complete local-development defaults.

## Project layout

```text
src/main/java/.../
  controller/       REST endpoints
  service/          business logic, ingestion, analytics, AI fallbacks
  websocket/        live-update invalidation endpoint
  config/           security, Kafka, Redis, scheduling, properties
src/main/resources/
  db/migration/     Flyway database migrations
  application.yml   application configuration
ml/                 model training and inference scripts
src/test/           unit, MVC, and integration tests
.github/workflows/  continuous-integration workflow
```

## Current scope and planned improvements

- Vehicle positions currently represent the latest known location. Persist position history to PostgreSQL when route replay or trajectory analytics are required.
- The local model is deliberately conservative: it falls back until each route/stop has enough representative history and a validated model artifact.
- A single backend instance safely handles its in-process scheduler. Deployments with multiple application replicas should add distributed scheduling/locking before enabling polling in every replica.
- Static GTFS refresh exists as an operational concern; production rollout should schedule it, monitor it, and retain feed-version compatibility history.

## Frontend

Clone and run the React dashboard from the separate [frontend repository](https://github.com/Zachary-Yan6/Transportation-reliability-platform-frontend). Configure its API base URL for this backend and use a JWT returned by the login endpoint. Its Vite development server normally runs on `http://localhost:5173`, which matches the example CORS configuration above.
