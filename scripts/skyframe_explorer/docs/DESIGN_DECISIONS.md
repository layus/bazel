# Skyframe Graph Explorer — Design Decisions

This document records key design choices and their rationale.

---

## DD-1: Server-side graph state (pinned/expanded sets)

**Decision**: The backend maintains the authoritative set of pinned and
expanded nodes.  The frontend is a pure renderer of the backend's
`compute_view()` output.

**Rationale**: The Skyframe graph can contain 100k+ nodes.  Keeping
expand/fold/pin logic server-side avoids sending the full graph to the
browser and lets the backend lazily fetch children via gRPC only when
needed.

**Trade-off**: Every user interaction requires a round-trip.  Acceptable
for an internal debugging tool on a local network.

---

## DD-2: Pinned nodes as view roots

**Decision**: Every visible subgraph is rooted by a pinned node.  There
is no "show all roots" mode.  The user must search for a node and pin it
to start exploring.

**Rationale**: Skyframe graphs have 60+ roots and 90k+ nodes.  Showing
everything at once is useless.  Pinning gives the user explicit control
over what they see.  Search is the entry point.

---

## DD-3: Prune-on-fold / prune-on-remove

**Decision**: When a node is folded or removed, its subtree is pruned
back to the next pinned descendant.  Orphaned expanded nodes are
removed from the expanded set via `_prune_unreachable()`.

**Rationale**: Without pruning, folding a node would leave its children
as expanded-but-invisible, causing confusion when they reappear later.
Pruning to pinned nodes preserves intentional anchors while cleaning up
transient exploration.

---

## DD-4: Type filter list is global and fixed

**Decision**: The list of node types in the filter panel is fetched once
from `/api/types` at startup (which returns all distinct `function_type`
values from the entire indexed graph).  It does not change as the user
navigates.

**Rationale**: Types should be predictable.  If the filter list changed
every time the visible graph changed, the user would lose their mental
model of which types exist.  The full list is small (typically <100
types) and stable within a Bazel invocation.

---

## DD-5: Default type filter = BUILD_DRIVER + CONFIGURED_TARGET

**Decision**: On startup, only `BUILD_DRIVER` and `CONFIGURED_TARGET`
types are enabled.  All other types are hidden by default.

**Rationale**: These are the two most commonly relevant types when
debugging build graphs.  Users can toggle individual types or disable
filtering entirely via the "On/Off" toggle.

---

## DD-6: Pinned nodes bypass type filter

**Decision**: Nodes that are pinned are always visible regardless of
the type filter.

**Rationale**: If a user explicitly pins a node, they want to see it.
Hiding it because of a filter would be surprising and frustrating,
especially since pinning is the primary mechanism for accessing nodes
of types hidden by the filter.

---

## DD-7: Double-click to expand, right-click for menu

**Decision**: Single click is left to Cytoscape's default behavior
(select, drag, pan, box-select).  Double-click toggles expand/fold.
Right-click opens a context menu.

**Rationale**: Overriding single click caused conflicts with
Cytoscape's native selection and drag behavior.  Double-click is a
natural "drill-in" gesture.  Context menu provides the full set of
actions (expand, fold, pin, unpin, remove subtree).

**Previous approach**: Single click was initially used for expand/fold,
but this conflicted with node selection and drag.

---

## DD-8: Edges between all visible nodes

**Decision**: `compute_view()` shows edges between any two visible
nodes that are connected in the underlying graph, not just edges from
expanded parents to their children.

**Rationale**: If two nodes happen to be visible (e.g. both pinned
independently) and are connected in the real graph, the edge should
appear.  This preserves the invariant that the displayed graph is a
faithful subgraph of the real Skyframe graph.

---

## DD-9: Self-signed TLS

**Decision**: The server generates a self-signed certificate at
startup using `openssl` and serves HTTPS.

**Rationale**: The tool is accessed over a network (DMZ node).  HTTPS
prevents casual traffic sniffing.  A self-signed cert is acceptable
for an internal debugging tool.

---

## DD-10: Single-file frontend

**Decision**: The entire frontend is a single `index.html` file with
inline CSS and JavaScript.  No build step, no npm, no bundler.

**Rationale**: Simplicity.  The frontend is small enough (~900 lines)
to remain manageable in one file.  External dependencies (Cytoscape,
dagre) are loaded from CDN.  This makes the tool trivially deployable
alongside the Bazel repository.

---

## DD-11: Detail pane shows all children with pin buttons

**Decision**: When a node is selected, the bottom detail pane fetches
and displays all of its children (even those not in the current graph).
Each child has a Pin/Unpin button.

**Rationale**: The detail pane is the primary way to discover and add
nodes that are hidden by the type filter.  Pinning from the detail
pane makes the node visible regardless of the filter (see DD-6).

---

## DD-12: Redraw button (separate from Refresh)

**Decision**: A "Redraw" button re-runs the dagre layout without
fetching new data.  "Refresh" re-indexes the entire graph from Bazel.

**Rationale**: After toggling filters or manually dragging nodes, the
user may want to clean up the layout.  A full refresh is too aggressive
for this—it clears all pinned/expanded state and re-fetches from gRPC.

---

## DD-13: Filter panel fixed width

**Decision**: The filter panel width is locked to its expanded-state
width after initial render.  Collapsing to swatches does not shrink
the panel.

**Rationale**: Prevents jarring width changes when toggling between
expanded and collapsed views.  The panel's width is determined by the
longest type name, which provides a stable visual anchor.

---

## DD-14: `--reload` for development

**Decision**: `--reload` flag passes the app as an import string to
uvicorn and uses a FastAPI lifespan handler to initialize the model
in the reloaded subprocess.  The gRPC address is passed via
environment variable.

**Rationale**: Enables fast iteration during development.  Changes to
`server.py` or `index.html` trigger automatic restart.  The lifespan
handler ensures the model is initialized correctly in the child process.

---

## DD-15: Canonical name parsing into label/config/argument

**Decision**: The backend parses Skyframe canonical names (e.g.
`CONFIGURED_TARGET:ConfiguredTargetKey{label=//src:bazel-dev,
config=BuildConfigurationKey[b56206e...]}`) into separate fields:
`label` (extracted from `label=...`), `config` (short 8-char hash from
`config=BuildConfigurationKey[...]`), and `argument` (everything after
the `TYPE:` prefix).

**Rationale**: The raw canonical name is a Java `toString()` dump — too
long and noisy for display.  Parsing on the server lets the frontend
show a clean label while keeping the full canonical name available for
the detail pane.  For keys without `label=...` (e.g. `FILE:/path`), the
argument becomes the label.

---

## DD-16: Node label ellipsizes from the left

**Decision**: When a node label is too long, it is truncated from the
left with an ellipsis (`…`) so the rightmost part (target name) stays
visible.

**Rationale**: Bazel labels are hierarchical left-to-right.  The most
distinguishing part is the target name at the end (e.g. `:bazel-dev` in
`//src/main/java/com/google/...:bazel-dev`).  Left-ellipsis preserves
the unique suffix.

---

## DD-17: Color by type vs. color by config toggle

**Decision**: A radio button above the type filter panel lets the user
switch between coloring nodes by function type or by configuration hash.
When in config mode, type filter swatches become transparent.

**Rationale**: When debugging configuration-related issues, seeing which
nodes share the same config is more useful than seeing their type.  The
two palettes are independent.  Type swatches go transparent (not hidden)
to preserve panel layout while signaling they are not meaningful in
config mode.

---

## DD-18: Short config hash inside node

**Decision**: When a node has a configuration, the first 6 characters of
the config hash are shown as a second line inside the node rectangle.

**Rationale**: 6 chars is enough to visually distinguish configs
(collision in 16M values).  Showing it inside the node avoids needing to
hover or click for config identity.  The full hash is in the detail pane
and tooltip.

---

## DD-19: Transitive edge hiding

**Decision**: A toggle ("Hide transitive") hides redundant transitive
edges.  An edge A→C is hidden if there exists an indirect path A→B→…→C
through other visible nodes (BFS reachability check from A's other
children).

**Rationale**: Skyframe graphs have many transitive dependencies that
create visual clutter without adding information.  Hiding them reveals
the essential structure.  The check is done client-side on the visible
subgraph only, so performance is acceptable for typical view sizes
(<1000 nodes).

---

## DD-20: Sliding toggle switches instead of radio buttons

**Decision**: View controls (color mode, transitive edges) use CSS
sliding toggle switches in a separate floating panel above the type
filter panel.

**Rationale**: Toggle switches are more compact and visually clear
than radio buttons or checkboxes.  The separate floating panel keeps
view controls distinct from type filtering.  Higher z-index ensures
it stays above the filter panel.

---

## DD-21: Children animate from parent position

**Decision**: Newly added nodes are positioned at their parent node's
location before layout runs, so the dagre animation moves them outward
from the parent.

**Rationale**: The previous behavior (fade-in from a default position)
looked arbitrary.  Expanding from the parent creates a visual connection
between the expand action and the resulting children, making the graph
exploration feel more spatial and intuitive.

---

## DD-22: Canvas renderer (no SVG option)

**Decision**: The graph uses Cytoscape.js's Canvas renderer.  There is
no SVG rendering mode.

**Rationale**: Cytoscape.js only supports Canvas for interactive
rendering.  SVG export is possible via the `cytoscape-svg` extension
for static snapshots, but the interactive graph must use Canvas.
Switching to an SVG-based library (e.g. D3) would require a full
rewrite of the visualization layer.
