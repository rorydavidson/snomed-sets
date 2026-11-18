import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';

/** Who runs this deployment, as configured; null fields have not been set. */
export interface SiteInfo {
  operator: string | null;
  privacyContact: string | null;
}

/**
 * What this site keeps about people, why and for how long. Keep it in step with the code: it
 * describes the profile, messages, cookies, browser storage and the visitors' search limit.
 */
@Component({
  selector: 'app-privacy-page',
  templateUrl: './privacy-page.html',
  styles: `
    .privacy {
      max-width: var(--container-md);
    }

    section {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
      margin-top: var(--space-4);
    }

    h2 {
      font-size: var(--fs-h4);
    }

    ul {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      margin: 0;
      padding-left: var(--space-5);
    }

    td {
      vertical-align: top;
    }
  `,
})
export class PrivacyPage implements OnInit {
  private readonly http = inject(HttpClient);

  protected readonly site = signal<SiteInfo | null>(null);

  ngOnInit(): void {
    this.http.get<SiteInfo>('/api/site').subscribe({
      next: (info) => this.site.set(info),
      // The page still reads properly without the deployment's details.
      error: () => this.site.set({ operator: null, privacyContact: null }),
    });
  }
}
