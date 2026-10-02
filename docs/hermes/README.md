# Portfolio Performance for Hermes

How to run Portfolio Performance (PP) on the homelab next to the Hermes agent, so that Hermes can
import bank statements (CSV), verify the import against the statement, check account balances and
spot divergences such as duplicates.

- [Architecture](#architecture)
- [1. Build the image](#1-build-the-image)
- [2. Deploy the stack (ansible-infra)](#2-deploy-the-stack-ansible-infra)
- [3. One-time setup in the application](#3-one-time-setup-in-the-application)
- [4. Give Hermes access](#4-give-hermes-access)
- [5. The bank statement workflow](#5-the-bank-statement-workflow)
- [API reference for the agent](#api-reference-for-the-agent)
- [Operations and troubleshooting](#operations-and-troubleshooting)
- [Security model](#security-model)

## Architecture

```
 minipc-dietpi-gva (DietPi, Docker)
 ┌──────────────────────────────────────────────────────────────────────────┐
 │  network "proxy"                                                         │
 │                                                                          │
 │  ┌──────────┐  http://portfolio-performance:5712   ┌───────────────────┐ │
 │  │  hermes  │ ───────────────────────────────────► │ portfolio-        │ │
 │  │          │  Host: localhost:5712                │ performance       │ │
 │  │          │  Authorization: Bearer <token>       │  socat :5712 ─┐   │ │
 │  └──────────┘                                      │               ▼   │ │
 │                                                    │  PP app 127.0.0.1 │ │
 │  ┌──────────┐  https://portfolio.<internal_domain> │  :5712 (REST API) │ │
 │  │ traefik  │ ───────────────────────────────────► │  noVNC :5800      │ │
 │  └──────────┘   (you, in a browser, LAN only)      └───────────────────┘ │
 └──────────────────────────────────────────────────────────────────────────┘
```

- The container runs the **real desktop application** on a virtual display. The REST API lives
  inside the application; there is no headless server. You see and use the application through
  your browser (noVNC on port 5800), which is how you enable the API, create the token, and watch
  what Hermes does.
- The REST API only listens on `127.0.0.1` inside the container. A `socat` forwarder in the
  container (see `docker/startapp.sh`) makes it reachable on the container's own address, port
  5712, for containers on the same Docker network.
- The API rejects requests whose `Host` header is not a loopback name (DNS-rebinding protection).
  Hermes therefore calls `http://portfolio-performance:5712` **and sends `Host: localhost:5712`**.
  Without that header every request answers `403 forbidden-host`.
- Changes made through the API are held in memory until saved — by Hermes via
  `POST /v1/files/{file}/save`, or by you in the application.

## 1. Build the image

The GitHub workflow `.github/workflows/docker.yml` builds the image on every push to `master`,
`feature/**` and tags, and on demand (*Actions → Docker Image → Run workflow*). It

1. builds the product with Maven (`mvn verify -DskipTests`),
2. stages the Linux builds (`docker/stage-product.sh` → `docker/dist/{amd64,arm64}`),
3. builds `docker/Dockerfile` for `linux/amd64` and `linux/arm64`,
4. pushes to `ghcr.io/nephilim84/portfolio-performance`.

Tags: the branch name (`master`, `feature-rest-api-csv-import`), the git tag, `sha-<short>`, and
`latest` on the default branch. Pull requests build but do not push. No secrets are needed — the
workflow pushes with its own `GITHUB_TOKEN`. The first run takes long (the Maven build resolves the
whole Eclipse target platform); later runs use the Maven and layer caches.

After the first push, the package is private by default. The homelab already logs in to GHCR
(`github_username` / `github_pat` in `roles/docker_stack/tasks/main.yml`), which is enough as long
as the PAT can read packages of `Nephilim84`.

Building locally instead:

```bash
mvn -f portfolio-app/pom.xml verify -DskipTests
docker/stage-product.sh
docker build -t portfolio-performance docker
```

## 2. Deploy the stack (ansible-infra)

In `/Users/pgaspar/Documents/gitlab/ansible_home/ansible-infra`:

**a) Template** `roles/docker_stack/templates/portfolio-performance.yaml`:

```yaml
services:
  portfolio-performance:
    image: ghcr.io/nephilim84/portfolio-performance:{{ portfolio_performance_version | default('feature-rest-api-csv-import') }}
    container_name: portfolio-performance
    restart: unless-stopped
    environment:
      TZ: Europe/Zurich
      USER_ID: "1000"
      GROUP_ID: "1000"
      # the application window is sized to the browser
      DISPLAY_WIDTH: "1600"
      DISPLAY_HEIGHT: "1000"
      # protect the browser view (noVNC); check the variable names against
      # the jlesage/baseimage-gui documentation of the image's base version
      WEB_AUTHENTICATION: "1"
      WEB_AUTHENTICATION_USERNAME: "{{ portfolio_web_username | default('admin') }}"
      WEB_AUTHENTICATION_PASSWORD: "{{ portfolio_web_password }}"
    volumes:
      # workspace: preferences, enabled files, authorized API clients
      - /home/{{ username }}/docker/portfolio-performance/config:/config
      # the portfolio files
      - /home/{{ username }}/docker/portfolio-performance/storage:/storage
    networks:
      - proxy
    labels:
      - traefik.enable=true
      # LAN only: the browser view gives full control over the application
      - traefik.http.routers.portfolio.rule=Host(`portfolio.{{ internal_domain }}`)
      - traefik.http.routers.portfolio.entrypoints=web,websecure
      - traefik.http.services.portfolio.loadbalancer.server.port=5800
      - homepage.group=Applications
      - homepage.name=Portfolio Performance
      - homepage.href=https://portfolio.{{ internal_domain }}
      - homepage.target=_blank
      - homepage.description=Portfolio Performance (REST API for Hermes)

networks:
  proxy:
    external: true
    name: proxy
```

Do **not** add a Traefik route for port 5712 and do not publish it with `ports:` — Hermes reaches
it over the `proxy` network, and nothing else should.

**b) Stack entry** in the stack list of `roles/docker_stack/tasks/main.yml` (next to `hermes`):

```yaml
     - name: portfolio-performance
       file: portfolio-performance.yaml
       dirs:
        - config
        - storage
```

**c) Secrets** in the vault of `group_vars/linux_servers/` (encrypt as for the other secrets):

```yaml
portfolio_web_password: "<password for the browser view>"
portfolio_api_token: "<filled in after step 3>"
```

**d) Hermes environment** — add to `services.hermes.environment` in `hermes.yaml`:

```yaml
      # Portfolio Performance REST API (docs/hermes in the portfolio repo)
      PORTFOLIO_API_URL: "http://portfolio-performance:5712"
      PORTFOLIO_API_HOST_HEADER: "localhost:5712"
      PORTFOLIO_API_TOKEN: "{{ portfolio_api_token }}"
```

Both containers are on `proxy`, so no further network change is needed.

**e) Deploy:** `ansible-playbook run.yml --tags stack`.

**f) The portfolio file:** copy your `.xml`/`.portfolio` file to
`/home/dietpi/docker/portfolio-performance/storage/`. If the file is password protected, you enter
the password in the application when opening it; the API then works on the decrypted in-memory
file and saves it encrypted again.

## 3. One-time setup in the application

Open `https://portfolio.<internal_domain>` in the browser:

1. **File → Open** the portfolio file from `/storage`. Keep it open — the API only serves files that
   are open in the application. PP reopens the files of the last session when it restarts.
2. **Preferences → REST API**:
   - tick **Enable REST API (localhost only)**, keep port **5712**;
   - enable your file and give it the alias **`main`** (Hermes addresses it as `/v1/files/main/…`).
3. Still on that page, **Add client** → name it `hermes` → copy the token shown. It is shown
   **once**; it is stored hashed. Put it in the vault as `portfolio_api_token` and redeploy
   (`--tags stack`) so the Hermes container picks it up.
4. Optional but recommended: for each bank, run one import manually through **File → Import → CSV**,
   map the columns once, and **save the configuration** under a short name (e.g. `UBS Giro`). Hermes
   can then import that bank's statements with `"configuration": "UBS Giro"` and no mapping.

Check from the Hermes container:

```bash
docker exec hermes curl -s -H "Host: localhost:5712" \
  -H "Authorization: Bearer $PORTFOLIO_API_TOKEN" http://portfolio-performance:5712/v1/files
```

Expected: `{"items":[{"id":"…","alias":"main","label":"…","path":"/storage/…","dirty":false}]}`.

The pairing flow (`POST /v1/auth/requests`, approved in the application) also works, but needs
someone at the browser view to click *Allow* — the manual token is simpler for a server.

## 4. Give Hermes access

Copy [`SKILL.md`](SKILL.md) into the Hermes skills directory — on the host
`/home/dietpi/docker/hermes/config/skills/portfolio-performance/SKILL.md` (the container sees it as
`/home/node/.hermes/skills/…`). It tells Hermes how to call the API, the reconciliation workflow,
and the rules (preview before commit, never accept a duplicate without evidence, save only after
verification). The complete contract is served by the API itself at `GET /v1/openapi.yaml` (no
token needed) — Hermes can read it when the skill is not enough.

## 5. The bank statement workflow

What Hermes does when you send it a statement (CSV attached in Telegram or the WebUI):

1. **Find the account.** `GET /v1/files/main/cash-accounts` — match by name/IBAN in the note, or ask
   you. Remember the uuid for that bank.
2. **Preview.** `POST /v1/files/main/csv-import/preview` with the CSV, the account in
   `cashAccounts`, and the statement's closing balance in `expectedBalances`. Nothing is changed.
3. **Read the report.**
   - `columns`: is every column mapped as meant (date, amount, text)? Is the amount format right
     (`0.000,00` for `1.234,56`)? A wrong mapping explains most surprises.
   - `parseErrors`: lines that could not be read at all.
   - `items[]` with `import: false`: `reason` says why. A `duplicate` warning names the existing
     transaction (`potentialDuplicateOf`), a `duplicate-in-file` warning the other line
     (`repeatsLines`), a `target` error the missing account.
   - `reconciliation[]`: `match`, or `mismatch` with the `difference`.
4. **Resolve.** Exclude wrong lines (`excludeLines`), accept genuine repetitions (`acceptWarnings`,
   e.g. two identical card payments on one day that the statement really lists twice), fix the
   mapping — and preview again until `reconciliation` says `match`.
5. **Diagnose a remaining mismatch.** The file was already off before this statement:
   `GET /v1/files/main/cash-accounts/{uuid}/statement?from=…&to=…` returns opening balance, every
   booking with signed `cashFlow` and running `balance`, and flags bookings that look like
   duplicates of each other. Compare with the bank's running balance to find the first divergence.
6. **Commit.** `POST /v1/files/main/csv-import` with the same body. Check `summary.import`,
   `reconciliation` and `consistencyIssues`.
7. **Save.** `POST /v1/files/main/save`.
8. **Report** to you: imported N rows, skipped M (with reasons), balance matches/does not match.

## API reference for the agent

All requests: base `http://portfolio-performance:5712`, headers `Host: localhost:5712` and
`Authorization: Bearer <token>`, JSON bodies with `Content-Type: application/json`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/v1/openapi.yaml` | The full contract (no token needed) |
| GET | `/v1/files` | Open, enabled files; `dirty` = unsaved changes |
| GET | `/v1/files/main/cash-accounts` | Cash accounts (`uuid`, `name`, `currencyCode`) |
| GET | `/v1/files/main/investment-accounts` | Investment accounts (securities accounts) |
| GET | `/v1/files/main/instruments` | Securities |
| GET | `/v1/files/main/transactions?from=&to=&account=&instrument=&type=` | Bookings |
| GET | `/v1/files/main/cash-accounts/{uuid}/statement?from=&to=` | Statement with running balance |
| GET | `/v1/files/main/holdings?date=` | Statement of assets |
| GET | `/v1/files/main/performance?openingDate=&closingDate=` | Returns (TTWROR, IRR) and breakdown |
| GET | `/v1/files/main/trades` | Matched buys/sells, realised gains |
| GET | `/v1/files/main/csv-import/types` | Import types, their field codes and formats |
| GET | `/v1/files/main/csv-import/configurations` | Saved CSV configurations (by `label`) |
| POST | `/v1/files/main/csv-import/preview` | Evaluate an import, change nothing |
| POST | `/v1/files/main/csv-import` | Import |
| POST | `/v1/files/main/save` | Save the file to disk |

### CSV import request

```json
{
  "type": "cash-account-transactions",
  "csv": "Date;Amount;Text\n02.01.2024;2.500,00;Salary\n05.01.2024;-61,20;Groceries\n",
  "decimalSeparator": ",",
  "dateFormat": "dd.MM.yyyy",
  "columns": [
    {"header": "Date",   "field": "date"},
    {"header": "Amount", "field": "value"},
    {"header": "Text",   "field": "note"}
  ],
  "cashAccounts": ["<uuid of the account>"],
  "expectedBalances": [{"date": "2024-01-31", "balance": 4923.81}],
  "excludeLines": [],
  "acceptWarnings": []
}
```

| Key | Meaning |
|---|---|
| `type` | `cash-account-transactions` (bank statement), `investment-account-transactions` (broker), `instruments`, `instrument-prices` (+ `instrument`), `holdings`. Optional with `configuration`. |
| `csv` / `csvBase64` + `encoding` | The file as text, or as raw bytes (e.g. `windows-1252` bank exports). |
| `configuration` | Label of a saved CSV configuration — replaces delimiter, encoding, skipped lines and mapping. |
| `delimiter`, `skipLines`, `firstLineHeader` | Detected / `0` / `true` by default. |
| `decimalSeparator`, `dateFormat` | Defaults for all amount/date columns; detected from the values when omitted. |
| `columns[]` | `{"index": 0 \| "header": "…", "field": "<code>" \| null, "format": …}`. Field codes: `date`, `time`, `value`, `type`, `note`, `currency`, `isin`, `wkn`, `ticker`, `name`, `shares`, `fees`, `taxes`, `gross`, `account`, `account2nd`, `portfolio`, … (see `/csv-import/types`). Headers equal to a field code map automatically. |
| type column `format` | `{"deposit": "Gutschrift\|Lohn", "removal": "Lastschrift"}` — without it, the app's own labels and the API values (`deposit`, `removal`, `interest`, `fees`, `dividends`, `buy`, `sell`, …) are accepted. Without any type column, the sign decides: positive = `deposit`, negative = `removal`. |
| `cashAccounts`, `investmentAccount` | Targets; default to the only active account in that currency / the only investment account. |
| `targetCashAccounts`, `targetInvestmentAccount` | Receiving side of transfers; never defaulted. |
| `excludeLines`, `acceptWarnings` | CSV line numbers (as reported in `items[].line`). |
| `expectedBalances[]` | `{"cashAccount"?, "date", "balance"}` from the statement. |
| `itemDetail` | `all` (default), `issues`, `none` — use `issues` for long statements. |
| `convertBuySellToDelivery`, `removeDividends`, `importNotes` | Import options of the wizard. |

Unknown keys are rejected (`422`), so a typo cannot silently become a default.

### Report (excerpt)

```json
{
  "dryRun": true,
  "columns": [{"index": 1, "header": "Amount", "sample": "2.500,00", "field": "value", "format": "0.000,00"}],
  "summary": {"lines": 4, "items": 4, "ok": 3, "warnings": 1, "errors": 0, "duplicates": 1, "import": 3},
  "items": [{"line": 2, "type": "deposit", "date": "2024-01-02", "amount": {"value": 2500, "currency": "EUR"},
             "status": "warning",
             "messages": [{"severity": "warning", "check": "duplicate", "message": "Potential duplicate"}],
             "potentialDuplicateOf": ["7c1e…"], "import": false, "reason": "warning-not-accepted"}],
  "parseErrors": [],
  "balances": [{"date": "2024-02-01", "balanceBefore": {"value": 2500, "currency": "EUR"},
                "importedCashFlow": {"value": 2423.81, "currency": "EUR"},
                "balanceAfter": {"value": 4923.81, "currency": "EUR"}}],
  "reconciliation": [{"date": "2024-02-01", "status": "match",
                      "difference": {"value": 0, "currency": "EUR"}}]
}
```

### Errors

`application/problem+json`; the `type` ends in a stable code:

| Status | Code | What to do |
|---|---|---|
| 400 | `invalid-request` | Unknown query parameter or invalid JSON — fix the request. |
| 401 | `unauthorized` | Token missing/revoked — ask the user for a new one. |
| 403 | `forbidden-host` | `Host` header missing — send `Host: localhost:5712`. |
| 404 | `not-found` | Unknown or not-enabled file/entity. |
| 409 | `file-not-open` | The file is not open in the application — ask the user to open it. |
| 409 | `save-failed` | Saving failed — the application shows the reason; tell the user. |
| 413 | `request-too-large` | CSV over 16 MiB — split it. |
| 422 | `validation` | See `errors[]`: each has `field`, `code`, `message` (e.g. which required field is unmapped, with the column list). |
| 423 | `user-interaction` | The user has a dialog open — wait `Retry-After` seconds and retry. |

## Operations and troubleshooting

| Symptom | Cause / fix |
|---|---|
| Connection refused on `portfolio-performance:5712` | REST API not enabled in Preferences, or the application is still starting (1–2 min after a container start). |
| `403 forbidden-host` | Hermes did not send `Host: localhost:5712`. |
| `404` for `/v1/files/main` | File not enabled, or alias not `main` (Preferences → REST API). |
| `409 file-not-open` | Open the file in the browser view. |
| Frequent `423` | Someone has a dialog open in the browser view. Close it. |
| Amounts off by a factor of 1000 or so | Wrong decimal separator — check `columns[].format`, pass `decimalSeparator`. |
| Everything is a duplicate | The statement overlaps an earlier import — correct; exclude those lines. |
| Changes lost after a restart | The file was not saved — `POST …/save` after each verified import. |

- **Logs:** `docker logs portfolio-performance`; the application log is in the workspace under
  `/home/dietpi/docker/portfolio-performance/config/workspace/.metadata/.log`. Every API import and
  save is logged there ("REST API imported 12 item(s) of type …").
- **Backups:** the application writes a backup next to the file on every save (if enabled in its
  preferences). Include `/home/dietpi/docker/portfolio-performance/` in the Duplicacy job.
- **Updates:** push to the branch → the workflow publishes a new image → redeploy the stack. The
  workspace (enabled files, tokens, CSV configurations) survives in `/config`.
- **Revoking Hermes:** Preferences → REST API → select `hermes` → Revoke. Effective immediately.

## Security model

- The API has no network listener outside the container except the `socat` forwarder, which is only
  reachable on the internal `proxy` network. Do not route port 5712 through Traefik or Cloudflare.
- Every request needs the token; the token is stored hashed in `/config`.
- The API only sees files you enabled individually.
- Browser requests are rejected (`Origin` header), as are other host names (`Host` header) — that is
  why Hermes sends `Host: localhost:5712`.
- The browser view on 5800 is full control over the application: keep it LAN-only behind Traefik
  and protected by the web password.
- Hermes can create transactions and save the file. Keep the application's backup-on-save enabled
  and the storage directory in the backups.
