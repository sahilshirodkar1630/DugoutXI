import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';

export interface Loaded<T> {
  data: T | null;
  error: string | null;
  loading: boolean;
  /** Fetches again, keeping the current data on screen until the new data arrives. */
  reload: () => void;
  /** Replaces the data without a round trip, for when a mutation already returned the new version. */
  setData: (next: T) => void;
}

/**
 * Loads something from the API and keeps it in state.
 *
 * Two things matter here. A request still in flight when the component unmounts, or when the dependencies change,
 * is abandoned rather than written into state, which is what stops the "setState on an unmounted component" class
 * of bug and the flicker where an older response overwrites a newer one. And errors arrive as a plain message,
 * because that is all a screen needs in order to show something sensible.
 *
 * `deps` works like the dependency array of useEffect: when a value in it changes, the data is fetched again.
 */
export function useLoad<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  deps: unknown[] = [],
  options: { skip?: boolean } = {},
): Loaded<T> {
  const { skip = false } = options;
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(!skip);
  const [reloadCount, setReloadCount] = useState(0);

  // The fetcher is usually an inline arrow function, so it is held in a ref to keep it out of the dependency array.
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  useEffect(() => {
    if (skip) {
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    let cancelled = false;
    setLoading(true);

    fetcherRef
      .current(controller.signal)
      .then((result) => {
        if (!cancelled) {
          setData(result);
          setError(null);
        }
      })
      .catch((cause: unknown) => {
        if (cancelled || controller.signal.aborted) {
          return;
        }
        setError(cause instanceof ApiError ? cause.message : 'Something went wrong');
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
      controller.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, reloadCount, skip]);

  const reload = useCallback(() => setReloadCount((n) => n + 1), []);

  return { data, error, loading, reload, setData };
}

/**
 * Runs a change against the API: tracks whether it is in flight and what went wrong, so a button can disable
 * itself and a form can show the reason it was refused.
 */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = useCallback(async <T>(work: () => Promise<T>): Promise<T | null> => {
    setBusy(true);
    setError(null);
    try {
      return await work();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'Something went wrong');
      return null;
    } finally {
      setBusy(false);
    }
  }, []);

  return { run, busy, error, clearError: useCallback(() => setError(null), []) };
}
