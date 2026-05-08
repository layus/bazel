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

function setup() {
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

function test_build_query_single_target() {
  setup
  bazel build --query="//:x" >& "$TEST_log" || fail "Expected success"
  test -f bazel-genfiles/x.out || fail "x.out was not built"
}

function test_build_query_union_expression() {
  setup
  bazel build --query="//:x + //:y" >& "$TEST_log" || fail "Expected success"
  test -f bazel-genfiles/x.out || fail "x.out was not built"
  test -f bazel-genfiles/y.out || fail "y.out was not built"
}

function test_build_query_wildcard() {
  setup
  bazel build --query="//:all" >& "$TEST_log" || fail "Expected success"
  test -f bazel-genfiles/x.out || fail "x.out was not built"
  test -f bazel-genfiles/y.out || fail "y.out was not built"
  test -f bazel-genfiles/z.out || fail "z.out was not built"
}

function test_build_query_empty_result_succeeds() {
  setup
  bazel build --query="//:x - //:x" >& "$TEST_log" || fail "Expected success"
  expect_log "Nothing will be built"
}

function test_build_query_kind_filter_builds_only_matching_rules() {
  mkdir -p pkg
  cat > pkg/BUILD <<'EOF'
genrule(name = "gen_a", outs = ["a.out"], cmd = "echo a > $@")
genrule(name = "gen_b", outs = ["b.out"], cmd = "echo b > $@")
filegroup(name = "fg", srcs = ["a.out"])
EOF
  # kind("genrule", ...) should match only the two genrules, not the filegroup
  bazel build --query='kind("genrule", //pkg:all)' >& "$TEST_log" \
    || fail "Expected success"
  test -f bazel-genfiles/pkg/a.out || fail "a.out was not built"
  test -f bazel-genfiles/pkg/b.out || fail "b.out was not built"
  # The filegroup itself produces no output file; just check it was NOT queried
  expect_not_log "//pkg:fg"
}

function test_build_query_deps_builds_transitive_deps() {
  mkdir -p dep
  cat > dep/BUILD <<'EOF'
genrule(name = "lib",  outs = ["lib.out"],  cmd = "echo lib > $@")
genrule(name = "top",  srcs = [":lib"], outs = ["top.out"], cmd = "cat $< > $@")
EOF
  # deps(//dep:top) resolves both :top and :lib; building both should succeed
  bazel build --query='deps(//dep:top)' >& "$TEST_log" \
    || fail "Expected success"
  test -f bazel-genfiles/dep/lib.out || fail "lib.out was not built"
  test -f bazel-genfiles/dep/top.out || fail "top.out was not built"
}

function test_build_query_set_difference_excludes_targets() {
  setup
  # Build all targets except :z
  bazel build --query='//:all - //:z' >& "$TEST_log" \
    || fail "Expected success"
  test -f bazel-genfiles/x.out || fail "x.out was not built"
  test -f bazel-genfiles/y.out || fail "y.out was not built"
  test ! -f bazel-genfiles/z.out || fail "z.out should NOT have been built"
}

function test_build_query_attr_filter_by_name() {
  mkdir -p tagged
  cat > tagged/BUILD <<'EOF'
genrule(name = "want", outs = ["want.out"], cmd = "echo want > $@",
        tags = ["my_tag"])
genrule(name = "skip", outs = ["skip.out"], cmd = "echo skip > $@")
EOF
  # attr("tags", "my_tag", ...) selects only targets whose tags contain my_tag
  bazel build --query='attr("tags", "my_tag", //tagged:all)' >& "$TEST_log" \
    || fail "Expected success"
  test -f bazel-genfiles/tagged/want.out || fail "want.out was not built"
  test ! -f bazel-genfiles/tagged/skip.out || fail "skip.out should NOT have been built"
}

function test_build_query_syntax_error_fails() {
  setup
  bazel build --query="this is not ( valid" >& "$TEST_log" \
    && fail "Expected failure" || true
  expect_log "Query syntax error"
}

function test_build_query_and_cli_pattern_fails() {
  setup
  bazel build --query="//:x" -- //:x >& "$TEST_log" \
    && fail "Expected failure" || true
  expect_log "Only one of command-line target patterns"
}

function test_build_query_and_target_pattern_file_fails() {
  setup
  echo "//:x" > targets.txt
  bazel build --query="//:x" --target_pattern_file=targets.txt >& "$TEST_log" \
    && fail "Expected failure" || true
  expect_log "Only one of command-line target patterns"
}

run_suite "Tests for bazel build --query"
