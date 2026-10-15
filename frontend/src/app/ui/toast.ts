import { Component, Injectable, inject, signal } from '@angular/core';

export interface ToastMsg { id: number; text: string; kind: 'ok' | 'error' | 'info'; }

@Injectable({ providedIn: 'root' })
export class Toasts {
  readonly items = signal<ToastMsg[]>([]);
  private next = 1;

  show(text: string, kind: ToastMsg['kind'] = 'ok', ms = 3500) {
    const id = this.next++;
    this.items.update(l => [...l, { id, text, kind }]);
    setTimeout(() => this.dismiss(id), ms);
  }

  error(e: any, fallback = 'Something went wrong') {
    const msg = e?.error?.detail || e?.error?.error?.message || (typeof e?.error?.error === 'string' ? e.error.error : null) || e?.message || fallback;
    this.show(e?.status === 403 && !e?.error?.detail ? 'Your role can view but not change this.' : msg, 'error', 6000);
  }

  dismiss(id: number) {
    this.items.update(l => l.filter(t => t.id !== id));
  }
}

@Component({
  selector: 'veil-toasts',
  template: `
    <div class="fixed bottom-4 right-4 z-50 flex flex-col gap-2 w-[min(92vw,380px)]" aria-live="polite">
      @for (t of toasts.items(); track t.id) {
        <div class="fade-up rounded-xl px-4 py-3 text-sm shadow-lg border flex items-start gap-3"
             [class]="t.kind === 'error' ? 'bg-white border-block/30 text-ink' : t.kind === 'info' ? 'bg-white border-line text-ink' : 'bg-ink border-ink text-white'">
          <span class="mt-0.5 size-2 rounded-full shrink-0" [class]="t.kind === 'error' ? 'bg-block' : t.kind === 'info' ? 'bg-brand' : 'bg-protect'"></span>
          <span class="flex-1">{{ t.text }}</span>
          <button class="opacity-60 hover:opacity-100" (click)="toasts.dismiss(t.id)" aria-label="Dismiss">✕</button>
        </div>
      }
    </div>`,
})
export class ToastHost {
  toasts = inject(Toasts);
}
