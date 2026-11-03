# OpenBench Functional Test Case Descriptions

Two editions, as for the SRS and the design document:

- **Full** (`main.tex` → `OpenBench-Test-Cases.pdf`): every test case, 91 of them. The reference.
- **Short** (`main-short.tex` → `OpenBench-Test-Cases-short.pdf`): the submission. The same introduction, environment, approach, and glossary; only the 63 High priority cases and the 5 Low priority cases of the Stretch requirements, which cover every requirement. The 23 Medium priority cases keep their numbers, so the short edition's numbers skip them, and each area ends with an "In the full edition only" line listing them; a `\TC{key}` reference to one of them links there.

Graded by `Functional-Test-Case-Rubric.pdf` (completeness of test cases 40, organization 20, clarity and detail 30, introductory material 10).

| Part | File |
|---|---|
| Title page, record of changes, contents | `sections/00-front-matter.tex`; short: `sections/short/00-front-matter.tex` (same Record of Changes: change both) |
| 1 Introduction (purpose, product under test, scope, references, conventions) | `sections/01-introduction.tex` |
| 2 Test environment (software, configuration, accounts, seeded data, time control) | `sections/02-environment.tex` |
| 3 Test approach and special considerations | `sections/03-approach.tex` |
| 4 Functional test cases, one subsection per SRS feature in SRS order | `sections/04-test-cases.tex` + `sections/cases/NN-<feature>.tex`; short: `sections/short/04-test-cases.tex` + `build/short/cases/NN-<feature>.tex` (generated) |
| A Traceability matrix (generated) | `sections/appendix-a-traceability.tex` ← `build/traceability.tex`; short: `sections/short/appendix-a-traceability.tex` ← `build/traceability-short.tex` |
| B Glossary (generated from `CONTEXT.md`) | `sections/appendix-b-glossary.tex` ← `build/glossary-compact.tex` |

## Writing a test case

Each `sections/cases/NN-<feature>.tex` sets its area code with `\tcarea{DRF}` and lists, in a comment, the SRS requirements the feature must cover. A test case:

```latex
\begin{testcase}{draft-submit-shortfall}{Submission refused while the Required sum exceeds the Available Balance}{Draft.Quote.Line, Ledger.Account}
\tcfield{Actor}{...}
\tcfield{Priority}{High}
\tcfield{Preconditions}{...}
\tcfield{Test data}{...}
\tcsteps
\tcstep{Action}{Input}{Expected result}
\tcfield{Pass criteria}{...}
\tcfield{Automated by}{...}
\end{testcase}
```

Test cases are numbered `TC-<area>-nn` in order within their area; refer to one with `\TC{key}`, never by its printed number. The third argument is the only record of coverage: `tools/gen_trace.py` reads it, together with the `\fr` lines of `../srs/sections/short/04-system-features.tex`, and builds the matrix of Appendix A, marking every requirement without a test case "Not covered" in red. It warns on stderr when a test case names an identifier that is not in the SRS short edition (a typo, or a full-edition identifier such as `Draft.Line.Validate` that the automated tests also use). `\req{Id}` links to the requirement's row of the matrix.

The short edition is never edited by hand: `tools/gen_short.py` copies each `sections/cases/NN-<feature>.tex` into `build/short/cases/`, replacing every Medium priority test case with `\tcomitted{key}{Title}` (which keeps the numbering) and ending the area with `\tcomittedlist`. Every test case therefore needs a `Priority` field; the generator stops if one has none. A new High or Low case appears in both editions; a new Medium case only in the full edition. If the number of cases of a priority changes, update the counts in `sections/short/04-test-cases.tex` and at the top of this file.

`\tbd{...}` marks a placeholder in red; `make tbd` lists those left. None may remain in the submitted PDF.

Use the glossary's terms (`CONTEXT.md`) with their capitals, American spelling, and no em dashes, as in the SRS and the design document. Do not name the sample documents studied for structure.

## Build

From the repository root, as for the other documents:

```
nix develop path:nix -c make -C docs/test-cases          # both editions
nix develop path:nix -c make -C docs/test-cases short    # the short edition only
nix develop path:nix -c make -C docs/test-cases pages    # page count
nix develop path:nix -c make -C docs/test-cases tbd      # placeholders left
nix develop path:nix -c make -C docs/test-cases clean
```

`longtable` is pinned to v4.13, as in the other documents. The built PDFs beside the Makefile are tracked; rebuild and commit it with the sources.
