import { Component, OnInit, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { Session } from './core/session';
import { Navbar } from './shell/navbar';

@Component({
  imports: [RouterOutlet, Navbar],
  selector: 'app-root',
  templateUrl: './app.html',
})
export class App implements OnInit {
  protected readonly session = inject(Session);

  ngOnInit(): void {
    this.session.load();
  }
}
