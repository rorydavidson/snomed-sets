import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** The signed-in person's own profile. Only ever shown to them. */
export interface Profile {
  name: string;
  /** Shown instead of their name on the value sets where they choose it. */
  organisation: string | null;
  /** Whether signed-in people may message them about their value sets. */
  contactable: boolean;
  /** Their account's verified address, where messages would go. Null if the account has none. */
  accountEmail: string | null;
  avatarUrl: string | null;
}

@Injectable({ providedIn: 'root' })
export class ProfileApi {
  private readonly http = inject(HttpClient);

  get(): Observable<Profile> {
    return this.http.get<Profile>('/api/profile');
  }

  update(organisation: string | null, contactable: boolean): Observable<Profile> {
    return this.http.put<Profile>('/api/profile', { organisation, contactable });
  }

  uploadAvatar(file: File): Observable<Profile> {
    const form = new FormData();
    form.append('file', file);
    return this.http.put<Profile>('/api/profile/avatar', form);
  }

  removeAvatar(): Observable<Profile> {
    return this.http.delete<Profile>('/api/profile/avatar');
  }
}
