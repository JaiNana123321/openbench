# OpenBench documentation (snapshot)

These files are copied from the documentation repository [`swe_csds393`](https://github.com/JaiNana123321/swe_csds393) at commit `e03bcc9` on 2026-10-20: SRS version 0.3 and Software Design Document version 1.0, both dated 2026-10-05. They are the specification this code implements. Change a requirement, a design decision, or a term in `swe_csds393` first, then copy the changed file here; do not edit the copies in place.

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
| `research/allocation-concurrency-postgres.md` | The study behind ADR-0006: exclusion constraints, per-type locking, and the hourly Pool Capacity check in PostgreSQL 16 and Spring Boot 3. |
| `demo1/Demo1-Rubric.pdf` | The grading rubric for Demo 1 on 2026-10-23. |
| `agents/` | Conventions for the agent-assisted workflow: issue tracker, triage labels, domain docs. |

Where to look for common questions:

- What a module provides and its Java signatures: `design/sections/04a-modules-foundation.tex`, `04b-modules-core.tex`, `04c-modules-edge.tex`.
- Tables, columns, and constraints: `design/sections/06-data.tex` and `design/figures/schema-*.puml`.
- REST conventions, error mapping, and endpoints: `design/sections/07-api.tex`.
- The requirement behind a feature: `srs/sections/short/04-system-features.tex` for the in-scope list, `srs/sections/features/*.tex` for the full set. Requirement identifiers such as `Exclusive.Conflict` are the same in both editions and in the design's traceability table (`design/sections/appendix-a-traceability.tex`).
