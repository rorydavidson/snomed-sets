import { Component, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SessionState } from '../core/session';
import { SnomedSignIn } from './snomed-sign-in';

/**
 * The top bar shared by SNOMED International tools, ported from the shared-ui navbar in
 * IHTSDO/snomed-ui. It is a local copy rather than a dependency because that library is not
 * published and is tied to ngx-translate and the authoring platform's sign-in. The markup and
 * Tailwind classes are kept as close to the original as possible so the two look the same;
 * compare navbar.html with projects/shared-ui/src/lib/navbar/navbar.html when either changes.
 *
 * Differences from the original: this app's own logo instead of SNOMED International's (it is
 * not one of their apps), so the tools drawer is headed with their logo to show those links are
 * theirs, and the links menu leaves out their status page and privacy policy. No language picker (this app has no translations), no roles
 * panel (roles are not sent to the browser), sign-in and sign-out go through this app's backend,
 * and menu entries are real links and buttons so they work from the keyboard.
 */

interface SnomedApp {
  name: string;
  link: string;
  faIcon: string;
  /** A full Tailwind class rather than a colour name, so Tailwind finds it when scanning. */
  colourClass: string;
  group: number;
}

/**
 * The shared-ui drawer list, less the staff tools that only have dev addresses there.
 * Icons, colours and groups are as in its drawerConfig.json.
 */
const SNOMED_APPS: readonly SnomedApp[] = [
  { name: 'Docs', link: 'https://docs.snomed.org/', faIcon: 'fa-book', colourClass: 'text-sky-500', group: 1 },
  { name: 'E-Learning', link: 'https://elearning.ihtsdotools.org/', faIcon: 'fa-building-columns', colourClass: 'text-cyan-500', group: 1 },
  { name: 'Forums', link: 'https://forums.snomed.org/', faIcon: 'fa-comments', colourClass: 'text-sky-500', group: 1 },
  { name: 'Implementation Support', link: 'https://www.implementation.snomed.org/', faIcon: 'fa-life-ring', colourClass: 'text-blue-500', group: 1 },
  { name: 'Spaces', link: 'https://spaces.snomed.org/', faIcon: 'fa-users', colourClass: 'text-sky-500', group: 1 },
  { name: 'Browser', link: 'https://browser.ihtsdotools.org/', faIcon: 'fa-magnifying-glass', colourClass: 'text-violet-500', group: 2 },
  { name: 'Lookup', link: 'https://lookup.snomedtools.org/', faIcon: 'fa-binoculars', colourClass: 'text-violet-500', group: 2 },
  { name: 'Snap2 SNOMED', link: 'https://snap.snomedtools.org/', faIcon: 'fa-link', colourClass: 'text-emerald-500', group: 3 },
];

/** Groups in order, each with its apps sorted by name, as the shared-ui drawer shows them. */
const APP_GROUPS: readonly (readonly SnomedApp[])[] = [...new Set(SNOMED_APPS.map((app) => app.group))]
  .sort((a, b) => a - b)
  .map((group) =>
    SNOMED_APPS.filter((app) => app.group === group).sort((a, b) => a.name.localeCompare(b.name)),
  );

const EXTERNAL_LINKS = [
  { label: 'SNOMED International', link: 'https://www.snomed.org/' },
] as const;

type Menu = 'user' | 'apps' | 'links';

@Component({
  selector: 'app-navbar',
  imports: [RouterLink, SnomedSignIn],
  templateUrl: './navbar.html',
  host: { '(document:keydown.escape)': 'close()' },
})
export class Navbar {
  readonly title = input.required<string>();
  readonly session = input.required<SessionState>();
  readonly signOut = output<void>();

  protected readonly appGroups = APP_GROUPS;
  protected readonly links = EXTERNAL_LINKS;
  protected readonly open = signal<Menu | null>(null);

  protected toggle(menu: Menu): void {
    this.open.update((current) => (current === menu ? null : menu));
  }

  protected close(): void {
    this.open.set(null);
  }

  protected requestSignOut(): void {
    this.close();
    this.signOut.emit();
  }

  /** "Sample Person" becomes "SP"; a single name gives one letter. */
  protected initials(name: string): string {
    const words = name.trim().split(/\s+/).filter(Boolean);
    if (words.length === 0) {
      return '?';
    }
    const first = words[0].charAt(0);
    const last = words.length > 1 ? words[words.length - 1].charAt(0) : '';
    return (first + last).toUpperCase();
  }
}
