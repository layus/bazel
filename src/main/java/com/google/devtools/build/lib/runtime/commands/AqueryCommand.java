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

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.buildtool.AqueryProcessor;
import com.google.devtools.build.lib.buildtool.AqueryProcessor.AqueryActionFilterException;
import com.google.devtools.build.lib.buildtool.BuildAqueryProcessor;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.BuildTool;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.query2.aquery.ActionGraphQueryEnvironment;
import com.google.devtools.build.lib.query2.aquery.AqueryOptions;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.commands.QueryCommandHandler.QueryProcessor;
import com.google.devtools.build.lib.runtime.commands.QueryCommandHandler.QueryProcessor.ProcessorCreationException;
import com.google.devtools.build.lib.runtime.commands.QueryCommandHandler.QueryScopeException;
import com.google.devtools.build.lib.runtime.commands.QueryCommandHandler.UniverseScope;
import com.google.devtools.build.lib.server.FailureDetails.ActionQuery;
import com.google.devtools.build.lib.server.FailureDetails.ActionQuery.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.common.options.OptionPriority.PriorityCategory;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;

/** Handles the 'aquery' command on the Blaze command line. */
@Command(
    name = "aquery",
    buildPhase = ANALYZES,
    inheritsOptionsFrom = {BuildCommand.class},
    options = {AqueryOptions.class},
    usesConfigurationOptions = true,
    shortDescription = "Analyzes the given targets and queries the action graph.",
    allowResidue = true,
    binaryStdOut = true,
    completion = "label",
    help = "resource:aquery.txt")
public final class AqueryCommand implements QueryCommandHandler {

  static final String QUERY_TYPE = "aquery";

  @Override
  public void editOptions(OptionsParser optionsParser) {
    try {
      optionsParser.parse(
          PriorityCategory.COMPUTED_DEFAULT,
          "Option required by aquery",
          ImmutableList.of("--nobuild"));
    } catch (OptionsParsingException e) {
      throw new IllegalStateException("Aquery's known options failed to parse", e);
    }
  }

  @Override
  public String readQueryString(OptionsParsingResult options, CommandEnvironment env)
      throws QueryException {
    AqueryOptions aqueryOptions = options.getOptions(AqueryOptions.class);
    return QueryOptionHelper.readQuery(
        aqueryOptions, options, env, aqueryOptions.getQueryCurrentSkyframeState());
  }

  @Override
  public UniverseScope deriveUniverseScope(
      OptionsParsingResult options, QueryExpression expr, CommandEnvironment env)
      throws QueryScopeException {
    AqueryOptions aqueryOptions = options.getOptions(AqueryOptions.class);
    try {
      return UniverseScope.of(
          QueryCommandUtils.getTopLevelTargets(
              aqueryOptions.getUniverseScope(),
              expr,
              aqueryOptions.getQueryCurrentSkyframeState()));
    } catch (QueryException e) {
      env.getReporter().handle(Event.error(e.getMessage()));
      throw new QueryScopeException(
          createFailureResult(
              Strings.nullToEmpty(e.getMessage()),
              Code.SKYFRAME_STATE_WITH_COMMAND_LINE_EXPRESSION));
    }
  }

  @Override
  public BuildTool.AnalysisPostProcessor createStandaloneProcessor(
      QueryExpression expr, TargetPattern.Parser parser, CommandEnvironment env)
      throws QueryScopeException {
    try {
      return new AqueryProcessor(expr, parser);
    } catch (AqueryActionFilterException e) {
      String message = e.getMessage() + "\n" + expr;
      env.getReporter().handle(Event.error(message));
      throw new QueryScopeException(createFailureResult(message, Code.INVALID_AQUERY_EXPRESSION));
    }
  }

  @Override
  public BlazeCommandResult runWithProcessor(
      CommandEnvironment env,
      BuildTool.AnalysisPostProcessor processor,
      BuildRequest request,
      OptionsParsingResult options,
      UniverseScope universeScope,
      QueryProcessor queryProcessor) {
    AqueryProcessor aqueryProcessor = (AqueryProcessor) processor;
    if (options.getOptions(AqueryOptions.class).getQueryCurrentSkyframeState()) {
      return aqueryProcessor.dumpActionGraphFromSkyframe(env);
    }
    try {
      return BlazeCommandResult.detailedExitCode(
          new BuildTool(env, aqueryProcessor)
              .processRequest(request, null, options)
              .getDetailedExitCode());
    } catch (StackOverflowError e) {
      String message = "Aquery output was too large to handle";
      env.getReporter().handle(Event.error(message));
      return createFailureResult(message, Code.AQUERY_OUTPUT_TOO_BIG);
    }
  }

  @Override
  public String getQueryType() {
    return QUERY_TYPE;
  }

  @Override
  public ImmutableMap<String, QueryFunction> getFunctionsMap(CommandEnvironment env) {
    return getAqueryFunctionsMap(env);
  }

  @Override
  public BlazeCommandResult createParseFailureResult(String message) {
    return createFailureResult(message, Code.EXPRESSION_PARSE_FAILURE);
  }

  static BlazeCommandResult createFailureResult(String message, Code detailedCode) {
    return BlazeCommandResult.failureDetail(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setActionQuery(ActionQuery.newBuilder().setCode(detailedCode))
            .build());
  }

  @Override
  public QueryProcessor createQueryProcessor(CommandEnvironment env) {
    return new QueryProcessor() {
      @Override
      public java.util.List<String> deriveUniverseTargets(
          java.util.List<String> explicitScope, QueryExpression expr)
          throws ProcessorCreationException {
        try {
          return QueryCommandUtils.getTopLevelTargets(
              explicitScope, expr, /* queryCurrentSkyframeState= */ false);
        } catch (QueryException e) {
          String message = e.getMessage();
          env.getReporter().handle(Event.error(message));
          throw new ProcessorCreationException(
              createFailureResult(message, Code.INCORRECT_ARGUMENTS));
        }
      }

      @Override
      public void customizeRequestBuilder(BuildRequest.Builder builder) {}

      @Override
      public BuildTool.AnalysisPostProcessor createAnalysisPostProcessor(
          QueryExpression expr, TargetPattern.Parser parser)
          throws ProcessorCreationException {
        try {
          return new BuildAqueryProcessor(expr, parser);
        } catch (AqueryActionFilterException e) {
          String message = e.getMessage() + "\n" + expr;
          env.getReporter().handle(Event.error(message));
          throw new ProcessorCreationException(
              createFailureResult(message, Code.INVALID_AQUERY_EXPRESSION));
        }
      }

      @Override
      public void prepareOptions(OptionsParsingResult options) {
        try {
          ((OptionsParser) options)
              .parse(
                  PriorityCategory.SOFTWARE_REQUIREMENT,
                  "build --aquery suppresses the default target result printer",
                  ImmutableList.of("--show_result=0"));
        } catch (OptionsParsingException e) {
          throw new IllegalStateException("build --aquery failed to set --show_result=0", e);
        }
      }

      @Override
      public void onBuildSuccess(BuildTool.AnalysisPostProcessor processor, OutErr outErr) {
        ((BuildAqueryProcessor) processor).printMatchedActions(outErr);
      }
    };
  }

  static ImmutableMap<String, QueryFunction> getAqueryFunctionsMap(CommandEnvironment env) {
    ImmutableMap.Builder<String, QueryFunction> functionsBuilder = ImmutableMap.builder();

    for (QueryFunction queryFunction : ActionGraphQueryEnvironment.FUNCTIONS) {
      functionsBuilder.put(queryFunction.getName(), queryFunction);
    }

    for (QueryFunction queryFunction : ActionGraphQueryEnvironment.AQUERY_FUNCTIONS) {
      functionsBuilder.put(queryFunction.getName(), queryFunction);
    }

    for (QueryFunction queryFunction : env.getRuntime().getQueryFunctions()) {
      functionsBuilder.put(queryFunction.getName(), queryFunction);
    }
    return functionsBuilder.buildOrThrow();
  }
}
