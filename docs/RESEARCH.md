# Why Veil: the problem research

This is the research behind the choice of problem. It was done on 2026-10-15, before any code was written.

## How the problem was chosen

The brief asked for a hard, important business problem that a technical product can solve, that people can
adopt as a plugin or service for free, and that genuinely needs AI. These candidates were considered:

| Candidate | Evidence it matters | Why it was or wasn't picked |
|---|---|---|
| **Leaking company data into AI tools ("shadow AI")** | Gartner: by 2027, more than 40% of AI-related data breaches will come from improper cross-border use of GenAI. Mimecast 2026: 80% of organisations worry about GenAI data leakage, 60% have no strategy for it. Verizon DBIR 2026: 858,440 DLP events involving uploads to GenAI tools, with source code the top data type. Netskope 2026: 47% of enterprise GenAI users use personal accounts. DTEX 2026: shadow AI is the top driver of negligent insider incidents. | **Picked.** Every company that uses AI has this problem today, the fix is technical, it ships naturally as a browser extension *and* an API proxy, it can be fully free and self-hosted, and it uses AI in a way that isn't decorative (semantic classification, injection detection, semantic caching, an AI policy copilot). It is also a different domain from the earlier three portfolio projects, which are all fintech. |
| India MSME delayed payments | ₹55,244 crore filed on MSME Samadhaan, ~₹20,979 crore still pending (Aug 2026); 73-day average wait (Recordent 2026). A new MSME Development (Amendment) Bill was passed in Aug 2026. | Real and large, but a fourth fintech project, and the hard part is legal and behavioural, not technical. Crowded with AI collections vendors (Cleavr, Seenn, Daylit, Moonflow). |
| YC Fall 2026 RFS: "Self-Maintaining APIs" | Agents that notice upstream API changes and open fix PRs. | Very interesting, but hard to demo end to end without real third-party APIs, and less of a business-problem story. |
| YC Fall 2026 RFS: "AI-Native Compliance Infrastructure" | Fragmented compliance work: monitoring, audits, reporting. | Veil sits inside this request: it produces the audit trail and compliance evidence for AI use. |
| YC Fall 2026 RFS: "Proving You're Human" | Deepfake fraud on calls and transactions. | Needs biometric or telecom infrastructure that can't be built credibly for free. |

## The problem, in plain words

Employees paste customer records, contracts, salary sheets, API keys and source code into ChatGPT, Claude
and Gemini every day, usually to get their job done faster, often from personal accounts that the company
can't see. Apps built on LLMs do the same thing automatically: a support bot sends the whole ticket,
customer's phone number and all, to a model hosted in another country.

Banning AI doesn't work (people use their phones). Companies need a way to say **yes** to AI while making sure
the sensitive parts never leave.

### Why it's urgent now

- **Regulation has teeth.** India's DPDP Rules 2025 were notified in November 2025. Penalties start in
  November 2026, the bulk of obligations apply from May 2027, and failing to keep reasonable security
  safeguards can cost up to ₹250 crore per instance. GDPR (EU) and HIPAA (US healthcare) already apply.
- **Cross-border transfer.** Most frontier models run outside India and the EU. Sending personal data to them
  is a cross-border transfer, which is exactly what Gartner's prediction is about.
- **Spend is exploding without control.** Enterprise LLM API spend went from $3.5B (late 2024) to $8.4B
  (mid-2025) according to Menlo Ventures. Teams hand out raw provider keys with no per-team budgets.

### Why it's hard

1. **Detection must be accurate.** Too many false positives and employees route around the tool; too few and
   data leaks. Regexes alone flag every 12-digit number as Aadhaar. You need checksums (Verhoeff for Aadhaar,
   Luhn for cards, mod-97 for IBAN, GSTIN's check character), context, and semantic understanding for things
   with no pattern at all, like "unreleased quarterly results" or a confidential project codename.
2. **It sits on the hot path.** Every AI request goes through it, so it has to add only milliseconds, scale
   horizontally and keep working when its own database or Kafka is down.
3. **Redaction must not break the answer.** If you replace "Priya Sharma" with `[PERSON_1]`, the model's answer
   says `[PERSON_1]` too. A good firewall swaps the real value back into the response, including in streamed
   responses where a placeholder can be split across two network chunks.
4. **It must fit how people already work.** Employees use chat websites; developers use OpenAI SDKs. The
   product must work as a browser extension *and* as a drop-in API endpoint with no code changes.
5. **Security teams need evidence,** not just blocking: who sent what kind of data where, what was redacted,
   trends per team, and a report for the auditor. And the evidence itself must not store the sensitive data.

## Sources

- Gartner press release, 17 Feb 2025: "Gartner Predicts 40% of AI Data Breaches Will Arise from Cross-Border GenAI Misuse by 2027" — https://gartner.com/en/newsroom/press-releases/2025-02-17-gartner-predicts-forty-percent-of-ai-data-breaches-will-arise-from-cross-border-genai-misuse-by-2027
- Shadow AI statistics 2026 (Mimecast, Verizon DBIR, DTEX, Netskope figures) — https://www.cloudnuro.ai/blog/shadow-ai-statistics-2026-enterprise-trends-and-governance and https://www.kiteworks.com/cybersecurity-risk-management/shadow-ai-data-leakage-governance/
- DPDP Rules 2025 timeline and penalties — https://cyberpeace.org/resources/blogs/the-dpdp-compliance-clock-is-running-is-your-organisation-actually-ready
- Menlo Ventures enterprise LLM spend — https://www.bigdatawire.com/this-just-in/menlo-ventures-report-enterprise-llm-spend-reaches-8-4b-as-anthropic-overtakes-openai
- Y Combinator Requests for Startups (Fall 2026) — https://www.ycombinator.com/rfs
- MSME delayed payments (Crisil, Aug 2026) — https://intelligence.crisil.com/en/homepage/newsroom/press-releases/2026/08/executed-well-msme-bill-can-be-an-ibc-moment-for-delayed-payments.html
- Recordent SME receivables report 2026 — https://knnindia.co.in/news/newsdetails/msme/delayed-payments-stretch-msme-cash-cycles-strain-working-capital-report

Several of the shadow-AI figures come from vendor blogs quoting primary reports; they are indicative, not audited.
