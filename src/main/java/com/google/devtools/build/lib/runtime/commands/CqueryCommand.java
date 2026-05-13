// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.runtime.commands;

import static com.google.devtools.build.lib.runtime.Command.BuildPhase.ANALYZES;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.config.CoreOptions.IncludeConfigFragmentsEnum;
import com.google.devtools.build.lib.buildtool.BuildCqueryProcessor;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.BuildTool;
import com.google.devtools.build.lib.buildtool.CqueryProcessor;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.query2.cquery.ConfiguredTargetQueryEnvironment;
import com.google.devtools.build.lib.query2.cquery.CqueryOptions;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.commands.QueryCommandHandler.QueryProcessor;
import com.google.devtools.build.lib.runtime.commands.QueryCommandHandler.QueryScopeException;
import com.google.devtools.build.lib.runtime.commands.QueryCommandHandler.UniverseScope;
import com.google.devtools.build.lib.runtime.commands.QueryCommandUtils.CqueryUniverseScope;
import com.google.devtools.build.lib.server.FailureDetails.ConfigurableQuery;
import com.google.devtools.build.lib.server.FailureDetails.ConfigurableQuery.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.common.options.OptionPriority.PriorityCategory;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;

/** Handles the 'cquery' command on the Blaze command line. */
@Command(
    name = "cquery",
    buildPhase = ANALYZES,
    // We inherit from TestCommand so that we pick up changes like `test --test_arg=foo` in .bazelrc
    // files.
    // Without doing this, there is no easy way to use the output of cquery to determine whether a
    // test has changed between two invocations, because the testrunner action is not easily
    // introspectable.
    inheritsOptionsFrom = {TestCommand.class},
    options = {CqueryOptions.class},
    usesConfigurationOptions = true,
    shortDescription = "Loads, analyzes, and queries the specified targets w/ configurations.",
    allowResidue = true,
    binaryStdOut = true,
    completion = "label",
    help = "resource:cquery.txt")
public final class CqueryCommand implements QueryCommandHandler {

  static final String QUERY_TYPE = "cquery";

  @Override
  public void editOptions(OptionsParser optionsParser) {
    CqueryOptions cqueryOptions = optionsParser.getOptions(CqueryOptions.class);
    try {
      if (!cqueryOptions.getTransitions().equals(CqueryOptions.Transitions.NONE)) {
        optionsParser.parse(
            PriorityCategory.COMPUTED_DEFAULT,
            "Option required by setting the --transitions flag",
            ImmutableList.of("--output=transitions"));
      }
      optionsParser.parse(
          PriorityCategory.COMPUTED_DEFAULT,
          "Options required by cquery",
          ImmutableList.of("--nobuild"));
      optionsParser.parse(
          PriorityCategory.COMPUTED_DEFAULT,
          "cquery should include 'tags = [\"manual\"]' targets by default",
          ImmutableList.of("--build_manual_tests"));
      optionsParser.parse(
          PriorityCategory.SOFTWARE_REQUIREMENT,
          // https://github.com/bazelbuild/bazel/issues/11078
          "cquery should not exclude test_suite rules",
          ImmutableList.of("--noexpand_test_suites"));
      if (cqueryOptions.getShowRequiredConfigFragments() != IncludeConfigFragmentsEnum.OFF) {
        optionsParser.parse(
            PriorityCategory.COMPUTED_DEFAULT,
            "Options required by cquery's --show_config_fragments flag",
            ImmutableList.of(
                "--include_config_fragments_provider="
                    + cqueryOptions.getShowRequiredConfigFragments()));
      }
      optionsParser.parse(
          PriorityCategory.SOFTWARE_REQUIREMENT,
          "cquery should not exclude tests",
          ImmutableList.of("--nobuild_tests_only"));
    } catch (OptionsParsingException e) {
      throw new IllegalStateException("Cquery's known options failed to parse", e);
    }
  }

  @Override
  public String readQueryString(OptionsParsingResult options, CommandEnvironment env)
      throws QueryException {
    return QueryOptionHelper.readQuery(
        options.getOptions(CqueryOptions.class), options, env, /* allowEmptyQuery= */ false);
  }

  @Override
  public UniverseScope deriveUniverseScope(
      OptionsParsingResult options, QueryExpression expr, CommandEnvironment env)
      throws QueryScopeException {
    CqueryUniverseScope scope =
        QueryCommandUtils.deriveCqueryUniverseScope(
            options.getOptions(CqueryOptions.class).getUniverseScope(), expr);
    return new UniverseScope(scope.targets, scope.targetsForProjectResolution);
  }

  @Override
  public BuildTool.AnalysisPostProcessor createStandaloneProcessor(
      QueryExpression expr, TargetPattern.Parser parser, CommandEnvironment env)
      throws QueryScopeException {
    return new CqueryProcessor(expr, parser);
  }

  @Override
  public BlazeCommandResult runWithProcessor(
      CommandEnvironment env,
      BuildTool.AnalysisPostProcessor processor,
      BuildRequest request,
      OptionsParsingResult options,
      UniverseScope universeScope,
      QueryProcessor queryProcessor) {
    DetailedExitCode detailedExitCode =
        new BuildTool(env, processor)
            .processRequest(
                request,
                /* validator= */ null,
                /* postBuildCallback= */ null,
                options,
                universeScope.targetsForProjectResolution())
            .getDetailedExitCode();
    return BlazeCommandResult.detailedExitCode(detailedExitCode);
  }

  @Override
  public void customizeStandaloneRequestBuilder(BuildRequest.Builder builder) {
    builder.setCheckforActionConflicts(false).setReportIncompatibleTargets(false);
  }

  @Override
  public String getQueryType() {
    return QUERY_TYPE;
  }

  @Override
  public ImmutableMap<String, QueryFunction> getFunctionsMap(CommandEnvironment env) {
    return getCqueryFunctionsMap(env);
  }

  @Override
  public BlazeCommandResult createParseFailureResult(String message) {
    return createFailureResult(message, Code.EXPRESSION_PARSE_FAILURE);
  }

  static BlazeCommandResult createFailureResult(String message, Code detailedCode) {
    return BlazeCommandResult.failureDetail(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setConfigurableQuery(ConfigurableQuery.newBuilder().setCode(detailedCode))
            .build());
  }

  @Override
  public QueryProcessor createQueryProcessor(CommandEnvironment env) {
    return new QueryProcessor() {
      @Override
      public java.util.List<String> deriveUniverseTargets(
          java.util.List<String> explicitScope, QueryExpression expr)
          throws ProcessorCreationException {
        return QueryCommandUtils.deriveCqueryUniverseScope(explicitScope, expr).targets;
      }

      @Override
      public void customizeRequestBuilder(BuildRequest.Builder builder) {
        builder.setCheckforActionConflicts(false).setReportIncompatibleTargets(false);
      }

      @Override
      public BuildTool.AnalysisPostProcessor createAnalysisPostProcessor(
          QueryExpression expr, TargetPattern.Parser parser)
          throws ProcessorCreationException {
        return new BuildCqueryProcessor(expr, parser);
      }

      @Override
      public void prepareOptions(OptionsParsingResult options) {}

      @Override
      public void onBuildSuccess(BuildTool.AnalysisPostProcessor processor, OutErr outErr) {}
    };
  }

  static ImmutableMap<String, QueryFunction> getCqueryFunctionsMap(CommandEnvironment env) {
    ImmutableMap.Builder<String, QueryFunction> functionsBuilder = ImmutableMap.builder();
    for (QueryFunction queryFunction : ConfiguredTargetQueryEnvironment.FUNCTIONS) {
      functionsBuilder.put(queryFunction.getName(), queryFunction);
    }
    for (QueryFunction queryFunction : env.getRuntime().getQueryFunctions()) {
      functionsBuilder.put(queryFunction.getName(), queryFunction);
    }
    return functionsBuilder.buildOrThrow();
  }
}
