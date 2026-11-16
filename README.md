# SNOMED CT value sets

A collaborative application for creating, managing and sharing SNOMED CT value sets.

| Part | What it is |
|---|---|
| `web/` | Angular frontend, served by nginx |
| `api/` | Spring Boot backend for frontend. Handles Keycloak sign-in, stores value sets in MariaDB and calls Snowstorm |
| `docker-compose.yml` | Runs the frontend, backend and database together |

The browser only ever talks to the `web` container, which passes API and sign-in requests on to
the backend. Tokens stay on the server; the browser holds a session cookie.

## Running it

1. Copy `.env.example` to `.env` and fill in the database password and the Keycloak settings.
2. Start the stack:

   ```bash
   docker compose up --build
   ```

3. Open http://localhost:8080.

### Keycloak client

Create a confidential OpenID Connect client with the standard (authorisation code) flow and:

- Valid redirect URI: `http://localhost:8080/login/oauth2/code/keycloak`
- Valid post-logout redirect URI: `http://localhost:8080/`

The backend reads the Keycloak configuration from the issuer when it starts, so it will not start
if `KEYCLOAK_ISSUER_URI` cannot be reached from inside the `api` container.

### Snowstorm

`SNOWSTORM_BASE_URL` selects the Snowstorm instance. To run one locally:

```bash
docker compose --profile local-snowstorm up --build
```

and set `SNOWSTORM_BASE_URL=http://snowstorm:8080`. A local Snowstorm starts empty and needs a
SNOMED CT release imported before it returns concepts. It also needs several gigabytes of memory.

## Tests

Backend (needs Docker running, and JDK 25 or later):

```bash
cd api && mvn test
```

Frontend:

```bash
cd web && npm install && npx ng test --watch=false
```
