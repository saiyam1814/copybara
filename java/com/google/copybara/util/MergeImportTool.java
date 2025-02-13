/*
 * Copyright (C) 2022 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.util;

import com.google.copybara.util.console.Console;
import com.google.copybara.shell.CommandException;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;

/**
 * A tool that assists with Merge Imports
 *
 * <p>Merge Imports allow users to persist changes in non-source-of-truth destinations. Destination
 * only changes are defined by files that exist in the destination workdir, but not in the origin
 * baseline or in the origin workdir.
 */
public final class MergeImportTool {

  private final Console console;
  private final Diff3Util diff3Util;

  // TODO refactor to accept a diffing tool
  public MergeImportTool(Console console, Diff3Util diff3Util) {
    this.console = console;
    this.diff3Util = diff3Util;
  }

  /**
   * A command that calls diff3 to merge files in the working directories
   *
   * <p>The origin is treated as the source of truth. Files that exist at baseline and destination
   * but not in the origin will be deleted. Files that exist in the destination but not in the
   * origin or in the baseline will be considered "destination only" and propagated.
   *
   * @param originWorkdir The working directory for the origin repository, already populated by the
   *     caller
   * @param destinationWorkdir A copy of the destination repository state, already populated by the
   *     caller
   * @param baselineWorkdir A copy of the baseline repository state, already populated by the caller
   * @param diff3Workdir A working directory for the Diff3Util
   */
  public void mergeImport(
      Path originWorkdir, Path destinationWorkdir, Path baselineWorkdir, Path diff3Workdir)
      throws IOException {
    HashSet<Path> visitedSet = new HashSet<>();
    HashSet<Path> mergeErrorPaths = new HashSet<>();

    SimpleFileVisitor<Path> originWorkdirFileVisitor =
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (attrs.isSymbolicLink()) {
              return FileVisitResult.CONTINUE;
            }
            Path relativeFile = originWorkdir.relativize(file);
            Path baselineFile = baselineWorkdir.resolve(relativeFile);
            Path destinationFile = destinationWorkdir.resolve(relativeFile);
            CommandOutputWithStatus output;
            if (!Files.exists(destinationFile) || !Files.exists(baselineFile)) {
              return FileVisitResult.CONTINUE;
            }
            try {
              output = diff3Util.diff(file, destinationFile, baselineFile, diff3Workdir);
              visitedSet.add(relativeFile);
              if (output.getTerminationStatus().getExitCode() == 1) {
                mergeErrorPaths.add(file);
                return FileVisitResult.CONTINUE;
              }
            } catch (CommandException e) {
              throw new IOException("Could not execute diff3", e);
            }
            Files.write(file, output.getStdoutBytes());

            return FileVisitResult.CONTINUE;
          }
        };

    SimpleFileVisitor<Path> destinationWorkdirFileVisitor =
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (attrs.isSymbolicLink()) {
              return FileVisitResult.CONTINUE;
            }
            Path relativeFile = destinationWorkdir.relativize(file);
            Path originFile = originWorkdir.resolve(relativeFile);
            Path baselineFile = baselineWorkdir.resolve(relativeFile);
            if (visitedSet.contains(relativeFile)) {
              return FileVisitResult.CONTINUE;
            }
            // destination only file - keep it
            if (!Files.exists(originFile) && !Files.exists(baselineFile)) {
              Files.copy(file, originWorkdir.resolve(relativeFile));
            }
            // file was deleted in origin, propagate to destination
            if (!Files.exists(originFile) && Files.exists(baselineFile)) {
              Files.delete(file);
            }
            return FileVisitResult.CONTINUE;
          }
        };

    Files.walkFileTree(originWorkdir, originWorkdirFileVisitor);
    Files.walkFileTree(destinationWorkdir, destinationWorkdirFileVisitor);
    if (!mergeErrorPaths.isEmpty()) {
      mergeErrorPaths.forEach(
          path -> console.warn(String.format("Merge error for path %s", path.toString())));
    }
  }
}
