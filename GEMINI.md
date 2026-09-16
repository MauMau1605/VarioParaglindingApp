# Multi-Agent Architecture: Android USB Variometer (LK8EX1)

All code, comments, documentation, and agent reasoning must be in English.

## Primary Directives
1. Default entry point is the **Orchestrator** (`.antigravity/prompts/orchestrator.md`).
2. Route tasks step-by-step according to role specializations.
3. **Absolute Project Rule:** Zero dynamic memory allocation on the critical fast-path (LK8EX1 USB parsing & Audio synthesis). Prevent GC invocation during runtime.