# OpenBench

An internal resource marketplace: a Team's Manager posts one Request for people, Exclusive resources such as rooms, and Metered capacity such as GPU hours, and the system allocates each line under its Allocation Class and settles it in credits.

The specification (SRS, design document, ADRs) is in [`docs/`](docs/README.md); the domain vocabulary is in [`CONTEXT.md`](CONTEXT.md).

## Prerequisites

- Either Nix with direnv (`direnv allow` loads JDK 17, Maven, Node 24, and psql 16 from `flake.nix`), or JDK 17 and Maven 3.9.
- Docker. The tests start PostgreSQL 16 with Testcontainers, and the application starts it from `compose.yaml`.

## Run the tests

```sh
mvn verify
```

This runs the unit tests, the integration tests on PostgreSQL 16 with the production migrations, and the ArchUnit test of the module rules. When `web/package.json` exists, the build also installs Node, runs `npm ci` and `npm run build` in `web/`, and packs the client into the jar; add `-Dskip.npm -Dskip.installnodenpm` to skip that.

## Run the application with demo data

```sh
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

Spring Boot starts the database from `compose.yaml`, Flyway migrates it, and the `demo` profile adds the demo world's seed data (`src/main/resources/db/demo`). The application listens on <http://localhost:8080>; the API is under `/api`, and its OpenAPI document is at `/api/docs` (browsable at `/api/docs/ui` once signed in). Stopping the application stops the database container and keeps its data; `docker compose down` removes it.

A quick check from the command line:

```sh
curl -s -c jar -b jar http://localhost:8080/api/me                       # 401, sets XSRF-TOKEN
TOKEN=$(awk '$6 == "XSRF-TOKEN" { print $7 }' jar)
curl -s -c jar -b jar -H "X-XSRF-TOKEN: $TOKEN" -d username=alice -d password=demo http://localhost:8080/api/login
curl -s -c jar -b jar http://localhost:8080/api/me                       # 200, Alice
```

### Demo logins

Every demo Member's password is `demo`.

| Username | Name | Team | Roles besides Member |
|---|---|---|---|
| `alice` | Alice Chen | Platform | Manager |
| `ben` | Ben Ortiz | Platform | |
| `bob` | Bob Patel | Research | Manager |
| `rita` | Rita Gomez | Research | |
| `oscar` | Oscar Reed | Operations | Manager |
| `carol` | Carol Davis | Operations | Administrator |
| `dave` | Dave Kim | Operations | Resource Owner |
| `erin` | Erin Walsh | Operations | Capacity Planner |

## Configuration

Settings come from `src/main/resources/application.yaml` and can be overridden with environment variables:

| Variable | Meaning | Default |
|---|---|---|
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | The database. In production the application connects as `openbench_app` and Flyway as the schema owner (`SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD`). | from `compose.yaml` |
| `OPENBENCH_ZONE` | The company time zone | `America/New_York` |
| `SERVER_SERVLET_SESSION_COOKIE_SECURE` | Mark the session cookie `Secure`; set it to `true` behind HTTPS | `false` |

## Web client
