import { useCallback, useState } from 'react';
import { Prompt } from '../components/Prompt';
import type { PromptConfig } from '../components/Prompt';
import { useAction } from './useLoad';

/**
 * Wires a confirmation modal to an action.
 *
 * `ask` opens the modal with the question and the work to run on confirm. The modal stays open while the request is
 * in flight and, if the backend refuses, shows the reason in place so the user can adjust and try again rather
 * than losing what they typed.
 */
export function usePrompt(onDone?: () => void) {
  const [pending, setPending] = useState<{
    config: PromptConfig;
    work: (reason: string | undefined) => Promise<unknown>;
  } | null>(null);
  const { run, busy, error, clearError } = useAction();

  const ask = useCallback(
    (config: PromptConfig, work: (reason: string | undefined) => Promise<unknown>) => {
      clearError();
      setPending({ config, work });
    },
    [clearError],
  );

  const close = useCallback(() => {
    setPending(null);
    clearError();
  }, [clearError]);

  const element = pending ? (
    <Prompt
      config={pending.config}
      busy={busy}
      error={error}
      onCancel={close}
      onConfirm={async (reason) => {
        const result = await run(() => pending.work(reason));
        if (result !== null) {
          setPending(null);
          onDone?.();
        }
      }}
    />
  ) : null;

  return { ask, element, busy };
}
