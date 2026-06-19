# 🔭 Skyscrape

**An interactive explorer for Bazel's Skyframe dependency graph.**

Skyscrape connects to a running Bazel server and lets you search, pin, expand,
and navigate the in-memory Skyframe graph — right in your browser.

https://github.com/user-attachments/assets/0342d0e0-a7a3-46b1-8a5b-01e7bb1cc6b3

---

## 🏙️ What It Does

Bazel's Skyframe graph can contain **100 000+ nodes**. Skyscrape gives you a
focused, on-demand view:

- **Search** for any node by name
- **Pin** 📌 nodes to anchor your exploration
- **Expand / Fold** subtrees interactively
- **Filter by type** — only see `CONFIGURED_TARGET`, `BUILD_DRIVER`, or any
  combination
- **Visualize** the dependency DAG with an auto-laid-out 📊 graph
  (Cytoscape.js + dagre)

No need to dump the whole graph — explore exactly what matters to you. ⭐

---

## 🏢 Architecture

```
┌──────────────┐  gRPC   ┌──────────────────┐  HTTPS   ┌──────────────┐
│ Bazel Server │◄───────►│  server.py        │◄───────►│  Browser     │
│ (Skyframe    │         │  (FastAPI)        │         │ (Cytoscape)  │
│  GraphService)│         └──────────────────┘         └──────────────┘
└──────────────┘
```

| Layer | Technology |
|-------|------------|
| Data source | Bazel's in-process gRPC `SkyframeGraphService` |
| Backend | Python 3 — FastAPI + uvicorn |
| Frontend | Single-page HTML/JS — Cytoscape.js + dagre layout |
| Transport | HTTPS (self-signed cert, auto-generated) |

---

## 🚀 Quick Start

### Prerequisites

- A running Bazel server with the Skyframe graph gRPC service enabled
- Python 3
- `pip install -r requirements.txt` (FastAPI, uvicorn, grpcio)

### Run

```bash
cd scripts/skyscrape
python3 server.py [host:port]
```

Skyscrape auto-detects the Bazel server address from your workspace if you
don't provide one explicitly. You can also point to a specific output base:

```bash
python3 server.py --output_base /path/to/output_base --port 8050
```

Then open **https://localhost:8050** in your browser.

### Development mode

```bash
python3 server.py --reload
```

This enables uvicorn file-watching so changes to `server.py` or `static/`
are picked up automatically.

---

## 📊 How to Use

1. **Refresh** — indexes the Skyframe graph from the running Bazel server
2. **Search** — find nodes by canonical name (debounced, substring match)
3. **Pin** — click a search result to pin it into the view
4. **Expand** — double-click a node to reveal its dependencies
5. **Fold / Remove** — right-click a node for the context menu
6. **Filter** — toggle node types in the side panel to reduce noise

---

## ⭐ File Layout

```
scripts/skyscrape/
├── README.md              ← you are here
├── server.py              # Backend (FastAPI + GraphModel)
├── requirements.txt       # Python dependencies
├── static/
│   └── index.html         # Frontend (all-in-one HTML/CSS/JS)
└── docs/
    ├── ARCHITECTURE.md    # Detailed architecture
    ├── DESIGN_DECISIONS.md
    ├── INVARIANTS.md
    └── UX.md              # Visual & interaction spec
```
