# Agent Instructions — Skyframe Graph Explorer

You are working on the Skyframe Graph Explorer, a web app for exploring
Bazel's Skyframe dependency graph.

## Documentation Maintenance

After completing any change to the codebase, **update the design docs** in
this directory.  The docs are the source of truth for invariants, design
decisions, UX behavior, and architecture.

### Rules

1. **Read first**: Before starting work, read `INVARIANTS.md`,
   `DESIGN_DECISIONS.md`, `UX.md`, and `ARCHITECTURE.md` to understand
   the current system contracts.

2. **Update after every change**: If your change modifies, adds, or
   removes a behavior documented in these files, update the relevant
   doc(s) before considering the task complete.

3. **Never delete numbered entries**: Design decisions (DD-N) and
   invariants (INV-N) are referenced by number.  If one becomes
   obsolete:
   - Keep the line with its number and original title.
   - Mark it as `**[REMOVED]**`.
   - Remove the body (rationale, enforcement, etc.).
   - Example:
     ```
     ## DD-7: Double-click to expand, right-click for menu — **[REMOVED]**
     ```

4. **Append new entries**: New decisions or invariants get the next
   available number.  Never reuse a removed number.

5. **UX.md is a living spec**: Update tables, diagrams, and descriptions
   to reflect the current UI.  There are no numbered entries here — just
   keep it accurate.

6. **ARCHITECTURE.md tracks structure**: Update the API table, component
   diagram, data flow, and file layout when they change.

### Checklist (copy into your plan when relevant)

- [ ] Read design docs before starting
- [ ] Check if any invariants are affected
- [ ] Check if any design decisions are affected
- [ ] Update UX.md if interaction or visual behavior changed
- [ ] Update ARCHITECTURE.md if API, data flow, or file layout changed
- [ ] Add new DD-N / INV-N entries if new design choices were made
- [ ] Mark removed DD-N / INV-N as **[REMOVED]** (keep number and title)
