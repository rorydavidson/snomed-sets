import { HttpClient, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { tap } from 'rxjs';

export type SessionState =
  | { status: 'loading' }
  | { status: 'signedOut' }
  | { status: 'signedIn'; name: string }
  | { status: 'error' };

/** The backend sends the browser from here to whichever sign-in is configured. It is not an API call. */
export const SIGN_IN_URL = '/sign-in';

@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly state = signal<SessionState>({ status: 'loading' });

  load(): void {
    this.http.get<{ name: string }>('/api/me').subscribe({
      next: (me) => this.state.set({ status: 'signedIn', name: me.name }),
      error: (error: HttpErrorResponse) =>
        this.state.set({ status: error.status === 401 ? 'signedOut' : 'error' }),
    });
  }

  signOut(): void {
    // The backend ends its own session and replies with the Keycloak sign-out address.
    this.http.post('/logout', null, { responseType: 'text' }).subscribe({
      next: (redirectTo) => window.location.assign(redirectTo),
      error: () => this.state.set({ status: 'error' }),
    });
  }

  markSignedOut(): void {
    this.state.set({ status: 'signedOut' });
  }
}

/** If the session expires while the app is open, drop back to the sign-in screen. */
export const sessionExpiryInterceptor: HttpInterceptorFn = (request, next) => {
  const session = inject(Session);
  return next(request).pipe(
    tap({
      error: (error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 401) {
          session.markSignedOut();
        }
      },
    }),
  );
};
