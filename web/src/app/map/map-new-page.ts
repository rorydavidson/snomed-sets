import { Component, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { catchError, of, switchMap, tap } from 'rxjs';
import { MapApi, SourceRow, describeMapError } from '../core/map-api';
import { CodeSystem, CodeSystemVersion, ValueSetApi, describeError } from '../core/value-set-api';
import { CodeListInput } from './code-list-input';

/** Starts a map from a list of local codes. The mapping itself is done on the map's own page. */
@Component({
  selector: 'app-map-new-page',
  imports: [ReactiveFormsModule, RouterLink, CodeListInput],
  template: `
    <main class="page">
      <p><a routerLink="/maps">Back to your maps</a></p>

      @if (loadError(); as message) {
        <div class="error-panel" role="alert">
          <h1>A map cannot be created right now</h1>
          <p>{{ message }}</p>
        </div>
      } @else if (loaded()) {
        <h1>New map to SNOMED CT</h1>
        <p>
          A map gives each code of a local list one or more SNOMED CT concepts. It is built on one release, and is
          private to you.
        </p>

        <form class="form" [formGroup]="form" (ngSubmit)="create()" novalidate>
          <div class="columns">
            <section>
              <div class="field">
                <label for="name">Name</label>
                <input id="name" type="text" formControlName="name" maxlength="200" autocomplete="off" />
                @if (showError('name')) {
                  <p class="field-error">Give the map a name.</p>
                }
              </div>

              <div class="field">
                <label for="description">Description</label>
                <textarea id="description" formControlName="description" rows="3" maxlength="2000"></textarea>
              </div>

              <div class="field">
                <label for="local-name">Name of the local code list</label>
                <input id="local-name" type="text" formControlName="localSystemName" maxlength="200" autocomplete="off" />
                <p class="meta">For example "Ward diagnosis picklist".</p>
                @if (showError('localSystemName')) {
                  <p class="field-error">Say what the local codes are.</p>
                }
              </div>

              <div class="field">
                <label for="local-version">Version of the local code list (optional)</label>
                <input id="local-version" type="text" formControlName="localSystemVersion" maxlength="100" autocomplete="off" />
              </div>

              <div class="field">
                <label for="local-uri">Identifier of the local code system (optional)</label>
                <input id="local-uri" type="text" class="mono" formControlName="localSystemUri" maxlength="500" autocomplete="off" />
                <p class="meta">A URI, if the code system has one. It is stored as typed and never opened.</p>
              </div>

              <div class="field">
                <label for="code-system">Code system</label>
                <select id="code-system" formControlName="codeSystem">
                  @for (codeSystem of codeSystems(); track codeSystem.shortName) {
                    <option [value]="codeSystem.shortName">{{ codeSystem.name }}</option>
                  }
                </select>
              </div>

              <div class="field">
                <label for="code-system-version">Release</label>
                @if (versions(); as list) {
                  @if (list.length > 0) {
                    <select id="code-system-version" formControlName="codeSystemVersion">
                      @for (release of list; track release.version) {
                        <option [value]="release.version">{{ release.version }}{{ $first ? ' (latest)' : '' }}</option>
                      }
                    </select>
                  } @else if (versionsError()) {
                    <p id="code-system-version" class="field-error">The releases could not be loaded, so the latest release will be used.</p>
                  } @else {
                    <p id="code-system-version">This code system has no release yet, so its latest content is used.</p>
                  }
                } @else {
                  <p id="code-system-version" class="muted" role="status">Loading releases…</p>
                }
                <p class="meta">The code system and release cannot be changed here afterwards.</p>
              </div>
            </section>

            <section class="wide">
              <h2>Local codes</h2>
              <app-code-list-input (rowsChange)="rows.set($event)" />
              <p class="meta">You can start with none and add codes later.</p>
            </section>
          </div>

          @if (saveError(); as message) {
            <p class="field-error" role="alert">{{ message }}</p>
          }
          <div class="actions">
            <button type="submit" class="button button-primary" [disabled]="saving() || rows() === null">
              {{ saving() ? 'Creating…' : 'Create map' }}
            </button>
            <a class="button button-secondary" routerLink="/maps">Cancel</a>
          </div>
        </form>
      } @else {
        <p class="muted" role="status">Loading…</p>
      }
    </main>
  `,
})
export class MapNewPage implements OnInit {
  private readonly api = inject(MapApi);
  private readonly valueSets = inject(ValueSetApi);
  private readonly router = inject(Router);

  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, Validators.pattern(/\S/)]],
    description: [''],
    localSystemName: ['', [Validators.required, Validators.pattern(/\S/)]],
    localSystemVersion: [''],
    localSystemUri: [''],
    codeSystem: ['SNOMEDCT'],
    /** Empty for a code system with no releases listed, which then uses its latest content. */
    codeSystemVersion: [''],
  });

  protected readonly loaded = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly codeSystems = signal<CodeSystem[]>([]);
  /** The releases of the chosen code system, newest first. Null while they load. */
  protected readonly versions = signal<CodeSystemVersion[] | null>(null);
  protected readonly versionsError = signal(false);
  /** The local codes to start with, or null while the list cannot be used as it is. */
  protected readonly rows = signal<SourceRow[] | null>([]);

  constructor() {
    const { codeSystem, codeSystemVersion } = this.form.controls;
    // Choosing a code system loads its releases and picks the latest.
    codeSystem.valueChanges
      .pipe(
        tap(() => {
          this.versions.set(null);
          this.versionsError.set(false);
        }),
        switchMap((chosen) =>
          this.valueSets.codeSystemVersions(chosen).pipe(
            catchError(() => {
              this.versionsError.set(true);
              return of<CodeSystemVersion[]>([]);
            }),
          ),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((versions) => {
        this.versions.set(versions);
        codeSystemVersion.setValue(versions[0]?.version ?? '');
      });
  }

  ngOnInit(): void {
    this.valueSets.codeSystems().subscribe({
      next: (codeSystems) => {
        if (codeSystems.length === 0) {
          this.loadError.set('Snowstorm has no code systems to build a map against.');
          return;
        }
        this.codeSystems.set(codeSystems);
        const preferred = codeSystems.find((candidate) => candidate.shortName === 'SNOMEDCT') ?? codeSystems[0];
        this.form.controls.codeSystem.setValue(preferred.shortName);
        this.loaded.set(true);
      },
      error: (error: unknown) => this.loadError.set(describeError(error)),
    });
  }

  protected showError(control: 'name' | 'localSystemName'): boolean {
    const field = this.form.controls[control];
    return field.invalid && field.touched;
  }

  protected create(): void {
    const rows = this.rows();
    if (this.form.invalid || rows === null) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    this.saving.set(true);
    this.saveError.set(null);
    this.api
      .create({
        details: {
          name: value.name.trim(),
          description: value.description.trim(),
          localSystemName: value.localSystemName.trim(),
          localSystemUri: value.localSystemUri.trim() || null,
          localSystemVersion: value.localSystemVersion.trim() || null,
        },
        codeSystem: value.codeSystem,
        codeSystemVersion: value.codeSystemVersion === '' ? null : value.codeSystemVersion,
        rows,
      })
      .subscribe({
        next: (created) => void this.router.navigate(['/maps', created.id]),
        error: (error: unknown) => {
          this.saving.set(false);
          this.saveError.set(describeMapError(error));
        },
      });
  }
}
