import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { LiveService } from '../../core/live.service';
import { Logo } from '../../ui/logo';
import { Api } from '../../core/api';
import { NgTemplateOutlet } from '@angular/common';

interface NavItem { path: string; label: string; icon: string; exact?: boolean; }

/** Console layout: a sidebar on desktop, a top bar with a drawer on small screens. */
@Component({
  selector: 'veil-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Logo, NgTemplateOutlet],
  template: `
    <div class="min-h-screen lg:grid lg:grid-cols-[248px_1fr]">
      <aside class="hidden lg:flex flex-col border-r border-line bg-[#fbfbf9] sticky top-0 h-screen">
        <ng-container *ngTemplateOutlet="nav" />
      </aside>

      <div class="lg:hidden sticky top-0 z-30 flex items-center justify-between px-4 h-14 border-b border-line bg-[#fbfbf9]/95 backdrop-blur">
        <a routerLink="/console"><veil-logo [size]="26" /></a>
        <button class="btn btn-ghost btn-sm" (click)="open.set(!open())" [attr.aria-expanded]="open()" aria-label="Menu">☰</button>
      </div>
      @if (open()) {
        <div class="lg:hidden fixed inset-0 z-40 bg-black/30" (click)="open.set(false)"></div>
        <aside class="lg:hidden fixed z-50 inset-y-0 left-0 w-72 bg-[#fbfbf9] border-r border-line flex flex-col fade-up">
          <ng-container *ngTemplateOutlet="nav" />
        </aside>
      }

      <main class="min-w-0 px-4 sm:px-6 lg:px-10 py-6 lg:py-8 max-w-[1400px] w-full">
        <router-outlet />
      </main>
    </div>

    <ng-template #nav>
      <div class="px-5 pt-5 pb-4 flex items-center justify-between">
        <a routerLink="/console" (click)="open.set(false)"><veil-logo [size]="28" /></a>
        <span class="inline-flex items-center gap-1.5 text-[11px] text-muted" [title]="live.connected() ? 'Live feed connected' : 'Live feed reconnecting'">
          <span class="size-1.5 rounded-full" [class]="live.connected() ? 'bg-protect pulse-dot' : 'bg-stone-300'"></span>{{ live.connected() ? 'Live' : 'Offline' }}
        </span>
      </div>
      <div class="px-5 pb-3">
        <div class="text-[11px] text-muted">Organisation</div>
        <div class="text-sm font-medium truncate">{{ auth.user()?.orgName }}</div>
        <div class="text-[11px] text-muted mt-0.5">Policy v{{ policyVersion() ?? '…' }}</div>
      </div>
      <nav class="flex-1 overflow-y-auto px-3 py-2 flex flex-col gap-4" aria-label="Console">
        @for (group of groups; track group.title) {
          <div>
            <div class="eyebrow px-2 mb-1.5">{{ group.title }}</div>
            @for (item of group.items; track item.path) {
              <a [routerLink]="item.path" routerLinkActive="!bg-white !text-ink shadow-[0_1px_0_#e5e4de] border-line"
                 [routerLinkActiveOptions]="{ exact: !!item.exact }" (click)="open.set(false)"
                 class="flex items-center gap-2.5 px-2 h-8 rounded-lg text-[13.5px] text-ink-2 hover:text-ink hover:bg-white/70 border border-transparent">
                <span class="w-4 text-center text-[13px] opacity-70" aria-hidden="true">{{ item.icon }}</span>{{ item.label }}
                @if (item.path === 'incidents' && openIncidents() > 0) {
                  <span class="ml-auto text-[10px] font-semibold bg-block text-white rounded-full px-1.5 tabular">{{ openIncidents() }}</span>
                }
              </a>
            }
          </div>
        }
      </nav>
      <div class="border-t border-line p-4 flex items-center gap-3">
        <div class="size-8 rounded-full bg-ink text-white flex items-center justify-center text-xs font-semibold">{{ initials() }}</div>
        <div class="min-w-0 flex-1">
          <div class="text-sm font-medium truncate">{{ auth.user()?.name }}</div>
          <div class="text-[11px] text-muted">{{ auth.user()?.role === 'ADMIN' ? 'Security admin' : 'Auditor (read-only)' }}</div>
        </div>
        <button class="btn btn-ghost btn-sm" (click)="auth.logout()">Sign out</button>
      </div>
    </ng-template>`,
})
export class Shell implements OnInit, OnDestroy {
  auth = inject(AuthService);
  live = inject(LiveService);
  private api = inject(Api);
  open = signal(false);
  policyVersion = signal<number | null>(null);
  openIncidents = signal(0);
  private timer?: ReturnType<typeof setInterval>;

  groups: { title: string; items: NavItem[] }[] = [
    { title: 'Monitor', items: [
      { path: '/console', label: 'Overview', icon: '◎', exact: true },
      { path: 'activity', label: 'Activity', icon: '≡' },
      { path: 'incidents', label: 'Incidents', icon: '△' },
      { path: 'shadow-ai', label: 'Shadow AI', icon: '◐' },
    ] },
    { title: 'Protect', items: [
      { path: 'playground', label: 'Playground', icon: '▷' },
      { path: 'policies', label: 'Policies', icon: '§' },
      { path: 'dictionary', label: 'Company terms', icon: '¶' },
    ] },
    { title: 'Operate', items: [
      { path: 'keys', label: 'Keys & teams', icon: '⚿' },
      { path: 'providers', label: 'Providers', icon: '⇄' },
      { path: 'integrations', label: 'Integrations', icon: '⊕' },
      { path: 'compliance', label: 'Compliance', icon: '✓' },
    ] },
  ];

  initials() {
    return (this.auth.user()?.name ?? '?').split(' ').map(p => p[0]).slice(0, 2).join('');
  }

  ngOnInit() {
    this.live.connect();
    this.refresh();
    this.timer = setInterval(() => this.refresh(), 15000);
  }

  ngOnDestroy() {
    this.live.disconnect();
    clearInterval(this.timer);
  }

  private refresh() {
    this.api.policy().subscribe({ next: p => this.policyVersion.set(p.version), error: () => {} });
    this.api.incidents('active').subscribe({ next: r => this.openIncidents.set(r.items.length), error: () => {} });
  }
}
