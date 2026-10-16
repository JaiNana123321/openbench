import type { FormEvent } from 'react';
import { useLocation, useNavigate, type Path } from 'react-router';
import { Button, Field, ProblemMessage } from '../ui';
import { useSignIn } from './session';

/** Where RequireAuth sends a signed-out visitor, remembering the page they asked for. */
export interface LoginState {
  from?: Path;
}

/** /login: the sign-in form. On success it goes to the page asked for, or the home page. */
export function LoginPage() {
  const signIn = useSignIn();
  const navigate = useNavigate();
  const { state } = useLocation() as { state: LoginState | null };

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    signIn.mutate(
      { username: String(form.get('username')), password: String(form.get('password')) },
      { onSuccess: () => navigate(state?.from ?? '/', { replace: true }) },
    );
  }

  return (
    <div className="login">
      <title>Sign in · OpenBench</title>
      <div className="login__brand">
        <span className="brand">OpenBench</span>
      </div>
      <main className="login__card">
        <h1>Sign in</h1>
        <p className="login__lead">People, rooms, and compute for your Team, settled in credits.</p>
        <form onSubmit={handleSubmit}>
          <Field label="Username">
            <input name="username" autoComplete="username" required autoFocus />
          </Field>
          <Field label="Password">
            <input name="password" type="password" autoComplete="current-password" required />
          </Field>
          <ProblemMessage error={signIn.error} title="Sign-in failed" />
          <Button type="submit" busy={signIn.isPending} className="button--block">
            Sign in
          </Button>
        </form>
      </main>
    </div>
  );
}
