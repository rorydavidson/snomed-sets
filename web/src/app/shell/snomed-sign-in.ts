import { Component, input } from '@angular/core';
import { SIGN_IN_URL } from '../core/session';

/**
 * The sign-in button, branded like a "Sign in with…" button so people know which account to use.
 * It is built from HTML rather than a flat image so the text uses the page font, scales cleanly
 * and can be read by screen readers. The large size is for pages, the compact one for the top bar.
 */
@Component({
  selector: 'app-snomed-sign-in',
  host: { '[class.compact]': "size() === 'compact'" },
  template: `
    <a class="sign-in" [href]="signInUrl">
      <img class="mark" src="snomed-international-logo.png" alt="" />
      @if (size() === 'compact') {
        <span class="label">Sign in</span>
      } @else {
        <span class="label">
          <strong>Sign in</strong>
          <span>with your SNOMED International account</span>
        </span>
      }
    </a>
  `,
  styleUrl: './snomed-sign-in.scss',
})
export class SnomedSignIn {
  readonly size = input<'large' | 'compact'>('large');
  protected readonly signInUrl = SIGN_IN_URL;
}
