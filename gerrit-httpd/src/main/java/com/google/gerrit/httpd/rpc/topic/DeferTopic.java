// Copyright (C) 2011 The Android Open Source Project,
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

package com.google.gerrit.httpd.rpc.topic;

import com.google.gerrit.common.ChangeHookRunner;
import com.google.gerrit.common.data.TopicDetail;
import com.google.gerrit.common.errors.NoSuchEntityException;
import com.google.gerrit.httpd.rpc.Handler;
import com.google.gerrit.reviewdb.ChangeSet;
import com.google.gerrit.reviewdb.ReviewDb;
import com.google.gerrit.reviewdb.Topic;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.TopicUtil;
import com.google.gerrit.server.mail.DeferredSender;
import com.google.gerrit.server.mail.EmailException;
import com.google.gerrit.server.project.InvalidChangeOperationException;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.project.NoSuchTopicException;
import com.google.gerrit.server.project.TopicControl;
import com.google.gwtorm.client.OrmException;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import javax.annotation.Nullable;

class DeferTopic extends Handler<TopicDetail> {
  interface Factory {
    DeferTopic create(ChangeSet.Id changeSetId, String message);
  }

  private final TopicControl.Factory topicControlFactory;
  private final ReviewDb db;
  private final IdentifiedUser currentUser;
  private final DeferredSender.Factory deferredSenderFactory;
  private final TopicDetailFactory.Factory topicDetailFactory;

  private final ChangeSet.Id changeSetId;
  @Nullable
  private final String message;

  private final ChangeHookRunner hooks;

  @Inject
  DeferTopic(final TopicControl.Factory topicControlFactory,
      final ReviewDb db, final IdentifiedUser currentUser,
      final DeferredSender.Factory deferredSenderFactory,
      final TopicDetailFactory.Factory topicDetailFactory,
      @Assisted final ChangeSet.Id changeSetId,
      @Assisted @Nullable final String message, final ChangeHookRunner hooks) {
    this.db = db;
    this.currentUser = currentUser;
    this.deferredSenderFactory = deferredSenderFactory;
    this.topicControlFactory = topicControlFactory;
    this.topicDetailFactory = topicDetailFactory;

    this.changeSetId = changeSetId;
    this.message = message;
    this.hooks = hooks;
  }

  @Override
  public TopicDetail call() throws NoSuchTopicException, NoSuchChangeException,
      OrmException, EmailException, NoSuchEntityException,
      ChangeSetInfoNotAvailableException, InvalidChangeOperationException {

    final Topic.Id topicId = changeSetId.getParentKey();
    final TopicControl topicControl = topicControlFactory.validateFor(topicId);
    if (!topicControl.canDefer()) {
      throw new NoSuchTopicException(topicId);
    }

    TopicUtil.defer(changeSetId, currentUser, message, db,
        deferredSenderFactory, hooks);

    return topicDetailFactory.create(topicId).call();
  }
}
