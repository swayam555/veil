import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, Team, VirtualKey } from '../../core/api';
import { AuthService } from '../../core/auth.service';
import { ago, usd } from '../../core/format';
import { PageHeader } from '../../ui/page';
import { Toasts } from '../../ui/toast';
import { Confirm } from '../../ui/confirm';

@Component({
  selector: 'veil-keys',
  imports: [FormsModule, PageHeader],
  template: `
    <veil-page-header title="Keys & teams"
      subtitle="Apps and browser extensions use Veil virtual keys, never raw provider keys. Each key has its own team, rate limit and budget, and can be revoked in a second.">
      @if (auth.isAdmin()) { <button class="btn btn-primary" (click)="creating.set(true)">New key</button> }
    </veil-page-header>

    <section class="card overflow-x-auto mb-6">
      <table class="table">
        <thead><tr><th>Key</th><th>Team</th><th>Type</th><th class="text-right">Rate limit</th><th class="text-right">Daily budget</th><th>Created</th><th>Status</th><th></th></tr></thead>
        <tbody>
          @for (k of keys(); track k.id) {
            <tr [class.opacity-55]="k.revokedAt">
              <td><div class="font-medium text-[13.5px]">{{ k.name }}</div><div class="font-mono text-[11px] text-muted">{{ k.prefix }}…{{ k.last4 }}</div></td>
              <td class="text-[13px]">{{ k.teamName }}</td>
              <td class="text-[13px]">{{ k.kind === 'EXTENSION' ? 'Browser extension' : 'Application' }}</td>
              <td class="text-right tabular text-[13px]">{{ k.rpmLimit ? k.rpmLimit + '/min' : 'None' }}</td>
              <td class="text-right tabular text-[13px]">{{ k.dailyBudgetMicros ? usd(k.dailyBudgetMicros, 2) : 'Team budget' }}</td>
              <td class="text-xs text-muted whitespace-nowrap">{{ k.createdBy }} · {{ ago(k.createdAt) }}</td>
              <td>@if (k.revokedAt) { <span class="text-xs text-block font-medium">Revoked {{ ago(k.revokedAt) }}</span> } @else { <span class="text-xs text-protect font-medium">Active</span> }</td>
              <td class="text-right">@if (!k.revokedAt && auth.isAdmin()) { <button class="btn btn-ghost btn-sm" (click)="revoke(k)">Revoke</button> }</td>
            </tr>
          }
        </tbody>
      </table>
    </section>

    <h2 class="font-semibold mb-3">Teams and daily AI budgets</h2>
    <section class="card overflow-x-auto">
      <table class="table">
        <thead><tr><th>Team</th><th class="text-right">Active keys</th><th class="text-right">Daily budget (USD)</th><th></th></tr></thead>
        <tbody>
          @for (t of teams(); track t.id) {
            <tr>
              <td class="font-medium">{{ t.name }} <span class="font-mono text-[11px] text-muted ml-1">{{ t.id }}</span></td>
              <td class="text-right tabular">{{ t.activeKeys }}</td>
              <td class="text-right">
                @if (auth.isAdmin()) {
                  <input type="number" min="0" step="1" class="input !w-28 !h-8 text-right tabular" [ngModel]="t.dailyBudgetMicros / 1e6"
                         (change)="setBudget(t, $any($event.target).value)" [attr.aria-label]="'Budget for ' + t.name" />
                } @else { <span class="tabular">{{ t.dailyBudgetMicros ? usd(t.dailyBudgetMicros, 2) : 'Unlimited' }}</span> }
              </td>
              <td class="text-xs text-muted">{{ t.dailyBudgetMicros ? '' : '0 = unlimited' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>

    @if (creating()) {
      <div class="fixed inset-0 z-40 bg-black/30" (click)="close()"></div>
      <div class="fixed z-50 inset-x-4 top-[8vh] mx-auto max-w-lg card p-6 shadow-2xl fade-up" role="dialog" aria-label="New key">
        @if (!secret()) {
          <h2 class="text-lg font-semibold mb-4">New virtual key</h2>
          <div class="flex flex-col gap-3">
            <div><label class="label">Name</label><input class="input" [(ngModel)]="form.name" placeholder="e.g. Support reply bot" /></div>
            <div class="grid grid-cols-2 gap-3">
              <div><label class="label">Team</label><select class="select" [(ngModel)]="form.teamId">@for (t of teams(); track t.id) { <option [value]="t.id">{{ t.name }}</option> }</select></div>
              <div><label class="label">Used by</label><select class="select" [(ngModel)]="form.kind"><option value="API">An application</option><option value="EXTENSION">Browser extensions</option></select></div>
            </div>
            <div class="grid grid-cols-2 gap-3">
              <div><label class="label">Requests per minute (0 = none)</label><input type="number" min="0" class="input" [(ngModel)]="form.rpmLimit" /></div>
              <div><label class="label">Daily budget USD (0 = team's)</label><input type="number" min="0" class="input" [(ngModel)]="form.budget" /></div>
            </div>
          </div>
          <div class="flex justify-end gap-2 mt-6">
            <button class="btn btn-ghost" (click)="close()">Cancel</button>
            <button class="btn btn-primary" (click)="create()" [disabled]="!form.name.trim()">Create key</button>
          </div>
        } @else {
          <h2 class="text-lg font-semibold mb-1">Copy your key now</h2>
          <p class="text-sm text-muted mb-4">Veil stores only a hash of it, so it can't be shown again.</p>
          <div class="flex gap-2"><code class="flex-1 rounded-lg bg-ink text-white font-mono text-[12.5px] px-3 py-2.5 break-all">{{ secret() }}</code>
            <button class="btn btn-ghost" (click)="copy()">{{ copied() ? 'Copied' : 'Copy' }}</button></div>
          @if (form.kind === 'API') {
            <div class="mt-4 text-xs text-muted">Use it with any OpenAI SDK:</div>
            <pre class="mt-1 rounded-lg bg-[#fbfbf9] border border-line p-3 text-[12px] overflow-x-auto">client = OpenAI(base_url="{{ origin }}/v1", api_key="{{ secret() }}")</pre>
          } @else {
            <p class="mt-4 text-xs text-muted">Paste it into the Veil browser extension's options page, with server {{ origin }}.</p>
          }
          <div class="flex justify-end mt-6"><button class="btn btn-primary" (click)="close()">Done</button></div>
        }
      </div>
    }`,
})
export class Keys {
  private api = inject(Api);
  private toasts = inject(Toasts);
  private confirm = inject(Confirm);
  auth = inject(AuthService);
  keys = signal<VirtualKey[]>([]);
  teams = signal<Team[]>([]);
  creating = signal(false);
  secret = signal('');
  copied = signal(false);
  form = { name: '', teamId: 'support', kind: 'API', rpmLimit: 60, budget: 0 };
  origin = location.origin;
  ago = ago; usd = usd;

  constructor() {
    this.load();
  }

  load() {
    this.api.keys().subscribe({ next: k => this.keys.set(k), error: e => this.toasts.error(e) });
    this.api.teams().subscribe({ next: t => this.teams.set(t), error: () => {} });
  }

  create() {
    this.api.createKey({ name: this.form.name.trim(), teamId: this.form.teamId, kind: this.form.kind, rpmLimit: Number(this.form.rpmLimit) || 0,
      dailyBudgetMicros: Math.round((Number(this.form.budget) || 0) * 1e6) }).subscribe({
      next: r => { this.secret.set(r.secret); this.load(); },
      error: e => this.toasts.error(e),
    });
  }

  close() {
    this.creating.set(false);
    this.secret.set('');
    this.copied.set(false);
    this.form = { name: '', teamId: 'support', kind: 'API', rpmLimit: 60, budget: 0 };
  }

  copy() {
    navigator.clipboard?.writeText(this.secret()).then(() => this.copied.set(true));
  }

  async revoke(k: VirtualKey) {
    if (!(await this.confirm.ask('Revoke this key?', `Apps using "${k.name}" will be refused within a second. This can't be undone.`, 'Revoke key'))) return;
    this.api.revokeKey(k.id).subscribe({ next: () => { this.toasts.show('Key revoked. Every proxy has dropped it.'); this.load(); }, error: e => this.toasts.error(e) });
  }

  setBudget(t: Team, value: string) {
    const micros = Math.round(Math.max(0, Number(value) || 0) * 1e6);
    this.api.updateTeam(t.id, t.name, micros).subscribe({ next: () => { this.toasts.show(`Budget for ${t.name} updated`); this.load(); }, error: e => this.toasts.error(e) });
  }
}
