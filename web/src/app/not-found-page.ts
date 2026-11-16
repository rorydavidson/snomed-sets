import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found-page',
  imports: [RouterLink],
  template: `
    <main class="page">
      <h1>Page not found</h1>
      <p>The address may be wrong, or the page may have moved.</p>
      <p><a routerLink="/">Go to the library</a></p>
    </main>
  `,
})
export class NotFoundPage {}
