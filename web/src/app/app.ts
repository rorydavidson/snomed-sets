import { Component, OnInit, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { SIGN_IN_URL, Session } from './core/session';

@Component({
  imports: [RouterOutlet, RouterLink],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App implements OnInit {
  protected readonly session = inject(Session);
  protected readonly signInUrl = SIGN_IN_URL;

  ngOnInit(): void {
    this.session.load();
  }
}
