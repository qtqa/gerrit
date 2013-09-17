// Copyright (C) 2012 The Android Open Source Project
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

package com.google.gerrit.server.change;

import com.google.gerrit.common.ChangeHooks;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.ChangeMessage;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.Unstage.Input;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.MergeQueue;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.util.Collections;

public class Unstage implements RestModifyView<RevisionResource, Input> {
  public static class Input {
    public boolean waitForMerge;
  }

  public enum Status {
    NEW;
  }

  public static class Output {
    public Status status;
    transient Change change;

    private Output(Status s, Change c) {
      status = s;
      change = c;
    }
  }

  private final ChangeHooks hooks;
  private final Provider<ReviewDb> dbProvider;
  private final GitRepositoryManager repoManager;
  private final MergeQueue mergeQueue;

  @Inject
  Unstage(ChangeHooks hooks,
      Provider<ReviewDb> dbProvider,
      GitRepositoryManager repoManager,
      MergeQueue mergeQueue) {
    this.hooks = hooks;
    this.dbProvider = dbProvider;
    this.repoManager = repoManager;
    this.mergeQueue = mergeQueue;
  }

  @Override
  public Output apply(RevisionResource rsrc, Input input) throws AuthException,
      ResourceConflictException, RepositoryNotFoundException, IOException,
      OrmException {
    ChangeControl control = rsrc.getControl();
    IdentifiedUser caller = (IdentifiedUser) control.getCurrentUser();
    Change change = rsrc.getChange();
    if (!control.canUnstage()) {
      throw new AuthException("unstaging not permitted");
    } else if (change.getStatus() != Change.Status.STAGED
        && change.getStatus() != Change.Status.STAGING) {
      throw new ResourceConflictException("change is " + status(change));
    }

    change = unstage(rsrc, caller);
    if (change == null) {
      throw new ResourceConflictException("change is "
          + status(dbProvider.get().changes().get(rsrc.getChange().getId())));
    }

    // Rebuild staging branch.
    Repository git = null;
    try {
      final Branch.NameKey branch = change.getDest();
      ReviewDb db = dbProvider.get();
      git = repoManager.openRepository(control.getProject().getNameKey());
      ChangeUtil.rebuildStaging(branch, caller, db, git, mergeQueue, hooks);
    } catch (NoSuchRefException e) {
      throw new IllegalStateException(e.getMessage());
    } finally {
      // Make sure that access to Git repository is closed.
      if (git != null) {
        git.close();
      }
    }

    switch (change.getStatus()) {
      case NEW:
        return new Output(Status.NEW, change);
      default:
        throw new ResourceConflictException("change is " + status(change));
    }
  }

  public Change unstage(RevisionResource rsrc, IdentifiedUser caller)
      throws OrmException {
    Change change = rsrc.getChange();
    ReviewDb db = dbProvider.get();
    db.changes().beginTransaction(change.getId());
    try {
      final PatchSet.Id psId = rsrc.getPatchSet().getId();
      change = ChangeUtil.rejectStagedChange(psId, caller, db);
      ChangeMessage message = new ChangeMessage(
          new ChangeMessage.Key(
              change.getId(),
              ChangeUtil.messageUUID(dbProvider.get())),
          caller.getAccountId(),
          change.getLastUpdatedOn(),
          change.currentPatchSetId());
      message.setMessage("Unstaged");
      db.changeMessages().insert(Collections.singleton(message));
      db.commit();
    } finally {
      db.rollback();
    }
    return change;
  }

  private static String status(Change change) {
    return change != null ? change.getStatus().name().toLowerCase() : "deleted";
  }

  public static class CurrentRevision implements
      RestModifyView<ChangeResource, Input> {
    @SuppressWarnings("unused")
    private final ChangeHooks hooks;
    private final Provider<ReviewDb> dbProvider;
    private final Unstage unstage;
    private final ChangeJson json;

    @Inject
    CurrentRevision(ChangeHooks hooks,
        Provider<ReviewDb> dbProvider,
        Unstage unstage,
        ChangeJson json) {
      this.hooks = hooks;
      this.dbProvider = dbProvider;
      this.unstage = unstage;
      this.json = json;
    }

    @Override
    public Object apply(ChangeResource rsrc, Input input) throws AuthException,
        ResourceConflictException, RepositoryNotFoundException, IOException,
        OrmException {
      PatchSet ps = dbProvider.get().patchSets()
        .get(rsrc.getChange().currentPatchSetId());
      if (ps == null) {
        throw new ResourceConflictException("current revision is missing");
      } else if (!rsrc.getControl().isPatchVisible(ps, dbProvider.get())) {
        throw new AuthException("current revision not accessible");
      }
      Output out = unstage.apply(new RevisionResource(rsrc, ps), input);
      return json.format(out.change);
    }
  }
}
