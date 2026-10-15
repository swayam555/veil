import { Injectable, inject, signal } from '@angular/core';
import { AuthService } from './auth.service';

export interface LiveEvent {
  id: string; at: string; team: string; source: string; kind: string; site: string; model: string; action: string;
  status: string; types: string[]; latencyMs: number; cacheHit: boolean; costMicros: number;
}

/** The live request feed over Server-Sent Events, shared by every screen that shows it. Reconnects on its own. */
@Injectable({ providedIn: 'root' })
export class LiveService {
  private auth = inject(AuthService);
  readonly events = signal<LiveEvent[]>([]);
  readonly connected = signal(false);
  private source: EventSource | null = null;
  private users = 0;

  connect() {
    this.users++;
    if (this.source) return;
    const token = this.auth.token();
    if (!token) return;
    this.source = new EventSource(`/api/insight/live?access_token=${encodeURIComponent(token)}`);
    this.source.addEventListener('hello', () => this.connected.set(true));
    this.source.addEventListener('request', (e: MessageEvent) => {
      const ev = JSON.parse(e.data) as LiveEvent;
      this.events.update(l => [ev, ...l].slice(0, 60));
    });
    this.source.onerror = () => this.connected.set(false);
  }

  disconnect() {
    this.users = Math.max(0, this.users - 1);
    if (this.users === 0 && this.source) {
      this.source.close();
      this.source = null;
      this.connected.set(false);
    }
  }
}
