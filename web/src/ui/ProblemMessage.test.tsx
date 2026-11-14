import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import { ProblemMessage } from './ProblemMessage';

function refusal(status: number, code: string, title: string) {
  return new ApiError({
    type: 'about:blank', status, code, title,
    detail: 'The server detail stays exactly as written.',
    instance: '/api',
  });
}

describe('ProblemMessage', () => {
  it.each([
    [422, 'INSUFFICIENT_BALANCE', 'Unprocessable Entity', 'Not enough credits'],
    [409, 'WRONG_STATE', 'Conflict', 'Action unavailable in this state'],
  ])('uses the code title for HTTP %s and preserves the detail', (status, code, reason, title) => {
    render(<ProblemMessage error={refusal(status, code, reason)} />);
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent(title);
    expect(alert).not.toHaveTextContent(reason);
    expect(screen.getByText('The server detail stays exactly as written.')).toBeInTheDocument();
  });

  it.each(['NEW_REFUSAL', 'toString'])('uses the neutral fallback for unknown code %s', (code) => {
    render(<ProblemMessage error={refusal(422, code, 'Unprocessable Entity')} />);
    expect(screen.getByText('Not done')).toBeInTheDocument();
    expect(screen.getByRole('alert')).not.toHaveTextContent('Unprocessable Entity');
  });

  it.each(['SKILL_NAME_TAKEN', 'NEW_REFUSAL'])('prefers an explicit title for code %s', (code) => {
    render(<ProblemMessage error={refusal(422, code, 'Unprocessable Entity')} title="Please try another name" />);
    expect(screen.getByText('Please try another name')).toBeInTheDocument();
    expect(screen.getByRole('alert')).not.toHaveTextContent('Unprocessable Entity');
  });

  it('renders nothing without an error', () => {
    render(<ProblemMessage error={null} />);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('keeps the unexpected error message for errors outside the API', () => {
    render(<ProblemMessage error={new Error('internal detail')} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Something went wrong');
    expect(screen.getByRole('alert')).toHaveTextContent('An unexpected error occurred. Reload the page and try again.');
  });
});
