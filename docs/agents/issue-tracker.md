# Issue tracker: Local Markdown

Issues and specs for this repo live as Markdown files under `.scratch/`.

## Conventions

- One effort per directory: `.scratch/<effort>/`.
- A build specification, once produced, is `.scratch/<effort>/spec.md`.
- Tickets are individual files under `.scratch/<effort>/issues/`, numbered from `01`.
- Ticket state is recorded in a `Status:` line near the top of the file.
- Conversation history is appended under `## Comments` when it must be retained.

## When a skill says "publish to the issue tracker"

Create a Markdown file under `.scratch/<effort>/`.

## When a skill says "fetch the relevant ticket"

Read the referenced Markdown file. A number refers to the matching file under the effort's `issues/` directory.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a file with one child file per ticket.

- **Map**: `.scratch/<effort>/map.md` holds Destination, Notes, Decisions-so-far, Not-yet-specified fog, and Out-of-scope boundaries.
- **Child ticket**: `.scratch/<effort>/issues/NN-<slug>.md`. `Type:` records `research`, `prototype`, `grilling`, or `task`; `Status:` records `open`, `claimed`, or `resolved`.
- **Blocking**: `Blocked by: NN, NN` near the top. A ticket is unblocked when every listed ticket is `resolved`.
- **Frontier**: scan the effort's issue files in number order and select open, unblocked, unclaimed tickets.
- **Claim**: change `Status: open` to `Status: claimed` and save before starting work.
- **Resolve**: append the resolution under `## Answer`, set `Status: resolved`, then add a linked one-line gist to the map's Decisions-so-far.
