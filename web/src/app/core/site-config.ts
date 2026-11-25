import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { CanActivateFn, RedirectCommand, Router } from '@angular/router';
import { filter, map, take } from 'rxjs';

/**
 * Facts about this deployment, as configured. Null fields have not been set, or could not be
 * loaded: for mapsEnabled that means it is not known whether maps are switched on.
 */
export interface SiteInfo {
  operator: string | null;
  privacyContact: string | null;
  mapsEnabled: boolean | null;
}

const UNKNOWN: SiteInfo = { operator: null, privacyContact: null, mapsEnabled: null };

/** Loads /api/site once, the first time a page needs it, and shares it. */
@Injectable({ providedIn: 'root' })
export class SiteConfig {
  private readonly http = inject(HttpClient);
  private requested = false;

  /** Null until loaded. */
  readonly info = signal<SiteInfo | null>(null);

  load(): void {
    if (this.requested) {
      return;
    }
    this.requested = true;
    this.http.get<SiteInfo>('/api/site').subscribe({
      next: (info) => this.info.set({ ...UNKNOWN, ...info }),
      // Pages still read properly without the deployment's details.
      error: () => this.info.set(UNKNOWN),
    });
  }
}

/**
 * For the map pages. When the deployment has switched maps off, or it cannot be told whether it
 * has, shows the not-found page instead and keeps the address as it was. The server refuses the
 * maps API either way; this only saves people from a page that cannot work.
 */
export const mapsEnabledGuard: CanActivateFn = () => {
  const site = inject(SiteConfig);
  const router = inject(Router);
  site.load();
  return toObservable(site.info).pipe(
    filter((info) => info !== null),
    take(1),
    map((info) =>
      info.mapsEnabled === true
        ? true
        : new RedirectCommand(router.parseUrl('/not-found'), { skipLocationChange: true }),
    ),
  );
};
