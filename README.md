# FamilyHub API

[![CI](https://github.com/joe-bor/family-hub-api/actions/workflows/ci.yml/badge.svg)](https://github.com/joe-bor/family-hub-api/actions/workflows/ci.yml)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4-brightgreen)

REST API backend for **[FamilyHub](https://familyhub.joe-bor.me/)** — the shared family organizer. Spring Boot + Java 21, JWT auth, PostgreSQL. Pairs with the [React frontend](https://github.com/joe-bor/FamilyHub).

## Overview

FamilyHub runs on a single shared household account: one family login, with members modeled as colored profiles rather than separate logins. This API serves:

- **Calendar** — events with recurrence (RRULE), all-day, and multi-day support
- **Chores** — recurring daily / weekly / monthly routines with per-period completion
- **Lists** — shared grocery and to-do lists
- **Meals** — weekly meal planning
- **Recipes** — a recipe library, including import-from-URL
- **Google Calendar** — OAuth connect, calendar selection, and a scheduled one-way sync

## Tech stack

- **Java 21**, **Spring Boot 4** (Web MVC, Data JPA, Validation)
- **Spring Security** + **JWT** (jjwt) — stateless auth
- **PostgreSQL** with **Flyway** migrations (prod) · **H2** in-memory (dev)
- **iCal4j** for RRULE expansion · **Google Calendar API** for sync · **jsoup** for recipe import
- **Maven** (wrapper) · **Lombok**

## API

Base path: `/api`. All routes require a `Bearer` JWT except `/api/health`, `/api/auth/**`, and the Google OAuth callback.

| Group | Base | What |
|-------|------|------|
| Auth | `/api/auth` | `register`, `login`, `check-username` |
| Family | `/api/family` | get / update / delete the family |
| Members | `/api/family/members` | CRUD member profiles |
| Calendar | `/api/calendar/events` | event CRUD (range query, recurrence) |
| Chores | `/api/chores` | board + recurring templates + completion |
| Lists | `/api/lists` | lists, items, preferences |
| Meals | `/api/meals` | weekly board + slots |
| Recipes | `/api/recipes` | CRUD + `import` from URL |
| Google | `/api/google` | OAuth, calendar selection, sync |
| Health | `/api/health` | liveness probe |

The owned calendar audience contract and V21 migration are documented in [FamilyHub's calendar audience guide](https://github.com/thehallifax/FamilyHub/blob/main/docs/CALENDAR-AUDIENCE.md).

## Getting started

**Prerequisites:** JDK 21 (Docker is only needed to run the test suite).

```bash
# dev profile = H2 in-memory; no external database required
JWT_SECRET=$(openssl rand -base64 48) \
TOKEN_ENCRYPTION_KEY=$(openssl rand -base64 32) \
./mvnw spring-boot:run
```

The API starts on **http://localhost:8080**. Verify it's up:

```bash
curl http://localhost:8080/api/health
```

In dev, the H2 console is available at `/h2-console`.

## Configuration

Configuration is supplied via environment variables. The `dev` profile is active by default (H2, Flyway disabled); `prod` uses PostgreSQL with Flyway.

| Variable | Required | Notes |
|----------|----------|-------|
| `JWT_SECRET` | ✅ | HMAC signing key; use a long random value (HS384) |
| `TOKEN_ENCRYPTION_KEY` | ✅ | Base64-encoded 32-byte key (AES-256) for stored Google tokens |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | — | Google Calendar OAuth (optional in dev) |
| `GOOGLE_REDIRECT_URI` / `GOOGLE_FRONTEND_REDIRECT` | — | OAuth redirect overrides |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | prod | PostgreSQL connection (`prod` profile) |
| `CORS_ALLOWED_ORIGINS` | prod | Comma-separated origins (dev defaults to `localhost:5173,localhost:3000`) |

Activate the production profile with `SPRING_PROFILES_ACTIVE=prod` (or `--spring.profiles.active=prod`).

## Database & migrations

- **dev** — H2 in-memory (`jdbc:h2:mem`), schema via Hibernate `ddl-auto: update`, Flyway off, data resets on restart.
- **prod** — PostgreSQL, schema owned by **Flyway** migrations in `src/main/resources/db/migration` (`V1`–`V16`).

## Testing

```bash
./mvnw test
```

Tests run against a real PostgreSQL via **Testcontainers** (Flyway migrations applied), so Docker must be running. `JWT_SECRET` is supplied through the environment in CI.

## Build & deploy

```bash
./mvnw clean package      # -> target/app.jar
```

- **Docker** — multi-stage build (`eclipse-temurin:21` → `21-jre`, non-root user). Images publish to the **GitHub Container Registry**: `ghcr.io/joe-bor/family-hub-api` (tagged `latest`, commit SHA, and semver on release).
- **Versioning** — automated with **release-please**; merging the release PR cuts a GitHub Release and retags the image. Current release: **v1.6.0**.
- **Production** runs as a container on a DigitalOcean droplet against Neon PostgreSQL. See the [deployment guide](https://github.com/joe-bor/family-hub/blob/main/docs/deployment-guide.md).

## Project structure

```
com.familyhub.demo
├── controller   REST endpoints
├── service      business logic
├── repository   Spring Data JPA
├── model        JPA entities
├── dto          request / response payloads
├── mapper       entity ⇄ DTO
├── security     JWT + Spring Security config
├── filter       JWT authentication filter
├── config       application configuration
├── scheduler    Google Calendar sync job
├── event        application events
└── exception    error handling
```

## Docs

- **Agent entry:** [`AGENTS.md`](AGENTS.md) (canonical) / [`CLAUDE.md`](CLAUDE.md) — this repo is mentor-first for AI agents.
- **Product source of truth:** the PRD, roadmap, and backlog live in the [`family-hub`](https://github.com/joe-bor/family-hub) workspace repo under `docs/product/`.

## License

[AGPL-3.0](LICENSE) — Copyright © 2026 Joezari Borlongan. Open source; any fork or hosted derivative (including a modified API served over a network) must also publish its source.
