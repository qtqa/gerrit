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
package com.google.gerrit.httpd.rpc.changedetail;

import com.google.gerrit.common.ChangeHookRunner;
import com.google.gerrit.common.data.ApprovalTypes;
import com.google.gerrit.common.data.ChangeDetail;
import com.google.gerrit.common.errors.NoSuchEntityException;
import com.google.gerrit.httpd.rpc.Handler;
import com.google.gerrit.reviewdb.Branch;
import com.google.gerrit.reviewdb.Change;
import com.google.gerrit.reviewdb.PatchSet;
import com.google.gerrit.reviewdb.ReviewDb;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.MergeOp;
import com.google.gerrit.server.git.MergeQueue;
import com.google.gerrit.server.patch.PatchSetInfoNotAvailableException;
import com.google.gerrit.server.project.CanSubmitResult;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gerrit.server.workflow.FunctionState;
import com.google.gwtorm.client.OrmException;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import org.eclipse.jgit.lib.Repository;

import java.io.IOException;


/**
 * RPC service implementation that uses Guice for moving change to staging
 * branch.
 *
 * ChangeDetail is returned as a result to callers.
 *
 */
class UnstageChange extends Handler<ChangeDetail> {
  /**
   * Guice (Gerrit) factory interface for creating StagingAction for a specific
   * patch set.
   */
  interface Factory {
    UnstageChange create(PatchSet.Id patchSet);
  }

  private final ReviewDb db;
  private final MergeQueue merger;
  private final ApprovalTypes approvalTypes;
  private final FunctionState.Factory functionState;
  private final IdentifiedUser user;
  private final ChangeDetailFactory.Factory changeDetailFactory;
  private final ChangeControl.Factory changeControlFactory;
  private final MergeOp.Factory mergeFactory;
  private final PatchSet.Id patchSetId;
  private final GitRepositoryManager gitManager;
  private final ChangeHookRunner hooks;

  @Inject
  UnstageChange(final ReviewDb db, final MergeQueue merger,
      final ApprovalTypes approvalTypes,
      final FunctionState.Factory functionState,
      final IdentifiedUser user,
      final ChangeDetailFactory.Factory changeDetailFactory,
      final ChangeControl.Factory changeControlFactory,
      final MergeOp.Factory stagingFactory,
      @Assisted final PatchSet.Id patchSetId,
      final GitRepositoryManager gitManager,
      final ChangeHookRunner hooks) {
    this.db = db;
    this.merger = merger;
    this.approvalTypes = approvalTypes;
    this.functionState = functionState;
    this.user = user;
    this.changeDetailFactory = changeDetailFactory;
    this.changeControlFactory = changeControlFactory;
    this.mergeFactory = stagingFactory;
    this.patchSetId = patchSetId;
    this.gitManager = gitManager;
    this.hooks = hooks;
  }

  /**
   * RPC service call method for moving a change from NEW state to STAGING
   * state.
   *
   * @see ChangeUtil.moveToStaging for actual implementation.
   */
  @Override
  public ChangeDetail call() throws OrmException, NoSuchEntityException,
      PatchSetInfoNotAvailableException, NoSuchChangeException {
    final Change.Id changeId = patchSetId.getParentKey();
    // Construct a change control object that will be used to check if the
    // change can be merged.
    final ChangeControl changeControl =
      changeControlFactory.validateFor(changeId);

    // Check if the change can be merged to staging branch.
    CanSubmitResult err =
      changeControl.canStage(patchSetId, db, approvalTypes,
          functionState);

    Repository git = null;
    if (changeControl.canAbandon()) {
      try {
        // Open a handle to Git repository.
        git =
          gitManager.openRepository(changeControl.getProject().getNameKey());

        // Remove staging approvals and reset status.
        ChangeUtil.rejectStagedChange(patchSetId, user, db);

        // Rebuild staging branch.
        final Branch.NameKey branch = changeControl.getChange().getDest();
        ChangeUtil.rebuildStaging(branch, user, db, git, mergeFactory, merger,
            hooks);
      } catch (IOException e) {
        throw new IllegalStateException(e.getMessage());
      } catch (NoSuchRefException e) {
        throw new IllegalStateException(e.getMessage());
      } finally {
        // Make sure that access to Git repository is closed.
        if (git != null) {
          git.close();
        }
      }
      return changeDetailFactory.create(changeId).call();
    } else {
      // Report error message to user. User cannot move this change to staging.
      // The problem is caused because of illegal stage of missing access
      // rights.
      throw new IllegalStateException(err.getMessage());
    }
  }
}
