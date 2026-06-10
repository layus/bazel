#!/usr/bin/env python3
"""Exercise the SkyframeGraphService.

Modes:
  dot (default)   - Print roots and their children in DOT/Graphviz format.
  interactive (-i) - Drill down the graph interactively, picking nodes from
                     numbered lists starting at the roots.

Usage:
  # First, generate the Python gRPC stubs from the proto:
  #   python3 -m grpc_tools.protoc \
  #       -Isrc/main/protobuf \
  #       --python_out=. --grpc_python_out=. \
  #       src/main/protobuf/skyframe_graph.proto
  #
  # Then run a Bazel build so the graph is populated, and:
  #   python3 scripts/skyframe_graph_dot.py [host:port]
  #   python3 scripts/skyframe_graph_dot.py -i [host:port]
  #
  # If no address is given, reads it from the output_base server dir.

Prerequisites:
  pip install grpcio grpcio-tools
"""

import argparse
import os
import sys

import grpc

# The generated stubs are expected in the working directory.
import skyframe_graph_pb2
import skyframe_graph_pb2_grpc


def find_server_address(output_base=None):
    """Read the Bazel server address from the command_port file.

    If output_base is not given, tries to find it under
    ~/.cache/bazel/_bazel_<user>/ by looking for a running server.
    """
    if output_base:
        port_file = os.path.join(output_base, "server", "command_port")
        if os.path.isfile(port_file):
            with open(port_file) as f:
                return f.read().strip()
        return None

    # Search for output bases with an active command_port file.
    import getpass
    import pathlib

    user = getpass.getuser()
    base_dir = pathlib.Path.home() / ".cache" / "bazel" / f"_bazel_{user}"
    if not base_dir.is_dir():
        return None
    candidates = []
    for d in base_dir.iterdir():
        port_file = d / "server" / "command_port"
        if port_file.is_file():
            try:
                addr = port_file.read_text().strip()
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
        print("Use --output_base or pass the address explicitly.", file=sys.stderr)
        return None
    return None


def sanitize_label(name):
    """Escape special characters for DOT labels."""
    return name.replace("\\", "\\\\").replace('"', '\\"')


def connect(args):
    """Parse address, connect, refresh index, and return the stub."""
    address = args.address or find_server_address(args.output_base)
    if not address:
        print("ERROR: Could not determine Bazel server address.", file=sys.stderr)
        print("Pass it explicitly: skyframe_graph_dot.py [::1]:12345", file=sys.stderr)
        sys.exit(1)

    # gRPC Python needs 'ipv6:[::1]:port' for IPv6 bracket addresses.
    if address.startswith("[") and not address.startswith("ipv6:"):
        address = "ipv6:" + address
    print(f"Connecting to {address}", file=sys.stderr)
    channel = grpc.insecure_channel(address)
    stub = skyframe_graph_pb2_grpc.SkyframeGraphServiceStub(channel)

    print("Refreshing index...", file=sys.stderr)
    refresh_resp = stub.RefreshIndex(skyframe_graph_pb2.RefreshIndexRequest())
    print(
        f"  {refresh_resp.total_node_count} nodes, {refresh_resp.root_count} roots",
        file=sys.stderr,
    )
    return stub


# ---------------------------------------------------------------------------
# Mode: DOT
# ---------------------------------------------------------------------------


def mode_dot(stub, args):
    """Fetch roots + BFS children and emit Graphviz DOT to stdout."""
    roots = list(stub.GetRoots(skyframe_graph_pb2.GetRootsRequest()))
    print(f"  Streaming {len(roots)} root nodes...", file=sys.stderr)

    visited = {}
    for r in roots:
        visited[r.id] = r

    frontier = [r.id for r in roots]
    for depth in range(args.depth):
        next_frontier = []
        to_fetch = []
        for nid in frontier:
            node = visited[nid]
            for dep_id in node.direct_dep_ids:
                if dep_id not in visited:
                    to_fetch.append(dep_id)
        if not to_fetch:
            break
        print(
            f"  Depth {depth + 1}: fetching {len(to_fetch)} children...",
            file=sys.stderr,
        )
        resp = stub.GetNodes(skyframe_graph_pb2.GetNodesRequest(ids=to_fetch))
        for child in resp.nodes:
            visited[child.id] = child
            next_frontier.append(child.id)
        frontier = next_frontier

    root_ids = {r.id for r in roots}
    print("digraph skyframe {")
    print("  rankdir=LR;")
    print('  node [shape=box, fontsize=10, fontname="monospace"];')
    for nid, node in sorted(visited.items()):
        label = sanitize_label(node.canonical_name)
        color = "lightblue" if nid in root_ids else "white"
        print(f'  n{nid} [label="{label}", style=filled, fillcolor={color}];')
        for dep_id in node.direct_dep_ids:
            if dep_id in visited:
                print(f"  n{nid} -> n{dep_id};")
    print("}")


# ---------------------------------------------------------------------------
# Mode: Interactive drill-down
# ---------------------------------------------------------------------------


def pick_from_list(nodes, prompt="Pick a node"):
    """Display a numbered list of nodes and let the user pick one.

    Returns the chosen NodeInfo, or None if the user types 'q' or hits Ctrl-D.
    """
    if not nodes:
        print("  (no nodes)")
        return None
    for i, n in enumerate(nodes):
        state = "done" if n.is_done else "NOT done"
        deps = len(n.direct_dep_ids)
        print(f"  [{i:>4}]  {n.canonical_name}  ({state}, {deps} deps)")
    while True:
        try:
            raw = input(f"{prompt} [0-{len(nodes) - 1}] (q to quit): ")
        except (EOFError, KeyboardInterrupt):
            print()
            return None
        raw = raw.strip()
        if raw.lower() == "q":
            return None
        try:
            idx = int(raw)
            if 0 <= idx < len(nodes):
                return nodes[idx]
        except ValueError:
            pass
        print(f"  Invalid choice. Enter a number 0-{len(nodes) - 1} or 'q'.")


def mode_interactive(stub):
    """Interactive drill-down starting from the graph roots."""
    print("\n=== Roots ===")
    roots = list(stub.GetRoots(skyframe_graph_pb2.GetRootsRequest()))
    if not roots:
        print("  Graph has no roots (empty graph or not refreshed).")
        return

    path = []
    current_list = roots

    while True:
        node = pick_from_list(current_list)
        if node is None:
            break
        path.append(node)
        print(f"\n>>> {node.canonical_name}")
        print(
            f"    id={node.id}  type={node.function_type}  "
            f"done={node.is_done}  keeps_edges={node.keeps_edges}  "
            f"lifecycle={node.lifecycle_state}"
        )

        dep_ids = list(node.direct_dep_ids)
        if not dep_ids:
            print("  (leaf node — no children)")
            break

        print(f"\n=== Children of {node.canonical_name} ({len(dep_ids)} deps) ===")
        resp = stub.GetNodes(skyframe_graph_pb2.GetNodesRequest(ids=dep_ids))
        current_list = list(resp.nodes)

    if path:
        print("\n--- Path taken ---")
        for i, n in enumerate(path):
            indent = "  " * i
            print(f"{indent}-> {n.canonical_name}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main():
    parser = argparse.ArgumentParser(description="Query the Skyframe graph via gRPC")
    parser.add_argument(
        "address", nargs="?", help="gRPC host:port (default: auto-detect)"
    )
    parser.add_argument(
        "--output_base",
        default=None,
        help="Bazel output_base directory to read command_port from",
    )
    parser.add_argument(
        "-i",
        "--interactive",
        action="store_true",
        help="Interactive mode: drill down from roots by picking nodes",
    )
    parser.add_argument(
        "--depth",
        type=int,
        default=1,
        help="(dot mode) How many levels of children to follow (default: 1)",
    )
    args = parser.parse_args()

    stub = connect(args)

    if args.interactive:
        mode_interactive(stub)
    else:
        mode_dot(stub, args)


if __name__ == "__main__":
    main()
