import { useState } from 'react';
import type { ReactNode } from 'react';
import { ErrorNote } from './ui';

/**
 * A small modal that asks for confirmation, and for a reason where the backend insists on one.
 *
 * Several actions in this app are refused without a written reason: an admin overriding an organizer, taking a
 * payment back, writing off a settlement. Rather than let the user find that out from a 400, the reason is asked
 * for here and the submit button stays disabled until there is one.
 */
export interface PromptConfig {
  title: string;
  message?: ReactNode;
  confirmLabel?: string;
  /** 'required' makes the reason compulsory, 'optional' offers it, 'none' hides it. */
  reason?: 'required' | 'optional' | 'none';
  reasonLabel?: string;
  danger?: boolean;
}

export function Prompt({
  config,
  busy,
  error,
  onConfirm,
  onCancel,
}: {
  config: PromptConfig;
  busy?: boolean;
  error?: string | null;
  onConfirm: (reason: string | undefined) => void;
  onCancel: () => void;
}) {
  const [reason, setReason] = useState('');
  const reasonMode = config.reason ?? 'none';
  const reasonMissing = reasonMode === 'required' && reason.trim().length === 0;

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label={config.title}>
      <form
        className="modal"
        onSubmit={(event) => {
          event.preventDefault();
          if (reasonMissing) return;
          onConfirm(reason.trim() === '' ? undefined : reason.trim());
        }}
      >
        <h3>{config.title}</h3>
        {config.message && <div className="muted">{config.message}</div>}

        {reasonMode !== 'none' && (
          <label className="field">
            <span className="field-label">
              {config.reasonLabel ?? 'Reason'}
              {reasonMode === 'optional' && <span className="muted small"> (optional)</span>}
            </span>
            <textarea
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              rows={3}
              maxLength={500}
              autoFocus
              placeholder={
                reasonMode === 'required'
                  ? 'Everyone affected can see this, so say what happened'
                  : 'Anything worth recording'
              }
            />
          </label>
        )}

        <ErrorNote>{error}</ErrorNote>

        <div className="row gap end">
          <button type="button" className="btn btn-quiet" onClick={onCancel} disabled={busy}>
            Cancel
          </button>
          <button
            type="submit"
            className={config.danger ? 'btn btn-danger' : 'btn btn-primary'}
            disabled={busy || reasonMissing}
          >
            {busy ? 'Working…' : (config.confirmLabel ?? 'Confirm')}
          </button>
        </div>
      </form>
    </div>
  );
}
