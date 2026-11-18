import { Component, signal } from '@angular/core';
import { ValueSetSteps } from './value-set-steps';

/** Kept in this browser only; it holds nothing about the person. */
const DISMISSED_KEY = 'snomed-sets.steps-tip-dismissed';

/**
 * A tip above the library for people who have signed in, showing how a value set is made until
 * they close it. Visitors see the same steps under the landing hero instead.
 */
@Component({
  selector: 'app-steps-tip',
  imports: [ValueSetSteps],
  template: `
    @if (!dismissed()) {
      <section class="tip" aria-labelledby="steps-tip-title">
        <app-value-set-steps headingId="steps-tip-title" />
        <button type="button" class="close" aria-label="Dismiss tip" title="Dismiss tip" (click)="dismiss()">
          <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M4 4l8 8M12 4l-8 8" /></svg>
        </button>
      </section>
    }
  `,
  styleUrl: './steps-tip.scss',
})
export class StepsTip {
  protected readonly dismissed = signal(readDismissed());

  protected dismiss(): void {
    this.dismissed.set(true);
    try {
      localStorage.setItem(DISMISSED_KEY, 'true');
    } catch {
      // Storage can be unavailable, for example in some private windows; the tip then returns next visit.
    }
  }
}

function readDismissed(): boolean {
  try {
    return localStorage.getItem(DISMISSED_KEY) === 'true';
  } catch {
    return false;
  }
}
