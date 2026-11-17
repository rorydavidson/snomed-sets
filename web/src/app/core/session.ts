import { HttpClient, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { CanActivateFn } from '@angular/router';
import { filter, map, take, tap } from 'rxjs';

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

/**
 * For pages that change things, such as the editor. Waits until it is known whether someone is
 * signed in, then sends a visitor who is not to the sign-in, which brings them back to the library.
 */
export const signedInGuard: CanActivateFn = () => {
  const session = inject(Session);
  return toObservable(session.state).pipe(
    filter((state) => state.status !== 'loading'),
    take(1),
    map((state) => {
      if (state.status === 'signedIn') {
        return true;
      }
      if (state.status === 'signedOut') {
        window.location.assign(SIGN_IN_URL);
      }
      return false;
    }),
  );
};

/**
 * If the session expires while the app is open, carry on as a visitor who has not signed in:
 * the published library stays readable and the header offers sign-in again.
 */
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
