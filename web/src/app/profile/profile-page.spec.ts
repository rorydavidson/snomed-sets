import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Profile } from '../core/profile-api';
import { Session } from '../core/session';
import { ProfilePage } from './profile-page';

const PROFILE: Profile = {
  name: 'Olive Owner',
  organisation: null,
  contactable: false,
  accountEmail: 'olive@example.org',
  avatarUrl: null,
};

describe('ProfilePage', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<ProfilePage>;
  let page: HTMLElement;

  async function open(profile: Profile): Promise<void> {
    fixture = TestBed.createComponent(ProfilePage);
    page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    http.expectOne('/api/profile').flush(profile);
    await fixture.whenStable();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ProfilePage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(Session).state.set({ status: 'signedIn', name: 'Olive Owner', avatarUrl: null });
  });

  afterEach(() => http.verify());

  it('saves the organisation and lets the person be messaged at their account address', async () => {
    await open(PROFILE);
    expect(page.textContent).toContain('Messages are emailed to olive@example.org');

    const organisation = page.querySelector<HTMLInputElement>('#organisation')!;
    organisation.value = '  Example Health Trust ';
    organisation.dispatchEvent(new Event('input'));
    const contact = page.querySelector<HTMLInputElement>('input[type="checkbox"]')!;
    contact.click();
    page.querySelector('form')!.dispatchEvent(new Event('submit'));

    const request = http.expectOne({ method: 'PUT', url: '/api/profile' });
    expect(request.request.body).toEqual({ organisation: 'Example Health Trust', contactable: true });
    request.flush({ ...PROFILE, organisation: 'Example Health Trust', contactable: true });
    await fixture.whenStable();
    expect(page.textContent).toContain('Saved.');
  });

  it('cannot switch on messages without a verified address, and says why', async () => {
    await open({ ...PROFILE, accountEmail: null });

    expect(page.querySelector<HTMLInputElement>('input[type="checkbox"]')!.disabled).toBe(true);
    expect(page.textContent).toContain('no verified email address');
  });

  it('uploads a picture and shows it in the top bar straight away', async () => {
    await open(PROFILE);
    const input = page.querySelector<HTMLInputElement>('input[type="file"]')!;
    const file = new File([new Uint8Array([137, 80, 78, 71])], 'me.png', { type: 'image/png' });
    Object.defineProperty(input, 'files', { value: [file] });
    input.dispatchEvent(new Event('change'));

    const request = http.expectOne({ method: 'PUT', url: '/api/profile/avatar' });
    expect((request.request.body as FormData).get('file')).toBe(file);
    request.flush({ ...PROFILE, avatarUrl: '/api/avatars/abc?v=2' });
    await fixture.whenStable();

    expect(page.querySelector<HTMLImageElement>('img.avatar')?.getAttribute('src')).toBe('/api/avatars/abc?v=2');
    expect(TestBed.inject(Session).state()).toEqual({ status: 'signedIn', name: 'Olive Owner', avatarUrl: '/api/avatars/abc?v=2' });
  });

  it('refuses a file that is not a PNG or JPEG without uploading it', async () => {
    await open(PROFILE);
    const input = page.querySelector<HTMLInputElement>('input[type="file"]')!;
    Object.defineProperty(input, 'files', { value: [new File(['<svg/>'], 'me.svg', { type: 'image/svg+xml' })] });
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(page.querySelector('[role="alert"]')?.textContent).toContain('PNG or JPEG');
  });
});
