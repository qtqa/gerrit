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

import com.google.gerrit.reviewdb.Branch;
import com.google.gerrit.reviewdb.Change;
import com.google.gerrit.reviewdb.PatchSet;
import com.google.gerrit.reviewdb.Project;
import com.google.gerrit.reviewdb.ReviewDb;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.sshd.BaseCommand;
import com.google.gerrit.sshd.commands.StagingCommand.BranchNotFoundException;
import com.google.gwtorm.client.OrmException;
import com.google.inject.Inject;

import org.apache.sshd.server.Environment;
import org.eclipse.jgit.lib.Repository;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

public class StagingListChanges extends BaseCommand {

  @Inject
  private GitRepositoryManager gitManager;

  @Inject
  private ReviewDb db;

  private Repository git;

  @Option(name = "--project", aliases = {"-p"},
      required = true, usage = "project name")
  private String project;

  @Option(name = "--branch", aliases = {"-b"},
      required = true, usage = "any branch name, e.g. refs/staging/master or refs/builds/my_build")
  private String branch;

  @Option(name = "--destination", aliases = {"-d"},
      required = true, usage = "destination branch filter, e.g. refs/heads/master or just master")
  private String destination;

  @Override
  public void start(final Environment env) {
    startThread(new CommandRunnable() {
      @Override
      public void run() throws Exception {
        parseCommandLine();
        StagingListChanges.this.list();
      }
    });
  }

  private void list() throws UnloggedFailure {
    final PrintWriter stdout = toPrintWriter(out);
    try {
      final Project.NameKey projectKey = StagingCommand.getProjectKey(project);
      git = gitManager.openRepository(projectKey);
      final Branch.NameKey branchKey = new Branch.NameKey(projectKey, branch);
      final Branch.NameKey destinationKey = new Branch.NameKey(projectKey,
          destination);
      final List<PatchSet> open = StagingCommand.openChanges(git, db,
          branchKey, destinationKey);

      for (PatchSet patchSet : open) {
        final Change.Id changeId = patchSet.getId().getParentKey();
        final Change change = db.changes().get(changeId);
        if (change.getStatus().isOpen()) {
          stdout.println(patchSet.getRevision().get() + " " + patchSet.getId() + " " + change.getSubject());
        }
      }
    } catch (IOException e) {
      throw new UnloggedFailure(1, "Fatal: cannot access repository", e);
    } catch (OrmException e) {
      throw new UnloggedFailure(1, "Fatal: cannot access Gerrit database", e);
    } catch (BranchNotFoundException e) {
      throw new UnloggedFailure(1, "fatal: " + e.getMessage(), e);
    } finally {
      stdout.flush();
      if (git != null) {
        git.close();
      }
    }
  }
}
