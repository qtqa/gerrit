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

package com.google.gerrit.httpd.rpc.topic;

import com.google.gerrit.common.ChangeHookRunner;
import com.google.gerrit.common.data.ApprovalTypes;
import com.google.gerrit.common.data.TopicDetail;
import com.google.gerrit.common.errors.NoSuchEntityException;
import com.google.gerrit.httpd.rpc.Handler;
import com.google.gerrit.reviewdb.ChangeSet;
import com.google.gerrit.reviewdb.ReviewDb;
import com.google.gerrit.reviewdb.Topic;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.TopicUtil;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.MergeOp;
import com.google.gerrit.server.git.MergeQueue;
import com.google.gerrit.server.project.CanSubmitResult;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gerrit.server.project.NoSuchTopicException;
import com.google.gerrit.server.project.TopicControl;
import com.google.gerrit.server.workflow.TopicFunctionState;
import com.google.gwtorm.client.OrmException;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import org.eclipse.jgit.lib.Repository;

import java.io.IOException;


class StagingAction extends Handler<TopicDetail> {
  interface Factory {
    StagingAction create(ChangeSet.Id changeSetId);
  }

  private final ReviewDb db;
  private final MergeQueue merger;
  private final ApprovalTypes approvalTypes;
  private final IdentifiedUser user;
  private final TopicDetailFactory.Factory topicDetailFactory;
  private final ChangeControl.Factory changeControlFactory;
  private final TopicControl.Factory topicControlFactory;
  private final TopicFunctionState.Factory topicFunctionState;
  private final MergeOp.Factory opFactory;
  private final GitRepositoryManager gitManager;
  private final ChangeHookRunner hooks;

  private final ChangeSet.Id changeSetId;

  @Inject
  StagingAction(final ReviewDb db, final MergeQueue mq,
      final IdentifiedUser user,
      final ApprovalTypes approvalTypes,
      final TopicDetailFactory.Factory topicDetailFactory,
      final ChangeControl.Factory changeControlFactory,
      final TopicControl.Factory topicControlFactory,
      final TopicFunctionState.Factory topicFunctionState,
      final MergeOp.Factory opFactory,
      final GitRepositoryManager gitManager,
      final ChangeHookRunner hooks,
      @Assisted final ChangeSet.Id changeSetId) {
    this.db = db;
    this.merger = mq;
    this.approvalTypes = approvalTypes;
    this.user = user;
    this.changeControlFactory = changeControlFactory;
    this.topicControlFactory = topicControlFactory;
    this.topicDetailFactory = topicDetailFactory;
    this.topicFunctionState = topicFunctionState;
    this.opFactory = opFactory;
    this.gitManager = gitManager;
    this.hooks = hooks;

    this.changeSetId = changeSetId;
  }

  @Override
  public TopicDetail call() throws OrmException, NoSuchEntityException,
      IllegalStateException, ChangeSetInfoNotAvailableException,
      NoSuchTopicException, NoSuchChangeException {

    final Topic.Id topicId = changeSetId.getParentKey();
    final TopicControl topicControl =
        topicControlFactory.validateFor(topicId);

    CanSubmitResult result = topicControl.canStage(db, changeSetId,
        changeControlFactory, approvalTypes, topicFunctionState);

    Repository git = null;
    if (result == CanSubmitResult.OK) {
      try {
        // Open a handle to Git repository.
        git =
          gitManager.openRepository(topicControl.getProject().getNameKey());

        // Move change to staging.
        TopicUtil.stage(changeSetId, user, db, opFactory, merger, git, hooks);
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
      return topicDetailFactory.create(topicId).call();
    } else {
      // Report error message to user. User cannot move this change to staging.
      // The problem is caused because of illegal stage of missing access
      // rights.
      throw new IllegalStateException(result.getMessage());
    }
  }
}
