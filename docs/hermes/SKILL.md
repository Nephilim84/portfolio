---
name: portfolio-performance
description: Read the user's Portfolio Performance file (accounts, transactions, holdings, performance) and import bank or broker statements (CSV) with verification against the statement's balance and duplicate detection. Use when the user sends a bank/broker statement, asks about account balances, transactions, holdings or portfolio performance.
---

# Portfolio Performance

The user's investment and bank records live in a Portfolio Performance file served by a local
REST API.

## Calling the API

```bash
pp() {  # pp <METHOD> <path> [json-body]
  curl -sS -X "$1" "$PORTFOLIO_API_URL$2" \
    -H "Host: $PORTFOLIO_API_HOST_HEADER" \
    -H "Authorization: Bearer $PORTFOLIO_API_TOKEN" \
    -H "Content-Type: application/json" ${3:+--data-binary "$3"}
}
pp GET /v1/files
```

- **Always send the `Host` header** — without it every call is `403 forbidden-host`.
- The file is addressed as `main`: `/v1/files/main/...`.
- For large bodies (a whole CSV), build the JSON with `jq` and pass it with `--data-binary @file`.
- The full contract: `GET /v1/openapi.yaml` (read it for anything not covered here).
- Money is `{"value": 12.5, "currency": "EUR"}`. `amount`/`fees`/`taxes` are magnitudes — the
  direction is the transaction `type`. Only `cashFlow`, `importedCashFlow` and `difference` are
  signed.

## Reading

| Question | Call |
|---|---|
| Which accounts? | `GET /v1/files/main/cash-accounts`, `.../investment-accounts` |
| Balance of an account on a day | `GET /v1/files/main/cash-accounts/{uuid}/statement?from=D&to=D` → `closingBalance` |
| Bookings of a period | `GET /v1/files/main/transactions?from=YYYY-MM-DD&to=YYYY-MM-DD&account={uuid}` |
| What do I hold? | `GET /v1/files/main/holdings` |
| How did I perform? | `GET /v1/files/main/performance?openingDate=YYYY-MM-DD` |
| Realised gains | `GET /v1/files/main/trades?status=closed` |

Query parameters are strict: an unknown one is `400` listing the accepted ones.

## Importing a statement — always this sequence

1. **Account.** `GET /v1/files/main/cash-accounts`. Pick the account the statement belongs to; if
   unsure, ask the user. Check `GET .../csv-import/configurations` — if a saved configuration
   exists for this bank, use `"configuration": "<label>"` instead of a mapping.
2. **Preview** — `POST /v1/files/main/csv-import/preview`:
   ```json
   {"type": "cash-account-transactions", "csv": "<the file as text>",
    "cashAccounts": ["<uuid>"],
    "expectedBalances": [{"date": "<statement closing date>", "balance": <closing balance>}],
    "itemDetail": "issues"}
   ```
   Add `columns` (`{"header": "...", "field": "date|value|note|type|..."}`), `decimalSeparator`
   (`","` for `1.234,56`), `dateFormat` (`dd.MM.yyyy`), `skipLines` as the file needs. For a
   non-UTF-8 file send `csvBase64` + `encoding` (e.g. `windows-1252`).
3. **Check the report:**
   - `columns` — every needed column mapped, `format` and `sample` plausible?
   - `parseErrors` — must be empty or explained.
   - `items` with `import: false` — read `reason` and `messages[].check`:
     - `duplicate`: the row matches an existing transaction (`potentialDuplicateOf`). Normally the
       statement overlaps a previous import → leave it out (it already is).
     - `duplicate-in-file`: the row repeats other lines (`repeatsLines`). Accept only if the
       statement genuinely lists both bookings.
     - `target`: no account to book on — the message lists candidates; add one to `cashAccounts`.
     - `error`: fix the mapping, or exclude the line.
   - `reconciliation[].status` — the goal is `match`.
4. **Iterate** with `excludeLines` / `acceptWarnings` (CSV line numbers) and mapping fixes until
   `reconciliation` is `match`. Never accept a warning only to make the balance match — each
   accepted row needs a reason from the statement itself.
5. **Mismatch that will not go away** → the file deviated before this statement. Get
   `GET /v1/files/main/cash-accounts/{uuid}/statement?from=<statement start>&to=<statement end>`,
   compare its running `balance` with the statement's, find the first booking where they differ,
   and look at `potentialDuplicateOf` in the statement. **Report it to the user; do not delete or
   correct existing transactions** — the API cannot, and the user decides.
6. **Commit** — `POST /v1/files/main/csv-import` with exactly the body of the last preview. Check
   `summary.import`, `reconciliation` and `consistencyIssues`.
7. **Save** — `POST /v1/files/main/save` — only after the commit report is as expected.
8. **Tell the user:** rows imported, rows left out and why, final balance vs. the statement,
   anything unusual (new instruments created, consistency issues).

## Rules

- Never commit without a preview of the same body first.
- Never save when `reconciliation` is `mismatch` unless the user agreed after you explained it.
- `423 user-interaction`: the user has a dialog open — wait `Retry-After` seconds, retry (max 5).
- `409 file-not-open`: ask the user to open the file in Portfolio Performance.
- `401`: the token is invalid — tell the user; do not try to pair on your own.
- Import types other than bank statements: `investment-account-transactions` (broker exports,
  needs `investmentAccount` if several), `instrument-prices` (needs `instrument`). The fields each
  type takes: `GET /v1/files/main/csv-import/types`.
