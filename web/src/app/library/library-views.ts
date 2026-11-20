import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

/** Switches the library between its two views: value sets and maps. */
@Component({
  selector: 'app-library-views',
  imports: [RouterLink, RouterLinkActive],
  template: `
    <nav class="views" aria-label="Library views">
      <a routerLink="/" routerLinkActive="current" [routerLinkActiveOptions]="{ exact: true }" ariaCurrentWhenActive="page">Value sets</a>
      <a routerLink="/maps" routerLinkActive="current" ariaCurrentWhenActive="page">Maps</a>
    </nav>
  `,
  styles: `
    .views {
      display: flex;
      gap: var(--space-2);
      border-bottom: 1px solid var(--border-subtle);
    }

    a {
      padding: var(--space-2) var(--space-4);
      font-weight: var(--fw-semibold);
      color: var(--text-muted);
      text-decoration: none;
      border-bottom: 3px solid transparent;
      margin-bottom: -1px;
    }

    a:hover {
      color: var(--color-primary);
    }

    a.current {
      color: var(--color-primary);
      border-bottom-color: var(--color-primary);
    }
  `,
})
export class LibraryViews {}
