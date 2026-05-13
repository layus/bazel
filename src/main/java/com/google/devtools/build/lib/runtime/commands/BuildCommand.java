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
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions;
import com.google.devtools.build.lib.buildtool.BuildTool;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.local.LocalExecutionOptions;
import com.google.devtools.build.lib.pkgcache.LoadingOptions;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.query2.cquery.CqueryOptions;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.runtime.LoadingPhaseThreadsOption;
import com.google.devtools.build.lib.server.FailureDetails.ConfigurableQuery;
import com.google.devtools.build.lib.server.FailureDetails.ConfigurableQuery.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.SkyfocusOptions;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.common.options.OptionPriority.PriorityCategory;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
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

  private static final AqueryCommand AQUERY_HANDLER = new AqueryCommand();
  private static final CqueryCommand CQUERY_HANDLER = new CqueryCommand();

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    BlazeRuntime runtime = env.getRuntime();

    BuildRequestOptions buildRequestOptions = options.getOptions(BuildRequestOptions.class);
    String cqueryExpression = buildRequestOptions.getBuildCquery();
    String aqueryExpression = buildRequestOptions.getBuildAquery();

    int querySourcesSpecified =
        (options.getResidue().isEmpty() ? 0 : 1)
            + (buildRequestOptions.getTargetPatternFile().isEmpty() ? 0 : 1)
            + (buildRequestOptions.getBuildQuery().isEmpty() ? 0 : 1)
            + (cqueryExpression.isEmpty() ? 0 : 1)
            + (aqueryExpression.isEmpty() ? 0 : 1);
    if (querySourcesSpecified > 1) {
      String message =
          "Only one of command-line target patterns, --target_pattern_file, --query, --cquery,"
              + " or --aquery may be specified";
      env.getReporter().handle(Event.error(message));
      return BlazeCommandResult.failureDetail(
          FailureDetail.newBuilder()
              .setMessage(message)
              .setConfigurableQuery(
                  ConfigurableQuery.newBuilder().setCode(Code.EXPRESSION_PARSE_FAILURE))
              .build());
    }
    if (!cqueryExpression.isEmpty()) {
      return CQUERY_HANDLER.query(
          env,
          options,
          cqueryExpression,
          "Interrupted while resolving repository mapping for --" + CQUERY_HANDLER.getQueryType());
    }
    if (!aqueryExpression.isEmpty()) {
      return AQUERY_HANDLER.query(
          env,
          options,
          aqueryExpression,
          "Interrupted while resolving repository mapping for --" + AQUERY_HANDLER.getQueryType());
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

}
