# Finova web app

React + TypeScript (Vite). In docker-compose (`--profile apps`) nginx serves the build at http://localhost:5173 and
forwards `/api/*` to the API Gateway, so the browser talks to one origin and needs no CORS.

```
npm ci
npm run dev        # http://localhost:5173, /api → Gateway on http://localhost:8080 (API_TARGET to change it)
npm test           # unit tests
npm run coverage   # tests with line coverage >= 90%
npm run build      # type check + production build in dist/
```

## Screens

- `/ingresar`: sign-in with the robot that watches the email and covers its eyes for the password.
- `/billetera`: balance, send by recipient email (`POST /users/lookup`), PIN (`POST /auth/financial-session`),
  `POST /transfers` and live tracking (`GET /transfers/{id}`). A send keeps its `Idempotency-Key` and financial
  session until its data changes, so retrying after a lost answer never charges twice. Sends made from the browser
  are listed from `localStorage`; the backend has no "my transfers" endpoint.
- `/actividad` (auditors only): the audit trail in plain words, a whole transfer at a time, and the hash-chain check.
  The raw fields stay folded under "Datos técnicos para soporte".

## End-to-end (`e2e/`)

Playwright against the whole stack. The flows move real money between the demo accounts.

```
docker compose --profile apps up -d --build    # from the repository root
npx playwright install chromium                # once
npm run e2e                                    # E2E_BASE_URL to point elsewhere (default http://localhost:5173)
```

Not in CI: it needs every service built and running.

## nginx (`nginx/`)

- `/api/*` → `api-gateway:8080` without the `/api` prefix. nginx sets `X-Real-IP` to the browser's address; the
  Gateway trusts it only from this container (`TRUSTED_PROXIES: frontend`), so each browser has its own login limit.
- `/api/actuator/*` answers 404: the Gateway's metrics stay inside the network.
- Security headers on every response, with a CSP that allows only this origin. Fonts are bundled (`@fontsource`) and
  never inlined (`assetsInlineLimit: 0`), so the CSP needs no `data:` or third-party host.
- Any unknown path gets `index.html` (single-page app); `/assets/*` is cached for a year (hashed names).
