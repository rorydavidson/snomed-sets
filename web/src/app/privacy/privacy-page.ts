import { Component, OnInit, computed, inject } from '@angular/core';
import { SiteConfig } from '../core/site-config';

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
  private readonly config = inject(SiteConfig);

  protected readonly site = this.config.info;
  /**
   * Maps are left out only when the deployment says they are switched off. If that cannot be
   * loaded the page describes them, as saying too much here is safer than leaving something out.
   */
  protected readonly maps = computed(() => this.site()?.mapsEnabled !== false);

  ngOnInit(): void {
    this.config.load();
  }
}
