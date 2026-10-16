import type { ReactNode } from 'react';

interface FieldProps {
  label: string;
  /** The control: an input, select, or textarea. The label wraps it, so no id is needed. */
  children: ReactNode;
  /** Help shown under the control. */
  hint?: ReactNode;
  /** The message for this field, such as `fieldError(save.error, 'name')` of a rejected write. */
  error?: string;
}

/** A labelled form control with its help and error text. */
export function Field({ label, children, hint, error }: FieldProps) {
  return (
    <div className={error ? 'field field--invalid' : 'field'}>
      <label>
        <span className="field__label">{label}</span>
        {children}
      </label>
      {hint && <span className="field__hint">{hint}</span>}
      {error && (
        <span className="field__error" role="alert">
          {error}
        </span>
      )}
    </div>
  );
}
