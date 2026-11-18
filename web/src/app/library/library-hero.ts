import { Component } from '@angular/core';
import { SnomedSignIn } from '../shell/snomed-sign-in';

/** A few real children of 195967001 |Asthma|, to show what an ECL rule selects. */
const EXAMPLE_CONCEPTS = [
  { id: '389145006', term: 'Allergic asthma' },
  { id: '266361008', term: 'Intrinsic asthma' },
  { id: '281239006', term: 'Acute asthma' },
  { id: '409663006', term: 'Cough variant asthma' },
  { id: '370221004', term: 'Severe asthma' },
  { id: '72301000119103', term: 'Asthma in pregnancy' },
] as const;

/**
 * The introduction visitors see above the published library: what a value set is and how to
 * start. The illustration on the right is the app's logo idea made literal: a rule
 * between braces, and the concepts it gathers.
 */
@Component({
  selector: 'app-library-hero',
  imports: [SnomedSignIn],
  templateUrl: './library-hero.html',
  styleUrl: './library-hero.scss',
})
export class LibraryHero {
  protected readonly concepts = EXAMPLE_CONCEPTS;
}
