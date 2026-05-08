// Copyright 2014 The Bazel Authors. All rights reserved.
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

import static com.google.devtools.build.lib.runtime.Command.BuildPhase.EXECUTES;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.AnalysisOptions;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions;
import com.google.devtools.build.lib.buildtool.AqueryProcessor.AqueryActionFilterException;
import com.google.devtools.build.lib.buildtool.BuildAqueryProcessor;
import com.google.devtools.build.lib.buildtool.BuildCqueryProcessor;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions;
import com.google.devtools.build.lib.buildtool.BuildResult;
import com.google.devtools.build.lib.buildtool.BuildTool;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.cmdline.TargetPattern.Parser;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.local.LocalExecutionOptions;
import com.google.devtools.build.lib.pkgcache.LoadingOptions;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.query2.aquery.ActionGraphQueryEnvironment;
import com.google.devtools.build.lib.query2.aquery.AqueryOptions;
import com.google.devtools.build.lib.query2.cquery.ConfiguredTargetQueryEnvironment;
import com.google.devtools.build.lib.query2.cquery.CqueryOptions;
import com.google.devtools.build.lib.query2.engine.AllPathsFunction;
import com.google.devtools.build.lib.query2.engine.FunctionExpression;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QueryParser;
import com.google.devtools.build.lib.query2.engine.QuerySyntaxException;
import com.google.devtools.build.lib.query2.engine.SomePathFunction;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.runtime.LoadingPhaseThreadsOption;
import com.google.devtools.build.lib.server.FailureDetails.ActionQuery;
import com.google.devtools.build.lib.server.FailureDetails.ConfigurableQuery;
import com.google.devtools.build.lib.server.FailureDetails.ConfigurableQuery.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue.RepositoryMappingResolutionException;
import com.google.devtools.build.lib.skyframe.SkyfocusOptions;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.common.options.OptionPriority.PriorityCategory;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Handles the 'build' command on the Blaze command line, including targets named by arguments
 * passed to Blaze.
 */
@Command(
    name = "build",
    buildPhase = EXECUTES,
    options = {
      BuildRequestOptions.class,
      ExecutionOptions.class,
      LocalExecutionOptions.class,
      PackageOptions.class,
      AnalysisOptions.class,
      LoadingOptions.class,
      KeepGoingOption.class,
      LoadingPhaseThreadsOption.class,
      BuildEventProtocolOptions.class,
      SkyfocusOptions.class,
      RemoteAnalysisCachingOptions.class,
      CqueryOptions.class,
    },
    usesConfigurationOptions = true,
    shortDescription = "Builds the specified targets.",
    allowResidue = true,
    completion = "label",
    help = "resource:build.txt")
public final class BuildCommand implements BlazeCommand {

  @Override
  public void editOptions(OptionsParser optionsParser) {
    BuildRequestOptions buildRequestOptions = optionsParser.getOptions(BuildRequestOptions.class);
    if (buildRequestOptions == null) {
      return;
    }
    if (!buildRequestOptions.getBuildCquery().isEmpty()
        || !buildRequestOptions.getBuildAquery().isEmpty()) {
      try {
        optionsParser.parse(
            PriorityCategory.SOFTWARE_REQUIREMENT,
            "build --cquery/--aquery requires sequential analysis and execution phases",
            ImmutableList.of("--noexperimental_merged_skyframe_analysis_execution"));
      } catch (OptionsParsingException e) {
        throw new IllegalStateException("build --cquery/--aquery option failed to parse", e);
      }
    }
  }

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    BlazeRuntime runtime = env.getRuntime();

    BuildRequestOptions buildRequestOptions = options.getOptions(BuildRequestOptions.class);
    String cqueryExpression = buildRequestOptions.getBuildCquery();
    String aqueryExpression = buildRequestOptions.getBuildAquery();
    if (!cqueryExpression.isEmpty() && !aqueryExpression.isEmpty()) {
      String message = "Only one of command-line target patterns, --target_pattern_file, --query,"
          + " --cquery, or --aquery may be specified";
      env.getReporter().handle(Event.error(message));
      return BlazeCommandResult.failureDetail(
          FailureDetail.newBuilder()
              .setMessage(message)
              .setConfigurableQuery(
                  ConfigurableQuery.newBuilder().setCode(Code.EXPRESSION_PARSE_FAILURE))
              .build());
    }
    if (!cqueryExpression.isEmpty()) {
      return execWithCquery(env, options, runtime, cqueryExpression);
    }
    if (!aqueryExpression.isEmpty()) {
      return execWithAquery(env, options, runtime, aqueryExpression);
    }

    List<String> targets;
    try {
      targets = TargetPatternsHelper.readFrom(env, options);
    } catch (TargetPatternsHelper.TargetPatternsHelperException e) {
      env.getReporter().handle(Event.error(e.getMessage()));
      return BlazeCommandResult.failureDetail(e.getFailureDetail());
    }
    if (targets.isEmpty()) {
      env.getReporter()
          .handle(
              Event.warn(
                  "Usage: "
                      + runtime.getProductName()
                      + " build <options> <targets>."
                      + "\nInvoke `"
                      + runtime.getProductName()
                      + " help build` for full description of usage and options."
                      + "\nYour request is correct, but requested an empty set of targets."
                      + " Nothing will be built."));
    }

    BuildRequest request;
    try (SilentCloseable closeable = Profiler.instance().profile("BuildRequest.create")) {
      request =
          BuildRequest.builder()
              .setCommandName(getClass().getAnnotation(Command.class).name())
              .setId(env.getCommandId())
              .setOptions(options)
              .setStartupOptions(runtime.getStartupOptionsProvider())
              .setOutErr(env.getReporter().getOutErr())
              .setTargets(targets)
              .setStartTimeMillis(env.getCommandStartTime())
              .build();
    }
    DetailedExitCode detailedExitCode =
        new BuildTool(env).processRequest(request, null, options).getDetailedExitCode();
    return BlazeCommandResult.detailedExitCode(detailedExitCode);
  }

  /**
   * Handles {@code build --aquery=<expr>}: parses the aquery expression, derives the universe
   * scope, builds the targets, then evaluates the aquery over the resulting action graph and writes
   * matching actions to stdout.
   */
  private BlazeCommandResult execWithAquery(
      CommandEnvironment env,
      OptionsParsingResult options,
      BlazeRuntime runtime,
      String aqueryExpression) {
    BuildRequestOptions buildRequestOptions = options.getOptions(BuildRequestOptions.class);
    int sourcesSpecified =
        (options.getResidue().isEmpty() ? 0 : 1)
            + (buildRequestOptions.getTargetPatternFile().isEmpty() ? 0 : 1)
            + (buildRequestOptions.getBuildQuery().isEmpty() ? 0 : 1)
            + (buildRequestOptions.getBuildCquery().isEmpty() ? 0 : 1);
    if (sourcesSpecified > 0) {
      String message =
          "Only one of command-line target patterns, --target_pattern_file, --query, --cquery,"
              + " or --aquery may be specified";
      env.getReporter().handle(Event.error(message));
      return BlazeCommandResult.failureDetail(
          createAqueryFailureDetail(message, ActionQuery.Code.INCORRECT_ARGUMENTS));
    }

    TargetPattern.Parser mainRepoTargetParser;
    try {
      boolean keepGoing = options.getOptions(KeepGoingOption.class).getKeepGoing();
      int loadingPhaseThreads =
          options.getOptions(LoadingPhaseThreadsOption.class).getThreads();
      RepositoryMapping repoMapping =
          env.getSkyframeExecutor()
              .getMainRepoMapping(keepGoing, loadingPhaseThreads, env.getReporter());
      mainRepoTargetParser =
          new Parser(env.getRelativeWorkingDirectory(), RepositoryName.MAIN, repoMapping);
    } catch (RepositoryMappingResolutionException e) {
      env.getReporter().handle(Event.error(e.getMessage()));
      return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode());
    } catch (InterruptedException e) {
      String errorMessage = "Interrupted while resolving repository mapping for --aquery";
      env.getReporter().handle(Event.error(errorMessage));
      return BlazeCommandResult.detailedExitCode(
          InterruptedFailureDetails.detailedExitCode(errorMessage));
    }

    HashMap<String, QueryFunction> functions = new HashMap<>();
    for (QueryFunction queryFunction : ActionGraphQueryEnvironment.FUNCTIONS) {
      functions.put(queryFunction.getName(), queryFunction);
    }
    for (QueryFunction queryFunction : ActionGraphQueryEnvironment.AQUERY_FUNCTIONS) {
      functions.put(queryFunction.getName(), queryFunction);
    }
    for (QueryFunction queryFunction : env.getRuntime().getQueryFunctions()) {
      functions.put(queryFunction.getName(), queryFunction);
    }

    QueryExpression expr;
    try {
      expr = QueryParser.parse(aqueryExpression, functions);
    } catch (QuerySyntaxException e) {
      String message =
          String.format(
              "Error while parsing --aquery '%s': %s",
              QueryExpression.truncate(aqueryExpression), e.getMessage());
      env.getReporter().handle(Event.error(message));
      return BlazeCommandResult.failureDetail(
          createAqueryFailureDetail(message, ActionQuery.Code.EXPRESSION_PARSE_FAILURE));
    }

    List<String> universeTargets = options.getOptions(CqueryOptions.class).getUniverseScope();
    if (universeTargets.isEmpty()) {
      LinkedHashSet<String> targetPatternSet = new LinkedHashSet<>();
      expr.collectTargetPatterns(targetPatternSet);
      universeTargets = new ArrayList<>(targetPatternSet);
    }

    BuildAqueryProcessor aqueryProcessor;
    try {
      aqueryProcessor = new BuildAqueryProcessor(expr, mainRepoTargetParser);
    } catch (AqueryActionFilterException e) {
      String message = e.getMessage() + "\n" + expr;
      env.getReporter().handle(Event.error(message));
      return BlazeCommandResult.failureDetail(
          createAqueryFailureDetail(message, ActionQuery.Code.INVALID_AQUERY_EXPRESSION));
    }

    try {
      ((OptionsParser) options).parse(
          PriorityCategory.SOFTWARE_REQUIREMENT,
          "build --aquery suppresses the default target result printer",
          ImmutableList.of("--show_result=0"));
    } catch (OptionsParsingException e) {
      throw new IllegalStateException("build --aquery failed to set --show_result=0", e);
    }

    BuildRequest request;
    try (SilentCloseable closeable = Profiler.instance().profile("BuildRequest.create")) {
      request =
          BuildRequest.builder()
              .setCommandName(getClass().getAnnotation(Command.class).name())
              .setId(env.getCommandId())
              .setOptions(options)
              .setStartupOptions(runtime.getStartupOptionsProvider())
              .setOutErr(env.getReporter().getOutErr())
              .setTargets(universeTargets)
              .setStartTimeMillis(env.getCommandStartTime())
              .build();
    }

    BuildResult buildResult =
        new BuildTool(env, aqueryProcessor)
            .processRequest(request, /* validator= */ null, options);
    if (buildResult.getSuccess()) {
      aqueryProcessor.printMatchedActions(env.getReporter().getOutErr());
    }
    return BlazeCommandResult.detailedExitCode(buildResult.getDetailedExitCode());
  }

  private static FailureDetail createAqueryFailureDetail(
      String message, ActionQuery.Code code) {
    return FailureDetail.newBuilder()
        .setMessage(message)
        .setActionQuery(ActionQuery.newBuilder().setCode(code))
        .build();
  }

  /**
   * Handles {@code build --cquery=<expr>}: parses the cquery expression, derives the universe
   * scope (the targets to analyze), runs analysis, then evaluates the cquery over the resulting
   * configured-target graph and builds only the matched targets.
   */
  private BlazeCommandResult execWithCquery(
      CommandEnvironment env,
      OptionsParsingResult options,
      BlazeRuntime runtime,
      String cqueryExpression) {
    // Reject conflicts with other target-source options.
    BuildRequestOptions buildRequestOptions = options.getOptions(BuildRequestOptions.class);
    int sourcesSpecified =
        (options.getResidue().isEmpty() ? 0 : 1)
            + (buildRequestOptions.getTargetPatternFile().isEmpty() ? 0 : 1)
            + (buildRequestOptions.getBuildQuery().isEmpty() ? 0 : 1);
    if (sourcesSpecified > 0) {
      String message =
          "Only one of command-line target patterns, --target_pattern_file, --query, or --cquery"
              + " may be specified";
      env.getReporter().handle(Event.error(message));
      return BlazeCommandResult.failureDetail(
          createCqueryFailureDetail(message, Code.EXPRESSION_PARSE_FAILURE));
    }

    TargetPattern.Parser mainRepoTargetParser;
    try {
      boolean keepGoing = options.getOptions(KeepGoingOption.class).getKeepGoing();
      int loadingPhaseThreads =
          options.getOptions(LoadingPhaseThreadsOption.class).getThreads();
      RepositoryMapping repoMapping =
          env.getSkyframeExecutor()
              .getMainRepoMapping(keepGoing, loadingPhaseThreads, env.getReporter());
      mainRepoTargetParser =
          new Parser(env.getRelativeWorkingDirectory(), RepositoryName.MAIN, repoMapping);
    } catch (RepositoryMappingResolutionException e) {
      env.getReporter().handle(Event.error(e.getMessage()));
      return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode());
    } catch (InterruptedException e) {
      String errorMessage = "Interrupted while resolving repository mapping for --cquery";
      env.getReporter().handle(Event.error(errorMessage));
      return BlazeCommandResult.detailedExitCode(
          InterruptedFailureDetails.detailedExitCode(errorMessage));
    }

    HashMap<String, QueryFunction> functions = new HashMap<>();
    for (QueryFunction queryFunction : ConfiguredTargetQueryEnvironment.FUNCTIONS) {
      functions.put(queryFunction.getName(), queryFunction);
    }
    for (QueryFunction queryFunction : env.getRuntime().getQueryFunctions()) {
      functions.put(queryFunction.getName(), queryFunction);
    }

    QueryExpression expr;
    try {
      expr = QueryParser.parse(cqueryExpression, functions);
    } catch (QuerySyntaxException e) {
      String message =
          String.format(
              "Error while parsing --cquery '%s': %s",
              QueryExpression.truncate(cqueryExpression), e.getMessage());
      env.getReporter().handle(Event.error(message));
      return BlazeCommandResult.failureDetail(
          createCqueryFailureDetail(message, Code.EXPRESSION_PARSE_FAILURE));
    }

    // Derive the universe scope the same way CqueryCommand does.
    List<String> universeTargets = options.getOptions(CqueryOptions.class).getUniverseScope();
    if (universeTargets.isEmpty()) {
      LinkedHashSet<String> targetPatternSet = new LinkedHashSet<>();
      expr.collectTargetPatterns(targetPatternSet);
      universeTargets = new ArrayList<>(targetPatternSet);
      if (expr instanceof FunctionExpression functionExpr
          && (functionExpr.getFunction() instanceof SomePathFunction
              || functionExpr.getFunction() instanceof AllPathsFunction)) {
        universeTargets = List.of(targetPatternSet.iterator().next());
      }
    }

    BuildRequest request;
    try (SilentCloseable closeable = Profiler.instance().profile("BuildRequest.create")) {
      request =
          BuildRequest.builder()
              .setCommandName(getClass().getAnnotation(Command.class).name())
              .setId(env.getCommandId())
              .setOptions(options)
              .setStartupOptions(runtime.getStartupOptionsProvider())
              .setOutErr(env.getReporter().getOutErr())
              .setTargets(universeTargets)
              .setStartTimeMillis(env.getCommandStartTime())
              .setCheckforActionConflicts(false)
              .setReportIncompatibleTargets(false)
              .build();
    }

    DetailedExitCode detailedExitCode =
        new BuildTool(env, new BuildCqueryProcessor(expr, mainRepoTargetParser))
            .processRequest(request, /* validator= */ null, options)
            .getDetailedExitCode();
    return BlazeCommandResult.detailedExitCode(detailedExitCode);
  }

  private static FailureDetail createCqueryFailureDetail(String message, Code code) {
    return FailureDetail.newBuilder()
        .setMessage(message)
        .setConfigurableQuery(ConfigurableQuery.newBuilder().setCode(code))
        .build();
  }
}
