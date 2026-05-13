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
import com.google.devtools.build.lib.buildtool.BuildTool;
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
import com.google.devtools.common.options.OptionsParsingResult;
import java.util.List;

/**
 * Implemented by query commands ({@link AqueryCommand}, {@link CqueryCommand}) to provide the
 * standalone execution skeleton ({@code aquery} / {@code cquery} commands).
 *
 * <p>The {@link #exec} default handles the full execution: cache reset, query parsing, target
 * derivation, processor creation, and {@link BuildTool#processRequest}. Implementors supply the
 * per-type details via the abstract methods below.
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

  /** Thrown when query setup fails (e.g. invalid filter); carries the pre-built failure result. */
  final class QuerySetupException extends Exception {
    private final BlazeCommandResult result;

    QuerySetupException(BlazeCommandResult result) {
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
   * Reads the raw query string from the command-line residue or {@code --query_file}, handling
   * any query-type-specific allowance for an empty expression.
   *
   * @throws QueryException if the query string cannot be read
   */
  String readQueryString(OptionsParsingResult options, CommandEnvironment env)
      throws QueryException;

  /**
   * Derives the top-level universe targets from the options and parsed expression.
   *
   * @throws QuerySetupException if the scope is invalid
   */
  List<String> deriveUniverseTargets(
      CommandEnvironment env, OptionsParsingResult options, QueryExpression expr)
      throws QuerySetupException;

  /**
   * Constructs the standalone {@link BuildTool.AnalysisPostProcessor} for this query type.
   *
   * @throws QuerySetupException if processor construction fails
   */
  BuildTool.AnalysisPostProcessor createStandaloneProcessor(
      QueryExpression expr, TargetPattern.Parser parser, CommandEnvironment env)
      throws QuerySetupException;

  /**
   * Applies any query-type-specific settings to the {@link BuildRequest.Builder} (e.g. disabling
   * conflict checks for cquery). No-op by default.
   */
  default void customizeStandaloneRequest(BuildRequest.Builder builder) {}

  /**
   * Runs the query build request. The default uses the standard 3-argument
   * {@link BuildTool#processRequest}. Override to handle query-type-specific paths (e.g. aquery's
   * skyframe state or stack-overflow protection).
   */
  default BlazeCommandResult runStandaloneQuery(
      CommandEnvironment env,
      BuildTool.AnalysisPostProcessor processor,
      BuildRequest request,
      OptionsParsingResult options) {
    return BlazeCommandResult.detailedExitCode(
        new BuildTool(env, processor).processRequest(request, /* validator= */ null, options)
            .getDetailedExitCode());
  }

  /** Resets remote-analysis-cache deserialized keys before a standalone query. */
  default void resetCache(CommandEnvironment env) {
    QueryCommandUtils.resetDeserializedKeysFromRemoteAnalysisCache(env);
  }

  @Override
  default BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    resetCache(env);

    String queryString;
    try {
      queryString = readQueryString(options, env);
    } catch (QueryException e) {
      return BlazeCommandResult.failureDetail(e.getFailureDetail());
    }

    TargetPattern.Parser mainRepoTargetParser;
    try {
      mainRepoTargetParser =
          QueryCommandUtils.resolveMainRepoTargetParserOrReport(
              env, options, "Fetch interrupted: ");
    } catch (RepoMappingException e) {
      return e.getResult();
    }

    QueryExpression expr;
    try {
      expr = parseQueryExpression(env, queryString);
    } catch (QueryParseException e) {
      return e.getResult();
    }

    List<String> universeTargets;
    BuildTool.AnalysisPostProcessor processor;
    try {
      universeTargets = deriveUniverseTargets(env, options, expr);
      processor = createStandaloneProcessor(expr, mainRepoTargetParser, env);
    } catch (QuerySetupException e) {
      return e.getResult();
    }

    BuildRequest.Builder builder =
        BuildRequest.builder()
            .setCommandName(getClass().getAnnotation(Command.class).name())
            .setId(env.getCommandId())
            .setOptions(options)
            .setStartupOptions(env.getRuntime().getStartupOptionsProvider())
            .setOutErr(env.getReporter().getOutErr())
            .setTargets(universeTargets)
            .setStartTimeMillis(env.getCommandStartTime());
    customizeStandaloneRequest(builder);

    return runStandaloneQuery(env, processor, builder.build(), options);
  }
}
