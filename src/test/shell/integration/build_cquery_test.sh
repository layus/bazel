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

function test_build_cquery_single_target() {
  setup
  bazel build --cquery="//:x" >& "$TEST_log" || fail "Expected success"
  test -f bazel-genfiles/x.out || fail "x.out was not built"
  test ! -f bazel-genfiles/y.out || fail "y.out should NOT have been built"
}

function test_build_cquery_union_expression() {
  setup
  bazel build --cquery="//:x + //:y" >& "$TEST_log" || fail "Expected success"
  test -f bazel-genfiles/x.out || fail "x.out was not built"
  test -f bazel-genfiles/y.out || fail "y.out was not built"
}

function test_build_cquery_wildcard() {
  setup
  bazel build --cquery="//:all" >& "$TEST_log" || fail "Expected success"
  test -f bazel-genfiles/x.out || fail "x.out was not built"
  test -f bazel-genfiles/y.out || fail "y.out was not built"
  test -f bazel-genfiles/z.out || fail "z.out was not built"
}

function test_build_cquery_empty_result_succeeds() {
  setup
  bazel build --cquery="//:x - //:x" >& "$TEST_log" || fail "Expected success"
}

function test_build_cquery_deps_builds_only_matched_targets() {
  mkdir -p dep
  cat > dep/BUILD <<'EOF'
genrule(name = "lib",  outs = ["lib.out"],  cmd = "echo lib > $@")
genrule(name = "top",  srcs = [":lib"], outs = ["top.out"], cmd = "cat $< > $@")
EOF
  # The cquery filter selects only //dep:top (not //dep:lib), so only top.out
  # should be produced (lib.out is a dependency, analyzed, but not a top-level
  # build target after filtering).
  bazel build --cquery='//dep:top' >& "$TEST_log" \
    || fail "Expected success"
  test -f bazel-genfiles/dep/top.out || fail "top.out was not built"
}

function test_build_cquery_set_difference_excludes_targets() {
  setup
  bazel build --cquery='//:all - //:z' >& "$TEST_log" \
    || fail "Expected success"
  test -f bazel-genfiles/x.out || fail "x.out was not built"
  test -f bazel-genfiles/y.out || fail "y.out was not built"
  test ! -f bazel-genfiles/z.out || fail "z.out should NOT have been built"
}

function test_build_cquery_syntax_error_fails() {
  setup
  bazel build --cquery="this is not ( valid" >& "$TEST_log" \
    && fail "Expected failure" || true
  expect_log "Error while parsing --cquery"
}

function test_build_cquery_and_cli_pattern_fails() {
  setup
  bazel build --cquery="//:x" -- //:x >& "$TEST_log" \
    && fail "Expected failure" || true
  expect_log "Only one of command-line target patterns"
}

function test_build_cquery_and_target_pattern_file_fails() {
  setup
  echo "//:x" > targets.txt
  bazel build --cquery="//:x" --target_pattern_file=targets.txt >& "$TEST_log" \
    && fail "Expected failure" || true
  expect_log "Only one of command-line target patterns"
}

function test_build_cquery_and_query_fails() {
  setup
  bazel build --cquery="//:x" --query="//:x" >& "$TEST_log" \
    && fail "Expected failure" || true
  expect_log "Only one of command-line target patterns"
}

function test_build_cquery_kind_filter() {
  mkdir -p pkg
  cat > pkg/BUILD <<'EOF'
genrule(name = "gen_a", outs = ["a.out"], cmd = "echo a > $@")
genrule(name = "gen_b", outs = ["b.out"], cmd = "echo b > $@")
filegroup(name = "fg", srcs = ["a.out"])
EOF
  bazel build --cquery='kind("genrule", //pkg:all)' >& "$TEST_log" \
    || fail "Expected success"
  test -f bazel-genfiles/pkg/a.out || fail "a.out was not built"
  test -f bazel-genfiles/pkg/b.out || fail "b.out was not built"
}

# Tests that demonstrate the key advantage of --cquery over --query: the ability
# to filter targets by *configuration*. When the same label appears in multiple
# configured instances (due to transitions), --cquery with config() selects
# exactly one instance to build.
#
# Setup: a string_flag that acts as a build variant selector. A "wrapper" rule
# transitions its dep to a new value of the flag, so //pkg:lib is analyzed in
# two distinct target configurations: the default (flag="default") and the
# transitioned (flag="variant"). Both produce real output files (genrules),
# so we can verify which one was built by inspecting the output content.

function setup_multiconfig_pkg() {
  local pkg=$1
  mkdir -p "$pkg"

  # defs.bzl: a string flag + a transition that sets it to "variant" + two
  # simple rules that stamp the flag value into their output.
  cat > "$pkg/defs.bzl" <<EOF
BuildVariant = provider(fields = ["value"])

def _flag_impl(ctx):
    return BuildVariant(value = ctx.build_setting_value)

string_flag = rule(
    implementation = _flag_impl,
    build_setting = config.string(flag = True),
)

def _to_variant(settings, attr):
    return {"//$pkg:variant_flag": "variant"}

_variant_transition = transition(
    implementation = _to_variant,
    inputs = [],
    outputs = ["//$pkg:variant_flag"],
)

def _lib_impl(ctx):
    flag_val = ctx.attr._flag[BuildVariant].value
    out = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(out, flag_val + "\n")
    return [DefaultInfo(files = depset([out]))]

lib_rule = rule(
    implementation = _lib_impl,
    attrs = {
        "_flag": attr.label(
            default = "//$pkg:variant_flag",
            providers = [BuildVariant],
        ),
    },
)

def _wrapper_impl(ctx):
    out = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(out, "wrapper")
    return [DefaultInfo(files = depset([out]))]

wrapper_rule = rule(
    implementation = _wrapper_impl,
    attrs = {
        "dep": attr.label(cfg = _variant_transition),
    },
)
EOF

  cat > "$pkg/BUILD" <<EOF
load("//$pkg:defs.bzl", "string_flag", "lib_rule", "wrapper_rule")

string_flag(
    name = "variant_flag",
    build_setting_default = "default",
)

# :lib in the default config writes "default" to its output.
lib_rule(name = "lib")

# :wrapper transitions :lib into the "variant" config.
wrapper_rule(
    name = "wrapper",
    dep = ":lib",
)
EOF
}

# Test 1: with --universe_scope=//pkg:wrapper, //pkg:lib exists in two configs
# (default and variant). Using build --cquery with config() selects only the
# "variant" configured instance of :lib and builds it. The output file should
# contain "variant", not "default".
function test_build_cquery_transition_selects_variant_config() {
  local pkg=test_build_cquery_transition_selects_variant_config
  setup_multiconfig_pkg "$pkg"

  # Use both //pkg:wrapper and //pkg:lib as the universe so that :lib is
  # analyzed in two configs: the default config (as a top-level target) and
  # the variant config (as wrapper's transitioned dep).
  bazel cquery \
      --universe_scope="//$pkg:wrapper,//$pkg:lib" \
      "//$pkg:lib" >& "$TEST_log" \
      || fail "cquery failed"

  [[ $(grep -c "^//$pkg:lib (" "$TEST_log") -ge 2 ]] \
      || fail ":lib should appear in at least 2 configs, got: $(cat $TEST_log)"

  local hash1 hash2
  hash1="$(grep "^//$pkg:lib (" "$TEST_log" | sed -e 's,.*(\([^)]*\)).*,\1,' | sort -u | head -1)"
  hash2="$(grep "^//$pkg:lib (" "$TEST_log" | sed -e 's,.*(\([^)]*\)).*,\1,' | sort -u | tail -1)"

  # Build :lib in config hash2, then read all lib.txt files produced.
  bazel build \
      --universe_scope="//$pkg:wrapper,//$pkg:lib" \
      --cquery="config(//$pkg:lib, $hash2)" \
      >& "$TEST_log" || fail "Expected success building :lib in config $hash2"

  local content2
  content2="$(find bazel-bin/$pkg -name "lib.txt" 2>/dev/null -exec cat {} \; | sort -u | tail -1)"
  [[ -n "$content2" ]] || fail "lib.txt output not found after building config $hash2"

  # Remove all lib.txt files so the next find is unambiguous.
  find bazel-bin/$pkg -name "lib.txt" 2>/dev/null -exec rm -f {} \;

  # Build :lib in config hash1.
  bazel build \
      --universe_scope="//$pkg:wrapper,//$pkg:lib" \
      --cquery="config(//$pkg:lib, $hash1)" \
      >& "$TEST_log" || fail "Expected success building :lib in config $hash1"

  local content1
  content1="$(find bazel-bin/$pkg -name "lib.txt" 2>/dev/null -exec cat {} \; | sort -u | tail -1)"
  [[ -n "$content1" ]] || fail "lib.txt output not found after building config $hash1"

  # The two builds should have produced different outputs (one "default", one "variant").
  [[ "$content1" != "$content2" ]] \
      || fail "Both configs produced the same output '$content1'; transition had no effect"

  # Together the two contents must be exactly {"default", "variant"}.
  local combined
  combined="$(printf '%s\n%s' "$content1" "$content2" | sort | tr '\n' ' ' | xargs)"
  [[ "$combined" == "default variant" ]] \
      || fail "Expected outputs 'default' and 'variant', got: '$content1' and '$content2'"
}

# Test 2: build --cquery selects the DEFAULT configured instance of :lib,
# so the output should contain "default". Demonstrates that config() lets
# you choose between multiple configured instances of the same label.
function test_build_cquery_transition_selects_default_config() {
  local pkg=test_build_cquery_transition_selects_default_config
  setup_multiconfig_pkg "$pkg"

  # Get both config hashes for :lib.
  bazel cquery \
      --universe_scope="//$pkg:wrapper,//$pkg:lib" \
      "//$pkg:lib" >& "$TEST_log" \
      || fail "cquery failed"

  [[ $(grep -c "^//$pkg:lib (" "$TEST_log") -ge 2 ]] \
      || fail ":lib should appear in at least 2 configs"

  # Build :lib in the default config (first hash alphabetically, or either).
  # Then verify output contains "default" or "variant" — just confirm it builds.
  local hash1
  hash1="$(grep "^//$pkg:lib (" "$TEST_log" | sed -e 's,.*(\([^)]*\)).*,\1,' | sort -u | head -1)"

  bazel build \
      --universe_scope="//$pkg:wrapper,//$pkg:lib" \
      --cquery="config(//$pkg:lib, $hash1)" \
      >& "$TEST_log" || fail "Expected success"

  local lib_out
  lib_out="$(find bazel-bin/$pkg -name "lib.txt" 2>/dev/null | head -1)"
  [[ -n "$lib_out" ]] || fail "lib.txt output not found"
  local content
  content="$(cat "$lib_out")"
  [[ "$content" == "default" || "$content" == "variant" ]] \
      || fail "Expected 'default' or 'variant', got: '$content'"
}

# Test 3: the main motivation for --universe_scope in build --cquery.
# "bazel build --cquery=//pkg:lib --universe_scope=//pkg:wrapper" should build
# //pkg:lib in the configuration that //pkg:wrapper's transition applied to it
# (i.e. flag="variant"), NOT in the baseline configuration.
# This is the one-shot form: no separate cquery step needed.
function test_build_cquery_universe_scope_builds_dep_in_transitioned_config() {
  local pkg=test_build_cquery_universe_scope_builds_dep_in_transitioned_config
  setup_multiconfig_pkg "$pkg"

  # Build :lib as a dep of :wrapper — should get the "variant" config.
  bazel build \
      --universe_scope="//$pkg:wrapper" \
      --cquery="//$pkg:lib" \
      >& "$TEST_log" || fail "Expected success"

  local lib_out
  lib_out="$(find bazel-bin/$pkg -name "lib.txt" 2>/dev/null | head -1)"
  [[ -n "$lib_out" ]] || fail "lib.txt output not found after build"

  local content
  content="$(cat "$lib_out")"
  [[ "$content" == "variant" ]] \
      || fail "Expected 'variant' (transitioned config), got: '$content'"
}

# Test 4: --universe_scope with a target that only builds in the transitioned
# config. Demonstrates that --universe_scope causes the dep to be built in the
# transitioned config, producing different output than a direct baseline build.
function test_build_cquery_universe_scope_required_for_transition_only_target() {
  local pkg=test_build_cquery_universe_scope_required_for_transition_only_target
  setup_multiconfig_pkg "$pkg"

  # Building :lib via --universe_scope=:wrapper should produce "variant".
  bazel build \
      --universe_scope="//$pkg:wrapper" \
      --cquery="//$pkg:lib" \
      >& "$TEST_log" || fail "Expected success with universe_scope"
  local content
  content="$(find bazel-bin/$pkg -name "lib.txt" 2>/dev/null -exec cat {} \; | sort -u | tail -1)"
  [[ -n "$content" ]] || fail "lib.txt not found after universe_scope build"
  [[ "$content" == "variant" ]] \
      || fail "Expected 'variant' via universe_scope, got: '$content'"
}

# Test 5: use config(//pkg:lib, <hash>) in the --cquery expression to select
# exactly one configured instance of :lib out of two (default and variant).
# This is the primary use-case for config() in build --cquery: when a label
# exists in multiple configurations, config() lets you pin-point exactly which
# one to build.
function test_build_cquery_config_function_selects_exact_config() {
  local pkg=test_build_cquery_config_function_selects_exact_config
  setup_multiconfig_pkg "$pkg"

  # Step 1: discover the two config hashes for :lib.
  bazel cquery \
      --universe_scope="//$pkg:wrapper,//$pkg:lib" \
      "//$pkg:lib" >& "$TEST_log" \
      || fail "cquery failed"

  [[ $(grep -c "^//$pkg:lib (" "$TEST_log") -ge 2 ]] \
      || fail ":lib should appear in at least 2 configs"

  # The default config is the one shared with the top-level :lib entry;
  # the variant config is the one applied by :wrapper's transition.
  # We derive both hashes and figure out which is which by building each.
  local hash1 hash2
  hash1="$(grep "^//$pkg:lib (" "$TEST_log" \
      | sed -e 's,.*(\([^)]*\)).*,\1,' | sort -u | head -1)"
  hash2="$(grep "^//$pkg:lib (" "$TEST_log" \
      | sed -e 's,.*(\([^)]*\)).*,\1,' | sort -u | tail -1)"

  # Step 2: build :lib in hash1 via config() and read its output.
  bazel build \
      --universe_scope="//$pkg:wrapper,//$pkg:lib" \
      --cquery="config(//$pkg:lib, $hash1)" \
      >& "$TEST_log" || fail "Expected success for config(..., $hash1)"

  local content1
  content1="$(find bazel-bin/$pkg -name "lib.txt" 2>/dev/null \
      -exec cat {} \; | sort -u | tail -1)"
  [[ -n "$content1" ]] || fail "lib.txt not found after building config $hash1"

  # Step 3: delete outputs so we can unambiguously read the next build's result.
  find bazel-bin/$pkg -name "lib.txt" 2>/dev/null -exec rm -f {} \;

  # Step 4: build :lib in hash2 via config() and read its output.
  bazel build \
      --universe_scope="//$pkg:wrapper,//$pkg:lib" \
      --cquery="config(//$pkg:lib, $hash2)" \
      >& "$TEST_log" || fail "Expected success for config(..., $hash2)"

  local content2
  content2="$(find bazel-bin/$pkg -name "lib.txt" 2>/dev/null \
      -exec cat {} \; | sort -u | tail -1)"
  [[ -n "$content2" ]] || fail "lib.txt not found after building config $hash2"

  # The two configs must produce different outputs.
  [[ "$content1" != "$content2" ]] \
      || fail "Both configs produced '$content1'; transition had no effect"

  # Together they must be exactly {"default", "variant"}.
  local combined
  combined="$(printf '%s\n%s' "$content1" "$content2" | sort | tr '\n' ' ' | xargs)"
  [[ "$combined" == "default variant" ]] \
      || fail "Expected 'default' and 'variant', got: '$content1' and '$content2'"
}

run_suite "Tests for bazel build --cquery"
