// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.BuildResult;
import com.google.devtools.build.lib.buildtool.BuildTool;
import com.google.devtools.build.lib.query2.cquery.CqueryOptions;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QueryParser;
import com.google.devtools.build.lib.query2.engine.QuerySyntaxException;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.commands.QueryCommandUtils.RepoMappingException;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.common.options.OptionsParsingResult;
import java.util.List;

/**
 * Implemented by query commands ({@link AqueryCommand}, {@link CqueryCommand}) to expose shared
 * parsing and execution logic to {@link BuildCommand}.
 *
 * <p>The {@link #exec} default method provides the common skeleton for both standalone query
 * commands; implementors supply the differing details via the abstract methods below.
 */
interface QueryCommandHandler extends BlazeCommand {

  /** Thrown when a query expression fails to parse; carries the pre-built failure result. */
  final class QueryParseException extends Exception {
    private final BlazeCommandResult result;

    QueryParseException(BlazeCommandResult result) {
      this.result = result;
    }

    BlazeCommandResult getResult() {
      return result;
    }
  }

  /** Returns the query type name (e.g. {@code "aquery"} or {@code "cquery"}). */
  String getQueryType();

  /** Returns the map of supported {@link QueryFunction}s for this query command. */
  ImmutableMap<String, QueryFunction> getFunctionsMap(CommandEnvironment env);

  /** Returns a failure result for an expression parse error with the given message. */
  BlazeCommandResult createParseFailureResult(String message);

  /**
   * Parses {@code expressionString} using this handler's functions, reporting a human-readable
   * error and throwing {@link QueryParseException} on syntax failure.
   */
  default QueryExpression parseQueryExpression(
      CommandEnvironment env, String expressionString) throws QueryParseException {
    try {
      return QueryParser.parse(expressionString, getFunctionsMap(env));
    } catch (QuerySyntaxException e) {
      String message =
          String.format(
              "Error while parsing %s expression '%s': %s",
              getQueryType(), QueryExpression.truncate(expressionString), e.getMessage());
      env.getReporter().handle(Event.error(message));
      throw new QueryParseException(createParseFailureResult(message));
    }
  }

  /**
   * Holds the universe targets derived for a query execution.
   *
   * @param targets top-level targets to analyse
   * @param targetsForProjectResolution targets passed to project resolution (may be the same list
   *     or a different subset; {@code null} if unused by this query type)
   */
  record UniverseScope(List<String> targets, List<String> targetsForProjectResolution) {
    static UniverseScope of(List<String> targets) {
      return new UniverseScope(targets, null);
    }
  }

  /**
   * Reads the raw query string from the command-line residue or {@code --query_file}, handling
   * any query-type-specific allowance for an empty expression.
   *
   * @throws QueryException if the query string cannot be read
   */
  String readQueryString(OptionsParsingResult options, CommandEnvironment env)
      throws QueryException;

  /**
   * Derives the universe targets for the standalone query command from the explicit scope option
   * and the parsed expression.
   *
   * @throws BlazeCommandResult if the scope is invalid; implementations should throw via {@link
   *     QueryScopeException}
   */
  UniverseScope deriveUniverseScope(
      OptionsParsingResult options, QueryExpression expr, CommandEnvironment env)
      throws QueryScopeException;

  /** Thrown when universe-scope derivation fails; carries the ready-to-return result. */
  final class QueryScopeException extends Exception {
    private final BlazeCommandResult result;

    QueryScopeException(BlazeCommandResult result) {
      this.result = result;
    }

    BlazeCommandResult getResult() {
      return result;
    }
  }

  /**
   * Constructs the standalone {@link BuildTool.AnalysisPostProcessor} used by the dedicated
   * {@code aquery} / {@code cquery} commands (as opposed to {@code build --aquery/--cquery}).
   *
   * @throws QueryScopeException if processor construction fails
   */
  BuildTool.AnalysisPostProcessor createStandaloneProcessor(
      QueryExpression expr, TargetPattern.Parser parser, CommandEnvironment env)
      throws QueryScopeException;

  /**
   * Runs {@link BuildTool#processRequest} with the given processor, handling any
   * query-type-specific overloads, error handling, and post-processing.
   *
   * <p>The default implementation uses the standard 3-argument {@code processRequest} and calls
   * {@link QueryProcessor#onBuildSuccess} on success. Standalone query commands may override this
   * to use a different overload (e.g. cquery's 5-argument form) or handle the skyframe path.
   */
  default BlazeCommandResult runWithProcessor(
      CommandEnvironment env,
      BuildTool.AnalysisPostProcessor processor,
      BuildRequest request,
      OptionsParsingResult options,
      UniverseScope universeScope,
      QueryProcessor queryProcessor) {
    BuildResult buildResult =
        new BuildTool(env, processor).processRequest(request, /* validator= */ null, options);
    if (buildResult.getSuccess()) {
      queryProcessor.onBuildSuccess(processor, env.getReporter().getOutErr());
    }
    return BlazeCommandResult.detailedExitCode(buildResult.getDetailedExitCode());
  }

  /**
   * Applies any query-type-specific settings to the standalone {@link BuildRequest.Builder}
   * (e.g. disabling conflict checks for cquery). No-op by default.
   */
  default void customizeStandaloneRequestBuilder(BuildRequest.Builder builder) {}

  /** Resets remote-analysis-cache deserialized keys; timing varies by query type. */
  default void resetCache(CommandEnvironment env) {
    QueryCommandUtils.resetDeserializedKeysFromRemoteAnalysisCache(env);
  }

  /**
   * Core query execution: resolves the repo mapping, parses {@code expressionString}, derives the
   * universe scope, constructs the analysis post-processor, builds and runs the
   * {@link BuildRequest}, and invokes {@link QueryProcessor#onBuildSuccess} on success.
   *
   * <p>Used directly by {@link BuildCommand} for in-build queries ({@code build --aquery/--cquery})
   * and called from the {@link #exec} default for standalone query commands.
   *
   * @param expressionString the raw query expression (non-empty; callers must not pass empty)
   * @param interruptMessagePrefix prefix for the repo-mapping interrupt message
   */
  default BlazeCommandResult query(
      CommandEnvironment env,
      OptionsParsingResult options,
      String expressionString,
      String interruptMessagePrefix) {
    TargetPattern.Parser mainRepoTargetParser;
    try {
      mainRepoTargetParser =
          QueryCommandUtils.resolveMainRepoTargetParserOrReport(
              env, options, interruptMessagePrefix);
    } catch (RepoMappingException e) {
      return e.getResult();
    }

    QueryExpression expr;
    try {
      expr = expressionString.isEmpty() ? null : parseQueryExpression(env, expressionString);
    } catch (QueryParseException e) {
      return e.getResult();
    }

    QueryProcessor queryProcessor = createQueryProcessor(env);

    List<String> universeTargets;
    BuildTool.AnalysisPostProcessor processor;
    try {
      universeTargets =
          queryProcessor.deriveUniverseTargets(
              options.getOptions(CqueryOptions.class).getUniverseScope(), expr);
      processor = queryProcessor.createAnalysisPostProcessor(expr, mainRepoTargetParser);
    } catch (QueryProcessor.ProcessorCreationException e) {
      return e.getResult();
    }

    BuildRequest.Builder requestBuilder =
        BuildRequest.builder()
            .setCommandName(getClass().getAnnotation(Command.class).name())
            .setId(env.getCommandId())
            .setOptions(options)
            .setStartupOptions(env.getRuntime().getStartupOptionsProvider())
            .setOutErr(env.getReporter().getOutErr())
            .setTargets(universeTargets)
            .setStartTimeMillis(env.getCommandStartTime());
    queryProcessor.customizeRequestBuilder(requestBuilder);
    queryProcessor.prepareOptions(options);

    return runWithProcessor(
        env, processor, requestBuilder.build(), options, UniverseScope.of(universeTargets),
        queryProcessor);
  }

  /**
   * Standalone exec skeleton: resets the cache, reads the query string, then calls {@link #query}.
   *
   * <p>Standalone query commands ({@code aquery}, {@code cquery}) use this via the interface
   * default. The standalone-specific divergences (processor class, {@code processRequest} overload,
   * skyframe path) are handled by overriding {@link #runWithProcessor}.
   */
  default BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    resetCache(env);

    String queryString;
    try {
      queryString = readQueryString(options, env);
    } catch (QueryException e) {
      return BlazeCommandResult.failureDetail(e.getFailureDetail());
    }

    return query(env, options, queryString, "Fetch interrupted: ");
  }

  /**
   * Returns a {@link QueryProcessor} that encapsulates all query-type-specific behaviour needed
   * by {@link BuildCommand#execWithQuery}.
   */
  QueryProcessor createQueryProcessor(CommandEnvironment env);

  /**
   * Encapsulates the per-query-type behaviour for {@code build --aquery} / {@code build --cquery}.
   *
   * <p>Implementations are created fresh per invocation via {@link #createQueryProcessor()} so
   * that stateful processors (e.g. {@code BuildAqueryProcessor} caching matched actions) are safe.
   */
  interface QueryProcessor {

    /**
     * Thrown when processor setup fails (e.g. invalid aquery filter); carries the pre-built
     * {@link BlazeCommandResult} the caller should return.
     */
    final class ProcessorCreationException extends Exception {
      private final BlazeCommandResult result;

      ProcessorCreationException(BlazeCommandResult result) {
        this.result = result;
      }

      BlazeCommandResult getResult() {
        return result;
      }
    }

    /**
     * Derives the list of top-level universe targets from the explicit scope and the parsed
     * expression.
     *
     * @throws ProcessorCreationException if the scope is invalid (e.g. incompatible with
     *     {@code --skyframe_state})
     */
    List<String> deriveUniverseTargets(List<String> explicitScope, QueryExpression expr)
        throws ProcessorCreationException;

    /**
     * Applies any query-type-specific settings to the {@link BuildRequest.Builder}
     * (e.g. disabling conflict checks for cquery).
     */
    void customizeRequestBuilder(BuildRequest.Builder builder);

    /**
     * Constructs and returns the {@link BuildTool.AnalysisPostProcessor} for this query type.
     *
     * @throws ProcessorCreationException if construction fails (e.g. invalid aquery action
     *     filter expression)
     */
    BuildTool.AnalysisPostProcessor createAnalysisPostProcessor(
        QueryExpression expr, TargetPattern.Parser parser) throws ProcessorCreationException;

    /**
     * Performs any pre-build option adjustments (e.g. suppressing the default result printer
     * for {@code build --aquery}).
     */
    void prepareOptions(OptionsParsingResult options);

    /**
     * Called after a successful build to perform any post-build output
     * (e.g. printing matched actions for {@code build --aquery}).
     *
     * @param processor the same instance returned by {@link #createAnalysisPostProcessor}
     */
    void onBuildSuccess(BuildTool.AnalysisPostProcessor processor, OutErr outErr);
  }
}
