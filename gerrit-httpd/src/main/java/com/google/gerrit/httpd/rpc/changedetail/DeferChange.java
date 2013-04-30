// Copyright (C) 2009 The Android Open Source Project,
// Copyright (C) 2013 Digia Plc and/or its subsidiary(-ies).
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
import com.google.gerrit.common.data.ChangeDetail;
import com.google.gerrit.common.errors.NoSuchEntityException;
import com.google.gerrit.httpd.rpc.Handler;
import com.google.gerrit.reviewdb.Change;
import com.google.gerrit.reviewdb.PatchSet;
import com.google.gerrit.reviewdb.ReviewDb;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.MergeOp;
import com.google.gerrit.server.git.MergeQueue;
import com.google.gerrit.server.mail.DeferredSender;
import com.google.gerrit.server.mail.EmailException;
import com.google.gerrit.server.patch.PatchSetInfoNotAvailableException;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.InvalidChangeOperationException;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gwtorm.client.OrmException;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import javax.annotation.Nullable;

class DeferChange extends Handler<ChangeDetail> {
  interface Factory {
    DeferChange create(PatchSet.Id patchSetId, String message);
  }

  private final ChangeControl.Factory changeControlFactory;
  private final ReviewDb db;
  private final IdentifiedUser currentUser;
  private final DeferredSender.Factory deferredSenderFactory;
  private final ChangeDetailFactory.Factory changeDetailFactory;

  private final PatchSet.Id patchSetId;
  @Nullable
  private final String message;

  private final ChangeHookRunner hooks;
  private final MergeQueue merger;
  private final MergeOp.Factory opFactory;
  private final GitRepositoryManager gitManager;

  @Inject
  DeferChange(final ChangeControl.Factory changeControlFactory,
      final ReviewDb db, final IdentifiedUser currentUser,
      final DeferredSender.Factory deferredSenderFactory,
      final ChangeDetailFactory.Factory changeDetailFactory,
      @Assisted final PatchSet.Id patchSetId,
      @Assisted @Nullable final String message, final ChangeHookRunner hooks,
      MergeQueue merger, MergeOp.Factory opFactory,
      GitRepositoryManager gitManager) {
    this.changeControlFactory = changeControlFactory;
    this.db = db;
    this.currentUser = currentUser;
    this.deferredSenderFactory = deferredSenderFactory;
    this.changeDetailFactory = changeDetailFactory;

    this.patchSetId = patchSetId;
    this.message = message;
    this.hooks = hooks;
    this.merger = merger;
    this.opFactory = opFactory;
    this.gitManager = gitManager;
  }

  @Override
  public ChangeDetail call() throws NoSuchChangeException, OrmException,
      EmailException, NoSuchEntityException, InvalidChangeOperationException,
      PatchSetInfoNotAvailableException {
    final Change.Id changeId = patchSetId.getParentKey();
    final ChangeControl control = changeControlFactory.validateFor(changeId);
    if (!control.canDefer()) {
      throw new NoSuchChangeException(changeId);
    }

    ChangeUtil.defer(patchSetId, currentUser, message, db,
       deferredSenderFactory, hooks);

    final Change change = db.changes().get(changeId);
    final boolean staged = change.getStatus() == Change.Status.STAGED;

    // If the change was staged, the staging branch needs to be updated.
    if (staged) {
      Repository git = null;
      try {
        git = gitManager.openRepository(change.getProject());
        ChangeUtil.rebuildStaging(change.getDest(), currentUser, db, git,
            opFactory, merger, hooks);
      } catch (IOException e) {
        // Failed to open git repository.
      } catch (NoSuchRefException e) {
        // Invalid change destination branch.
      } finally {
        if (git != null) {
          git.close();
        }
      }
    }

    return changeDetailFactory.create(changeId).call();
  }
}
