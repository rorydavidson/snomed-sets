<p align="center">
  <img src="web/public/logo.svg" alt="SNOMED CT value sets logo" width="96" height="96">
</p>

# SNOMED CT value sets

A collaborative application for creating, managing and sharing SNOMED CT value sets.

## What it does

### Without signing in

- Visitors land on a short introduction above the library: what a value set is, an example of an
  ECL rule and the concepts it gathers, and the three steps of making one (define, publish, share).
  It has a "Sign in with your SNOMED International account" button. People who are signed in go
  straight to their library instead.
- Anyone can open the library and see the value sets that have been published and shared with
  the community. Drafts and private sets are not shown, and nor is who made a set.
- A published set can be opened to read its definition, versions and concept lists, and downloaded
  in the Global Patient Set layout. Other formats, concept details and everything that changes a
  set need a SNOMED International account.

### Library

- Every value set you own, and every set others have shared with the community, in one compact table.
- Above the table, a tip shows the three steps of making a value set until you close it. Closing it
  is remembered in that browser only, so nothing about it is stored on the server.
- Search by name or tag. Clicking a tag shows the other sets that carry it.
- Each row shows the latest version (or Draft), whether the set is private, how it is defined, the
  edition and release it is built on, its owner and last update.
- Deprecated sets stay in the list, greyed out and marked as deprecated, and can be hidden with a tickbox.
- A search for sets containing a given concept is planned; the field is there but switched off.

### Creating and editing value sets

- **Code system**: chosen from those your Snowstorm holds (International Edition, national
  extensions and so on) when the set is created, by its display name. It cannot be changed afterwards.
- **Release**: once a code system is chosen, a second list offers its releases, newest first. The
  latest is picked to start with; an earlier one builds the set against SNOMED CT as it was then.
  The set is built on that release's branch (for example `MAIN/2026-01-01`), not the live branch,
  so its content only moves when the owner upgrades it. The set page shows the code system and
  release at the top.
- **Defined by ECL**: write an Expression Constraint Language expression and see the matching
  concepts as you type. Concept identifiers written without a term have their preferred term added
  when the set is saved, so `<< 195967001` is stored as `<< 195967001 |Asthma|`.
- **Hand-picked list**: search for concepts by text, add several at once, or add all the children of
  one or more results. Each result shows how many children it has. Up to 2,000 concepts.
- **Imported concepts**: start a hand-picked list by importing concepts, and import more into it
  whenever you like with Import concepts, on the set page or in the editor. The review points out
  which concepts are already in the list (with a filter to show only those, or only the new ones),
  and only new ones are added. There are two sources:
  - **A list of identifiers**: paste them, or choose a plain text, CSV or TSV file (this app's own
    downloads included) or a FHIR ValueSet. `id |term|` lines copied from ECL work too. The file is
    read in the browser: only the identifiers, and the terms if you pick a term column, are sent.
    The server checks them against the chosen release: active concepts are found, and a term that
    is neither the preferred term nor the FSN is pointed out; description identifiers become their
    concepts; inactive concepts are not added, but their replacements from the historical
    association reference sets are offered, all ticked to start with; identifiers with a wrong check
    digit, relationship identifiers and identifiers the release does not have are listed with the
    reason; repeats are counted once. Up to 5,000 identifiers can be checked at once.
  - **An ECL expression**: every concept it matches in the release is added as it is today. The
    list does not follow the expression afterwards; for that, create a set defined by ECL. An
    expression matching more than 2,000 concepts is refused.

  The review can be downloaded as a CSV. A list holds at most 2,000 concepts.
- **Suggest an ECL expression**: for a hand-picked list, the owner can have an expression worked
  out that matches it, from where its concepts sit in the hierarchy. Only the owner can ask, because
  it is expensive. A member whose whole subtree is listed becomes `<< X`; one missing a few
  concepts or branches becomes `<< X MINUS (...)` when that is shorter than naming its members; members that are every child of
  a concept outside the list become `< P` or `<! P`; anything else is named on its own. The
  expression is expanded and compared with the list before it is shown, so it says whether it
  matches exactly and lists any differences. Inactive concepts kept in the list cannot be matched
  by ECL and are pointed out. The expression can be copied, or used to start a new ECL value set
  on the same code system and release; the hand-picked list is left as it is. An ECL set follows
  its expression, so new concepts below its clauses join it when it is upgraded.
- **Keeping a suggested expression**: the owner can keep one with the hand-picked list for
  reference. The server checks it again against the list before saving, and stores how well it
  matched, the release, the date and how many inactive concepts it cannot cover. The set page shows
  it with those caveats: it was worked out automatically, is not reviewed by a terminologist, does
  not define the set and is not downloaded. When the list changes, the owner is told the figures no
  longer hold. Others see it only while it matches exactly the version they are shown, so nothing
  about an unpublished draft is revealed.
- **Tags**: up to ten per set, typed in or picked from tags already in use.
- **Sharing**: a set is private to its owner until shared with the community.

### Viewing concepts

- Concept lists show each concept's preferred term and the language it is given in (US English by default).
- Clicking a concept opens its details. The header gives its preferred term in the edition's own
  language (Norwegian for the Norwegian Edition, for example), its fully specified name, and whether
  it comes from the International Edition or a national extension, with the module's name.
- The Overview tab lists its names by language, the edition's language first with the preferred
  term picked out, and shows where it sits as a compact outline: its "is a" parents as chips, then its
  children as a short list with a filter and "show all" for long ones. Every related concept can be
  clicked to move to it, and a trail leads back.
- The Definition tab has its defining relationships grouped as they are modelled and its
  expression, with a copy button. The Descriptions tab lists every description by language, with
  the language reference sets it is preferred or acceptable in and the module it comes from.

### Publishing and versions

- Publishing stores the set as a numbered version, with an optional note on what changed. The
  concept list is frozen at that moment (an ECL set may expand to at most 10,000 concepts), so a
  version does not change when SNOMED CT does.
- Other people always see the version in use. The owner works on a draft and can publish again or
  discard the changes made since the last version.
- Every version stays in the history and can be browsed.

### Upgrading to a new release

- When a newer release of the code system is out, the owner sees an Upgrade button on the set.
  Sets created before releases were used sit on the live branch and are offered an upgrade too.
- The upgrade first shows what would change, saving nothing. For an ECL set that is the concepts
  the definition gains and loses in the new release. For a hand-picked list it is the concepts that
  have become inactive, each with the replacements suggested by the historical association
  reference sets (Same as, Replaced by, Possibly equivalent to and so on). Every replacement is
  ticked to start with and any can be unticked. Inactive concepts are removed by default, but any
  can be kept instead: it stays in the list flagged as inactive, shown as such on the set page and
  in the editor, with Active 0 in the TSV download and `inactive: true` in the FHIR expansion. A
  concept kept in one upgrade starts as kept in the next. Changes to preferred terms are listed for both.
- A long list of inactive concepts stays compact: one table row per suggestion, a running count of
  what will be removed, kept and added, remove, keep, add or skip all at once, and a search and
  filter once the list is long.
- The owner then saves the upgrade as a draft or publishes it straight away as a new version.
- Each upgrade keeps a change report that can be downloaded as CSV, straight after upgrading or
  later from the set's upgrade history. Anyone who can see the set can see its upgrade history.

### Deprecating and deleting

- A set that has never been published can be deleted.
- A published set, or a single version of it, can be deprecated with a reason, and restored later.
  It stays readable and downloadable, marked as deprecated with that reason.
- When a version is deprecated, other people are shown the newest version that is not.
- Once every version of a set is deprecated, the owner may delete it, after a warning that others
  may already be using it. Deleting removes every version for everyone.

### Downloading

- The download buttons fetch whatever the set page is showing. Anyone who can see a set can
  download any of its published versions, the version in use or an older one picked from the history.
  The owner can also download the working draft, whether the set is shared or private.
- The Global Patient Set layout is a tab-separated file of SNOMED CT ID, FSN (en-us), PT (en-us)
  and Active.
- The FHIR download is an R4 `ValueSet` resource in JSON. Its compose holds the definition (the ECL
  as a SNOMED CT `constraint` filter, or the hand-picked concepts) and its expansion holds the
  concept list: the one stored when the version was published, or for a draft its current content.
  A draft has `version` and `status` set to `draft`. The SNOMED CT edition and
  release are given as a version URI, for example
  `http://snomed.info/sct/900000000000207008/version/20260101`, and left out for a set on the live
  branch. The owner's name is not included.

### Top bar

- The bar across the top uses the same design as SNOMED International's tools, but carries this
  app's own logo rather than SNOMED International's, as it is not one of their apps. It is a local
  copy of the navbar in the shared-ui library (IHTSDO/snomed-ui), in `web/src/app/shell/`, with the
  same Tailwind classes and Font Awesome icons. It does not need that library's translation or
  sign-in set-up. Keep the two in step when either changes.
- Text across the app, the bar included, is set in Google Sans. The font is bundled with the app
  rather than loaded from Google Fonts, so visitors' browsers do not contact Google.
- Tailwind is used only for the bar. It is set up in `web/src/styles/tailwind.css` without its
  base reset, so the rest of the app keeps its own styles and design tokens.
- It holds a compact SNOMED International sign-in button for visitors, or the account initials with a menu to sign out. It also has
  a grid of public SNOMED International tools, headed with their logo so it is clear those sites
  are theirs, and a menu with a link to snomed.org, each opening in a new tab. SNOMED
  International's status page and privacy policy are left out, as they do not cover this app.

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
   The `dev` profile loads four sample value sets owned by nobody who can sign in; use it only on
   your own machine. Older databases have those samples removed once by a migration.
2. Start the stack:

   ```bash
   docker compose up --build
   ```

3. Open http://localhost:8090.

### Behind a reverse proxy

On a server, put the application behind a reverse proxy that handles HTTPS, and do not open
`WEB_PORT` to the internet. If the proxy runs in Docker (Nginx Proxy Manager, for example), it
cannot reach the application at `localhost`, because that is the proxy's own container. Join the
two instead:

1. Set `PROXY_NETWORK` in `.env` to the proxy's network (`docker network ls` lists them).
2. Start the stack with the proxy file as well:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.proxy.yml up -d
   ```

3. Point the proxy host at scheme `http`, host `snomed-sets-web`, port `8080`, with websockets off.

The proxy must pass `X-Forwarded-Proto` and `X-Forwarded-Host` (Nginx Proxy Manager does), so
that sign-in redirects use the public `https://` address. Add that address to the Keycloak client
as described below.

### Cookies and browser security

- The sign-in cookie is HTTP-only, `SameSite=Lax` and sent over HTTPS only. Browsers make an
  exception for `http://localhost`. To sign in over plain HTTP from another machine, set
  `SESSION_COOKIE_SECURE=false` in `.env`, and never do that on a server.
- The web container sends a Content-Security-Policy that allows scripts, styles, images and API
  calls from the application's own address only. Inline styles are allowed because Angular adds
  component styles that way; inline scripts are not. For that reason the production build does not
  inline critical CSS (`angular.json`), which would need an inline script.

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
