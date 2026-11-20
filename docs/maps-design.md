# Maps: design and analysis

Status: agreed. Phases 1 and 2 built. Written 20 November 2026.

## 1. What is being asked for

A second kind of artefact beside value sets: a simple map between SNOMED CT and a local list of
codes and terms. Two directions:

- **To SNOMED CT.** The source rows are the local codes. Each is given SNOMED CT concepts as targets.
- **From SNOMED CT.** The source rows are SNOMED CT concepts. Each is given local codes as targets.

Like a value set, a map is built on one release of one code system, has a working draft and
frozen published versions, and is upgraded when a newer release is out. Rows are term matched as
soon as they arrive. A later "automap" step suggests targets with more than plain term matching.

Decisions already taken:

| Question | Decision |
|---|---|
| Cardinality | A source row has zero or more targets, each with a relationship, plus an explicit "no map" |
| Source of a from-map | The map owns its own SNOMED CT concept list. No link to value sets |
| Automap | Lexical only, inside the stack. No embeddings, no external model |
| Downloads | CSV/TSV and FHIR R4 ConceptMap |

## 2. Should this live in this application at all?

Worth saying before the design: SNOMED International already runs Snap2SNOMED, which imports a
code list, automaps it, supports dual review and exports the result. This feature overlaps with it.
The case for building it here anyway is that it is much lighter (one author, no project roles),
it is pinned to a release and versioned the way sets are, and published maps sit in the same
community library. If the wish is really for review workflows and teams, Snap2SNOMED is the better
home and this should stay small. The design below assumes "small".

It also changes what the application is. The name, the package (`org.snomed.valuesets`), the page
titles and the hero text all say "value sets". I would leave the package alone and only widen the
visible wording when maps are first shared publicly (phase 3).

## 3. How it fits what exists

Things a map can reuse as they are:

- Code system and release choice (`CodeSystemController`, `SnowstormClient.codeSystemVersions`).
- Ownership, private/community visibility, `CurrentUser`, organisation display, message owner.
- Version names (`VersionLabels`), share links, deprecation rules.
- The concept picker and concept details panel in the frontend.
- Identifier checking with historical replacements (`ConceptLookup`, `IdentifierImportService`) for
  the SNOMED CT side of a from-map.
- The browser-side file reader in `identifier-import.ts`, which already parses CSV/TSV and sends
  only the chosen columns.

Things that do not fit and should not be forced:

- `value_set` and `ValueSetService` (774 lines) are built around "a list of concepts, by ECL or by
  hand". A map is rows with targets. Putting maps into those tables would mean nullable columns and
  branches everywhere. **Recommendation: a separate `map` aggregate in its own package
  (`org.snomed.valuesets.map`), with its own tables.** Some publish and deprecate logic will be
  written twice. I think that is the cheaper mistake; shared pieces can be pulled out later if the
  duplication hurts, and that would be its own change rather than a quiet refactor.

## 4. Data model

Working content uses version number 0 and publishing copies it to version n, the same convention as
`value_set_member`.

```
map
  id, name, description
  direction            TO_SNOMED | FROM_SNOMED       fixed at creation
  code_system, branch                                 fixed code system, branch moves on upgrade
  local_system_name    e.g. "Ward diagnosis picklist"
  local_system_uri     optional, used as the system in the FHIR ConceptMap
  local_system_version optional free text
  target_scope_ecl     optional, to-maps only: targets must match this expression
  status, visibility, owner_subject, owner_name, show_organisation
  deprecated_at, deprecation_reason, created_at, updated_at

map_version            same shape as value_set_version: number, label, branch, note,
                       row counts by status, published_at, deprecation

map_row                one source
  id, map_id, version_number
  source_code, source_term
  source_active        for SNOMED CT sources kept after becoming inactive
  status               UNMAPPED | SUGGESTED | MAPPED | NO_MAP | REVIEW
  note                 optional, up to 500 characters
  position             keeps the uploaded order
  unique (map_id, version_number, source_code)

map_target             zero or more per row
  row_id, target_code, target_term
  relationship         EQUIVALENT | TARGET_BROADER | TARGET_NARROWER | RELATED
  origin               MANUAL | EXACT_TERM | AUTOMAP | UPGRADE
  score                null unless suggested
  accepted             false while it is only a suggestion

map_local_code         from-maps only: the vocabulary targets are chosen from
  map_id, version_number, code, term
```

Notes on the choices:

- **Relationship names say which side is broader.** FHIR R4 and R5 describe this the opposite way
  round from each other, and people get it wrong constantly. Storing `TARGET_BROADER` leaves no
  doubt, and the FHIR export translates (R4: `equivalent`, `wider`, `narrower`, `relatedto`;
  no-map rows become `unmatched`).
- **`REVIEW` status** is for a row that was mapped and has been disturbed by an upgrade or a new
  local code list. It keeps its targets but needs a human to look again.
- **Terms are copied in**, as they are for set members, so a published version reads the same
  forever and pages do not depend on Snowstorm.
- **Limits.** 5,000 rows a map (matching the identifier import), 10 targets a row, codes up to 100
  characters, terms up to 500. A published version is then at most a few tens of thousands of
  small rows, which is fine for MariaDB without anything clever.

## 5. Getting rows in

**Local codes (sources of a to-map, target vocabulary of a from-map).** Upload a CSV or TSV, or
type/paste rows by hand. The file is read in the browser, the user picks the code column and the
term column, and only those two are sent. That follows the existing import and keeps stray columns
(which might hold anything, patient data included) off the server. Your standing instruction is to
ask about server or client processing each time, so please confirm this one. The server validates
again: lengths, duplicate codes (rejected with a list, not merged silently), row limit.

**SNOMED CT sources of a from-map.** The same three routes as a hand-picked set: the concept
picker, a list of identifiers, or an ECL expression expanded once. Inactive concepts get their
historical replacements offered. This is `IdentifierImportService` almost unchanged.

## 6. Immediate term matching

Runs when rows are added. The goal is only exact matches after light normalisation (case,
surrounding whitespace, repeated spaces).

- **To-map.** One Snowstorm description search per row, against every active description in the
  edition's languages, kept only where a description equals the source term. Snowstorm has no
  batch term search, so 5,000 rows is 5,000 calls: about a minute at eight in parallel.
  **Recommendation: the browser sends rows for matching in chunks of 100 and shows progress.**
  No job table or background worker is needed. If the tab is closed, the remaining rows simply stay
  unmatched and a "Match remaining" button carries on.
- **From-map.** Cheap. Fetch the descriptions of the source concepts in batches and compare them
  with the local terms in memory. No searching.

**Exact matches are stored as suggestions, not as accepted maps.** You asked for rows to be
"immediately term matched", and they will be, but I would not mark them mapped automatically. The
same words can name a finding, a disorder, a situation and a morphology, and a silent wrong map is
worse than an empty one. A row with exactly one active exact match gets it pre-filled as
`SUGGESTED / EQUIVALENT`, and "Accept all single exact matches" does the lot in one click. Rows with
several exact matches show them all and wait. If you would rather auto-accept, it is a one-line
change in policy, but I would want that to be a deliberate choice.

## 7. The mapping workbench

One page, `/maps/:id/edit`, owner only.

- A table of source rows: code, term, status, targets so far. Filters by status and a text filter.
  Counts by status along the top, as a progress bar of sorts.
- Selecting a row opens a side panel. For a to-map it holds the suggestions, then the existing
  concept picker (searching within the scope expression if one is set) and concept details. For a
  from-map it holds the suggestions, then a filterable list of the local codes.
- Each target has a relationship (default Equivalent). "No map" is a row action and clears targets.
- Keyboard: up/down between rows, Enter to accept the top suggestion, N for no map. Mapping is
  repetitive work and this matters more than polish elsewhere.
- Saves per row as you go. There is no big Save button to lose work behind.

The read-only map page, `/maps/:id`, mirrors the value set page: header, versions, table, downloads,
share, deprecate.

## 8. Publishing, versions and sharing

Same rules as sets: publishing freezes rows and targets into a numbered version with an optional
note and name; others see the version in use; the owner keeps a draft; versions can be deprecated.

- Publishing with rows still `UNMAPPED`, `SUGGESTED` or `REVIEW` is allowed, with a warning giving
  the counts. Forcing completeness would stop people publishing work in progress that is still
  useful. Unaccepted suggestions are never included in a published version or a download.
- Maps are private until shared. Sharing puts the uploaded codes and terms in public view, so the
  share step should ask the owner to confirm they are entitled to publish that code list.

## 9. Upgrading

Two different things can move under a map, and both need handling.

**A new SNOMED CT release** (same flow as sets: preview, then save as draft or publish, with a
stored change report).

- To-map: targets that have become inactive. A single `SAME_AS` replacement is proposed as a
  straight swap keeping the relationship. Anything else (possibly equivalent to, replaced by,
  several candidates) lists the candidates and sets the row to `REVIEW`. A target with no
  replacement is removed and the row goes to `REVIEW`.
- From-map: source concepts that have become inactive. Offer to move the row's targets to the
  replacement concept, keep the inactive row flagged, or remove it.
- Both: preferred term changes listed and applied.

**A new version of the local code list.** Re-upload and diff by code: new codes become new rows (or
new vocabulary), missing codes are listed for removal, and a code whose term has changed is set to
`REVIEW`, because a changed term often means a changed meaning. This is not in your brief, but
without it a map can only follow SNOMED CT and never its other half.

## 10. Downloads

- **CSV/TSV.** One line per source and target pair; a row with no target gets one line. Columns:
  source code, source term, target code, target term, relationship, status, note. For SNOMED CT
  codes the term is the US English preferred term, as in set downloads.
- **FHIR R4 ConceptMap.** One group, SNOMED CT on one side with the edition and release as a
  version URI (as `FhirValueSet` does), the local system on the other. If no URI was given for the
  local system the export uses a `urn:uuid` generated once for the map. `unmatched` for no-map
  rows. Unmapped rows are left out. Owner's name is left out, as now.

## 11. Automap (phase 5)

Lexical, inside the stack, nothing leaves the server. It produces ranked suggestions with a
score and a reason, and accepts nothing by itself.

**To-map pipeline, per unmapped row:**

1. *Normalise.* Lower case, fold accents, strip punctuation, drop filler ("NOS", "unspecified",
   "NEC", "other"), expand a small editable abbreviation table ("L" to left, "#" to fracture,
   "Ca" to carcinoma, "Hx" to history).
2. *Find candidates.* Search Snowstorm with the whole normalised term. Snowstorm already matches
   word prefixes in any order. If nothing comes back, back off: drop stop words, then drop the least
   distinctive word, up to four searches a row. Restrict to the map's scope expression when set.
3. *Score.* Compare the source's word set with each candidate description's word set (Dice
   coefficient after light stemming), take the best description per concept, then adjust: exact
   normalised match scores 1.0; preferred term beats synonym; candidates outside the scope or of an
   unexpected semantic tag are marked down.
4. *Suggest a relationship.* Equal word sets suggest Equivalent. A source with extra words the
   target lacks suggests the target is broader. It is only a starting value.
5. *Reuse.* If the same owner has already accepted a map for the same normalised term in another
   of their maps, offer it first. Their own maps only, never other people's private work.

Top five per row are kept, banded High, Medium and Low, each showing which description matched and
which words did not. "Accept all High" exists but is not the default action.

**From-map** is simpler: the local list is small, so every source concept's descriptions are scored
against every local term in memory with the same scoring. No back-off searching.

What to expect: on tidy clinical picklists this kind of matching usually gets a useful first
suggestion for well over half the rows; on abbreviated or administrative lists far fewer. The scope
expression is the biggest single lever on precision, so the UI should push people to set one.
The honest limit is that it will not find "heart attack" for a source term with no word in common
with any description. That is what embeddings or a model would add, and the door stays open: the
suggestion table does not care where a suggestion came from.

Cost: up to four searches a row means up to 20,000 Snowstorm calls for a full map. Run it in the
same browser-driven chunks as term matching, owner only, one run at a time per map.

## 12. Risks and things I am unsure of

- **Licensing of maps.** The site tells people a set built on the International Edition from
  January 2026 is free to use as Global Patient Set content. I would **not** show that note on map
  downloads until someone at SNOMED International confirms it. CC BY-ND forbids derivatives, and a
  map is arguably one. This needs an answer from licensing, not from me.
- **Third-party code systems.** People will upload ICD-10 variants, CPT, Read codes, national
  tariffs. Some are licensed. Private maps are their business; shared ones are published by this
  site. Hence the confirmation at sharing, and the footer and privacy page need a line on it.
- **Personal data by accident.** A two-column upload limits it but cannot prevent someone choosing
  a column of free text. Terms are capped at 500 characters and the privacy page should say that
  uploaded lists are stored.
- **Snowstorm load.** Term matching and automap are the heaviest things this application will ask
  of it. Signed-in owners only, chunked, capped in parallel. Worth measuring against your instance
  before settling the 5,000 row limit.
- **Snowstorm endpoints.** The design assumes a description search that returns the matching
  description with its concept, and a batch fetch of descriptions by concept. I believe both exist
  but have not checked them against the version you run. First job of phase 1.
- **Anonymous visitors.** Sets are readable without signing in. I have assumed published maps are
  too, with the same restrictions. If the licensing answer is unfavourable, maps should need a
  sign-in.
- **Single author.** No reviewer role, no second opinion, no audit of who accepted what. Fine for
  "simple maps"; not fine if these are going to be used for anything clinical without review
  elsewhere. A line saying so on the map page (as the suggested ECL has) would be honest.

## 13. Phasing

Each phase is one pull request branched from `main`, with the README feature sections updated.
Every phase touches more than five files, so each wants a nod before it starts.

| Phase | Contents | Rough size |
|---|---|---|
| 1 | Schema; create a to-map from CSV or by hand; exact term matching; workbench with manual targets and relationships; CSV download. Private only | Largest: most of the new backend and the workbench |
| 2 | From-maps: SNOMED CT source list by picker, identifiers or ECL; local code list as target vocabulary | Medium, mostly reuse |
| 3 | Publish, versions, names, share links, deprecate, library tab, FHIR ConceptMap, public wording, privacy page | Medium |
| 4 | Upgrade to a new release with change report; update the local code list | Medium |
| 5 | Automap | Medium, self-contained |

You suggested automap as the second part. I have put it last because it only adds suggestions to
a workflow that has to exist first, and because publishing and upgrading are what make a map
trustworthy. If a demo matters more than durability, swap 5 with 3 and 4; nothing depends on the
order after phase 1.

Tests: backend API tests per phase in the style of `ValueSetApiTests` (Testcontainers MariaDB,
stubbed Snowstorm), unit tests for normalising and scoring, frontend specs for the workbench.

## 14. Decided after review

1. CSV files are parsed in the browser and only the code and term columns are sent.
2. Exact term matches are stored as suggestions to start with, not accepted maps.
3. Published maps are readable without signing in, once their owner shares them. The library
   gets two views, Sets and Maps.
4. Updating the local code list is in scope (phase 4).
5. The phase order in section 13 stands.

## 15. Phase 1 as built

Where the first part differs from the sections above:

- Tables and classes are called `code_map`, `CodeMap` and so on, because `Map` is already a Java
  type.
- A target has no `accepted` flag. A row's targets are all suggestions while the row is
  `SUGGESTED` and all accepted once it is `MAPPED`, so the row's status says it. Accepting one of
  several suggestions drops the others.
- There is one page per map, `/maps/:id`, which is the workbench. A separate read-only page comes
  with publishing in phase 3, when someone other than the owner can first open a map.
- `target_scope_ecl` and `score` are left until automap, and `REVIEW`, visibility, versions and
  deprecation until the phases that use them. Rows already carry `version_number`.
- The Snowstorm description search (`GET /browser/{branch}/descriptions`) and the batch fetch of
  descriptions by concept were checked against Snowstorm's source. The description search has
  also been run against a live Snowstorm with the 2026-07-01 International release: it returns the
  shortest descriptions first and refuses terms under three characters, as assumed. The batch
  fetch is not used until phase 2.
- The CSV download neutralises formula-like values with an apostrophe, as the upgrade report does.
  The TSV is exact.

## 16. Phase 2 as built

- A map from SNOMED CT has concepts as its rows. The local codes go in `code_map_local_code`, and
  a target must be one of them. `code_map_row` gained `source_fsn`.
- The local code list always arrives the same way (`POST /maps/{id}/local-codes`): it adds rows
  to a map to SNOMED CT and adds choices to a map from it. Concepts are added with
  `POST /maps/{id}/concepts`.
- Term matching in this direction uses `GET /{branch}/descriptions?conceptIds=`, 25 concepts a
  call, read in pages. It was run against a live Snowstorm. Inactive descriptions and text
  definitions are left out.
- Adding local codes resets `term_checked` on the rows still unmapped, so they are matched again.
- A local code cannot yet be removed from a map from SNOMED CT, or have its term changed. Both
  belong with updating the local list in phase 4, where targets pointing at a removed code have to
  be dealt with.
- Importing concepts by ECL takes 2,000 at a time, the limit of the shared import dialog. A map
  can still hold 5,000.

## 17. Deciding on suggestions together

- Suggested rows can be ticked and then accepted or dismissed in one go. The box in the table
  heading ticks every suggested row the filters let through, not only the page in view.
- `POST /maps/{id}/accepted-suggestions` now takes `{"rowIds": [...]}` and returns those rows as
  they are afterwards. It used to take nothing and accept every row with one suggestion.
  `POST /maps/{id}/dismissed-suggestions` is new and has the same shape.
- Accepting still leaves a row with several suggestions alone, so nobody maps one code to several
  "equivalent" targets without choosing. Dismissing takes them all.
- A dismissed row stays `term_checked`, so its suggestions do not come straight back. In a map from
  SNOMED CT they do come back when local codes are added, because that has unmapped rows matched
  again.

## 18. Local codes nothing is mapped to, and searching for concepts to add

- A map from SNOMED CT is built from the concept side, so nothing showed which local codes had
  been left out. The page now counts the local codes that are not an accepted target of any row
  and lists them in a panel. It is worked out in the browser; no new endpoint.
- Add concepts on a map from SNOMED CT opens the concept search, with the import of a list or an
  ECL expression one button further. Before, only the import was offered there.
- The list is read only. Starting from a local code and finding a concept for it is still two
  steps: add the concept, then choose the code on its row.

## 19. Sorting and filtering by column

- Each column heading of the workbench table sorts by that column, and holds a filter for it. The
  status list and the single "find a code or term" box above the table are gone, replaced by these.
- Sorting and filtering happen in the browser, on rows the page already holds. Nothing is stored:
  a reload goes back to the original order with no filters.
- The target column sorts by a row's first target and filters on the code and term of any of them.
- The box that ticks all suggestions still ticks what the filters let through, on every page.
