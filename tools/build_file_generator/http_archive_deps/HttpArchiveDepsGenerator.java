/*
 * Copyright 2018 The StartupOS Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.startupos.tools.build_file_generator.http_archive_deps;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.startupos.common.FileUtils;
import com.google.startupos.common.flags.Flag;
import com.google.startupos.common.flags.FlagDesc;
import com.google.startupos.common.repo.GitRepo;
import com.google.startupos.common.repo.GitRepoFactory;
import com.google.startupos.tools.build_file_generator.BuildFileParser;
import com.google.startupos.tools.build_file_generator.JavaClassAnalyzer;
import com.google.startupos.tools.build_file_generator.Protos.BuildFile;
import com.google.startupos.tools.build_file_generator.Protos.HttpArchiveDep;
import com.google.startupos.tools.build_file_generator.Protos.HttpArchiveDeps;
import com.google.startupos.tools.build_file_generator.Protos.JavaClass;
import com.google.startupos.tools.build_file_generator.Protos.WorkspaceFile;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;

public class HttpArchiveDepsGenerator {
  private static final FluentLogger log = FluentLogger.forEnclosingClass();
  private static final String BUILD_GENERATOR_TEMP_FOLDER = "build_generator_tmp";

  @FlagDesc(name = "http_archive_names", description = "http_archive names to process")
  static final Flag<List<String>> httpArchiveNames =
      Flag.createStringsListFlag(Collections.singletonList("startup_os"));

  private BuildFileParser buildFileParser;
  private JavaClassAnalyzer javaClassAnalyzer;
  private FileUtils fileUtils;
  private GitRepoFactory gitRepoFactory;

  @Inject
  public HttpArchiveDepsGenerator(
      BuildFileParser buildFileParser,
      JavaClassAnalyzer javaClassAnalyzer,
      FileUtils fileUtils,
      GitRepoFactory gitRepoFactory) {
    this.buildFileParser = buildFileParser;
    this.javaClassAnalyzer = javaClassAnalyzer;
    this.fileUtils = fileUtils;
    this.gitRepoFactory = gitRepoFactory;
  }

  public HttpArchiveDeps getHttpArchiveDeps(WorkspaceFile workspaceFile) throws IOException {
    HttpArchiveDeps.Builder result = HttpArchiveDeps.newBuilder();
    for (String httpArchiveName : httpArchiveNames.get()) {
      WorkspaceFile.HttpArchive httpArchive = WorkspaceFile.HttpArchive.getDefaultInstance();
      for (WorkspaceFile.HttpArchive currentHttpArchive : workspaceFile.getHttpArchiveList()) {
        if (currentHttpArchive.getName().equals(httpArchiveName)) {
          httpArchive = currentHttpArchive;
        }
      }
      if (httpArchive.getName().equals(httpArchiveName)) {
        String url = httpArchive.getUrls(0).split("/archive")[0] + ".git";
        String repoName = url.substring(url.lastIndexOf('/') + 1).replace(".git", "");

        GitRepo gitRepo = createRepo(url, repoName);
        switchToCommit(gitRepo, httpArchive.getStripPrefix());

        String absRepoPath =
            fileUtils.joinPaths(
                fileUtils.getCurrentWorkingDirectory(), BUILD_GENERATOR_TEMP_FOLDER, repoName);
        ImmutableList<String> getBuildFilesAbsPaths = getBuildFilesAbsPaths(absRepoPath);
        for (String path : getBuildFilesAbsPaths) {
          BuildFile buildFile = buildFileParser.getBuildFile(path);
          for (BuildFile.JavaLibrary javaLibrary : buildFile.getJavaLibraryList()) {
            addDeps(absRepoPath, result, path, javaLibrary.getSrcsList(), javaLibrary.getName());
          }
          for (BuildFile.JavaBinary javaBinary : buildFile.getJavaBinaryList()) {
            addDeps(absRepoPath, result, path, javaBinary.getSrcsList(), javaBinary.getName());
          }
        }
        result.setCommitId(getCommitId(httpArchive.getStripPrefix()));
        fileUtils.clearDirectoryUnchecked(
            fileUtils.joinPaths(
                fileUtils.getCurrentWorkingDirectory(), BUILD_GENERATOR_TEMP_FOLDER));
      } else {
        log.atWarning().log("Can't find http_archive with name: %s", httpArchiveName);
      }
    }
    fileUtils.deleteFileOrDirectoryIfExists(
        fileUtils.joinPaths(fileUtils.getCurrentWorkingDirectory(), BUILD_GENERATOR_TEMP_FOLDER));
    return result.build();
  }

  private GitRepo createRepo(String url, String repoName) {
    String absRepoPath =
        fileUtils.joinPaths(
            fileUtils.getCurrentWorkingDirectory(), BUILD_GENERATOR_TEMP_FOLDER, repoName);
    GitRepo gitRepo = gitRepoFactory.create(absRepoPath);
    gitRepo.cloneRepo(url, absRepoPath);
    return gitRepo;
  }

  private void switchToCommit(GitRepo gitRepo, String stripPrefix) {
    gitRepo.resetHard(getCommitId(stripPrefix));
  }

  private String getCommitId(String stripPrefix) {
    return stripPrefix.substring(stripPrefix.lastIndexOf('-') + 1);
  }

  private ImmutableList<String> getBuildFilesAbsPaths(String absRepoPath) {
    try {
      return ImmutableList.copyOf(
          fileUtils
              .listContentsRecursively(absRepoPath)
              .stream()
              .filter(path -> path.endsWith("/BUILD"))
              .filter(path -> !path.contains("/third_party/maven/"))
              .collect(Collectors.toList()));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void addDeps(
      String absRepoPath,
      HttpArchiveDeps.Builder result,
      String absBuildFilePath,
      List<String> javaClasses,
      String targetName)
      throws IOException {
    for (String javaClassName : javaClasses) {
      String absJavaClassPath = absBuildFilePath.replace("BUILD", javaClassName);
      if (fileUtils.fileExists(absJavaClassPath)) {
        JavaClass javaClass = javaClassAnalyzer.getJavaClass(absJavaClassPath);
        result.addHttpArchiveDep(
            HttpArchiveDep.newBuilder()
                .setJavaClass(
                    getProjectPackageSuffix(javaClass.getPackage(), absRepoPath)
                        + absBuildFilePath
                            .replace("/BUILD", ".")
                            .replace(absRepoPath, "")
                            .replace("/", ".")
                        + javaClassName)
                .setTarget(
                    absBuildFilePath.replace(absRepoPath, "/").replace("/BUILD", ":") + targetName)
                .build());
      }
    }
  }

  private String getProjectPackageSuffix(String classPackage, String absRepoPath) {
    String projectName = absRepoPath.substring(absRepoPath.lastIndexOf('/') + 1);
    String[] classPackageParts = classPackage.split(projectName.replace("-", ""));
    String absFilesystemPackagePath =
        absRepoPath + classPackageParts[classPackageParts.length - 1].replace(".", "/");
    String[] absPathParts = absFilesystemPackagePath.split(projectName);
    String filesystemPackage =
        absPathParts[absPathParts.length - 1]
            .replaceFirst("/", "")
            .replaceAll("/$", "")
            .replace("/", ".");
    if (classPackage.contains(filesystemPackage)) {
      return classPackage.replace(filesystemPackage, "").replaceAll(".$", "");
    }
    return "";
  }
}

