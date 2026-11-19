# Relay frontend

The web console for Relay, built with Vite, React 18, TypeScript and react-router, styled with plain CSS variables.

## Scripts

| Command | What it does |
|---|---|
| `npm run dev` | Starts the Vite dev server. `/api` is proxied to `http://localhost:8080`. |
| `npm run build` | Runs `tsc -b && vite build` and writes the output to `dist/`. |
| `npm run lint` | Runs ESLint with the flat config in `eslint.config.js`. |
| `npm test` | Runs `vitest run` with jsdom and Testing Library. `fetch` is mocked. |

Start the backend on port 8080 first, then run `npm install && npm run dev`.

## Deploying with Spring Boot

Copy `dist/` into the jar's static resources, for example `backend/src/main/resources/static/`. The app uses client-side routing (`BrowserRouter`) and relative `/api` URLs, so it must be served from the same origin as the API. The backend should forward unknown non-`/api` paths to `index.html` so that deep links work.

## Structure

```
src/
  api/client.ts      Typed fetch wrapper: envelope unwrapping, CSRF, ApiError mapping
  api/endpoints.ts   Typed functions for every console endpoint
  api/types.ts       DTO types (camelCase, matching the backend records)
  auth.tsx           Session user context (GET /api/auth/me)
  hooks.ts           useAsync / useAction helpers for loading, error and busy state
  components/        UI primitives, confirm dialog, schema FieldEditor
  pages/             Landing, Login, Register, Dashboard
  pages/project/     Project layout (sidebar and environment switcher) and all project screens
  test/              Test setup and fetch mock helpers
```

## Auth and CSRF

- Every request sends `credentials: 'same-origin'`, so the session cookie is used.
- Before any non-GET request, the client checks for the `XSRF-TOKEN` cookie. If it is missing, the client calls `GET /api/auth/csrf`, then sends the token in the `X-CSRF-TOKEN` header.
- After login and logout, the cached token is cleared and fetched again, because the server may rotate it.
- Error responses are converted to `ApiError { status, code, message, details }`, and the UI shows them as `CODE: message`.

## Environments

The active environment (`sandbox`, `development` or `production`) is stored in the `?env=` query parameter, so links can be shared. The sidebar switcher changes it. It affects the records explorer and custom API test runs.
