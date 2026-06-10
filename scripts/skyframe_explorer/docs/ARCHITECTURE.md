# Skyframe Graph Explorer — Architecture

## Overview

A web application for interactively exploring Bazel's in-memory Skyframe
dependency graph.  The user searches for nodes, pins them, expands their
children, and navigates the resulting subgraph visually.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Graph data source | Bazel's in-process gRPC service (`SkyframeGraphService`) |
| Backend | Python 3 — FastAPI + uvicorn |
| Frontend | Single-page HTML/JS — Cytoscape.js + dagre layout |
| Transport | HTTPS (self-signed cert generated at startup) |
| Proto stubs | `skyframe_graph_pb2` / `skyframe_graph_pb2_grpc` (generated) |

## Component Diagram

```
┌──────────────┐  gRPC   ┌──────────────────┐  HTTPS/REST  ┌──────────────┐
│ Bazel Server │◄───────►│  server.py        │◄────────────►│ index.html   │
│ (Skyframe    │         │  (FastAPI)        │              │ (Cytoscape)  │
│  GraphService)│         │                  │              │              │
└──────────────┘         │  GraphModel       │              │  Browser     │
                         │  ├─ node_cache    │              │              │
                         │  ├─ pinned set    │              │              │
                         │  ├─ expanded set  │              │              │
                         │  └─ type list     │              │              │
                         └──────────────────┘              └──────────────┘
```

## Data Flow

1. **Startup**: Backend calls `RefreshIndex` RPC → indexes all nodes.
   Fetches root nodes into cache.  Collects all distinct `function_type`
   values from the full graph via `ListNodes`.

2. **Frontend init**: Fetches `/api/types` (fixed list of all function types),
   then `/api/view` (current visible subgraph).

3. **User interaction** (search, expand, fold, pin, etc.):
   - Frontend calls a REST endpoint (e.g. `POST /api/expand/{id}`).
   - Backend mutates `GraphModel` state (pinned/expanded sets), fetches
     children via gRPC if needed, computes the new view, returns it with
     a delta (added/removed node IDs).
   - Frontend applies the delta to the Cytoscape graph and re-runs layout.

## Backend: `server.py`

### `GraphModel` (Model)

Holds all server-side state:

- **`node_cache`** — `Dict[int, dict]`: all nodes ever fetched from gRPC.
  Keyed by integer node ID.  Grows monotonically (never evicted except on
  refresh).
- **`pinned`** — `Set[int]`: nodes explicitly added to the view by the user
  (via search, pin action, etc.).  These are the roots of visible subgraphs.
- **`expanded`** — `Set[int]`: nodes whose children are visible.
- **`root_ids`** — `List[int]`: Skyframe graph root nodes (zero reverse deps).
- **`all_function_types`** — `List[str]`: sorted list of all function type
  strings in the indexed graph.

Node dicts in the cache contain parsed fields from `_parse_canonical_name()`:
`canonical_name` (full raw string), `argument` (after `TYPE:` prefix),
`label` (extracted from `label=...` or full argument), `config` (short
8-char hash from `config=BuildConfigurationKey[...]`, empty if none).

Key methods:

| Method | Effect |
|--------|--------|
| `refresh()` | Re-index via gRPC, clear all state, reload roots and types |
| `search(q)` | Search by canonical name substring (LRU cached) |
| `pin(id)` | Add node to pinned set (fetch if needed) |
| `unpin(id)` | Remove from pinned, prune unreachable |
| `expand(id)` | Fetch children, add to expanded set |
| `fold(id)` | Remove from expanded, prune unreachable |
| `remove(id)` | Unpin + unexpand, prune subtree |
| `reset()` | Clear pinned and expanded sets |
| `compute_view()` | Return all visible nodes + edges as JSON |

### REST API (Controller)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | GET | Serve `index.html` |
| `/api/types` | GET | All known function types |
| `/api/view` | GET | Current visible graph |
| `/api/children/{id}` | GET | All children of a node (for detail pane) |
| `/api/refresh` | POST | Re-index + return new view |
| `/api/expand/{id}` | POST | Expand node, return view with delta |
| `/api/fold/{id}` | POST | Fold node, return view with delta |
| `/api/pin/{id}` | POST | Pin node, return view with delta |
| `/api/unpin/{id}` | POST | Unpin node, return view with delta |
| `/api/remove/{id}` | POST | Remove subtree, return view with delta |
| `/api/reset` | POST | Clear all, return view with delta |
| `/api/search?q=…` | GET | Search nodes by canonical name |

### CLI

```
python3 server.py [address] --output_base PATH --port 8050 --host 0.0.0.0 --reload
```

- Auto-detects Bazel server address from `output_base` if not provided.
- Generates a self-signed TLS certificate at startup.
- `--reload` enables uvicorn file watching for development.

## Frontend: `static/index.html`

Single HTML file containing all CSS, markup, and JavaScript.

### Key Subsystems

- **Cytoscape.js graph** — dagre layout (left-to-right), interactive pan/zoom/select/drag.
- **Type filter panel** — fixed list of all types fetched at startup, collapsible, with global on/off toggle.
- **Detail pane** — bottom panel showing selected node info + interactive children list.
- **Search** — debounced substring search with dropdown results.
- **Context menu** — right-click menu for expand/fold/pin/unpin/remove.
- **Toolbar** — Refresh, Reset, Redraw, Fit buttons.

## File Layout

```
scripts/skyframe_explorer/
├── server.py                 # Backend (Model + Controller)
├── static/
│   └── index.html            # Frontend (View) — all-in-one HTML/CSS/JS
└── docs/
    ├── ARCHITECTURE.md        # This file
    ├── DESIGN_DECISIONS.md    # Rationale for key choices
    ├── INVARIANTS.md          # System invariants and rules
    └── UX.md                  # Interaction model and visual design
```
