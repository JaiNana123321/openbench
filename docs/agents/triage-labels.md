# Triage Labels

Every open issue carries exactly one `status:` label, which follows it through the workflow (see `issue-tracker.md`):

| Label | Meaning |
| --- | --- |
| `status: planned` | Not briefed yet |
| `status: ready` | Briefed; can be started by an agent or a teammate (the body says which) |
| `status: in progress` | An agent or a teammate is working on it |
| `status: in review` | The branch is done; the coordinator is reviewing it |
| `status: blocked` | Waiting on another issue (`Depends on #n`) or a decision |
| `status: to discuss` | Merged locally; to be discussed with Jai, who closes it by hand |

`wontfix` marks an issue that will not be actioned.

The skills speak in terms of five canonical triage roles. Use these labels for them:

| Role in mattpocock/skills | Label in our tracker |
| --- | --- |
| `needs-triage` | `status: planned` |
| `needs-info` | `status: blocked`, with the question in a comment |
| `ready-for-agent` | `status: ready` |
| `ready-for-human` | `status: ready`, with "For a teammate" in the body |
| `wontfix` | `wontfix` |
