## Agent skills

### Issue tracker

Issues and specs are tracked in GitHub Issues for `darklightn/roam`; use the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the default five canonical triage labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, and `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

This is a single-context repo with root `GLOSSARY.md` and `docs/adr/`. See `docs/agents/domain.md`.

### Retry limit

- Each task or operation may be attempted at most five times.
- Count retries across equivalent attempts, including alternate commands or transports used for the same task.
- If the fifth attempt fails, stop retrying and report the failure clearly.
- Classify the cause as one of: `network`, `business`, `parameter`, or `other`; include the evidence and the next actionable step when known.
