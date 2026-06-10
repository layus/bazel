# Skyframe Graph Explorer — UX Specification

## Visual Theme

Dark theme (`#1a1a2e` background) with blue accents (`#0f3460`, `#3498db`).
Monospace font for the detail pane; system-ui for everything else.

---

## Layout

```
┌─────────────────────────────────────────────────────────────┐
│ Toolbar: [Refresh] [Reset] [Redraw] [Fit]  [Search…]  Stats│
├─────────────────────────────────────────────────────────────┤
│                                            ┌──────────────┐│
│                                            │ Type Filters ││
│           Cytoscape Graph                  │ (collapsible)││
│           (pan, zoom, select, drag)        │              ││
│                                            └──────────────┘│
├─────────────────────────────────────────────────────────────┤
│ Detail Pane (resizable)                                     │
│ Node info + interactive children list                       │
└─────────────────────────────────────────────────────────────┘
```

---

## Toolbar

| Button | Action |
|--------|--------|
| **Refresh** (↻) | Re-index graph from Bazel, re-fetch types, clear all state |
| **Reset** | Clear pinned/expanded sets, show empty graph |
| **Redraw** | Re-run dagre layout without changing data |
| **Fit** | Zoom to fit all visible nodes with 30px padding |
| **Search** | Text input with debounced substring search (250ms) |
| **Stats** | Shows `Visible: N | Cached: M` |

---

## Search

- Debounced input (250ms), minimum 2 characters.
- Dropdown shows up to 20 results with: label (+ short config hash),
  function type, dep count, and ✅ badge if already in view.
- Clicking a result: pins the node, expands it, centers and flashes it.
- Escape closes dropdown.  Clicking outside closes dropdown.

---

## Graph Interaction

### Node Appearance

| State | Visual |
|-------|--------|
| Default | Round rectangle, colored by function type or config (see View Controls). Label shown with left-ellipsis; short config hash (6 chars) on second line when present |
| Expanded | Yellow border (`#e8d44d`, 3px) |
| Pinned | Orange double border (`#e67e22`) + 📌 prefix in label |
| Selected | Blue border (`#3498db`, 4px) — native Cytoscape |
| Search hit | Red border (`#e74c3c`, 4px) |
| Leaf (0 deps) | 60% opacity |

### Edge Appearance

- Thin (1px), dark gray (`#34495e`), bezier curves, small triangle arrows.

### Mouse Actions

| Action | Effect |
|--------|--------|
| **Click** | Native Cytoscape: select node (additive with Shift) |
| **Drag node** | Move node |
| **Drag background** | Pan |
| **Box select** | Select multiple nodes |
| **Scroll** | Zoom (sensitivity 0.3) |
| **Double-click node** | Expand (if folded) / Fold (if expanded) |
| **Right-click node** | Open context menu |
| **Hover node** | Show tooltip |

### Context Menu (right-click)

| Item | Condition | Action |
|------|-----------|--------|
| ▶ Expand children | Not expanded, has deps | Expand node |
| ◄ Hide children | Expanded | Fold node |
| 📌 Pin | Not pinned | Pin node |
| Unpin | Pinned | Unpin node |
| ✖ Remove subtree | Always | Unpin + remove subtree |

---

## View Controls Panel

Floating overlay, top-right, above the type filter panel.  Contains
sliding toggle switches:

```
 Color by   Type  [====o] / Config
 Hide transitive   Off  [o====] / On
```

### Color by (Type / Config)

- **Type** (default, switch off): Nodes colored by function type.
  Filter swatches show their type colors.
- **Config** (switch on): Nodes colored by configuration hash.  All
  filter panel swatches become transparent.  Nodes without a config
  get dim gray (`#555555`).

Switching recolors all nodes instantly without re-layout.

### Hide transitive edges

- **Off** (default): All edges between visible nodes are shown.
- **On**: Redundant transitive edges are hidden.  An edge A→C is
  hidden if there exists an indirect path A→B→…→C through other
  visible edges (BFS reachability check).

This reduces visual clutter in dense graphs while preserving the
essential structure.

---

## Type Filter Panel

Position: top-right corner of graph area, floating overlay.

### Header Row

```
[▼] Type filters  [color swatches...]  On [✓]
```

- **▼ toggle**: Click to collapse/expand the filter list.
- **Title**: "Type filters".
- **Inline swatches**: Visible when collapsed; show enabled type colors
  in available space (overflow hidden/clipped).
- **On checkbox**: Enables/disables filtering entirely.  When off, all
  nodes are shown.  Label reads "On" or "Off".

### Expanded View

Scrollable list of all known types (from `/api/types`).  Each row:

```
[✓] [■ color] TYPE_NAME                    count
```

- Checkbox toggles type visibility.
- Color swatch matches node type color (transparent when in config
  color mode).
- Count shows how many nodes of this type are currently in the graph
  (updated on each `applyView`, blank if 0).
- Scrollbar has 6px right padding so it doesn't overlap counts.

### Collapsed View

Only the header row is shown, with enabled-type color swatches inline.

### Behavior

- **Width**: Locked to expanded-state width (widest type label) so
  collapsing doesn't cause layout shift.
- **Default**: `BUILD_DRIVER` and `CONFIGURED_TARGET` enabled; all
  others hidden.
- **Filter changes**: Immediately trigger `applyTypeFilter()` →
  `relayout()`.
- **On/Off toggle**: Affects all types at once without modifying
  individual checkbox state.

---

## Detail Pane

Position: bottom of page, resizable vertically (min 80px, max 50vh,
default 160px).

### Single Node Selected

Shows node metadata as key-value pairs:

- ID, Label, Config (if present), Type, Canonical (full raw string),
  Lifecycle, Done, Deps, Expanded, Pinned, Root

Below metadata: **Children** section.

#### Children List

- Fetched on-demand from `/api/children/{id}`.
- Lists all children (deps), not just visible ones.
- Each child row:

  ```
  [■ color] label config_short            TYPE    [Pin]
  ```

  - **Color swatch**: matches function type color.
  - **Label**: parsed label + short (6-char) config hash.  Green if
    node is in the current graph.  Tooltip shows full canonical name.
  - **Type**: function type in small gray text.
  - **Pin/Unpin button**: Pins the child (adds to graph, visible even
    if type-filtered).  If already pinned, shows "Unpin" in orange.

### Multiple Nodes Selected

Shows count and breakdown by function type with color swatches:

```
5 nodes selected
■ CONFIGURED_TARGET: 3
■ BUILD_DRIVER: 2
```

### No Selection

Shows placeholder text: "Click a node to see details".

---

## Tooltip

Fixed-position tooltip following the cursor, shown on node hover.
Contains: label (bold), type, deps, config (if present), lifecycle.
Disappears on mouseout.
