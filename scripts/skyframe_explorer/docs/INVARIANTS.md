# Skyframe Graph Explorer — Invariants

Hard rules the system must maintain.  Violations are bugs.

---

## Graph Model Invariants

### INV-1: Pinned roots

> Every visible subgraph is rooted by at least one pinned node.

A node is visible if and only if it is pinned, or it is a child of an
expanded node.  There are no "floating" visible nodes with no path back
to a pin.

**Enforcement**: `_prune_unreachable()` is called after every fold,
remove, or unpin operation.  It walks from pinned nodes through expanded
edges and removes any expanded node not reached.

### INV-2: Faithful subgraph

> If two visible nodes are connected by a dependency edge in the
> underlying Skyframe graph, that edge is shown in the view.

The displayed graph is always a faithful subgraph of the real graph.
Edges are never omitted between visible nodes.

**Enforcement**: `compute_view()` iterates all visible nodes and
includes every edge whose source and target are both visible.

### INV-3: No orphaned expanded nodes

> Every node in the `expanded` set is reachable from a pinned node
> through a chain of expanded parent→child edges.

Folding or removing a node prunes the expanded set to maintain this.

**Enforcement**: `_prune_unreachable()` computes reachability and
intersects the expanded set.

### INV-4: Cache monotonicity

> Nodes are never removed from `node_cache` except on full refresh.

Once fetched, a node stays in cache for the lifetime of the session.
This guarantees that IDs remain stable and previously seen nodes can
be re-pinned without another gRPC call.

**Exception**: `refresh()` clears the entire cache.

### INV-5: Pinned nodes survive type filter

> A pinned node is always displayed, regardless of the type filter
> state.

The frontend's `applyTypeFilter()` skips nodes where `is_pinned` is
true, even if their `function_type` is in `hiddenTypes`.

---

## Frontend Invariants

### INV-6: Filter panel type list is stable

> The type filter panel always shows all types returned by `/api/types`
> at the last fetch.  It does not change when the visible graph changes.

Types are fetched once at startup and again on Refresh.  Individual
expand/fold/pin operations do not modify the type list.

### INV-7: Layout after every view change

> Every call to `applyView()` ends with `applyTypeFilter()` which ends
> with `relayout()`.

This ensures the visual layout always reflects the current set of
visible (non-filtered) nodes.

### INV-8: Edge visibility follows node visibility

> An edge is displayed if and only if both its source and target nodes
> are displayed (not hidden by the type filter).

**Enforcement**: `applyTypeFilter()` sets edge display to `'none'` when
either endpoint is hidden.

### INV-9: Detail pane shows all children

> When a single node is selected, the detail pane shows all of its
> children in the underlying graph, not just those currently visible.

Children are fetched on-demand via `/api/children/{id}`, which calls
`_ensure_children()` to fetch uncached children via gRPC.

---

## Interaction Invariants

### INV-10: Single click is native Cytoscape

> Single click/drag behavior is never overridden.  Cytoscape's default
> select, multi-select, drag, and box-select behaviors are preserved.

### INV-11: Double-click is expand/fold

> Double-clicking a node with children toggles between expanded and
> folded state.  Double-clicking a leaf node is a no-op.

### INV-12: Context menu is complete

> Right-click on a node shows a context menu with all applicable
> actions.  Menu items are shown/hidden based on node state:
> - Expand: shown if not expanded and has children
> - Fold: shown if expanded
> - Pin: shown if not pinned
> - Unpin: shown if pinned
> - Remove subtree: always shown

### INV-13: Transitive edge hiding preserves reachability

> When "Hide transitive" is on, an edge A→C is hidden only if C is
> reachable from A through at least one other visible path (A→B→…→C).
> Hiding never disconnects the visible graph.

**Enforcement**: `applyTransitiveFilter()` uses BFS from A's other
children to verify reachability before hiding.

### INV-14: config=null treated as no config

> If the canonical name contains `config=null`, the parsed `config`
> field is empty.  No "null" is shown in labels, detail pane, or
> tooltips.

**Enforcement**: `_parse_canonical_name()` checks for `null` after
stripping trailing brackets.

### INV-15: Every UI action triggers a relayout

> Any user interaction that changes the visible graph (expand, fold,
> pin, unpin, filter toggle, transitive toggle, type checkbox) must
> call `relayout()` so the layout reflects the current state.

**Enforcement**: All action handlers end with `applyView()` (which
calls `relayout()`) or call `relayout()` directly.

### INV-16: Floating panels ordered wider-to-narrower, top-to-bottom

> The right-side floating overlay panels are stacked top-to-bottom in
> decreasing width order.  The type filter panel (widest) is on top;
> the view-controls panel (narrower) is below.  This order is static
> and does not change at runtime.

**Enforcement**: HTML order and CSS positioning (`filter-panel` has
`top: 10px`; `view-controls` is positioned dynamically below it).
