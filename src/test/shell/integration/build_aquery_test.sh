#!/usr/bin/env bash
#
# Copyright 2026 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# --- begin runfiles.bash initialization ---
set -euo pipefail

if [[ ! -d "${RUNFILES_DIR:-/dev/null}" && ! -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  if [[ -f "$0.runfiles_manifest" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles_manifest"
  elif [[ -f "$0.runfiles/MANIFEST" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles/MANIFEST"
  elif [[ -f "$0.runfiles/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
    export RUNFILES_DIR="$0.runfiles"
  fi
fi
if [[ -f "${RUNFILES_DIR:-/dev/null}/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
  source "${RUNFILES_DIR}/bazel_tools/tools/bash/runfiles/runfiles.bash"
elif [[ -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  source "$(grep -m1 "^bazel_tools/tools/bash/runfiles/runfiles.bash " \
            "$RUNFILES_MANIFEST_FILE" | cut -d ' ' -f 2-)"
else
  echo >&2 "ERROR: cannot find @bazel_tools//tools/bash/runfiles:runfiles.bash"
  exit 1
fi
# --- end runfiles.bash initialization ---

source "$(rlocation "io_bazel/src/test/shell/integration_test_setup.sh")" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

add_to_bazelrc "build --package_path=%workspace%"

#### SETUP #############################################################

function setup_genrules() {
  cat > BUILD <<'EOF'
genrule(name = "x", outs = ["x.out"], cmd = "echo x > $@")
genrule(name = "y", outs = ["y.out"], cmd = "echo y > $@")
genrule(
    name = "z",
    srcs = [":x"],
    outs = ["z.out"],
    cmd = "cat $< > $@",
)
EOF
}

#### TESTS #############################################################

# Verify that --aquery prints a structured action header and output path, and builds the target.
function test_build_aquery_single_target_prints_output_path_and_builds() {
  setup_genrules
  bazel build --aquery="//:x" >& "$TEST_log" || fail "Expected success"
  # The target must be built.
  test -f bazel-genfiles/x.out || fail "x.out was not built"
  # The action header line must mention the mnemonic and "up-to-date".
  expect_log "Action Genrule.*up-to-date"
  # The output path of the genrule action for //:x must be printed (indented).
  expect_log "x\.out"
}

# Verify that y.out is NOT built when only //:x is in the aquery scope.
function test_build_aquery_builds_only_universe_targets() {
  setup_genrules
  bazel build --aquery="//:x" >& "$TEST_log" || fail "Expected success"
  test -f bazel-genfiles/x.out || fail "x.out was not built"
  test ! -f bazel-genfiles/y.out || fail "y.out should NOT have been built"
}

# mnemonic() filter: only x.out path printed, target built.
function test_build_aquery_mnemonic_filter() {
  setup_genrules
  bazel build --aquery='mnemonic("Genrule", //:x)' >& "$TEST_log" \
    || fail "Expected success"
  expect_log "x\.out"
  test -f bazel-genfiles/x.out || fail "x.out was not built"
}

# outputs() filter: select the action that produces x.out — path must appear in output.
function test_build_aquery_outputs_filter() {
  setup_genrules
  bazel build --aquery='outputs(".*x\.out", //:x)' >& "$TEST_log" \
    || fail "Expected success"
  expect_log "x\.out"
  test -f bazel-genfiles/x.out || fail "x.out was not built"
}

# Wildcard scope: all three genrule output paths appear and all targets built.
function test_build_aquery_wildcard_scope() {
  setup_genrules
  bazel build --aquery="//:all" >& "$TEST_log" || fail "Expected success"
  test -f bazel-genfiles/x.out || fail "x.out was not built"
  test -f bazel-genfiles/y.out || fail "y.out was not built"
  test -f bazel-genfiles/z.out || fail "z.out was not built"
  # All three output paths must appear in stdout.
  expect_log "x\.out"
  expect_log "y\.out"
  expect_log "z\.out"
}

# Syntax error in the aquery expression should fail with a useful message.
function test_build_aquery_syntax_error_fails() {
  setup_genrules
  bazel build --aquery="this is not ( valid" >& "$TEST_log" \
    && fail "Expected failure" || true
  expect_log "Error while parsing --aquery"
}

# Combining --aquery with a CLI target pattern should be rejected.
function test_build_aquery_and_cli_pattern_fails() {
  setup_genrules
  bazel build --aquery="//:x" -- //:x >& "$TEST_log" \
    && fail "Expected failure" || true
  expect_log "Only one of command-line target patterns"
}

# Combining --aquery and --cquery should be rejected.
function test_build_aquery_and_cquery_fails() {
  setup_genrules
  bazel build --aquery="//:x" --cquery="//:x" >& "$TEST_log" \
    && fail "Expected failure" || true
  expect_log "Only one of command-line target patterns"
}

# Combining --aquery and --query should be rejected.
function test_build_aquery_and_query_fails() {
  setup_genrules
  bazel build --aquery="//:x" --query="//:x" >& "$TEST_log" \
    && fail "Expected failure" || true
  expect_log "Only one of command-line target patterns"
}

# Combining --aquery and --target_pattern_file should be rejected.
function test_build_aquery_and_target_pattern_file_fails() {
  setup_genrules
  echo "//:x" > targets.txt
  bazel build --aquery="//:x" --target_pattern_file=targets.txt >& "$TEST_log" \
    && fail "Expected failure" || true
  expect_log "Only one of command-line target patterns"
}

# --universe_scope lets you query a dep in the context of a top-level target's
# action graph, while still building that universe.
function test_build_aquery_universe_scope() {
  setup_genrules
  # z depends on x; with universe_scope=z, the aquery over //:x should find
  # x's genrule action in z's configuration. The owner of x's action is //:x,
  # so only x.out is built (not z.out, which is not the owner).
  bazel build --universe_scope="//:z" --aquery="//:x" >& "$TEST_log" \
    || fail "Expected success"
  # x's genrule output path must be printed.
  expect_log "x\.out"
  test -f bazel-genfiles/x.out || fail "x.out was not built"
}

# Helper: write defs.bzl with a fake_binary rule that registers two actions:
#   FakeCompile -> <name>.o
#   FakeLink    -> <name>
# This mimics cc_binary without requiring @rules_cc.
function setup_fake_binary_rule() {
  local pkg="$1"
  mkdir -p "$pkg"
  cat > "$pkg/defs.bzl" <<'EOF'
def _fake_binary_impl(ctx):
    obj = ctx.actions.declare_file(ctx.label.name + ".o")
    ctx.actions.run_shell(
        mnemonic = "FakeCompile",
        outputs = [obj],
        command = "touch " + obj.path,
    )
    exe = ctx.actions.declare_file(ctx.label.name)
    ctx.actions.run_shell(
        mnemonic = "FakeLink",
        inputs = [obj],
        outputs = [exe],
        command = "touch " + exe.path,
    )
    return [DefaultInfo(executable = exe)]

fake_binary = rule(
    implementation = _fake_binary_impl,
    executable = True,
)
EOF
}

# fake_binary produces two actions: FakeCompile (.o) and FakeLink (binary).
# Both output paths must appear in the --aquery output.
function test_build_aquery_cc_binary_multiple_actions() {
  local pkg=test_build_aquery_cc_binary_multiple_actions
  setup_fake_binary_rule "$pkg"
  cat > "$pkg/BUILD" <<'EOF'
load(":defs.bzl", "fake_binary")
fake_binary(name = "hello")
EOF
  bazel build --aquery="//$pkg:hello" >& "$TEST_log" || fail "Expected success"
  test -f "bazel-bin/$pkg/hello" || fail "hello binary was not built"
  # The FakeCompile action header and .o output path must appear.
  expect_log 'FakeCompile'
  expect_log '\.o'
  # The FakeLink action header and binary output path must appear.
  expect_log 'FakeLink'
  expect_log "$pkg/hello"
}

# Two fake_binary targets, one depending on the other via a shared .o.
# When querying :app, both FakeCompile and FakeLink actions appear,
# and both .o paths plus the binary path are printed.
function test_build_aquery_cc_library_multiple_actions() {
  local pkg=test_build_aquery_cc_library_multiple_actions
  setup_fake_binary_rule "$pkg"
  # Add a second fake_binary :lib to the same package so :app's BUILD is simple.
  cat > "$pkg/BUILD" <<'EOF'
load(":defs.bzl", "fake_binary")
fake_binary(name = "lib")
fake_binary(name = "app")
EOF
  bazel build --aquery="//$pkg:app" >& "$TEST_log" || fail "Expected success"
  test -f "bazel-bin/$pkg/app" || fail "app binary was not built"
  # At least one FakeCompile action (.o) and one FakeLink action.
  expect_log '\.o'
  expect_log "$pkg/app"
}

# mnemonic("FakeLink") filter: only the binary output path is printed, not the .o.
function test_build_aquery_cc_binary_mnemonic_link_filter() {
  local pkg=test_build_aquery_cc_binary_mnemonic_link_filter
  setup_fake_binary_rule "$pkg"
  cat > "$pkg/BUILD" <<'EOF'
load(":defs.bzl", "fake_binary")
fake_binary(name = "hello")
EOF
  bazel build --aquery='mnemonic("FakeLink", //'$pkg':hello)' \
    >& "$TEST_log" || fail "Expected success"
  test -f "bazel-bin/$pkg/hello" || fail "hello binary was not built"
  # The FakeLink action header and binary output path must appear.
  expect_log 'FakeLink'
  expect_log "$pkg/hello"
  # No .o paths: FakeCompile actions are filtered out.
  expect_not_log '\.o'
}

# outputs(".*hello\.o") filter: only the .o path is printed, not the binary.
function test_build_aquery_cc_binary_outputs_filter() {
  local pkg=test_build_aquery_cc_binary_outputs_filter
  setup_fake_binary_rule "$pkg"
  cat > "$pkg/BUILD" <<'EOF'
load(":defs.bzl", "fake_binary")
fake_binary(name = "hello")
EOF
  bazel build --aquery='outputs(".*hello\.o", //'$pkg':hello)' \
    >& "$TEST_log" || fail "Expected success"
  test -f "bazel-bin/$pkg/hello" || fail "hello binary was not built"
  # The matched FakeCompile action header and .o output path must be printed.
  expect_log 'FakeCompile'
  expect_log 'hello\.o'
  # The FakeLink action (binary path) must NOT appear.
  expect_not_log 'FakeLink'
}

run_suite "Tests for bazel build --aquery"
