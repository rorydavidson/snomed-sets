import { Component, OnInit, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { Session } from './core/session';
import { Navbar } from './shell/navbar';
import { SiteFooter } from './shell/site-footer';

@Component({
  imports: [RouterOutlet, Navbar, SiteFooter],
  selector: 'app-root',
  templateUrl: './app.html',
  // The footer sits at the bottom of the window on short pages, and after the content on long ones.
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      min-height: 100vh;
    }

    app-site-footer {
      margin-top: auto;
    }
  `,
})
export class App implements OnInit {
  protected readonly session = inject(Session);

  ngOnInit(): void {
    this.session.load();
  }
}
