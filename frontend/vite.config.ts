// Vitest's own defineConfig, so the `test` block below is typed.
import { defineConfig } from 'vitest/config';
import { loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * The dev server runs on 5173, which is the origin the backend allows by default (app.cors.allowed-origins).
 *
 * Calls go straight to the API on 8080 rather than through a proxy, so the browser really does make a cross-origin
 * request in development — the same shape of request as in production. That way a CORS mistake shows up here
 * instead of after a deploy. Point VITE_API_URL somewhere else to run against another backend.
 *
 * `npm run share` is the exception: it lets friends on other devices try the app. The server listens on the
 * network, and the API is reached through this server at /api (forwarded to API_TARGET, 8080 by default), so a
 * phone on the Wi-Fi or a tunnel only needs this one port. The forwarded call carries the usual dev origin, so the
 * backend's CORS setting does not have to know about every Wi-Fi address or tunnel hostname.
 */
export default defineConfig(({ mode }) => {
  const sharing = mode === 'share';
  const env = loadEnv(mode, '.', '');
  return {
    plugins: [react()],
    server: {
      port: 5173,
      strictPort: true,
      ...(sharing && {
        host: true,
        // A tunnel arrives with its own hostname, which Vite would otherwise refuse.
        allowedHosts: true,
        proxy: {
          '/api': {
            target: env.API_TARGET || 'http://localhost:8080',
            // Seen by the backend as coming from the allowed dev origin, whatever host the visitor used.
            headers: { origin: 'http://localhost:5173' },
          },
        },
      }),
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      css: false,
    },
  };
});
