import { Component, Injectable, inject, signal } from '@angular/core';

interface Pending { title: string; text: string; confirm: string; danger: boolean; resolve: (ok: boolean) => void; }

/** In-app confirmation dialog (native confirm() blocks the page and can't be styled). */
@Injectable({ providedIn: 'root' })
export class Confirm {
  readonly pending = signal<Pending | null>(null);

  ask(title: string, text: string, confirm = 'Confirm', danger = true): Promise<boolean> {
    return new Promise(resolve => this.pending.set({ title, text, confirm, danger, resolve }));
  }

  answer(ok: boolean) {
    this.pending()?.resolve(ok);
    this.pending.set(null);
  }
}

@Component({
  selector: 'veil-confirm',
  template: `
    @if (c.pending(); as p) {
      <div class="fixed inset-0 z-[60] bg-black/30" (click)="c.answer(false)"></div>
      <div class="fixed z-[61] inset-x-4 top-[20vh] mx-auto max-w-md card p-6 shadow-2xl fade-up" role="alertdialog" [attr.aria-label]="p.title">
        <h2 class="text-lg font-semibold">{{ p.title }}</h2>
        <p class="text-sm text-ink-2 mt-2">{{ p.text }}</p>
        <div class="flex justify-end gap-2 mt-6">
          <button class="btn btn-ghost" (click)="c.answer(false)">Cancel</button>
          <button class="btn" [class]="p.danger ? 'btn-danger' : 'btn-primary'" (click)="c.answer(true)">{{ p.confirm }}</button>
        </div>
      </div>
    }`,
})
export class ConfirmHost {
  c = inject(Confirm);
}
