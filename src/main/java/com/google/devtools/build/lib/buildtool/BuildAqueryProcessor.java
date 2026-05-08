// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.analysis.AnalysisResult;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.buildtool.AqueryProcessor.AqueryActionFilterException;
import com.google.devtools.build.lib.buildtool.BuildTool.ExitException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.query2.PostAnalysisQueryEnvironment.TopLevelConfigurations;
import com.google.devtools.build.lib.query2.aquery.ActionGraphQueryEnvironment;
import com.google.devtools.build.lib.query2.aquery.AqueryActionFilter;
import com.google.devtools.build.lib.query2.aquery.AqueryOptions;
import com.google.devtools.build.lib.query2.aquery.AqueryUtils;
import com.google.devtools.build.lib.query2.common.CommonQueryOptions;
import com.google.devtools.build.lib.query2.cquery.CqueryOptions;
import com.google.devtools.build.lib.query2.engine.ActionFilterFunction;
import com.google.devtools.build.lib.query2.engine.FunctionExpression;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.server.FailureDetails.ActionQuery;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator;
import com.google.devtools.build.lib.skyframe.RuleConfiguredTargetValue;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.skyframe.WalkableGraph;
import com.google.devtools.common.options.Options;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;

/**
 * {@link BuildTool.AnalysisPostProcessor} for {@code build --aquery}: evaluates an aquery
 * expression over the post-analysis action graph, prints the output file paths of each matched
 * action to stdout, then returns a filtered {@link AnalysisResult} so that only the owner targets
 * of those actions are built.
 *
 * <p>This is the {@code build} analogue of {@code build --cquery}: just as {@code --cquery} prints
 * the labels of matched configured targets and builds only those, {@code --aquery} prints the
 * output paths of matched actions and builds only the owning targets.
 *
 * <p>Code sharing with {@link AqueryProcessor}: both extend {@link PostAnalysisQueryProcessor} and
 * delegate to the same {@link ActionGraphQueryEnvironment} setup.
 */
public final class BuildAqueryProcessor extends PostAnalysisQueryProcessor<ConfiguredTargetValue> {

  private final AqueryActionFilter actionFilters;

  /** Matched action data collected during {@link #process}, printed after a successful build. */
  private final List<MatchedAction> matchedActions = new ArrayList<>();

  /** Snapshot of a matched action's mnemonic, description, and exec-path outputs. */
  record MatchedAction(String mnemonic, String description, ImmutableList<String> outputExecPaths) {}

  public BuildAqueryProcessor(
      @Nullable QueryExpression queryExpression, TargetPattern.Parser mainRepoTargetParser)
      throws AqueryActionFilterException {
    super(queryExpression, mainRepoTargetParser);
    actionFilters = buildActionFilters(queryExpression);
  }

  @Override
  protected CommonQueryOptions getQueryOptions(CommandEnvironment env) {
    return env.getOptions().getOptions(CqueryOptions.class);
  }

  /**
   * Evaluates the aquery expression, records the matched actions for post-build printing (see
   * {@link #printMatchedActions}), then returns a filtered {@link AnalysisResult} restricted to
   * the owner {@link ConfiguredTarget}s so the build phase only executes actions for those targets.
   */
  public void printMatchedActions(OutErr outErr) {
    PrintWriter out = new PrintWriter(outErr.getOutputStream(), /* autoFlush= */ true);
    for (MatchedAction action : matchedActions) {
      out.println("Action " + action.mnemonic() + " (" + action.description() + ") up-to-date:");
      for (String path : action.outputExecPaths()) {
        out.println("  " + path);
      }
    }
    out.flush();
  }

  /**
   * Evaluates the aquery expression, records matched actions for deferred printing, then returns
   * a filtered {@link AnalysisResult} restricted to the owner {@link ConfiguredTarget}s.
   */
  @Override
  public AnalysisResult process(
      BuildRequest request,
      CommandEnvironment env,
      BlazeRuntime runtime,
      AnalysisResult analysisResult)
      throws InterruptedException, ViewCreationFailedException, ExitException {
    env.getSkyframeExecutor().deleteOldNodes(/* versionWindowForDirtyGc= */ 0);
    env.getSkyframeExecutor().applyInvalidation(env.getReporter());

    Set<ConfiguredTargetValue> matchedValues;
    try {
      matchedValues =
          evaluateQueryNodes(
              request,
              env,
              new TopLevelConfigurations(analysisResult.getTopLevelTargetsWithConfigs()),
              analysisResult.getAspectsMap(),
              env.getSkyframeExecutor().getTransitiveConfigurationKeys(),
              queryExpression);
    } catch (QueryException e) {
      String errorMessage = "Error evaluating --aquery expression";
      if (!request.getKeepGoing()) {
        throw new ViewCreationFailedException(errorMessage, e.getFailureDetail(), e);
      }
      env.getReporter().error(null, errorMessage + ": " + e.getFailureDetail().getMessage());
      return analysisResult;
    } catch (IOException e) {
      FailureDetail failureDetail =
          FailureDetail.newBuilder()
              .setMessage("I/O error evaluating --aquery expression: " + e.getMessage())
              .setActionQuery(
                  ActionQuery.newBuilder().setCode(ActionQuery.Code.AQUERY_OUTPUT_TOO_BIG))
              .build();
      throw new ExitException(DetailedExitCode.of(failureDetail));
    }

    ImmutableSet.Builder<ConfiguredTarget> ownersBuilder = ImmutableSet.builder();
    for (ConfiguredTargetValue ctv : matchedValues) {
      ConfiguredTarget ct = ctv.getConfiguredTarget();
      if (ct != null) {
        ownersBuilder.add(ct);
      }
      if (!(ctv instanceof RuleConfiguredTargetValue rctv)) {
        continue;
      }
      for (ActionAnalysisMetadata action : rctv.getActions()) {
        if (!AqueryUtils.matchesAqueryFilters(
            action, actionFilters, /* includePrunedInputs= */ false)) {
          continue;
        }
        ImmutableList<String> outputPaths =
            action.getOutputs().stream()
                .map(Artifact::getExecPathString)
                .collect(ImmutableList.toImmutableList());
        matchedActions.add(
            new MatchedAction(action.getMnemonic(), action.describe(), outputPaths));
      }
    }

    return analysisResult.withFilteredTargets(ownersBuilder.build());
  }

  @Override
  protected ActionGraphQueryEnvironment getQueryEnvironment(
      BuildRequest request,
      CommandEnvironment env,
      TopLevelConfigurations topLevelConfigurations,
      ImmutableMap<String, BuildConfigurationValue> transitiveConfigurations,
      ImmutableMap<AspectKeyCreator.AspectKey, ConfiguredAspect> topLevelAspects,
      WalkableGraph walkableGraph) {
    ImmutableList<QueryFunction> extraFunctions =
        ImmutableList.<QueryFunction>builder()
            .addAll(ActionGraphQueryEnvironment.AQUERY_FUNCTIONS)
            .addAll(env.getRuntime().getQueryFunctions())
            .build();
    AqueryOptions aqueryOptions = Options.getDefaults(AqueryOptions.class);
    StarlarkSemantics starlarkSemantics =
        env.getSkyframeExecutor()
            .getEffectiveStarlarkSemantics(
                env.getOptions().getOptions(BuildLanguageOptions.class));
    ActionGraphQueryEnvironment queryEnvironment =
        new ActionGraphQueryEnvironment(
            request.getKeepGoing(),
            env.getReporter(),
            extraFunctions,
            topLevelConfigurations,
            transitiveConfigurations,
            mainRepoTargetParser,
            env.getPackageManager().getPackagePath(),
            () -> walkableGraph,
            aqueryOptions,
            aqueryOptions.getLabelPrinter(
                starlarkSemantics, mainRepoTargetParser.getRepoMapping()));
    queryEnvironment.setActionFilters(actionFilters);
    return queryEnvironment;
  }

  private static AqueryActionFilter buildActionFilters(@Nullable QueryExpression queryExpression)
      throws AqueryActionFilterException {
    AqueryActionFilter.Builder actionFiltersBuilder = AqueryActionFilter.builder();

    if (!(queryExpression instanceof FunctionExpression)) {
      return actionFiltersBuilder.build();
    }

    Optional<FunctionExpression> functionExpressionOptional =
        Optional.of((FunctionExpression) queryExpression);
    FunctionExpression nonAqueryFilterFunctionExpression = null;

    while (functionExpressionOptional.isPresent()) {
      FunctionExpression functionExpression = functionExpressionOptional.get();

      if (functionExpression.getFunction() instanceof ActionFilterFunction actionFilterFunction) {
        if (nonAqueryFilterFunctionExpression != null) {
          throw new AqueryActionFilterException(
              "aquery filter functions (inputs, outputs, mnemonic) produce actions, and therefore"
                  + " can't be the input of other function types: "
                  + nonAqueryFilterFunctionExpression.getFunction().getName());
        }
        String patternString = functionExpression.getArgs().get(0).getWord();
        try {
          actionFiltersBuilder.put(actionFilterFunction.getName(), Pattern.compile(patternString));
        } catch (PatternSyntaxException e) {
          throw new AqueryActionFilterException("Wrong query syntax: " + e.getMessage());
        }
      } else {
        nonAqueryFilterFunctionExpression = functionExpression;
      }

      functionExpressionOptional = getNextFunctionExpression(functionExpression);
    }

    return actionFiltersBuilder.build();
  }

  private static Optional<FunctionExpression> getNextFunctionExpression(
      FunctionExpression functionExpression) {
    for (Argument arg : functionExpression.getArgs()) {
      if (arg.getType() == ArgumentType.EXPRESSION
          && arg.getExpression() instanceof FunctionExpression) {
        return Optional.of((FunctionExpression) arg.getExpression());
      }
    }
    return Optional.empty();
  }
}
