## Primary Directives
1. Default entry point is the **Orchestrator** (`.antigravity/prompts/orchestrator.md`) configured in `.antigravity/config.json`.
2. Automatically route tasks and delegate execution to specialized sub-agents defined in `.antigravity/prompts/` using their assigned models without requesting manual intervention.
3. **Absolute Project Rule:** Zero dynamic memory allocation on the critical fast-path (LK8EX1 USB parsing & Audio synthesis). Prevent GC invocation during runtime.
4. **Documentation Directive:** Keep `docs/ARCHITECTURE.md` and `docs/USER_MANUAL.md` continuously up to date via `doc_writer` (`.antigravity/prompts/doc_writer.md`) whenever architecture, protocols, UI, or workflows change.