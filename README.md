# MF Tracker

Local Spring Boot + MySQL app for tracking mutual fund holdings, values them
with live NAVs from AMFI, and lets you group by fund house or category and add new purchases.

## Run it

Requirements: Java 17+, Maven 3.9+, MySQL 8 (or Docker).

```bash
# 1. MySQL (pick one)
docker compose up -d                       # starts isolated MySQL on :3307 (root / root, db "mftracker_copy")
#    ...or use your own MySQL and set DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD

# 2. App
mvn spring-boot:run
# open http://localhost:8080
```

On first start the tables are created automatically. This copy contains no personal holdings or seeded investment data. Its MySQL container, host port (3307), database (mftracker_copy), and persistent volume are separate from the original project. Add purchases or upload a CAMS statement after logging in.

## How live NAV works

* NAVs come from AMFI's official `NAVAll.txt` (fallback: `api.mfapi.in`). AMFI publishes one NAV per scheme per
  business day (usually by late evening), so "real-time" means the latest published NAV.
* The app refreshes on startup, every 30 minutes, and when you click **Refresh NAV**.
* Each fund needs an **AMFI scheme code**. On refresh the app tries to match your fund names automatically
  (growth option, correct plan, live NAV within 15% of the NAV in your sheet). Anything it can't match with
  confidence stays "not linked" and keeps the spreadsheet NAV (marked *old*) until you link it under
  **Funds & scheme codes** (type part of the name, click the right scheme).
* Auto-matched funds are flagged "please verify" on that screen. Do glance through them once.

## Using it

* **Portfolio**: totals at the top; group by fund house, category, or not at all. Click a fund to see its purchases
  (edit/delete there).
* **Add purchase**: pick the fund, date, amount, and NAV or units (the other is calculated). "Look up NAV for
  this date" pulls the historical NAV. Your contract note is the authority; AMFI's NAV for the date may differ
  from the one applied to your order (cut-off times).
* **Funds & scheme codes**: add funds, fix fund house/category/plan, link scheme codes.

## Daily pick tab

Each day (first time you open the tab) the app suggests where Rs 1,000 could go:

1. It pulls the last ~2 months of NAVs (api.mfapi.in) for every fund linked to a scheme code.
2. It computes the 7-day NAV change for each, plus 1-month change and up-days.
3. It drops funds with a stale NAV or that are already more than 10% of your portfolio
   (`mftracker.advisor.max-weight`), and picks the highest 7-day gain, only if it is actually positive.
4. The pick is saved for the day, and the tab keeps a "Past picks" list showing how each one did since.
   "I invested Rs 1,000" opens the Add purchase form pre-filled.

Claude commentary is optional. Set `ANTHROPIC_API_KEY` (Windows: `set ANTHROPIC_API_KEY=sk-ant-...` before
`mvn spring-boot:run`) and Claude writes a short explanation from the numbers. The ranking itself is plain arithmetic;
Claude is told to use only the supplied data. Model defaults to `claude-haiku-4-5-20251001` (`ANTHROPIC_MODEL` to change).
Without a key the commentary is a rule-based sentence.

One week of NAV movement is a weak signal and this is not investment advice.

## Data issues found in Holdings.xlsx

See `src/main/resources/seed/holdings-warnings.txt`. The import keeps `amount` and `units` as the source of truth
and recomputes purchase NAV from them. The biggest ones to fix in the UI:

* **HDFC Pharma Direct** has exactly the same purchases as **Motilal Oswal Defence Fund - Direct** (copy-paste?).
* **Quant Value** (Rs 1,55,000 for 633.521 units) and **LIC Value** have wrong purchase data in the sheet.
* Possible duplicate rows: Motilal Oswal Defence Direct (5-Jul), SBI Banking & Financial (9-Mar).
* Several purchase NAVs in the sheet don't match amount/units (e.g. Invesco Large & Mid Cap rows reuse another fund's NAV).
* "Parakh Parek" is imported with fund house "PPFAS (check)"; rename if that's not Parag Parikh.

Suspicious rows show an orange remark next to the date.

## API (all JSON)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/portfolio?groupBy=fundHouse\|category\|none` | totals, groups, funds, purchases, gain/loss |
| POST | `/api/nav/refresh` | refresh NAVs now |
| GET/POST | `/api/funds` | list / create fund |
| PUT/DELETE | `/api/funds/{id}` | edit (incl. scheme code) / delete |
| GET | `/api/funds/{id}/suggestions` | likely AMFI schemes for a fund |
| GET | `/api/schemes/search?q=` | search the AMFI list |
| GET | `/api/funds/{id}/nav?date=YYYY-MM-DD` | historical NAV |
| GET | `/api/advisor/today` | today's pick (created on first call each day) |
| POST | `/api/advisor/today/refresh` | recompute today's pick |
| GET | `/api/advisor/history` | past picks and performance since |
| POST | `/api/purchases` | `{fundId,date,amount,nav?,units?,broker?}` |
| PUT/DELETE | `/api/purchases/{id}` | edit / delete purchase |

## Regenerating the seed from a new spreadsheet

`python3 tools/convert_holdings.py Holdings.xlsx src/main/resources/seed/holdings.json`
