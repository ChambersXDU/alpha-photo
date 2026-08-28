# AGENTS.md

## Working principles

- Do not assume. Surface uncertainty early and explicitly.
- Before designing a protocol experiment, inspect existing public documentation and implementations first. Use hardware experiments only to validate facts that remain unknown or device-specific.
- Write only the minimum code needed to solve the current problem.
- Change only what must change.
- Trust internal code and established invariants. Do not add defensive code for cases that should be impossible.
- Never swallow errors.
- Do not introduce one-off abstractions.
- Keep all repository code, comments, commit-facing documentation, and project documentation in English.
- Keep implementations simple, direct, and easy to inspect.

## Prohibited patterns

- Avoid degradation handling, fallbacks, hacks, heuristics, local stabilizations, or post-processing bandages that are not faithful general algorithms.
- Do not add speculative compatibility layers or future-proofing that the current task does not require.
- Do not add retries, guards, normalization, validation, or recovery paths unless the current problem genuinely requires them.
- Do not add hashing, SHA-256, checksums, or similar verification as defensive scaffolding unless cryptographic integrity is explicitly required by the current task.
- Do not repeatedly defend against cases that are impossible under trusted internal invariants.
- Do not hide failures behind default values, empty catches, silent returns, or best-effort behavior.
- Do not broaden scope with opportunistic refactors, cleanup, or unrelated improvements.

## Change discipline

- Prefer the smallest faithful implementation.
- Prefer explicit code over premature abstraction.
- If an assumption is necessary, state it instead of encoding a guess.
- If a required fact is unknown, stop and expose the uncertainty rather than inventing a fallback.
