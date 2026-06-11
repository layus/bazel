#!/usr/bin/env python3
"""Skyframe Graph Explorer — FastAPI backend.

MVC-style: this module is the Model (node cache, expanded set) and Controller
(expand/fold/reset endpoints).  The View is static/index.html.
"""

import argparse
from contextlib import asynccontextmanager
import functools
import getpass
import hashlib
import os
import pathlib
import re
import subprocess
import sys
import tempfile
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


def find_workspace_root(cwd: Optional[str] = None) -> Optional[str]:
    """Find the Bazel workspace root by walking up from CWD to find MODULE.bazel.
    
    Returns the absolute path to the workspace directory, or None if not found.
    """
    if cwd is None:
        cwd = os.getcwd()
    
    current = os.path.abspath(cwd)
    while True:
        module_bazel = os.path.join(current, "MODULE.bazel")
        if os.path.isfile(module_bazel):
            return current
        
        parent = os.path.dirname(current)
        if parent == current:  # reached root
            break
        current = parent
    
    return None


def compute_output_base_hash(workspace_path: str) -> str:
    """Compute the Bazel output base directory hash for a workspace path.
    
    Bazel uses an MD5 hash of the canonical workspace path to name the output
    base directory in ~/.cache/bazel/_bazel_<user>/.
    """
    # Normalize the path to use forward slashes and remove trailing slash
    normalized = workspace_path.replace("\\", "/").rstrip("/")
    # Compute MD5 hash
    return hashlib.md5(normalized.encode()).hexdigest()


def find_server_address(output_base: Optional[str] = None) -> str:
    """Find the Bazel server address from the command_port file.

    If output_base is provided, look there. Otherwise, detect from workspace
    and find the corresponding output_base directory.

    Exits with error if the file cannot be found.
    """
    # Determine port_file location
    if not output_base:
        # Detect workspace and find corresponding output_base
        workspace_root = find_workspace_root()
        if not workspace_root:
            print("ERROR: No MODULE.bazel found in current directory or parent directories.", file=sys.stderr)
            print("  Are you in a Bazel workspace?", file=sys.stderr)
            sys.exit(1)

        workspace_hash = compute_output_base_hash(workspace_root)
        user = getpass.getuser()
        base_dir = pathlib.Path.home() / ".cache" / "bazel" / f"_bazel_{user}"
        output_base = str(base_dir / workspace_hash)

    port_file = pathlib.Path(output_base) / "server" / "command_port"

    # Read and return the address
    if not port_file.is_file():
        print(f"ERROR: server/command_port not found at {port_file}", file=sys.stderr)
        print("  Make sure Bazel is running with the skyframe graph server enabled.", file=sys.stderr)
        sys.exit(1)

    try:
        return port_file.read_text().strip()
    except OSError as e:
        print(f"ERROR: Failed to read {port_file}: {e}", file=sys.stderr)
        sys.exit(1)


def grpc_address(address: str) -> str:
    """Ensure IPv6 bracket addresses get the ipv6: prefix for gRPC Python."""
    if address.startswith("[") and not address.startswith("ipv6:"):
        return "ipv6:" + address
    return address


# ---------------------------------------------------------------------------
# Model
# ---------------------------------------------------------------------------


class GraphModel:
    """Holds cached node data, pinned nodes, and the set of expanded node IDs."""

    def __init__(self, stub: skyframe_graph_pb2_grpc.SkyframeGraphServiceStub):
        self.stub = stub
        self.node_cache: Dict[int, dict] = {}
        self.root_ids: List[int] = []
        self.pinned: Set[int] = set()  # nodes explicitly added to the view
        self.expanded: Set[int] = set()
        self.all_function_types: List[str] = []

    # -- gRPC helpers -------------------------------------------------------

    ALL_FIELDS = skyframe_graph_pb2.NodeFieldFilter(
        include_canonical_name=True,
        include_function_type=True,
        include_direct_deps=True,
        include_lifecycle_state=True,
    )

    _LABEL_RE = re.compile(r"label=([^,}]+)")
    _CONFIG_RE = re.compile(r"config=(\S+)")

    @staticmethod
    def _parse_canonical_name(canonical_name: str, function_type: str) -> dict:
        """Parse a canonical name into components.

        Examples of canonical names from the Skyframe gRPC API:
          BUILD_DRIVER:BuildDriverKey of ActionLookupKey: ConfiguredTargetKey{label=//src:bazel-dev, config=BuildConfigurationKey[b56206e...]}
          CONFIGURED_TARGET:ConfiguredTargetKey{label=//src:bazel-dev, config=BuildConfigurationKey[b56206e...]}
          FILE:/some/path/to/file.txt

        Returns dict with keys: canonical_name, argument, label, config.
        - argument: everything after the 'TYPE:' prefix
        - label: extracted from label=... if present, otherwise the argument
        - config: extracted from config=... if present (short hash), otherwise empty
        """
        raw = canonical_name or ""
        # Strip the function_type prefix if present
        prefix = function_type + ":"
        if raw.startswith(prefix):
            argument = raw[len(prefix) :]
        else:
            argument = raw

        # Try to extract label=... from structured key strings
        label = argument
        config = ""
        m_label = GraphModel._LABEL_RE.search(argument)
        if m_label:
            label = m_label.group(1).strip()
        m_config = GraphModel._CONFIG_RE.search(argument)
        if m_config:
            cfg = m_config.group(1)
            # Treat null as no config
            if cfg.rstrip("]}") == "null":
                config = ""
            else:
                # Extract just the hash from BuildConfigurationKey[hash...]
                bracket = cfg.find("[")
                if bracket >= 0:
                    config = cfg[bracket + 1 :].rstrip("]}")
                    # Shorten to first 8 chars for display
                    if len(config) > 8:
                        config = config[:8]
                else:
                    config = cfg

        return {
            "canonical_name": raw,
            "argument": argument,
            "label": label,
            "config": config,
        }

    @staticmethod
    def _proto_to_dict(n) -> dict:
        parsed = GraphModel._parse_canonical_name(
            n.canonical_name or f"node-{n.id}", n.function_type
        )
        return {
            "id": n.id,
            "canonical_name": parsed["canonical_name"],
            "argument": parsed["argument"],
            "label": parsed["label"],
            "config": parsed["config"],
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
        self.pinned.clear()
        self.clear_search_cache()
        # Fetch roots into cache (but don't pin them — user searches to find them).
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
        # Collect all distinct function types from the full graph
        type_filter = skyframe_graph_pb2.NodeFieldFilter(
            include_function_type=True,
        )
        types_seen: Set[str] = set()
        for node in self.stub.ListNodes(
            skyframe_graph_pb2.ListNodesRequest(filter=type_filter)
        ):
            if node.function_type:
                types_seen.add(node.function_type)
        self.all_function_types = sorted(types_seen)

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
                    "canonical_name": d["canonical_name"],
                    "label": d["label"],
                    "config": d["config"],
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

    def pin(self, node_id: int):
        """Pin a node so it appears in the view (e.g. from search results)."""
        if node_id not in self.node_cache:
            # Fetch it
            resp = self.stub.GetNodes(
                skyframe_graph_pb2.GetNodesRequest(
                    ids=[node_id],
                    filter=self.ALL_FIELDS,
                )
            )
            for n in resp.nodes:
                d = self._proto_to_dict(n)
                self.node_cache[d["id"]] = d
        self.pinned.add(node_id)

    def expand(self, node_id: int):
        if node_id not in self.node_cache:
            raise KeyError(f"Node {node_id} not in cache")
        self._ensure_children(node_id)
        self.expanded.add(node_id)

    def fold(self, node_id: int):
        """Collapse children of node_id. The node stays visible, children hidden
        unless they are reachable via another path from a pin."""
        if node_id not in self.expanded:
            return
        self.expanded.discard(node_id)
        self._prune_unreachable()

    def remove(self, node_id: int):
        """Unpin a node and prune its subtree down to the next pinned descendant."""
        self.expanded.discard(node_id)
        self.pinned.discard(node_id)
        self._prune_unreachable()

    def unpin(self, node_id: int):
        """Remove pin from a node. If it is still reachable from another pin
        it stays visible; otherwise its subtree is pruned."""
        self.pinned.discard(node_id)
        self._prune_unreachable()

    def reset(self):
        self.expanded.clear()
        self.pinned.clear()

    def _prune_unreachable(self):
        """Remove from expanded any node not reachable from a pinned node
        via the expanded chain.  Invariant: every visible subgraph is
        rooted by a pin."""
        reachable: Set[int] = set()
        stack = list(self.pinned)
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
        """Return the full set of visible nodes + edges.

        Invariant: if two visible nodes are connected in the underlying
        graph (parent has child as dep), the edge is shown."""
        visible_ids: Set[int] = set()
        # Pinned nodes are always visible.
        visible_ids.update(self.pinned)
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
                    "canonical_name": nd["canonical_name"],
                    "label": nd["label"],
                    "config": nd["config"],
                    "function_type": nd["function_type"],
                    "is_done": nd["is_done"],
                    "dep_count": len(nd["dep_ids"]),
                    "expanded": nid in self.expanded,
                    "is_root": nid in root_set,
                    "is_pinned": nid in self.pinned,
                    "is_folded": nid not in self.expanded and len(nd["dep_ids"]) > 0,
                    "lifecycle_state": nd["lifecycle_state"],
                }
            )

        # Edges: show ALL edges between visible nodes, not just from
        # expanded parents.  This preserves the invariant that connected
        # visible nodes always appear connected.
        edge_set: Set[tuple] = set()
        for nid in visible_ids:
            nd = self.node_cache.get(nid)
            if nd is None:
                continue
            for dep_id in nd["dep_ids"]:
                if dep_id in visible_ids:
                    edge_set.add((nid, dep_id))
        edges = [{"source": s, "target": t} for s, t in edge_set]

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

# Will be set in main() or on startup (reload mode)
model: Optional[GraphModel] = None

STATIC_DIR = os.path.join(os.path.dirname(__file__), "static")


@asynccontextmanager
async def lifespan(app):
    global model
    if model is None:
        addr = os.environ.get("SKYFRAME_GRPC_ADDRESS")
        if addr:
            channel = grpc.insecure_channel(addr)
            stub = skyframe_graph_pb2_grpc.SkyframeGraphServiceStub(channel)
            model = GraphModel(stub)
            stats = model.refresh()
            print(
                f"  [reload] {stats['total_node_count']} nodes, {stats['root_count']} roots",
                file=sys.stderr,
            )
    yield


app = FastAPI(title="Skyframe Graph Explorer", lifespan=lifespan)


@app.get("/")
async def index():
    return FileResponse(
        os.path.join(STATIC_DIR, "index.html"),
        headers={"Cache-Control": "no-cache, no-store, must-revalidate"},
    )


@app.post("/api/refresh")
async def api_refresh():
    stats = model.refresh()
    view = model.compute_view()
    view["refresh_stats"] = stats
    view["added_node_ids"] = [n["id"] for n in view["nodes"]]
    view["removed_node_ids"] = []
    return view


@app.get("/api/types")
async def api_types():
    return {"types": model.all_function_types}


@app.get("/api/children/{node_id}")
async def api_children(node_id: int):
    """Return all children of a node, fetching them if not cached."""
    if node_id not in model.node_cache:
        raise HTTPException(404, f"Node {node_id} not in cache")
    model._ensure_children(node_id)
    nd = model.node_cache[node_id]
    visible_ids = {n["id"] for n in model.compute_view()["nodes"]}
    children = []
    for dep_id in nd["dep_ids"]:
        child = model.node_cache.get(dep_id)
        if child:
            children.append(
                {
                    "id": child["id"],
                    "canonical_name": child["canonical_name"],
                    "label": child["label"],
                    "config": child["config"],
                    "function_type": child["function_type"],
                    "dep_count": len(child["dep_ids"]),
                    "is_pinned": dep_id in model.pinned,
                    "in_graph": dep_id in visible_ids,
                }
            )
    return {"children": children}


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


@app.post("/api/remove/{node_id}")
async def api_remove(node_id: int):
    old_ids = {n["id"] for n in model.compute_view()["nodes"]}
    model.remove(node_id)
    return model.compute_view_with_delta(old_ids)


@app.post("/api/pin/{node_id}")
async def api_pin(node_id: int):
    old_ids = {n["id"] for n in model.compute_view()["nodes"]}
    model.pin(node_id)
    return model.compute_view_with_delta(old_ids)


@app.post("/api/unpin/{node_id}")
async def api_unpin(node_id: int):
    old_ids = {n["id"] for n in model.compute_view()["nodes"]}
    model.unpin(node_id)
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
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument(
        "--reload", action="store_true", help="Auto-reload on source changes"
    )
    args = parser.parse_args()

    address = args.address or find_server_address(args.output_base)

    address = grpc_address(address)
    print(f"Connecting to Bazel gRPC at {address}", file=sys.stderr)

    # Store address for reload subprocess
    os.environ["SKYFRAME_GRPC_ADDRESS"] = address

    if not args.reload:
        channel = grpc.insecure_channel(address)
        stub = skyframe_graph_pb2_grpc.SkyframeGraphServiceStub(channel)
        model = GraphModel(stub)
        stats = model.refresh()
        print(
            f"  {stats['total_node_count']} nodes, {stats['root_count']} roots",
            file=sys.stderr,
        )
        print(f"  {len(model.root_ids)} roots cached", file=sys.stderr)

    # Generate self-signed certificate
    cert_dir = tempfile.mkdtemp(prefix="skyframe_explorer_")
    cert_file = os.path.join(cert_dir, "cert.pem")
    key_file = os.path.join(cert_dir, "key.pem")
    subprocess.run(
        [
            "openssl",
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-keyout",
            key_file,
            "-out",
            cert_file,
            "-days",
            "365",
            "-nodes",
            "-subj",
            "/CN=skyframe-explorer",
        ],
        check=True,
        capture_output=True,
    )
    print(f"  TLS cert: {cert_file}", file=sys.stderr)

    print(f"\nStarting server at https://{args.host}:{args.port}", file=sys.stderr)
    uvicorn.run(
        "server:app" if args.reload else app,
        host=args.host,
        port=args.port,
        log_level="warning",
        ssl_certfile=cert_file,
        ssl_keyfile=key_file,
        reload=args.reload,
        reload_dirs=[os.path.dirname(__file__)] if args.reload else None,
    )


if __name__ == "__main__":
    main()
