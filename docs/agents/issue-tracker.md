# Issue tracker: GitHub Issues

Issues, bugs, and work briefs for this repo live in GitHub Issues on `JaiNana123321/openbench`. Use the `gh` CLI (`gh issue list`, `gh issue view <n>`, `gh issue create`, `gh issue comment`).

## Conventions

- One issue per piece of work (a feature brief, a test task) and one per defect. A feature issue's body is its brief: the requirements, the test cases that accept it (keys such as `prf-visibility` from `docs/test-cases/sections/cases/`), dependencies, and notes.
- Every Release 1 issue belongs to the milestone `Release 1`.
- Labels: one `status:` label (see `triage-labels.md`), moved as the work moves (planned, ready, in progress, in review, to discuss); a kind (`enhancement`, `bug`, `testing`, `documentation`), and one or more `module:<name>` labels (`web` for the client).
- A defect found by running a test case is filed as a `bug` naming the test case key, the build (commit), the step, what was expected, and what was observed (`TESTING.md` has the template).
- Dependencies are written in the body as `Depends on #n`.
- Commits and merge messages refer to an issue as `Refs #n`. Never write `Closes`, `Fixes`, or `Resolves` with an issue number: an issue is closed only after Jai agrees, by hand.
- Nothing is pushed to the remote without Jai's say-so. Branches and merges stay local; the issues are the only thing on GitHub that agents' work touches, and only the coordinator writes to them.

## When a skill says "publish to the issue tracker"

Create a GitHub issue with `gh issue create`, with the labels and milestone above.

## When a skill says "fetch the relevant ticket"

`gh issue view <n> --comments`.
