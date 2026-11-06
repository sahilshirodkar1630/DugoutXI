/**
 * The one place that talks to the backend.
 *
 * Every call goes through `request`, so the token, the JSON headers and the error shape are handled once. The
 * backend reports problems as RFC 7807 documents with a `code` and a `detail`; those are turned into an ApiError
 * carrying the status and the message, which is what the screens show the user. A 401 clears the stored token and
 * tells the app to send the user back to the sign-in page, because the token is either gone or no longer valid.
 */

const BASE_URL = (import.meta.env.VITE_API_URL ?? 'http://localhost:8080').replace(/\/$/, '');
const TOKEN_KEY = 'ipl-league.token';

export class ApiError extends Error {
  // Declared as fields rather than constructor parameter properties, which this project's TypeScript config
  // (erasableSyntaxOnly) does not allow.
  readonly status: number;
  readonly code: string;
  readonly fieldErrors?: Record<string, string>;

  constructor(status: number, code: string, message: string, fieldErrors?: Record<string, string>) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
  }

  /** True when the problem is something the user can fix by typing something different. */
  get isUserFixable() {
    return this.status === 400 || this.status === 409 || this.status === 422;
  }
}

/** Reading and writing the token is wrapped: private browsing can make storage throw. */
export const tokenStore = {
  get(): string | null {
    try {
      return localStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  },
  set(token: string) {
    try {
      localStorage.setItem(TOKEN_KEY, token);
    } catch {
      // A session that cannot be remembered still works until the tab is closed.
    }
  },
  clear() {
    try {
      localStorage.removeItem(TOKEN_KEY);
    } catch {
      // nothing to do
    }
  },
};

type Listener = () => void;
const unauthorizedListeners = new Set<Listener>();

/** The auth provider subscribes so that an expired token signs the user out wherever they happen to be. */
export function onUnauthorized(listener: Listener): () => void {
  unauthorizedListeners.add(listener);
  return () => unauthorizedListeners.delete(listener);
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  /** Set for sign-in and registration, where there is no token yet and a 401 is the answer, not a session problem. */
  anonymous?: boolean;
  signal?: AbortSignal;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, anonymous = false, signal } = options;
  const headers: Record<string, string> = {};
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  const token = tokenStore.get();
  if (token && !anonymous) {
    headers.Authorization = `Bearer ${token}`;
  }

  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    });
  } catch (cause) {
    if (signal?.aborted) {
      throw cause;
    }
    throw new ApiError(0, 'NETWORK', 'Could not reach the server. Is the backend running?');
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  const payload = text ? safeParse(text) : null;

  if (!response.ok) {
    if (response.status === 401 && !anonymous) {
      tokenStore.clear();
      unauthorizedListeners.forEach((listener) => listener());
    }
    throw toApiError(response.status, payload);
  }
  return payload as T;
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return { detail: text };
  }
}

/**
 * The backend's problem documents carry a `detail` worth showing and sometimes a map of field errors from bean
 * validation. Anything unexpected falls back to a plain message rather than showing the user raw JSON.
 */
function toApiError(status: number, payload: unknown): ApiError {
  const problem = (payload ?? {}) as {
    code?: string;
    detail?: string;
    title?: string;
    errors?: Record<string, string>;
  };
  const message =
    problem.detail ??
    problem.title ??
    (status === 403
      ? 'You are not allowed to do this'
      : status === 404
        ? 'Not found'
        : 'Something went wrong');
  return new ApiError(status, problem.code ?? 'ERROR', message, problem.errors);
}

/**
 * Fetches a file (an Excel export) with the user's token and hands it to the browser as a download. It goes
 * through this module like every other call, so the token and the error handling are the same.
 */
export async function downloadFile(path: string, fallbackName: string): Promise<void> {
  const token = tokenStore.get();
  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
  } catch {
    throw new ApiError(0, 'NETWORK', 'Could not reach the server. Is the backend running?');
  }
  if (!response.ok) {
    const text = await response.text();
    if (response.status === 401) {
      tokenStore.clear();
      unauthorizedListeners.forEach((listener) => listener());
    }
    throw toApiError(response.status, text ? safeParse(text) : null);
  }
  const blob = await response.blob();
  const name = fileNameFrom(response.headers.get('Content-Disposition')) ?? fallbackName;
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/** The file name from a Content-Disposition header, preferring the UTF-8 form. */
export function fileNameFrom(header: string | null): string | null {
  if (!header) return null;
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(header);
  if (utf8) return decodeURIComponent(utf8[1]);
  const plain = /filename="?([^";]+)"?/i.exec(header);
  return plain ? plain[1] : null;
}

export const api = {
  get: <T>(path: string, signal?: AbortSignal) => request<T>(path, { signal }),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: 'POST', body }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH', body }),
  del: <T>(path: string, body?: unknown) => request<T>(path, { method: 'DELETE', body }),
};
