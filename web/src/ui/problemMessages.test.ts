import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { problemMessages } from './problemMessages';

describe('problem message coverage', () => {
  it('has a title for every server refusal code', () => {
    const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../src/main/java');
    const sources = readdirSync(root, { recursive: true, encoding: 'utf8' })
      .filter((path) => path.endsWith('.java'))
      .map((path) => readFileSync(`${root}/${path}`, 'utf8'));
    const codes = new Set<string>();
    for (const source of sources) {
      // Literal refusals, shared HTTP/security problems, and codeFor's fixed status codes.
      for (const pattern of [
        /new\s+RuleViolation\s*\(\s*"([A-Z_]+)"/g,
        /Problems\.of\(\s*[^,]+,\s*"([A-Z_]+)"/g,
        /case\s+\d+\s*->\s*"([A-Z_]+)"/g,
        // Allocators also pass named constants and HourlyLoad's reason() to RuleViolation.
        /\bString\s+([A-Z_]+)\s*=\s*"\1"/g,
      ]) {
        for (const match of source.matchAll(pattern)) codes.add(match[1]);
      }
      const conflicts = source.match(/STATE_CONFLICT_CODES\s*=\s*Set\.of\(([\s\S]*?)\);/);
      for (const match of conflicts?.[1].matchAll(/"([A-Z_]+)"/g) ?? []) codes.add(match[1]);
    }

    // Guard the source scan; the server's list is read live, not copied into this test.
    expect(codes).toContain('INSUFFICIENT_BALANCE');
    expect(codes).toContain('POOL_FULL');
    expect(codes).toContain('SUGGESTION_CLOSED');
    const missing = [...codes].filter((code) => !Object.hasOwn(problemMessages, code));
    expect(missing).toEqual([]);
    for (const code of codes) expect(problemMessages[code].trim()).not.toBe('');
  });

  it('includes the codes needed by the parallel Suggestion and reopen work', () => {
    for (const code of ['SUGGESTION_CLOSED', 'TYPE_MISMATCH', 'VALIDATION_FAILED', 'WRONG_STATE']) {
      expect(problemMessages).toHaveProperty(code);
    }
  });
});
