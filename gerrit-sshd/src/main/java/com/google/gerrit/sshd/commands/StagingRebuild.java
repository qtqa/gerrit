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

import static com.google.gerrit.sshd.commands.StagingCommand.R_HEADS;

import com.google.gerrit.common.ChangeHookRunner;
import com.google.gerrit.reviewdb.Branch;
import com.google.gerrit.reviewdb.ReviewDb;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.MergeOp;
import com.google.gerrit.server.git.MergeQueue;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gerrit.sshd.BaseCommand;
import com.google.gwtorm.client.OrmException;
import com.google.inject.Inject;

import org.apache.sshd.server.Environment;
import org.eclipse.jgit.lib.Repository;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.io.PrintWriter;

public class StagingRebuild extends BaseCommand {

  @Inject
  private GitRepositoryManager gitManager;

  @Inject
  private ReviewDb db;

  @Inject
  private IdentifiedUser currentUser;

  @Inject
  private MergeQueue merger;

  @Inject
  private MergeOp.Factory opFactory;

  @Inject
  private ChangeHookRunner hooks;

  private Repository git;

  @Option(name = "--project", aliases = {"-p"},
      required = true, usage = "project name")
  private String project;

  @Option(name = "--branch", aliases = {"-b"},
      required = true, usage = "branch name, e.g. refs/heads/master or just master")
  private String branch;

  @Override
  public void start(final Environment env) {
    startThread(new CommandRunnable() {
      @Override
      public void run() throws Exception {
        parseCommandLine();
        StagingRebuild.this.rebuild();
      }
    });
  }

  private void rebuild() throws UnloggedFailure {
    final PrintWriter stdout = toPrintWriter(out);
    try {
      final Branch.NameKey branchNameKey =
        StagingCommand.getNameKey(project, R_HEADS, branch);

      git = gitManager.openRepository(branchNameKey.getParentKey());
      ChangeUtil.rebuildStaging(branchNameKey, currentUser, db, git, opFactory,
          merger, hooks);
    } catch (NoSuchRefException e) {
      throw new UnloggedFailure(1, "Fatal: branch does not exist", e);
    } catch (OrmException e) {
      throw new UnloggedFailure(1, "Fatal: failed to access database", e);
    } catch (IOException e) {
      throw new UnloggedFailure(1, "Fatal: failed to access repository", e);
    } finally {
      stdout.flush();
      if (git != null) {
        git.close();
      }
    }
  }
}
