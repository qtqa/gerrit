// Copyright (C) 2011 The Android Open Source Project
// Copyright (C) 2014 Digia Plc and/or its subsidiary(-ies).
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.gerrit.server.git;

import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.server.project.NoSuchRefException;

import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RefUpdate.Result;
import java.io.IOException;

/**
 * Utility methods for working with staging branches.
 */
public class StagingUtil {
  private static final String R_HEADS = "refs/heads/";
  private static final String R_STAGING = "refs/staging/";
  private static final String R_BUILDS = "refs/builds/";

  /**
   * Gets a staging branch for a branch.
   * @param branch Branch under refs/heads. E.g. refs/heads/master. Can be short
   *        name.
   * @return Matching staging branch. E.g. refs/staging/master
   */
  public static Branch.NameKey getStagingBranch(final Branch.NameKey branch) {
    return getBranchWithNewPrefix(branch, R_HEADS, R_STAGING);
  }

  /**
   * Gets a source branch for a staging branch.
   * @param staging Staging branch, e.g. refs/staging/master. Can be short name.
   * @return Matching branch refs/heads, e.g. refs/heads/master.
   */
  public static Branch.NameKey getSourceBranch(final Branch.NameKey staging) {
    return getBranchWithNewPrefix(staging, R_STAGING, R_HEADS);
  }

  private static Branch.NameKey getBranchWithNewPrefix(
      final Branch.NameKey branch,
      final String oldPrefix,
      final String newPrefix) {
    final String ref = branch.get();

    if (ref.startsWith(oldPrefix)) {
      // Create new ref replacing the old prefix with new.
      return new Branch.NameKey(branch.getParentKey(),
          newPrefix + ref.substring(oldPrefix.length()));
    }
    // Treat the ref as short name.
    return new Branch.NameKey(branch.getParentKey(), newPrefix + ref);
  }

  /**
   * Checks if branch exists.
   * @param git Git repository to search for the branch.
   * @param branch Branch name key.
   * @return True if branch exists.
   * @throws IOException Thrown, if the repository cannot be accessed.
   */
  public static boolean branchExists(Repository git,
      final Branch.NameKey branch) throws IOException {
    return git.getRef(branch.get()) != null;
  }

  /**
   * Create a staging branch from branch in refs/heads.
   *
   * @param git Git repository.
   * @param sourceBranch Branch under refs/heads. Can be short name,
   *        e.g. master.
   * @return Result of the ref update.
   * @throws IOException Thrown if repository cannot be accessed.
   * @throws NoSuchRefException Thrown if sourceBranch parameter does not exist
   *         in the repository.
   */
  public static Result createStagingBranch(Repository git,
      final Branch.NameKey sourceBranch) throws IOException, NoSuchRefException {
    final String sourceBranchName;
    if (sourceBranch.get().startsWith(R_HEADS)) {
      sourceBranchName = sourceBranch.get();
    } else {
      sourceBranchName = R_HEADS + sourceBranch.get();
    }

    final String stagingBranch = R_STAGING + sourceBranch.getShortName();

    return updateRef(git, stagingBranch, sourceBranchName, true);
  }

  /**
   * Create a staging branch from branch in refs/heads. Before updating the
   * staging branch, it is verified that its SHA value matches oldRef.
   *
   * @param git Git repository.
   * @param sourceBranch Branch under refs/heads. Can be short name,
   *        e.g. master.
   * @param oldRef Staging branch SHA value must match the value of this ref.
   * @return Result of the ref update.
   * @throws IOException Thrown if repository cannot be accessed.
   * @throws NoSuchRefException Thrown if sourceBranch parameter does not exist
   *         in the repository.
   */
  public static Result createStagingBranch(Repository git,
      final Branch.NameKey sourceBranch, final Branch.NameKey oldRef)
      throws IOException, NoSuchRefException {
    final String sourceBranchName;
    if (sourceBranch.get().startsWith(R_HEADS)) {
      sourceBranchName = sourceBranch.get();
    } else {
      sourceBranchName = R_HEADS + sourceBranch.get();
    }

    final String stagingBranch = R_STAGING + sourceBranch.getShortName();

    return updateRef(git, stagingBranch, sourceBranchName, oldRef.get());
  }

  /**
   * Creates a build ref. Build refs are stored under refs/builds.
   *
   * @param git Git repository.
   * @param stagingBranch Staging branch to create the build ref from. Can be
   *        short name.
   * @param newBranch Build ref name, under refs/builds. Can be short name.
   * @return
   * @throws IOException
   * @throws NoSuchRefException
   */
  public static Result createBuildRef(Repository git,
      final Branch.NameKey stagingBranch, final Branch.NameKey newBranch)
      throws IOException, NoSuchRefException {
    final String stagingBranchName;
    if (stagingBranch.get().startsWith(R_STAGING)) {
      stagingBranchName = stagingBranch.get();
    } else {
      stagingBranchName = R_STAGING + stagingBranch.get();
    }

    final String buildBranchName;
    if (newBranch.get().startsWith(R_BUILDS)) {
      buildBranchName = newBranch.get();
    } else {
      buildBranchName = R_BUILDS + newBranch.get();
    }

    return updateRef(git, buildBranchName, stagingBranchName, false);
  }

  /**
   * Update branch from build ref. Replaces the branch ref with a build ref.
   *
   * @param git Git repository.
   * @param branch Branch name under refs/heads. Can be short name.
   * @param build Build branch name under refs/builds. Can be short name.
   * @return Update ref result.
   * @throws IOException Thrown if Git repository cannot be accessed.
   * @throws NoSuchRefException Thrown if Build ref does not exist
   */
  public static Result updateBrachFromBuild(Repository git,
      final Branch.NameKey branch, final Branch.NameKey build)
      throws IOException, NoSuchRefException {
    final String branchName;
    if (branch.get().startsWith(R_HEADS)) {
      branchName = branch.get();
    } else {
      branchName = R_HEADS + branch.get();
    }

    final String buildName;
    if (build.get().startsWith(R_BUILDS)) {
      buildName = build.get();
    } else {
      buildName = R_BUILDS + build.get();
    }

    return updateRef(git, branchName, buildName, false);
  }

  private static Result updateRef(Repository git, final String ref,
      final String newValue, final String oldValue) throws IOException,
      NoSuchRefException {
    Ref newRef = git.getRef(newValue);
    if (newRef == null) {
      throw new NoSuchRefException(newValue);
    }
    Ref oldRef = git.getRef(oldValue);
    if (oldRef == null) {
      throw new NoSuchRefException(oldValue);
    }
    RefUpdate refUpdate = git.updateRef(ref);
    refUpdate.setNewObjectId(newRef.getObjectId());
    refUpdate.setExpectedOldObjectId(oldRef.getObjectId());
    return refUpdate.update();
  }

  private static Result updateRef(Repository git, final String ref,
      final String newValue, final boolean force) throws IOException,
      NoSuchRefException {
    Ref sourceRef = git.getRef(newValue);
    if (sourceRef == null) {
      throw new NoSuchRefException(newValue);
    }
    RefUpdate refUpdate = git.updateRef(ref);
    refUpdate.setNewObjectId(sourceRef.getObjectId());
    refUpdate.setForceUpdate(force);
    return refUpdate.update();
  }
}

