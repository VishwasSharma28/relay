import { useEffect, useRef, useState, type ReactNode } from 'react';

export function Spinner({ label = 'Loading…' }: { label?: string }) {
  return <div className="state state-loading" role="status"><span className="spinner" aria-hidden />{label}</div>;
}

export function ErrorBox({ error, onRetry }: { error: string | null | undefined; onRetry?: () => void }) {
  if (!error) return null;
  return (
    <div className="alert alert-error" role="alert">
      <span>{error}</span>
      {onRetry && <button className="btn btn-sm" onClick={onRetry}>Retry</button>}
    </div>
  );
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return <div className="state state-empty"><strong>{title}</strong>{children && <div className="muted">{children}</div>}</div>;
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="page-header">
      <div><h1>{title}</h1>{subtitle && <p className="muted">{subtitle}</p>}</div>
      {actions && <div className="row">{actions}</div>}
    </div>
  );
}

export function Card({ title, actions, children }: { title?: ReactNode; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="card">
      {(title || actions) && <div className="card-head"><h2>{title}</h2>{actions}</div>}
      {children}
    </section>
  );
}

export function ConfirmDialog(props: {
  open: boolean; title: string; message: ReactNode; confirmLabel?: string; danger?: boolean;
  busy?: boolean; error?: string | null; onConfirm: () => void; onCancel: () => void;
}) {
  const ref = useRef<HTMLButtonElement>(null);
  useEffect(() => { if (props.open) ref.current?.focus(); }, [props.open]);
  if (!props.open) return null;
  return (
    <div className="modal-backdrop" onKeyDown={(e) => e.key === 'Escape' && props.onCancel()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="confirm-title">
        <h2 id="confirm-title">{props.title}</h2>
        <div className="modal-body">{props.message}</div>
        <ErrorBox error={props.error} />
        <div className="row end">
          <button className="btn" ref={ref} onClick={props.onCancel} disabled={props.busy}>Cancel</button>
          <button className={props.danger ? 'btn btn-danger' : 'btn btn-primary'} onClick={props.onConfirm} disabled={props.busy}>
            {props.busy ? 'Working…' : props.confirmLabel ?? 'Confirm'}
          </button>
        </div>
      </div>
    </div>
  );
}

export function CopyButton({ text, label = 'Copy' }: { text: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      type="button"
      className="btn btn-sm"
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(text);
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        } catch {
          setCopied(false);
        }
      }}
    >
      {copied ? 'Copied' : label}
    </button>
  );
}
