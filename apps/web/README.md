# ParcelFlow web app

React and TypeScript customer experience for the synthetic account, transfer, and merchant payment APIs.

## Run locally

1. Start PostgreSQL and Keycloak from the repository root using the root README instructions.
2. Start the API from `services/api` and use the local `PAYMENT_WEBHOOK_SECRET` setting documented there.
3. In the repo root, install web dependencies and start this app:

   ```powershell
   npm --prefix apps/web install
   npm run dev:web
   ```

4. Open `http://localhost:3000` and sign in with `demo-customer-1` / `demo-customer-pass`.

The public OIDC client uses Authorization Code with PKCE and returns to the current app path, which supports static-hosted project pages. Browser tokens are kept in session storage for the current tab session. The app sends the access token only to the configured API origin. Do not put secrets in `VITE_*` variables; Vite embeds those values in the public browser bundle.

Copy `.env.example` to `.env.local` only when the API, Keycloak, or static-host base path differs. For GitHub Pages project hosting, set `VITE_BASE_PATH=/parcelflow-quality-engineering/` during the build. The current local API allows browser requests from `http://localhost:3000`; set `CORS_ALLOWED_ORIGINS` to the exact deployed app origins for a hosted environment.

## Build and browser smoke suite

```powershell
npm --prefix apps/web ci
npm run build:web
npm --prefix apps/web exec -- playwright install chromium
npm run test:e2e:web
```

The initial Playwright suite checks the public welcome/sign-in screen at desktop and mobile viewport sizes. The CI workflow retains traces, screenshots, and videos when a test fails. Authenticated account and payment journeys are the next coverage expansion.

## Journeys

- Review account availability, ledger balances, pending debits, and recent transactions.
- Transfer synthetic funds between accounts in the same currency.
- Exercise merchant checkout with approved, declined, and pending simulator outcomes. Approved authorizations are captured by the checkout flow; pending holds can be released from the result notice.

All values and payment tokens are synthetic. No real funds, card numbers, or provider credentials are handled.
