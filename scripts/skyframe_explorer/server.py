#!/usr/bin/env python3
"""Skyframe Graph Explorer — FastAPI backend.

MVC-style: this module is the Model (node cache, expanded set) and Controller
(expand/fold/reset endpoints).  The View is static/index.html.
"""

import argparse
import functools
import getpass
import os
import pathlib
import sys
from typing import Dict, List, Optional, Set

import grpc
import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

# Add parent dir so we can import the generated stubs from scripts/
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import skyframe_graph_pb2
import skyframe_graph_pb2_grpc

# ---------------------------------------------------------------------------
# Address detection (shared with skyframe_graph_dot.py)
# ---------------------------------------------------------------------------


def find_server_address(output_base: Optional[str] = None) -> Optional[str]:
    if output_base:
        port_file = os.path.join(output_base, "server", "command_port")
        if os.path.isfile(port_file):
            with open(port_file) as f:
                return f.read().strip()
        return None

    user = getpass.getuser()
    base_dir = pathlib.Path.home() / ".cache" / "bazel" / f"_bazel_{user}"
    if not base_dir.is_dir():
        return None
    candidates = []
    for d in base_dir.iterdir():
        pf = d / "server" / "command_port"
        if pf.is_file():
            try:
                addr = pf.read_text().strip()
                if addr:
                    candidates.append((d.name, addr))
            except OSError:
                pass
    if len(candidates) == 1:
        return candidates[0][1]
    if len(candidates) > 1:
        print("Multiple Bazel servers found:", file=sys.stderr)
        for name, addr in candidates:
            print(f"  {name}  ->  {addr}", file=sys.stderr)
        print("Use --output_base or --address.", file=sys.stderr)
    return None


def grpc_address(address: str) -> str:
    """Ensure IPv6 bracket addresses get the ipv6: prefix for gRPC Python."""
    if address.startswith("[") and not address.startswith("ipv6:"):
        return "ipv6:" + address
    return address


# ---------------------------------------------------------------------------
# Model
# ---------------------------------------------------------------------------


class GraphModel:
    """Holds cached node data and the set of expanded node IDs."""

    def __init__(self, stub: skyframe_graph_pb2_grpc.SkyframeGraphServiceStub):
        self.stub = stub
        self.node_cache: Dict[int, dict] = {}
        self.root_ids: List[int] = []
        self.expanded: Set[int] = set()

    # -- gRPC helpers -------------------------------------------------------

    ALL_FIELDS = skyframe_graph_pb2.NodeFieldFilter(
        include_canonical_name=True,
        include_function_type=True,
        include_direct_deps=True,
        include_lifecycle_state=True,
    )

    @staticmethod
    def _proto_to_dict(n) -> dict:
        return {
            "id": n.id,
            "label": n.canonical_name or f"node-{n.id}",
            "function_type": n.function_type,
            "dep_ids": list(n.direct_dep_ids),
            "lifecycle_state": n.lifecycle_state,
            "is_done": n.is_done,
            "keeps_edges": n.keeps_edges,
        }

    def refresh(self) -> dict:
        resp = self.stub.RefreshIndex(skyframe_graph_pb2.RefreshIndexRequest())
        self.node_cache.clear()
        self.expanded.clear()
        self.clear_search_cache()
        # Fetch roots
        roots = list(
            self.stub.GetRoots(
                skyframe_graph_pb2.GetRootsRequest(filter=self.ALL_FIELDS)
            )
        )
        self.root_ids = []
        for r in roots:
            d = self._proto_to_dict(r)
            self.node_cache[d["id"]] = d
            self.root_ids.append(d["id"])
        return {
            "total_node_count": resp.total_node_count,
            "root_count": resp.root_count,
        }

    def search(self, query: str, max_results: int = 20) -> dict:
        """Search nodes via gRPC SearchNodes RPC. Results cached with LRU(1000)."""
        return self._search_cached(query, max_results)

    @functools.lru_cache(maxsize=1000)
    def _search_cached(self, query: str, max_results: int) -> dict:
        resp = self.stub.SearchNodes(
            skyframe_graph_pb2.SearchNodesRequest(
                query=query,
                max_results=max_results,
                filter=self.ALL_FIELDS,
            )
        )
        nodes = []
        for n in resp.nodes:
            d = self._proto_to_dict(n)
            self.node_cache[d["id"]] = d
            nodes.append(
                {
                    "id": d["id"],
                    "label": d["label"],
                    "function_type": d["function_type"],
                    "is_done": d["is_done"],
                    "dep_count": len(d["dep_ids"]),
                    "lifecycle_state": d["lifecycle_state"],
                }
            )
        return {
            "nodes": nodes,
            "total_matches": resp.total_matches,
            "has_more": resp.has_more,
        }

    def clear_search_cache(self):
        self._search_cached.cache_clear()

    def _ensure_children(self, node_id: int):
        """Fetch children of node_id if not already cached."""
        node = self.node_cache.get(node_id)
        if node is None:
            return
        to_fetch = [did for did in node["dep_ids"] if did not in self.node_cache]
        if not to_fetch:
            return
        resp = self.stub.GetNodes(
            skyframe_graph_pb2.GetNodesRequest(
                ids=to_fetch,
                filter=self.ALL_FIELDS,
            )
        )
        for child in resp.nodes:
            d = self._proto_to_dict(child)
            self.node_cache[d["id"]] = d

    # -- State mutations ----------------------------------------------------

    def expand(self, node_id: int):
        if node_id not in self.node_cache:
            raise KeyError(f"Node {node_id} not in cache")
        self._ensure_children(node_id)
        self.expanded.add(node_id)

    def fold(self, node_id: int):
        if node_id not in self.expanded:
            return
        self.expanded.discard(node_id)
        # Recursively fold descendants that are no longer reachable.
        self._prune_unreachable()

    def reset(self):
        self.expanded.clear()

    def _prune_unreachable(self):
        """Remove from expanded any node not reachable from roots via expanded chain."""
        reachable: Set[int] = set()
        stack = list(self.root_ids)
        while stack:
            nid = stack.pop()
            if nid in reachable:
                continue
            reachable.add(nid)
            if nid in self.expanded:
                node = self.node_cache.get(nid)
                if node:
                    stack.extend(node["dep_ids"])
        self.expanded &= reachable

    # -- View computation ---------------------------------------------------

    def compute_view(self) -> dict:
        """Return the full set of visible nodes + edges."""
        visible_ids: Set[int] = set()
        # Roots are always visible.
        visible_ids.update(self.root_ids)
        # Children of expanded nodes are visible.
        for nid in list(self.expanded):
            node = self.node_cache.get(nid)
            if node:
                for dep_id in node["dep_ids"]:
                    visible_ids.add(dep_id)

        root_set = set(self.root_ids)
        nodes = []
        for nid in sorted(visible_ids):
            nd = self.node_cache.get(nid)
            if nd is None:
                continue
            nodes.append(
                {
                    "id": nd["id"],
                    "label": nd["label"],
                    "function_type": nd["function_type"],
                    "is_done": nd["is_done"],
                    "dep_count": len(nd["dep_ids"]),
                    "expanded": nid in self.expanded,
                    "is_root": nid in root_set,
                    "lifecycle_state": nd["lifecycle_state"],
                }
            )

        edges = []
        for nid in self.expanded:
            nd = self.node_cache.get(nid)
            if nd is None:
                continue
            for dep_id in nd["dep_ids"]:
                if dep_id in visible_ids:
                    edges.append({"source": nid, "target": dep_id})

        return {
            "nodes": nodes,
            "edges": edges,
            "stats": {
                "total_visible": len(nodes),
                "total_cached": len(self.node_cache),
            },
        }

    def compute_view_with_delta(self, old_ids: Set[int]) -> dict:
        view = self.compute_view()
        new_ids = {n["id"] for n in view["nodes"]}
        view["added_node_ids"] = sorted(new_ids - old_ids)
        view["removed_node_ids"] = sorted(old_ids - new_ids)
        return view


# ---------------------------------------------------------------------------
# FastAPI app
# ---------------------------------------------------------------------------

app = FastAPI(title="Skyframe Graph Explorer")

# Will be set in main()
model: Optional[GraphModel] = None

STATIC_DIR = os.path.join(os.path.dirname(__file__), "static")


@app.get("/")
async def index():
    return FileResponse(os.path.join(STATIC_DIR, "index.html"))


@app.post("/api/refresh")
async def api_refresh():
    stats = model.refresh()
    view = model.compute_view()
    view["refresh_stats"] = stats
    view["added_node_ids"] = [n["id"] for n in view["nodes"]]
    view["removed_node_ids"] = []
    return view


@app.get("/api/view")
async def api_view():
    view = model.compute_view()
    view["added_node_ids"] = []
    view["removed_node_ids"] = []
    return view


@app.post("/api/expand/{node_id}")
async def api_expand(node_id: int):
    if node_id not in model.node_cache:
        raise HTTPException(404, f"Node {node_id} not in cache")
    old_ids = {n["id"] for n in model.compute_view()["nodes"]}
    model.expand(node_id)
    return model.compute_view_with_delta(old_ids)


@app.post("/api/fold/{node_id}")
async def api_fold(node_id: int):
    old_ids = {n["id"] for n in model.compute_view()["nodes"]}
    model.fold(node_id)
    return model.compute_view_with_delta(old_ids)


@app.post("/api/reset")
async def api_reset():
    old_ids = {n["id"] for n in model.compute_view()["nodes"]}
    model.reset()
    return model.compute_view_with_delta(old_ids)


@app.get("/api/search")
async def api_search(q: str, max_results: int = 20):
    if not q or not q.strip():
        raise HTTPException(400, "query must not be empty")
    return model.search(q.strip(), min(max_results, 100))


# Serve static assets (CSS, JS if any)
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------


def main():
    global model

    parser = argparse.ArgumentParser(description="Skyframe Graph Explorer")
    parser.add_argument("address", nargs="?", help="gRPC host:port")
    parser.add_argument("--output_base", default=None)
    parser.add_argument("--port", type=int, default=8050)
    parser.add_argument("--host", default="127.0.0.1")
    args = parser.parse_args()

    address = args.address or find_server_address(args.output_base)
    if not address:
        print("ERROR: Could not determine Bazel server address.", file=sys.stderr)
        print("Pass it explicitly or use --output_base.", file=sys.stderr)
        sys.exit(1)

    address = grpc_address(address)
    print(f"Connecting to Bazel gRPC at {address}", file=sys.stderr)
    channel = grpc.insecure_channel(address)
    stub = skyframe_graph_pb2_grpc.SkyframeGraphServiceStub(channel)

    model = GraphModel(stub)
    stats = model.refresh()
    print(
        f"  {stats['total_node_count']} nodes, {stats['root_count']} roots",
        file=sys.stderr,
    )
    print(f"  {len(model.root_ids)} roots cached", file=sys.stderr)

    print(f"\nStarting server at http://{args.host}:{args.port}", file=sys.stderr)
    uvicorn.run(app, host=args.host, port=args.port, log_level="warning")


if __name__ == "__main__":
    main()
