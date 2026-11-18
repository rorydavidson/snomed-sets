import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { Profile, ProfileApi } from '../core/profile-api';
import { Session } from '../core/session';
import { describeError } from '../core/value-set-api';

const MAX_PICTURE_BYTES = 1024 * 1024;

/** Where a person sets their picture, their organisation and whether others may message them. */
@Component({
  selector: 'app-profile-page',
  templateUrl: './profile-page.html',
  styleUrl: './profile-page.scss',
})
export class ProfilePage implements OnInit {
  private readonly api = inject(ProfileApi);
  private readonly session = inject(Session);

  protected readonly profile = signal<Profile | null>(null);
  protected readonly loadError = signal<string | null>(null);

  protected readonly organisation = signal('');
  protected readonly contactable = signal(false);
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly uploading = signal(false);
  protected readonly pictureError = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loadError.set(null);
    this.api.get().subscribe({
      next: (profile) => this.show(profile),
      error: (error: unknown) => this.loadError.set(describeError(error)),
    });
  }

  protected save(event: Event): void {
    event.preventDefault();
    this.saving.set(true);
    this.saved.set(false);
    this.saveError.set(null);
    const organisation = this.organisation().trim();
    this.api.update(organisation === '' ? null : organisation, this.contactable()).subscribe({
      next: (profile) => {
        this.show(profile);
        this.saving.set(false);
        this.saved.set(true);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.saveError.set(describeError(error));
      },
    });
  }

  protected choosePicture(input: HTMLInputElement): void {
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    this.pictureError.set(null);
    if (!['image/png', 'image/jpeg'].includes(file.type)) {
      this.pictureError.set('Choose a PNG or JPEG image.');
      return;
    }
    if (file.size > MAX_PICTURE_BYTES) {
      this.pictureError.set('Choose an image of 1 MB or less.');
      return;
    }
    this.uploading.set(true);
    this.api.uploadAvatar(file).subscribe(this.pictureResult());
  }

  protected removePicture(): void {
    this.pictureError.set(null);
    this.uploading.set(true);
    this.api.removeAvatar().subscribe(this.pictureResult());
  }

  /** "Sample Person" becomes "SP". */
  protected initials(name: string): string {
    const words = name.trim().split(/\s+/).filter(Boolean);
    return words.length === 0 ? '?' : (words[0].charAt(0) + (words.length > 1 ? words[words.length - 1].charAt(0) : '')).toUpperCase();
  }

  private pictureResult() {
    return {
      next: (profile: Profile) => {
        this.uploading.set(false);
        this.profile.update((current) => (current ? { ...current, avatarUrl: profile.avatarUrl } : profile));
        this.session.setAvatar(profile.avatarUrl);
      },
      error: (error: unknown) => {
        this.uploading.set(false);
        this.pictureError.set(
          error instanceof HttpErrorResponse && error.status === 413 ? 'Choose an image of 1 MB or less.' : describeError(error),
        );
      },
    };
  }

  private show(profile: Profile): void {
    this.profile.set(profile);
    this.organisation.set(profile.organisation ?? '');
    this.contactable.set(profile.contactable);
  }
}
