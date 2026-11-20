import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ValueSetApi, describeError } from '../core/value-set-api';

/**
 * Opens a share link that names a value set by its SNOMED CT identifier, such as
 * /value-sets/sct/723264001 or /value-sets/sct/723264001/versions/2, by finding the set and
 * going on to its page.
 */
@Component({
  selector: 'app-identifier-link-page',
  imports: [RouterLink],
  template: `
    <main class="page">
      @if (error(); as message) {
        <div class="error-panel" role="alert">
          <h1>No value set could be found for {{ sctid }}</h1>
          <p>{{ message }}</p>
          <a routerLink="/">Back to the catalogue</a>
        </div>
      } @else {
        <p class="muted" role="status">Finding the value set for {{ sctid }}…</p>
      }
    </main>
  `,
})
export class IdentifierLinkPage implements OnInit {
  private readonly api = inject(ValueSetApi);
  private readonly router = inject(Router);
  private readonly params = inject(ActivatedRoute).snapshot.paramMap;

  protected readonly sctid = this.params.get('sctid') ?? '';
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    const version = this.params.get('version');
    this.api.getByIdentifier(this.sctid).subscribe({
      next: (set) =>
        void this.router.navigate(version ? ['/value-sets', set.id, 'versions', version] : ['/value-sets', set.id], {
          replaceUrl: true,
        }),
      error: (error: unknown) => this.error.set(describeError(error)),
    });
  }
}
