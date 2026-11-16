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

3. Open http://localhost:8090.

### Keycloak client

Create a confidential OpenID Connect client with the standard (authorisation code) flow and:

- Valid redirect URI: `http://localhost:8090/login/oauth2/code/keycloak`
- Valid post-logout redirect URI: `http://localhost:8090/`

The backend reads the Keycloak configuration from the issuer when it starts, so it will not start
if `KEYCLOAK_ISSUER_URI` cannot be reached from inside the `api` container.

### Signing in without Keycloak (development only)

To run without an identity provider, set `LOCAL_LOGIN_USERNAME` and `LOCAL_LOGIN_PASSWORD` in
`.env` and add `local-login` to the profiles:

```
SPRING_PROFILES_ACTIVE=dev,local-login
```

The sign-in link then leads to a username and password form, and the Keycloak settings are
ignored. There is one local user and no default password. Do not switch this profile on for a
shared or deployed environment.

### Snowstorm

`SNOWSTORM_BASE_URL` selects the Snowstorm instance. The public server refuses server-side calls,
so use one of your own:

- **Already running on this machine** (the default in `.env.example`):
  `SNOWSTORM_BASE_URL=http://host.docker.internal:8080`
- **Started with this stack**: run `docker compose --profile local-snowstorm up --build` and set
  `SNOWSTORM_BASE_URL=http://snowstorm:8080`. This one starts empty and needs a SNOMED CT release
  imported before it returns concepts. It is published on `localhost:8081` for that, and needs
  several gigabytes of memory.

## Tests

Backend (needs Docker running, and JDK 25 or later):

```bash
cd api && mvn test
```

To also run the tests that call a real Snowstorm with a release loaded:

```bash
cd api && SNOWSTORM_LIVE_URL=http://localhost:8080 mvn test
```

Frontend:

```bash
cd web && npm install && npx ng test --watch=false
```
