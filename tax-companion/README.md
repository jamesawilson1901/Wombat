# AU Tax Return Companion (2025-26)

A single-file React component: a phone-side explainer and "can I claim X?"
lookup tool to use while completing your own 2025-26 Australian tax return in
myTax on a laptop. Personal use only. It explains and looks up — it never
calculates your return, stores figures, or asks for a TFN. Nothing is
persisted (React state only; no localStorage).

## The file

`AuTaxCompanion.jsx` — default-exports the `AuTaxCompanion` component.
No dependencies beyond React; all styling is inline.

## Ways to run it

1. **Paste into claude.ai** as a React artifact — the "Ask a question" AI box
   works there without any API key (claude.ai proxies the Anthropic API call).
2. **Any React app** (e.g. `npm create vite@latest -- --template react`):
   drop the file in `src/`, render `<AuTaxCompanion />`. The AI box then needs
   the request routed through your own backend with an Anthropic API key —
   never put an API key in browser code.

## Updating the deduction index

Deductions are a plain JSON-style array (`DEDUCTIONS`) near the top of the
file. Copy any entry and edit it. Fields:
`id, name, syn (search synonyms), claimable (yes/no/depends), condition,
label (myTax label), records, year, url (ATO source), partPrivate`.

## Verification status

All rates and thresholds were verified for 2025-26 in August 2026 (sources
linked in-app next to each figure). Rates change every 1 July — re-verify
before reusing for a later year.
