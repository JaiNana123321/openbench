# Testing OpenBench

How to run the automated tests and the coverage reports, how to read the functional test case run report, how to run a test case by hand, and how to record a manual run and file each failure as a GitHub issue.

The functional test cases are in `docs/test-cases/` (91 cases, keys such as `drf-balance-boundary`, printed as `TC-DRF-08`); the PDF is `docs/test-cases/OpenBench-Test-Cases.pdf`. Everything runs locally; there is no CI.

## Automated tests

### Server (JUnit 5, PostgreSQL 16 through Testcontainers)

Docker must be running: every integration test runs against a PostgreSQL 16 container with the production Flyway migrations (`PostgresTest`).

| Command | What it does |
|---|---|
| `mvn verify` | Builds the web client, runs every server test, writes the coverage report and the test case run report, and fails if a test fails, a `@Verifies` key is unknown, or coverage is below the gate. |
| `mvn verify -Dskip.npm -Dskip.installnodenpm` | The same without building the web client (faster while working on the server). |
| `mvn test -Dtest=DraftCasesTest` | One class (or `-Dtest='DraftCasesTest#acceptsARequiredSumEqualToTheAvailableBalance'` for one method). No coverage gate; the run report then covers only the tests that ran. |

What `mvn verify` writes:

| Report | Path |
|---|---|
| Test results per class | `target/surefire-reports/` |
| Server coverage (JaCoCo) | `target/site/jacoco/index.html`, plus `jacoco.xml` and `jacoco.csv` |
| Functional test case run report | `target/test-cases/report.md` |

**Coverage gate.** JaCoCo's `check` fails `mvn verify` when the whole bundle falls below **90% of lines** or **80% of branches** (properties `coverage.line.minimum` and `coverage.branch.minimum` in `pom.xml`). Only `OpenBenchApplication` is excluded: it holds nothing but `main()`, which the tests never call because `@SpringBootTest` starts the context itself. Baseline on 2026-11-07: 97.2% of lines, 84.8% of branches. To see the gate fail, raise a minimum on the command line, for example `mvn jacoco:check@check -Dcoverage.branch.minimum=0.85` after a `mvn verify`; it prints `Rule violated for bundle openbench: branches covered ratio is 0.84, but expected minimum is 0.85`.

### Client (Vitest, React Testing Library)

| Command | What it does |
|---|---|
| `npm --prefix web test` | Runs every client test once. |
| `npm --prefix web run coverage` | The same with V8 coverage: writes `web/coverage/index.html` (and `lcov.info`, `coverage-summary.json`) and fails below the thresholds. |

The thresholds in `web/vite.config.ts` sit a few points below the baseline of 2026-11-07 (statements 95.0%, branches 83.1%, functions 96.8%, lines 97.4%): statements 90, branches 78, functions 92, lines 92. `src/main.tsx` (which only mounts `<App>`) and the test helpers in `src/test/` are excluded. `web/coverage/` is not committed.

## The functional test case run report

`target/test-cases/report.md` is generated from the build's own test run: a JUnit Platform listener (`edu.cwru.openbench.testcases.TestCaseReportListener`, registered in `src/test/resources/META-INF/services`) records the result of every test tagged with `@Verifies`, and at the end of the run reads the cases from `docs/test-cases/sections/cases/*.tex` and the requirements from `docs/srs/sections/short/04-system-features.tex`. It lists every case, whether or not a test runs it. It is written only when Surefire runs the tests (an IDE run writes nothing).

It has three parts:

1. **Summary.** The number of cases in each status, overall and by priority, and how many tagged tests failed or were disabled. The header names the commit of the build.
2. **One table per area** (INV, PRF, SUG, DRF, MAT, EXC, MET, LIF, REL, LED, DSH, ADM, in the document's order): case number, key, title, priority, requirements, status, and the tests that ran it.
3. **Requirements.** Each functional requirement of the SRS short edition, its cases with their statuses, and the best status among them.

A case's status:

| Status | Meaning |
|---|---|
| Passed | A test for the whole case passed, and none of its tests failed. |
| Passed (partial) | Only tests marked `[part]` (tagged `partial = true`) passed: some of the case's steps still need a manual run. |
| Failed | At least one of its tests failed. The report never hides a failure. |
| Not automated | No test ran it, or its only tests are disabled. Run it by hand. |

In the tests column, `ClassName (all N tests)` is a tag on the whole class, `**FAILED**` marks a failing test with the first line of its message, and `(disabled: reason)` a test switched off with `@Disabled`, usually because the case found a defect or needs a feature that is not built yet.

**Tagging a test.** Put `@Verifies("case-key")` on the test method (or on the class, for all its tests), with `partial = true` if it runs only some of the case's steps; one annotation can name several keys. `TestConventionsTest` fails the build for a key that is not in the document. Keep the case's "Automated by" field in the document in step with the tags. The client tests (Vitest) are not in the report; the "Automated by" fields name them where they cover a case.

## Running a test case by hand

1. **Reset to the test world.** Stop the application, run `docker compose down` (it removes the database container and its data), and start it again with the demo data: `mvn spring-boot:run -Dspring-boot.run.profiles=demo`. Open `http://localhost:8080`. The test world (Section 2.4 of the test case document) is then loaded; every seeded password is `demo`. Reset at the start of each area and before any case whose preconditions say "fresh test world", and before the next case if a calendar month has ended since the database was created (the seed posts the Grants of the month of creation).
2. **Work out the dates** (Section 2.5) on the day of the run, in the company time zone `America/New_York`:
   - **D**: the first Wednesday at least 14 days after today (D+1 is a Thursday, D+3 a Saturday, D+7 the next Wednesday);
   - **E**: a weekday two to six days from today;
   - **H**: the first whole hour at least two hours from the start of the case.
3. **Follow the steps** and compare each result with the expected result; the reference Quotes Q1 to Q11 are in Section 2.6. A step marked **[clock]** needs time to pass (an Objection Window, a window's start): wait for it, or rely on its automated test.
4. **Record** the outcome of every step in the run record below, and file each failure.

## Manual run record

Copy this into the run's notes (or a comment on the Release's tracking issue), one record per run:

```markdown
### Manual test run, <YYYY-MM-DD>

- Build: commit `<git rev-parse --short HEAD>` (from `main` / branch `<name>`)
- Tester: <name>
- Environment: <OS>, <browser and version>, Docker <version>; test world reset at <time>
- Day D used: <YYYY-MM-DD>

| Case | Key | Outcome | Notes and defect |
|---|---|---|---|
| TC-DRF-08 | drf-balance-boundary | Passed | |
| TC-EXC-03 | exc-recurrence-quantity | Failed at step 1 | #<issue> |
| TC-MAT-01 | mat-shortlist-build | Blocked | Matched lines not built |
```

Outcome is one of Passed, Failed at step *n*, Blocked (cannot be run on this build, with the reason), or Not run.

## Filing a defect

File every failed step as an issue in this repository, with the label `bug` and the milestone `Release 1`, one issue per defect:

```markdown
Title: <case number> <key>: <what went wrong, in a few words>

**Case:** TC-<AREA>-<nn> `<key>` (docs/test-cases, Section 4.<n>)
**Build:** commit `<sha>`; run by <tester> on <YYYY-MM-DD>; automated test `<Class#method>` if any
**Step:** <n>. <the step's action and input>
**Expected:** <the step's expected result, as the case states it>
**Observed:** <what happened instead: status code, problem detail `code` and message, screen text, values>
**Evidence:** <screenshot, or the request and response (curl -i, or the browser's network tab), or the report line>
**Notes:** <reproducible every time?; anything else needed to reproduce>
```

With the GitHub CLI: `gh issue create --label bug --milestone "Release 1" --title "..." --body-file defect.md`. When an automated test exposes the defect, keep the test, disable it with `@Disabled("<key> step <n>: <reason>")`, and link the issue; re-enable it in the fix.
