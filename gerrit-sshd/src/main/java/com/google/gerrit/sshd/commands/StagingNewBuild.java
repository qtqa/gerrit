// Copyright (C) 2012 Digia Plc and/or its subsidiary(-ies).
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
import static com.google.gerrit.sshd.commands.StagingCommand.R_STAGING;
import com.google.gerrit.reviewdb.Branch;
import com.google.gerrit.reviewdb.Change;
import com.google.gerrit.reviewdb.PatchSet;
import com.google.gerrit.reviewdb.Project;
import com.google.gerrit.reviewdb.ReviewDb;
import com.google.gerrit.reviewdb.Topic;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.TopicUtil;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.StagingUtil;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gerrit.sshd.BaseCommand;
import com.google.gerrit.sshd.commands.StagingCommand.BranchNotFoundException;
import com.google.gwtorm.client.OrmException;
import com.google.inject.Inject;

import org.apache.sshd.server.Environment;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

public class StagingNewBuild extends BaseCommand {

  @Inject
  private GitRepositoryManager gitManager;

  @Inject
  private ReviewDb db;

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
  public void start(final Environment env) {
    startThread(new CommandRunnable() {
      @Override
      public void run() throws Exception {
        parseCommandLine();
        StagingNewBuild.this.rename();
      }
    });
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

      // Make sure that are changes in the staging branch.
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
      if (result != Result.NEW && result != Result.FAST_FORWARD
          && result != Result.FORCED && result != Result.NO_CHANGE) {
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
    } finally {
      stdout.flush();
      if (git != null) {
        git.close();
      }
    }
  }

  private void openRepository() throws RepositoryNotFoundException {
    Project.NameKey projectKey = new Project.NameKey(project);
    git = gitManager.openRepository(projectKey);
  }

  private void updateChangeStatus(final Branch.NameKey buildBranchKey,
      final Branch.NameKey destinationKey)
      throws IOException, OrmException, BranchNotFoundException {
    List<PatchSet> patchSets =
      StagingCommand.openChanges(git, db, buildBranchKey, destinationKey);
    for (PatchSet patchSet : patchSets) {
      ChangeUtil.setIntegrating(patchSet.getId(), db);
      Change change = db.changes().get(patchSet.getId().getParentKey());
      Topic.Id topicId = change.getTopicId();
      if (topicId != null) {
        Topic topic = db.topics().get(topicId);
        if (topic.getStatus() != Topic.Status.INTEGRATING) {
          TopicUtil.setIntegrating(topicId, db);
        }
      }
    }
  }
}
