import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/** The SNOMED CT copyright and licence notice every page carries, with a link to the privacy page. */
@Component({
  selector: 'app-site-footer',
  imports: [RouterLink],
  template: `
    <footer class="site-footer">
      <div class="footer-inner">
        <nav class="footer-links" aria-label="About this site">
          <a routerLink="/privacy">Privacy</a>
          <a href="https://www.snomed.org/get-snomed" target="_blank" rel="noopener noreferrer">SNOMED CT licensing</a>
          <a href="https://www.snomed.org/" target="_blank" rel="noopener noreferrer">SNOMED International</a>
        </nav>
        <p>
          This site includes SNOMED Clinical Terms® (SNOMED CT®), which is used by permission of SNOMED International.
          All rights reserved. SNOMED CT® was originally created by the College of American Pathologists. “SNOMED” and
          “SNOMED CT” are registered trademarks of SNOMED International.
        </p>
        <p>
          Using SNOMED CT in your own products or systems needs a licence. Many countries are SNOMED International
          Members and license it to everyone in their territory at no charge; elsewhere, an Affiliate Licence is
          available from SNOMED International.
        </p>
        <p>
          Since the January 2026 release, the
          <a href="https://www.snomed.org/gps" target="_blank" rel="noopener noreferrer">SNOMED CT Global Patient Set</a>
          (GPS) includes every concept in the International Edition and is published under the
          <a href="https://creativecommons.org/licenses/by-nd/4.0/" target="_blank" rel="noopener noreferrer"
            >Creative Commons Attribution-NoDerivatives 4.0</a
          >
          licence (CC BY-ND 4.0). Downloads in the GPS layout of value sets built on the International Edition can be
          used under that licence, without a SNOMED CT Affiliate Licence. Concepts from national extensions are not
          part of the GPS.
        </p>
      </div>
    </footer>
  `,
  styles: `
    .site-footer {
      margin-top: var(--space-16);
      color: var(--text-muted);
      background: var(--surface-card);
      border-top: 1px solid var(--border-subtle);
    }

    .footer-inner {
      display: flex;
      flex-direction: column;
      gap: var(--space-3);
      max-width: var(--container-xl);
      margin: 0 auto;
      padding: var(--space-6) var(--space-8) var(--space-8);
      font-size: var(--fs-caption);
      line-height: var(--lh-relaxed);

      p {
        max-width: 90ch;
      }
    }

    .footer-links {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2) var(--space-6);
      font-size: var(--fs-body-sm);
      font-weight: var(--fw-semibold);
    }

    @media (max-width: 860px) {
      .footer-inner {
        padding-inline: var(--space-5);
      }
    }
  `,
})
export class SiteFooter {}
