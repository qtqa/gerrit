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
package com.google.gerrit.sshd.commands;

import static com.google.gerrit.sshd.commands.StagingCommand.R_BUILDS;
import static com.google.gerrit.sshd.commands.StagingCommand.R_HEADS;
import static com.google.gerrit.sshd.commands.StagingCommand.R_STAGING;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.StagingUtil;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gerrit.server.project.ProjectControl;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.gerrit.sshd.commands.StagingCommand.BranchNotFoundException;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;

import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map.Entry;

@CommandMetaData(name = "staging-new-build", descr = "Create a new build and place all the currently staged commits into a unique build branch and change the gerrit status to INTEGRATING.")
public class StagingNewBuild extends SshCommand {

  @Inject
  private GitRepositoryManager gitManager;

  @Inject
  private ReviewDb db;

  @Inject
  private ProjectControl.Factory projectControlFactory;

  private Repository git;

  @Option(name = "--project", aliases = {"-p"},
      required = true, usage = "project name")
  private String project;

  @Option(name = "--staging-branch", aliases = {"-s"},
      required = true, usage = "branch name, e.g. refs/staging/master")
  private String stagingBranch;

  @Option(name = "--build-id", aliases = {"-i"},
      required = true, usage = "build id, e.g. refs/builds/my_build or just my_build")
  private String build;

  @Override
  protected void run() throws UnloggedFailure {
    StagingNewBuild.this.rename();
  }

  private void rename() throws UnloggedFailure {
    final PrintWriter stdout = toPrintWriter(out);

    try {
      openRepository();

      Branch.NameKey buildBranchKey =
          StagingCommand.getNameKey(project, R_BUILDS, build);
      if (git.getRef(buildBranchKey.get()) != null) {
        throw new UnloggedFailure(1, "fatal: Target build already exists!");
      }

      Branch.NameKey stagingBranchKey =
          StagingCommand.getNameKey(project, R_STAGING, stagingBranch);

      Branch.NameKey branchNameKey =
          StagingCommand.getShortNameKey(project, R_STAGING, stagingBranch);

      // Check that required permissions are set
      Branch.NameKey destination =
          StagingCommand.getNameKey(project, R_HEADS, stagingBranch);
      validatePermissions(project, destination, buildBranchKey, stagingBranchKey);

      // Make sure that there are changes in the staging branch.
      if (StagingCommand.openChanges(git, db, stagingBranchKey, branchNameKey)
          .isEmpty()) {
        stdout.println("No changes in staging branch. Not creating a build reference");
        return;
      }

      // Create build reference.
      Result result =
          StagingUtil.createBuildRef(git, stagingBranchKey, buildBranchKey);

      if (result != Result.NEW && result != Result.FAST_FORWARD) {
        throw new UnloggedFailure(1, "fatal: failed to create new build ref: " + result);
      } else {
        updateChangeStatus(buildBranchKey, branchNameKey);
      }

      // Re-create staging branch.
      result = StagingUtil.createStagingBranch(git, branchNameKey);
      if (result != Result.FORCED) {
        throw new UnloggedFailure(1, "fatal: failed to reset staging branch: " + result);
      }
    } catch (IOException e) {
      throw new UnloggedFailure(1, "fatal: Failed to access repository", e);
    } catch (OrmException e) {
      throw new UnloggedFailure(1, "fatal: Failed to access database", e);
    } catch (BranchNotFoundException e) {
      throw new UnloggedFailure(1, "fatal: Failed to access build or staging ref", e);
    } catch (NoSuchRefException e) {
      throw new UnloggedFailure(1, "fatal: Invalid branch name", e);
    } catch (NoSuchProjectException e) {
      throw new UnloggedFailure(1, "fatal: Failed to access project", e);
    } finally {
      stdout.flush();
      if (git != null) {
        git.close();
      }
    }
  }

  private void openRepository() throws IOException {
    Project.NameKey projectKey = new Project.NameKey(project);
    git = gitManager.openRepository(projectKey);
  }

  private void updateChangeStatus(final Branch.NameKey buildBranchKey,
      final Branch.NameKey destinationKey)
      throws IOException, OrmException, BranchNotFoundException {
    List<Entry<PatchSet, RevCommit>> patchSets =
      StagingCommand.openChanges(git, db, buildBranchKey, destinationKey);
    for (Entry<PatchSet, RevCommit> patchSet : patchSets) {
      ChangeUtil.setIntegrating(patchSet.getKey().getId(), db);
    }
  }

  private void validatePermissions(final String project,
      final Branch.NameKey destination,
      final Branch.NameKey buildBranch,
      final Branch.NameKey stagingBranch)
      throws UnloggedFailure, NoSuchProjectException,
      MissingObjectException, IOException {
    // Check 'push' right to destination...
    Project.NameKey projectNameKey = new Project.NameKey(project);
    final ProjectControl projectControl = projectControlFactory.validateFor(projectNameKey);
    if (!projectControl.controlForRef(destination).canUpdate()) {
      throw new UnloggedFailure(1, "No Push right to " + destination);
    }
    // ...and 'create ref' right to refs/builds
    final ObjectId revid = git.resolve(stagingBranch.get());
    final RevWalk rw = new RevWalk(git);
    RevObject object = rw.parseCommit(revid);
    if (!projectControl.controlForRef(buildBranch).canCreate(rw, object)) {
      throw new UnloggedFailure(1, "No right to create ref " + buildBranch);
    }
  }
}

