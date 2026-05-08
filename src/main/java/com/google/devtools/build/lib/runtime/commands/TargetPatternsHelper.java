// Copyright 2020 The Bazel Authors. All rights reserved.
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.devtools.build.lib.buildtool.BuildRequestOptions;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.cmdline.TargetPattern.Parser;
import com.google.devtools.build.lib.packages.LabelPrinter;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.query2.common.AbstractBlazeQueryEnvironment;
import com.google.devtools.build.lib.query2.common.UniverseScope;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QuerySyntaxException;
import com.google.devtools.build.lib.query2.engine.QueryUtil;
import com.google.devtools.build.lib.query2.engine.QueryUtil.AggregateAllOutputFormatterCallback;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.runtime.LoadingPhaseThreadsOption;
import com.google.devtools.build.lib.runtime.ProjectFileSupport;
import com.google.devtools.build.lib.runtime.events.InputFileEvent;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.TargetPatterns;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue.RepositoryMappingResolutionException;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.common.options.OptionsParsingResult;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Provides support for reading target patterns from a file or the command-line. */
public final class TargetPatternsHelper {

  private static final Splitter TARGET_PATTERN_SPLITTER = Splitter.on('#');

  private TargetPatternsHelper() {}

  /**
   * Reads a list of target patterns, either from the command-line residue, by reading newline
   * delimited target patterns from the --target_pattern_file flag, or by evaluating a query
   * expression from the --query flag. If multiple sources are specified, or if reading fails,
   * throws {@link TargetPatternsHelperException}.
   */
  public static List<String> readFrom(CommandEnvironment env, OptionsParsingResult options)
      throws TargetPatternsHelperException {
    List<String> targets = options.getResidue();
    BuildRequestOptions buildRequestOptions = options.getOptions(BuildRequestOptions.class);
    String queryExpression = buildRequestOptions.getBuildQuery();
    String targetPatternFile = buildRequestOptions.getTargetPatternFile();

    // Check for conflicting options
    int sourcesSpecified =
        (targets.isEmpty() ? 0 : 1)
            + (targetPatternFile.isEmpty() ? 0 : 1)
            + (queryExpression.isEmpty() ? 0 : 1);
    if (sourcesSpecified > 1) {
      throw new TargetPatternsHelperException(
          "Only one of command-line target patterns, --target_pattern_file, or --query may be"
              + " specified",
          TargetPatterns.Code.TARGET_PATTERN_FILE_WITH_COMMAND_LINE_PATTERN);
    }

    if (!queryExpression.isEmpty()) {
      return resolveQueryTargets(env, options, queryExpression);
    } else if (!targetPatternFile.isEmpty()) {
      // Works for absolute or relative file.
      Path residuePath =
          env.getWorkingDirectory().getRelative(buildRequestOptions.getTargetPatternFile());
      try {
        env.getEventBus()
            .post(
                InputFileEvent.create(
                    /* type= */ "target_pattern_file", residuePath.getFileSize()));
        targets =
            FileSystemUtils.readLines(residuePath, ISO_8859_1).stream()
                .map(s -> TARGET_PATTERN_SPLITTER.splitToList(s).get(0))
                .map(String::trim)
                .filter(Predicate.not(String::isEmpty))
                .collect(toImmutableList());
      } catch (IOException e) {
        throw new TargetPatternsHelperException(
            "I/O error reading from " + residuePath.getPathString() + ": " + e.getMessage(),
            TargetPatterns.Code.TARGET_PATTERN_FILE_READ_FAILURE);
      }
    } else {
      try (SilentCloseable closeable =
          Profiler.instance().profile("ProjectFileSupport.getTargets")) {
        targets = ProjectFileSupport.getTargets(env.getRuntime().getProjectFileProvider(), options);
      }
    }
    return targets;
  }

  /**
   * Evaluates a query expression and returns the resulting target labels as strings.
   */
  private static List<String> resolveQueryTargets(
      CommandEnvironment env, OptionsParsingResult options, String queryExpression)
      throws TargetPatternsHelperException {
    try (SilentCloseable closeable = Profiler.instance().profile("resolveQueryTargets")) {
      boolean keepGoing = options.getOptions(KeepGoingOption.class).getKeepGoing();
      int loadingPhaseThreads = options.getOptions(LoadingPhaseThreadsOption.class).getThreads();

      TargetPattern.Parser mainRepoTargetParser;
      try {
        RepositoryMapping repoMapping =
            env.getSkyframeExecutor()
                .getMainRepoMapping(keepGoing, loadingPhaseThreads, env.getReporter());
        mainRepoTargetParser =
            new Parser(
                env.getRelativeWorkingDirectory(), RepositoryName.MAIN, repoMapping);
      } catch (RepositoryMappingResolutionException e) {
        throw new TargetPatternsHelperException(
            "Failed to get repository mapping: " + e.getMessage(),
            TargetPatterns.Code.TARGET_PATTERN_PARSE_FAILURE);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new TargetPatternsHelperException(
            "Interrupted while resolving repository mapping for --query",
            TargetPatterns.Code.TARGET_PATTERN_PARSE_FAILURE);
      }

      LabelPrinter labelPrinter = LabelPrinter.legacy();
      Set<Setting> settings = EnumSet.noneOf(Setting.class);

      try (AbstractBlazeQueryEnvironment<Target> queryEnv =
          QueryEnvironmentBasedCommand.newQueryEnvironment(
              env,
              keepGoing,
              /* orderedResults= */ false,
              UniverseScope.INFER_FROM_QUERY_EXPRESSION,
              loadingPhaseThreads,
              settings,
              /* useGraphlessQuery= */ false,
              mainRepoTargetParser,
              labelPrinter)) {

        QueryExpression expr = QueryExpression.parse(queryExpression, queryEnv);
        expr = queryEnv.transformParsedQuery(expr);

        AggregateAllOutputFormatterCallback<Target, Set<Target>> callback =
            QueryUtil.newOrderedAggregateAllOutputFormatterCallback(queryEnv);

        queryEnv.evaluateQuery(expr, callback);

        return callback.getResult().stream()
            .map(target -> labelPrinter.toString(target.getLabel()))
            .collect(toImmutableList());

      } catch (QuerySyntaxException e) {
        throw new TargetPatternsHelperException(
            "Query syntax error in --query: " + e.getMessage(),
            TargetPatterns.Code.TARGET_PATTERN_PARSE_FAILURE);
      } catch (QueryException e) {
        throw new TargetPatternsHelperException(
            "Query evaluation error in --query: " + e.getMessage(),
            TargetPatterns.Code.TARGET_PATTERN_PARSE_FAILURE);
      } catch (IOException e) {
        throw new TargetPatternsHelperException(
            "I/O error during --query evaluation: " + e.getMessage(),
            TargetPatterns.Code.TARGET_PATTERN_PARSE_FAILURE);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new TargetPatternsHelperException(
            "Interrupted during --query evaluation",
            TargetPatterns.Code.TARGET_PATTERN_PARSE_FAILURE);
      }
    }
  }

  /** Thrown when target patterns couldn't be read. */
  public static class TargetPatternsHelperException extends Exception {
    private final TargetPatterns.Code detailedCode;

    private TargetPatternsHelperException(String message, TargetPatterns.Code detailedCode) {
      super(Preconditions.checkNotNull(message));
      this.detailedCode = detailedCode;
    }

    public FailureDetail getFailureDetail() {
      return FailureDetail.newBuilder()
          .setMessage(getMessage())
          .setTargetPatterns(TargetPatterns.newBuilder().setCode(detailedCode))
          .build();
    }
  }
}
