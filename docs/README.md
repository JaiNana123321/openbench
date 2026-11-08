# OpenBench documentation (snapshot)

These files are copied from the documentation repository [`swe_csds393`](https://github.com/JaiNana123321/swe_csds393) at commit `e03bcc9` on 2026-10-20, then updated on 2026-11-07 from commit `f90134a` to SRS version 0.4, Software Design Document version 1.1, and an amended ADR-0001. That update lets an Optional Matched line outlive allocation and starts the Objection Window at submission (design RC-4), and measures the Refund lead time to the start of the released part's occurrence (design RC-5). The Functional Test Case Descriptions (version 0.2) were added on 2026-11-07 from commit `e16ec25`, without their build files, and updated on 2026-11-09 to the reviewed version 0.3 from commit `9bed03e`. They are the specification this code implements. Change a requirement, a design decision, or a term in `swe_csds393` first, then copy the changed file here; do not edit the copies in place.

| Path | Contents |
|---|---|
| `../CONTEXT.md` | The shared vocabulary. Class, table, endpoint, and test names use these terms. |
| `adr/` | Architecture decision records 0001 to 0007. 0005 to 0007 fix the module structure, the locking rules, and the allocator interface. |
| `srs/OpenBench-SRS-short.pdf` | The submitted SRS: the 47 requirements in scope (41 Release 1, 6 marked `[Stretch]`). |
| `srs/OpenBench-SRS.pdf` | The full SRS: all 158 requirements, the 12 use cases written out, the business rules, and the data dictionary. |
| `design/OpenBench-Design-short.pdf` | The submitted design: each module's provided interface as Java signatures, the core schema, the API conventions. |
| `design/OpenBench-Design.pdf` | The full design: operation tables, all seven scenarios, the schema of every module, and the endpoint-by-endpoint API table. |
| `srs/sections/`, `design/sections/` | The LaTeX sources of both editions (full edition at the top level, short edition in `short/`), for searching. `main.tex` and `main-short.tex` give the section order; `macros.tex` defines the markup such as `\fr{Id}{text}` for a requirement and `\req{Id}` for a reference to one. |
| `srs/figures/`, `design/figures/` | The PlantUML sources of every figure: the schema (`schema-*.puml`), class diagrams, state machines, and sequence diagrams. |
| `test-cases/OpenBench-Test-Cases.pdf` | The Functional Test Case Descriptions: the test environment and test world, and 91 black-box test cases `TC-<area>-nn`, each naming the SRS requirements it verifies and the automated test that runs it. `OpenBench-Test-Cases-short.pdf` is the submitted edition; `Functional-Test-Case-Rubric.pdf` grades it. |
| `test-cases/sections/cases/*.tex` | The test cases' sources, one file per SRS feature. Each case opens with `\begin{testcase}{key}{title}{requirements}`; the key, such as `drf-balance-boundary`, is the stable name automated tests use. `test-cases/README.md` explains the template. |
| `research/allocation-concurrency-postgres.md` | The study behind ADR-0006: exclusion constraints, per-type locking, and the hourly Pool Capacity check in PostgreSQL 16 and Spring Boot 3. |
| `demo1/Demo1-Rubric.pdf` | The grading rubric for Demo 1 on 2026-10-23. |
| `agents/` | Conventions for the agent-assisted workflow: issue tracker, triage labels, domain docs. |

Where to look for common questions:

- What a module provides and its Java signatures: `design/sections/04a-modules-foundation.tex`, `04b-modules-core.tex`, `04c-modules-edge.tex`.
- Tables, columns, and constraints: `design/sections/06-data.tex` and `design/figures/schema-*.puml`.
- REST conventions, error mapping, and endpoints: `design/sections/07-api.tex`.
- How a feature is tested from the user's side, and the data and dates the cases assume: `test-cases/sections/02-environment.tex` and `test-cases/sections/cases/*.tex`.
- The requirement behind a feature: `srs/sections/short/04-system-features.tex` for the in-scope list, `srs/sections/features/*.tex` for the full set. Requirement identifiers such as `Exclusive.Conflict` are the same in both editions and in the design's traceability table (`design/sections/appendix-a-traceability.tex`).
