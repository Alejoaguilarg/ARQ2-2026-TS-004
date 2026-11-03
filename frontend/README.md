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

## nginx (`nginx/`)

- `/api/*` → `api-gateway:8080` without the `/api` prefix. nginx sets `X-Real-IP` to the browser's address; the
  Gateway trusts it only from this container (`TRUSTED_PROXIES: frontend`), so each browser has its own login limit.
- `/api/actuator/*` answers 404: the Gateway's metrics stay inside the network.
- Security headers on every response, with a CSP that allows only this origin. Fonts are bundled (`@fontsource`) and
  never inlined (`assetsInlineLimit: 0`), so the CSP needs no `data:` or third-party host.
- Any unknown path gets `index.html` (single-page app); `/assets/*` is cached for a year (hashed names).
