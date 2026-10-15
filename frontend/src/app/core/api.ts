import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export type Action = 'ALLOW' | 'WARN' | 'REDACT' | 'ROUTE_LOCAL' | 'BLOCK';

export interface Rule {
  id: string; name: string; types: string[]; categories: string[]; teams: string[]; sources: string[];
  minConfidence: number; action: Action; enabled: boolean;
}
export interface Policy {
  version: number; rules: Rule[]; allowedModels: string[]; maxOutputTokens: number; failMode: 'OPEN' | 'CLOSED';
  semanticCache: boolean; storeRedactedPrompts: boolean; outputScanning: boolean;
}
export interface CatalogueEntry { type: string; label: string; category: string; severity: string; spanBased: boolean; regulations: string[]; }
export interface Team { id: string; name: string; dailyBudgetMicros: number; activeKeys: number; }
export interface VirtualKey {
  id: string; name: string; teamId: string; teamName: string; kind: 'API' | 'EXTENSION'; prefix: string; last4: string;
  rpmLimit: number; dailyBudgetMicros: number; createdBy: string; createdAt: string; revokedAt: string | null;
}
export interface Provider { id: string; name: string; kind: 'ECHO' | 'OPENAI_COMPATIBLE' | 'ANTHROPIC'; baseUrl: string | null; hasApiKey: boolean; local: boolean; enabled: boolean; }
export interface Route { model: string; targets: { providerId: string; upstreamModel: string }[]; inputUsdPerMTok: number; outputUsdPerMTok: number; }
export interface ScanFinding {
  type: string; label: string; category: string; severity: string; detail: string; confidence: number;
  start: number; end: number; action: Action; rule: string | null; value?: string | null;
}
export interface ScanResult {
  requestId: string; action: Action; reasons: string[]; findings: ScanFinding[]; redactedText: string;
  detectorMicros: Record<string, number>; micros: number; policyVersion: number; degraded: boolean;
}
export interface Trace {
  status: number; requestId?: string; action?: Action; reasons?: string[];
  findings?: { type: string; label: string; severity: string; detail: string; confidence: number; action: Action; rule: string | null; start: number; end: number }[];
  sentToModel?: string; modelAnswer?: string; finalAnswer?: string; servedBy?: string; routedLocally?: boolean; cacheHit?: boolean;
  attempts?: { providerId: string; providerName: string; model: string; outcome: string; ms: number }[];
  outputFindings?: string[]; tokens?: { in: number; out: number }; costMicros?: number;
  timings?: { detectMicros: number; detectors: Record<string, number>; upstreamMs: number; totalMs: number };
  error?: { message: string; code: string; details?: { reasons?: string[]; detected?: string[] } };
}
export interface RequestRow {
  id: string; at: string; teamId: string; teamName: string; actor: string | null; source: string; kind: string; site: string | null;
  model: string | null; provider: string | null; action: Action; status: string; types: string[]; findingCount: number;
  topSeverity: string | null; tokensIn: number; tokensOut: number; costMicros: number; latencyMs: number; cacheHit: boolean; preview: string | null;
}
export interface Incident {
  id: number; requestId: string; firstAt: string; lastAt: string; occurrences: number; severity: 'HIGH' | 'CRITICAL'; title: string;
  teamId: string; teamName: string; actor: string | null; keyId: string; source: string; site: string | null; types: string[];
  action: Action; status: 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED' | 'FALSE_POSITIVE'; assignee: string | null; notes: string | null;
  explanation: string | null; explainedBy: string | null; distinctValues: number; request?: any;
}

/** Thin typed wrappers over the console API (through the gateway). */
@Injectable({ providedIn: 'root' })
export class Api {
  constructor(private http: HttpClient) {}

  // control plane
  teams() { return this.http.get<Team[]>('/api/teams'); }
  updateTeam(id: string, name: string, dailyBudgetMicros: number) { return this.http.put<Team>(`/api/teams/${id}`, { name, dailyBudgetMicros }); }
  createTeam(name: string, dailyBudgetMicros: number) { return this.http.post<Team>('/api/teams', { name, dailyBudgetMicros }); }
  keys() { return this.http.get<VirtualKey[]>('/api/keys'); }
  createKey(body: { name: string; teamId: string; kind: string; rpmLimit: number; dailyBudgetMicros: number }) {
    return this.http.post<{ key: VirtualKey; secret: string }>('/api/keys', body);
  }
  revokeKey(id: string) { return this.http.post<VirtualKey>(`/api/keys/${id}/revoke`, {}); }
  keyLimits(id: string, rpmLimit: number, dailyBudgetMicros: number) { return this.http.put<VirtualKey>(`/api/keys/${id}/limits`, { rpmLimit, dailyBudgetMicros }); }
  policy() { return this.http.get<Policy>('/api/policy'); }
  publishPolicy(p: Policy, note: string) { return this.http.put<Policy>('/api/policy', p, { params: new HttpParams().set('note', note) }); }
  policyVersions() { return this.http.get<{ version: number; note: string; createdBy: string; createdAt: string; active: boolean; rules: number }[]>('/api/policy/versions'); }
  policyVersion(v: number) { return this.http.get<Policy>(`/api/policy/versions/${v}`); }
  rollback(v: number) { return this.http.post<Policy>(`/api/policy/versions/${v}/rollback`, {}); }
  copilot(instruction: string) { return this.http.post<{ rules: Rule[]; notes: string[]; mode: string }>('/api/policy/copilot', { instruction }); }
  catalogue() { return this.http.get<CatalogueEntry[]>('/api/policy/catalogue'); }
  changes() { return this.http.get<{ id: number; actor: string; action: string; target: string; detail: string; at: string }[]>('/api/policy/changes'); }
  providers() { return this.http.get<Provider[]>('/api/providers'); }
  saveProvider(id: string, body: { name: string; kind: string; baseUrl: string | null; apiKey: string | null; local: boolean; enabled: boolean }) {
    return this.http.put<Provider>(`/api/providers/${id}`, body);
  }
  routes() { return this.http.get<Route[]>('/api/routes'); }
  saveRoute(r: Route) { return this.http.put<Route>(`/api/routes/${encodeURIComponent(r.model)}`, r); }
  deleteRoute(model: string) { return this.http.delete(`/api/routes/${encodeURIComponent(model)}`); }
  dictionary() { return this.http.get<{ term: string; label: string }[]>('/api/dictionary'); }
  saveDictionary(terms: { term: string; label: string }[]) { return this.http.put<{ term: string; label: string }[]>('/api/dictionary', terms); }
  topics() { return this.http.get<{ label: string; description: string; example: string }[]>('/api/topics'); }
  saveTopics(t: { label: string; description: string; example: string }[]) { return this.http.put<{ label: string; description: string; example: string }[]>('/api/topics', t); }
  simulator() { return this.http.get<any>('/api/simulator'); }
  startSimulator(perMinute: number) { return this.http.post<any>('/api/simulator/start', {}, { params: new HttpParams().set('perMinute', perMinute) }); }
  stopSimulator() { return this.http.post<any>('/api/simulator/stop', {}); }

  // proxy (console endpoints)
  scan(text: string, team: string, source = 'api', policy?: Policy) { return this.http.post<ScanResult>('/api/playground/scan', { text, team, source, policy }); }
  chat(body: { prompt: string; system?: string; team: string; model: string }) { return this.http.post<Trace>('/api/playground/chat', body); }
  proxyStatus() { return this.http.get<any>('/api/proxy/status'); }
  setFault(providerId: string, errorRate: number, extraLatencyMs: number) {
    return this.http.put<Record<string, { errorRate: number; extraLatencyMs: number }>>(`/api/proxy/faults/${providerId}`, { errorRate, extraLatencyMs });
  }

  // insight
  overview(range: string) { return this.http.get<any>('/api/insight/overview', { params: { range } }); }
  requests(filters: Record<string, string>): Observable<{ items: RequestRow[]; nextCursor: string }> {
    let p = new HttpParams();
    for (const [k, v] of Object.entries(filters)) if (v) p = p.set(k, v);
    return this.http.get<{ items: RequestRow[]; nextCursor: string }>('/api/insight/requests', { params: p });
  }
  request(id: string) { return this.http.get<any>(`/api/insight/requests/${id}`); }
  incidents(status: string) { return this.http.get<{ items: Incident[]; counts: any }>('/api/insight/incidents', { params: status ? { status } : {} }); }
  incident(id: number) { return this.http.get<Incident>(`/api/insight/incidents/${id}`); }
  updateIncident(id: number, body: { status?: string; assignee?: string; notes?: string }) { return this.http.patch<Incident>(`/api/insight/incidents/${id}`, body); }
  explain(id: number) { return this.http.post<Incident>(`/api/insight/incidents/${id}/explain`, {}); }
  shadowAi(range: string) { return this.http.get<any>('/api/insight/shadow-ai', { params: { range } }); }
  compliance(range: string) { return this.http.get<any>('/api/insight/compliance', { params: { range } }); }
}
