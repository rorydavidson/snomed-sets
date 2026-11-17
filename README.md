<p align="center">
  <img src="web/public/logo.svg" alt="SNOMED CT value sets logo" width="96" height="96">
</p>

# SNOMED CT value sets

A collaborative application for creating, managing and sharing SNOMED CT value sets.

## What it does

### Library

- Every value set you own, and every set others have shared with the community, in one compact table.
- Search by name or tag. Clicking a tag shows the other sets that carry it.
- Each row shows the latest version (or Draft), whether the set is private, how it is defined, its
  code system, owner and last update.
- Deprecated sets stay in the list, greyed out and marked as deprecated, and can be hidden with a tickbox.
- A search for sets containing a given concept is planned; the field is there but switched off.

### Creating and editing value sets

- **Code system**: chosen from those your Snowstorm holds (International Edition, national
  extensions and so on) when the set is created. It cannot be changed afterwards.
- **Defined by ECL**: write an Expression Constraint Language expression and see the matching
  concepts as you type. Concept identifiers written without a term have their preferred term added
  when the set is saved, so `<< 195967001` is stored as `<< 195967001 |Asthma|`.
- **Hand-picked list**: search for concepts by text, add several at once, or add all the children of
  one or more results. Each result shows how many children it has. Up to 2,000 concepts.
- **Tags**: up to ten per set, typed in or picked from tags already in use.
- **Sharing**: a set is private to its owner until shared with the community.

### Viewing concepts

- Concept lists show each concept's preferred term and the language it is given in (US English by default).
- Clicking a concept opens its details: where it sits in the hierarchy, its defining relationships
  grouped as they are modelled, its expression (with a copy button) and all of its descriptions.

### Publishing and versions

- Publishing stores the set as a numbered version, with an optional note on what changed. The
  concept list is frozen at that moment (an ECL set may expand to at most 10,000 concepts), so a
  version does not change when SNOMED CT does.
- Other people always see the version in use. The owner works on a draft and can publish again or
  discard the changes made since the last version.
- Every version stays in the history and can be browsed.

### Deprecating and deleting

- A set that has never been published can be deleted.
- A published set, or a single version of it, can be deprecated with a reason, and restored later.
  It stays readable and downloadable, marked as deprecated with that reason.
- When a version is deprecated, other people are shown the newest version that is not.
- Once every version of a set is deprecated, the owner may delete it, after a warning that others
  may already be using it. Deleting removes every version for everyone.

### Downloading

- Anyone signed in can download a published community set in the Global Patient Set layout: a
  tab-separated file of SNOMED CT ID, FSN (en-us), PT (en-us) and Active.

## How it fits together

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

### Continuous integration

Every pull request, and every push to `main`, runs the backend tests, the frontend tests and
production build, and builds both Docker images (`.github/workflows/ci.yml`). The live Snowstorm
tests are skipped there.
