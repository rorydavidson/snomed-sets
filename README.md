<p align="center">
  <img src="web/public/logo.svg" alt="Sets &amp; Maps logo" width="96" height="96">
</p>

# Sets & Maps

A collaborative application for creating, managing and sharing SNOMED CT value sets and simple maps to and from SNOMED CT.

## What it does

### Without signing in

- Visitors land on a short introduction above the catalogue: what a value set is, with an example of
  an ECL rule and the concepts it gathers, and a "Sign in with your SNOMED International account"
  button. People who are signed in go straight to the catalogue instead.
- Anyone can open the catalogue and see the value sets that have been published and shared with
  the community. Drafts and private sets are not shown, and nor is who made a set.
- A published set can be opened to read its definition, versions and concept lists, and downloaded
  in the Global Patient Set layout. Other formats, concept details and everything that changes a
  set need a SNOMED International account.

### Catalogue

- Every value set you own, and every set others have shared with the community, in one compact table.
- Above the table, a tip shows the three steps of making a value set until you close it. Closing it
  is remembered in that browser only, so nothing about it is stored on the server.
- Search runs when you press Search, not while you type, and switches between two kinds:
  - **Value sets** matches words in set names, descriptions and tags, within the catalogue already
    on the page. Clicking a tag searches for the other sets that carry it.
  - **Concepts in sets** finds sets whose concept list holds a concept with any active description,
    synonyms included, in any language of its edition, with words starting with each word typed, in
    any order: "heart att" finds Myocardial infarction. Snowstorm does the matching, once for each
    release the lists were built on, and the concepts it finds are then looked for in the stored
    lists. A SNOMED CT ID is looked up in the lists directly. Each set is searched in the list you
    would be shown: the version in use, or your own working list of a hand-picked set. Sets defined
    by ECL are searched once published.
  - Very broad words are cut short at 10,000 matching concepts per release, and the page says so.
    If Snowstorm cannot be reached, only the stored preferred terms and fully specified names are
    searched, and the page says that too.
- Without signing in, concept searches are limited to one every 3 minutes per address, and 20 a
  minute from all visitors together, to keep the database quick. The page says so and counts down.
  The limits are held in memory, so they reset when the backend restarts.
- Each row shows the latest version (or Draft), whether the set is private, how it is defined, the
  edition and release it is built on, its owner, how many concepts it has, and its last update. The
  count is of the version in use, or of the working list of a hand-picked set never published; an
  ECL set never published shows a dash until it is.
- Visitors see how much has been shared so far under the introduction: the number of published
  community value sets and of different concepts across them, each counted once. Private sets and
  drafts are not counted. The figures are counted afresh on each visit, so a set made private or deleted drops out at once.
- Deprecated sets stay in the list, greyed out and marked as deprecated, and can be hidden with a tickbox.

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

### Maps

A map links the codes of a local list (a picklist, a legacy coding scheme) with SNOMED CT
concepts, in one direction. **To SNOMED CT**, each local code is given one or more concepts.
**From SNOMED CT**, each concept is given one or more local codes. Maps are private to their owner
for now. Publishing and versions, upgrading and automap are still to come, and are described in
`docs/maps-design.md`.

Maps can be switched off for a whole deployment with `MAPS_ENABLED=false`. The maps API is then
not there at all (every `/api/maps` address answers 404), the catalogue has no switch to Maps, map
addresses show the not-found page, and the privacy page no longer mentions maps. Maps already made
are kept in the database and come back when it is switched on again. It is on by default.

- **Two catalogue views.** Signed-in people have a switch at the top of the catalogue between Value
  sets and Maps. The Maps view lists your own maps with the release each is built on, how many
  codes it has and how many are done. Maps are private for now, so visitors see no switch.
- **Creating a map.** Choose the direction, which cannot be changed afterwards. Give the map a
  name, say what the local code list is called (with an optional version and identifier), and
  choose a code system and release as for a value set. A map is built on that release's branch.
- **Local codes.** Choose a CSV or TSV file, or paste or type one code and its term per line. The
  file is read in the browser: you say whether the first row is a header and which columns hold
  the code and the term, and only those two columns are sent. A code can be in a map once, and
  codes that differ only in case are different codes. Up to 5,000 codes, 100 characters a code
  and 500 a term. More can be added later with Add codes. In a map to SNOMED CT these codes are
  the rows to map. In a map from SNOMED CT they are the list targets are chosen from.
- **Concepts to map, for a map from SNOMED CT.** Picked with the concept search, or imported as
  a list of identifiers or as everything an ECL expression matches, with the same checks as a
  hand-picked value set: inactive concepts are not added and their replacements are offered. Up to
  5,000 concepts, and more can be added later with Add concepts, which offers the same concept
  search and import. Only identifiers are sent; the
  server checks each is active in the release and stores its terms. Changing the release while
  creating a map clears the concepts, as they were chosen on the other one.
- **Term matching, to SNOMED CT.** As soon as a map with new codes is opened, each term is looked for among the
  active SNOMED CT descriptions of the release, synonyms included, in every language of the
  edition. A concept with a description that is the same words (case and spacing aside) becomes a
  suggestion. The browser asks for 100 codes at a time and shows how far it has got; it can be
  stopped, and anything left is picked up with Match remaining.
- **Local codes nothing is mapped to.** A map from SNOMED CT says how many of its local codes
  have no concept mapped to them yet and lists them on request, with a filter. A suggestion nobody
  has accepted does not count. It is worked out in the browser from what the page already holds.
- **Term matching, from SNOMED CT.** Every active name of each concept (preferred term, fully
  specified name and synonyms, in every language of the edition) is compared with the local terms,
  so Myocardial infarction is offered a local code called "Heart attack". The names are fetched
  for 25 concepts at a time and no searching is needed, so this direction is quick. Adding local
  codes later has the concepts still unmapped looked at again.
- **Suggestions are not maps.** The same words can name a finding, a disorder and a morphology, so
  nothing is mapped until the owner accepts it. A code with exactly one suggestion can be accepted
  with Enter. A code with several waits for a choice.
- **Deciding on many suggestions at once.** Each suggested row has a tick box, and the box in the
  table heading ticks every suggested row the filters let through, on all pages. The ticked rows
  are then accepted or dismissed together, in maps to and from SNOMED CT alike. Accepting skips a
  row with several suggestions and says how many were left. Dismissing drops them all and leaves
  the rows unmapped. X ticks the row in focus.
- **The workbench.** Codes are in a table with their status (Unmapped, Suggested, Mapped, No map),
  a status filter and a text filter. The table runs the full width of the page. Clicking a code
  opens its targets under its row, and clicking it again closes them, with the concept search and concept details used for
  hand-picked sets. In a map from SNOMED CT the rows are concepts and the targets are picked from
  a filterable list of the local codes instead. A row can have up to ten targets.
  Each has a relationship: Equivalent, broader, narrower or Related, worded for the side the
  target is on ("Local code is broader"). "No map" records that nothing fits, which is not the same as not having looked. A
  note can be kept with each code. Every change is saved as it is made. Arrow keys move between
  rows, Space opens or closes one and N marks a row as having no map.
- **Targets are checked by the server.** The browser sends only codes. For a map to SNOMED CT
  the server checks each is an active concept in the map's release and stores its terms, so a
  made-up term cannot be attached to a concept. For a map from SNOMED CT each must be in the
  map's own list of local codes, and its term is taken from there.
- **Downloading.** The map as it stands, as CSV or TSV: one line for each code and target, and one
  for a code with no target, with the source code and term, the target code and term, the
  relationship, the status and the note. The column headings say which side is SNOMED CT. Suggestions nobody accepted are left out and their codes
  written as unmapped. In the CSV a value a spreadsheet would read as a formula starts with an
  apostrophe; the TSV has every code exactly as given, for programs.
- No licence note is shown beside map downloads. Whether a map counts as Global Patient Set
  content has not been confirmed, so the page does not say it does.

### Viewing concepts

- Concept lists show each concept's preferred term and the language it is given in. They start in the
  edition's own language (Norwegian for the Norwegian Edition, for example), falling back to US
  English for a concept with no term in it. When the edition has more than one language, a switch
  above the list shows it in any of them. Stored lists keep their US English terms; the other
  languages are looked up in Snowstorm a page at a time, and if Snowstorm cannot be reached the US
  English terms are shown.
- Downloads are always in US English, whatever language the list is shown in.
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
- A version can be given a name when it is published or later from the version history. It starts
  as the date of the code system release the set is built on, such as 2026-07-01, or 2026-07-01 (2)
  when another version of the set already has that name; clearing it shows the number instead. A
  version made by upgrading to a new release is named after that release. Sets on a live branch
  have no release date, so their versions start without a name. The name is shown instead of "Version 3" across the pages, with the
  number still shown beside it. Names are unique within a set, whatever their capitals, and use
  letters, digits, spaces and `. _ / : ( ) + -`, up to 40 characters. Links and downloads keep using
  the number, so renaming a version does not break them.
- A published set shared with the community has a Share button, and so does each of its versions.
  It copies a fixed link: `/value-sets/{id}` always opens the version in use, and
  `/value-sets/{id}/versions/{number}` always opens that version. Choosing a version on the page
  also puts its link in the address bar. Links work without signing in, as visitors may read
  published sets. Private sets have no Share button, as nobody else could open the link.
- A set can be given a SNOMED CT identifier: a reference set concept that identifies it. It must
  have a valid check digit, be a concept identifier, be an active concept in the set's release
  under 900000000000455006 |Reference set|, and not identify another set. The editor checks it with
  the backend and shows the concept's fully specified name, and the same checks run on saving. A
  set with an identifier is shared as `/value-sets/sct/{identifier}`, and its versions as
  `/value-sets/sct/{identifier}/versions/{number}`. Changing the identifier stops links shared with
  the old one from working.

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
- Since the January 2026 release the Global Patient Set holds every International Edition concept
  and is published under CC BY-ND 4.0. The page says so beside the downloads of a set built on the
  International Edition from 2026-01-01, as that TSV can be used without an Affiliate Licence. For a
  set built on a national extension it says instead that the extension's own concepts need a
  licence. It says nothing for older releases or the live branch.
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
- It holds a compact SNOMED International sign-in button for visitors, or the account's picture
  (or initials) with a menu leading to profile settings and sign-out. It also has
  a grid of public SNOMED International tools, headed with their logo so it is clear those sites
  are theirs, and a menu with a link to snomed.org, each opening in a new tab. SNOMED
  International's status page and privacy policy are left out, as they do not cover this app.

### Profile and messages

- Profile settings, from the account menu in the top bar, hold three optional things:
  - **A picture or logo.** A PNG or JPEG of up to 1 MB. The server decodes it and stores a fresh PNG
    of at most 256 pixels, so nothing else in the file, such as where a photo was taken, is kept. It
    replaces the initials in the top bar and is shown beside the owner on value sets.
  - **An organisation.** On each value set the owner chooses whether it is shown as made by them or
    by their organisation. The organisation is free text and nobody checks it.
  - **Messages from other people.** When switched on, signed-in people see "Message owner" on that
    person's value sets. Messages are emailed to the verified address on their SNOMED International
    account, which is stored only while the switch is on and is never shown to anyone else. The
    sender is told that their own address goes with the message as the reply-to, so the owner can
    answer them.
- Owners' names, organisations and pictures are only shown to people who are signed in, as before.
- Sending is limited to 5 messages an hour and 20 a day per person. Only who sent to which set and
  when is kept, for a day, to apply the limits; the message itself is never stored.
- Messages go through [Resend](https://resend.com). Messaging stays off until `RESEND_API_KEY`,
  `MESSAGES_FROM` (a sender on a domain verified in Resend) and `APP_URL` are set. `APP_URL` is used
  for the links in the email, so a sender cannot change where those links point.

### Footer and privacy

- Every page ends with the SNOMED CT notice: SNOMED CT is used by permission of SNOMED International,
  was originally created by the College of American Pathologists, and "SNOMED" and "SNOMED CT" are
  registered trademarks of SNOMED International. It says that using SNOMED CT needs a licence, that
  the Global Patient Set (every International Edition concept since January 2026) is published under
  CC BY-ND 4.0, and links to SNOMED CT licensing, SNOMED International and the privacy page.
- The privacy page lists everything the site keeps about people, why and for how long: an internal
  account ID (a random code from the SNOMED International account, never shown), name, the email
  address only while messages are switched on, organisation and picture, value sets, maps with the
  local codes uploaded to them, and a one-day record of messages sent. It also covers messages sent through Resend,
  the sign-in and request-forgery cookies, what the browser remembers, the visitors' search limit and
  web server logs, and points to SNOMED International's privacy policy for the account itself.
- `PRIVACY_OPERATOR` and `PRIVACY_CONTACT` set who the page names as running the site and the email
  address for privacy questions. Until they are set, the page asks people to contact whoever runs it.
  Keep the page in step with the code when what is stored changes.

## How it fits together

| Part | What it is |
|---|---|
| `web/` | Angular frontend, served by nginx |
| `api/` | Spring Boot backend for frontend. Handles Keycloak sign-in, stores value sets and maps in MariaDB and calls Snowstorm |
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

The client asks for the `openid`, `profile` and `email` scopes. The email address is only used for
messages between people (see Profile and messages), and only when Keycloak marks it as verified.
People who signed in before the email scope was added need to sign in again for it to be seen.

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

To try messaging locally, also set `LOCAL_LOGIN_EMAIL`. It is treated as the local user's verified
address.

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
