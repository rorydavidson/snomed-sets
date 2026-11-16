import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { sessionExpiryInterceptor } from './core/session';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // HttpClient's built-in XSRF support echoes the backend's XSRF-TOKEN cookie on writes.
    provideHttpClient(withInterceptors([sessionExpiryInterceptor])),
  ],
};
