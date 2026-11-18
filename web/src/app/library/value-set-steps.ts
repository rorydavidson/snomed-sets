import { Component, input } from '@angular/core';

/**
 * The three steps of making a value set. Visitors see them under the landing hero, and people who
 * have just signed in see them once as a tip above their library.
 */
@Component({
  selector: 'app-value-set-steps',
  template: `
    <h2 [id]="headingId()">How a value set is made</h2>
    <ol>
      <li>
        <h3>Define</h3>
        <p>Write an ECL rule, or pick concepts one by one. The list of concepts updates as you go.</p>
      </li>
      <li>
        <h3>Publish</h3>
        <p>Fix a version against a SNOMED CT release, so everyone using it gets exactly the same list.</p>
      </li>
      <li>
        <h3>Share and download</h3>
        <p>Share it with the community and download it as a Global Patient Set file or a FHIR ValueSet.</p>
      </li>
    </ol>
  `,
  styleUrl: './value-set-steps.scss',
})
export class ValueSetSteps {
  /** So the section around the steps can be labelled by their heading. */
  readonly headingId = input.required<string>();
}
