import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../../core/api';
import { AuthService } from '../../core/auth.service';
import { PageHeader } from '../../ui/page';
import { Toasts } from '../../ui/toast';

@Component({
  selector: 'veil-dictionary',
  imports: [FormsModule, PageHeader],
  template: `
    <veil-page-header title="Company terms" subtitle="Teach Veil what is confidential at your company. Terms are matched exactly (one pass over the text, however many terms); topics are learned from example sentences by meaning." />
    <div class="grid xl:grid-cols-2 gap-5 items-start">
      <section class="card p-5">
        <div class="flex items-center justify-between mb-1"><h2 class="font-semibold">Terms</h2>
          @if (auth.isAdmin()) { <button class="btn btn-primary btn-sm" (click)="saveTerms()" [disabled]="!termsDirty()">Save terms</button> }</div>
        <p class="text-xs text-muted mb-4">Project codenames, clients under NDA, internal hostnames. Case-insensitive, whole words.</p>
        <div class="flex flex-col gap-2">
          @for (t of terms(); track $index; let i = $index) {
            <div class="flex gap-2">
              <input class="input" [(ngModel)]="t.term" (ngModelChange)="termsDirty.set(true)" placeholder="Term" [disabled]="!auth.isAdmin()" aria-label="Term" />
              <input class="input" [(ngModel)]="t.label" (ngModelChange)="termsDirty.set(true)" placeholder="Why it's sensitive" [disabled]="!auth.isAdmin()" aria-label="Label" />
              @if (auth.isAdmin()) { <button class="btn btn-ghost" (click)="terms().splice(i, 1); termsDirty.set(true)" aria-label="Remove term">✕</button> }
            </div>
          }
          @if (auth.isAdmin()) { <button class="btn btn-ghost btn-sm self-start" (click)="terms().push({ term: '', label: '' }); termsDirty.set(true)">Add term</button> }
        </div>
      </section>
      <section class="card p-5">
        <div class="flex items-center justify-between mb-1"><h2 class="font-semibold">Confidential topics</h2>
          @if (auth.isAdmin()) { <button class="btn btn-primary btn-sm" (click)="saveTopics()" [disabled]="!topicsDirty()">Save topics</button> }</div>
        <p class="text-xs text-muted mb-4">A few example sentences per topic are enough: Veil embeds them on the proxy (no training, no data leaves) and flags prompts that mean the same thing.
          Built in: unreleased financials, M&amp;A, HR, health, legal, security incidents, strategy, prompt injection.</p>
        <div class="flex flex-col gap-3">
          @for (t of topics(); track $index; let i = $index) {
            <div class="rounded-lg border border-line p-3 flex flex-col gap-2">
              <div class="flex gap-2">
                <input class="input font-mono !w-36" [(ngModel)]="t.label" (ngModelChange)="topicsDirty.set(true)" placeholder="label" [disabled]="!auth.isAdmin()" aria-label="Topic label" />
                <input class="input" [(ngModel)]="t.description" (ngModelChange)="topicsDirty.set(true)" placeholder="Shown as" [disabled]="!auth.isAdmin()" aria-label="Description" />
                @if (auth.isAdmin()) { <button class="btn btn-ghost" (click)="topics().splice(i, 1); topicsDirty.set(true)" aria-label="Remove example">✕</button> }
              </div>
              <textarea class="textarea text-[13px]" rows="2" [(ngModel)]="t.example" (ngModelChange)="topicsDirty.set(true)" placeholder="An example sentence" [disabled]="!auth.isAdmin()" aria-label="Example"></textarea>
            </div>
          }
          @if (auth.isAdmin()) { <button class="btn btn-ghost btn-sm self-start" (click)="addTopic()">Add example</button> }
        </div>
      </section>
    </div>`,
})
export class Dictionary {
  private api = inject(Api);
  private toasts = inject(Toasts);
  auth = inject(AuthService);
  terms = signal<{ term: string; label: string }[]>([]);
  topics = signal<{ label: string; description: string; example: string }[]>([]);
  termsDirty = signal(false);
  topicsDirty = signal(false);

  constructor() {
    this.api.dictionary().subscribe({ next: t => this.terms.set(t), error: e => this.toasts.error(e) });
    this.api.topics().subscribe({ next: t => this.topics.set(t), error: () => {} });
  }

  addTopic() {
    const last = this.topics().at(-1);
    this.topics().push({ label: last?.label ?? 'topic', description: last?.description ?? '', example: '' });
    this.topicsDirty.set(true);
  }

  saveTerms() {
    this.api.saveDictionary(this.terms().filter(t => t.term.trim())).subscribe({
      next: t => { this.terms.set(t); this.termsDirty.set(false); this.toasts.show(`${t.length} terms saved and pushed to the proxies`); },
      error: e => this.toasts.error(e),
    });
  }

  saveTopics() {
    this.api.saveTopics(this.topics().filter(t => t.example.trim())).subscribe({
      next: t => { this.topics.set(t); this.topicsDirty.set(false); this.toasts.show('Topics saved. Proxies re-embed them in about a second.'); },
      error: e => this.toasts.error(e),
    });
  }
}
