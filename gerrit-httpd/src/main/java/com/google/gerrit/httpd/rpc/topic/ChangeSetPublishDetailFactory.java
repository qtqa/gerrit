// Copyright (C) 2011 The Android Open Source Project
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

import com.google.gerrit.common.data.ChangeSetPublishDetail;
import com.google.gerrit.common.data.PermissionRange;
import com.google.gerrit.common.errors.NoSuchEntityException;
import com.google.gerrit.httpd.rpc.Handler;
import com.google.gerrit.reviewdb.AbstractEntity;
import com.google.gerrit.reviewdb.ChangeSet;
import com.google.gerrit.reviewdb.ChangeSetApproval;
import com.google.gerrit.reviewdb.ChangeSetInfo;
import com.google.gerrit.reviewdb.ReviewDb;
import com.google.gerrit.reviewdb.Topic;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountInfoCacheFactory;
import com.google.gerrit.server.project.CanSubmitResult;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.project.NoSuchTopicException;
import com.google.gerrit.server.project.TopicControl;
import com.google.gwtorm.client.OrmException;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ChangeSetPublishDetailFactory extends Handler<ChangeSetPublishDetail> {
  interface Factory {
    ChangeSetPublishDetailFactory create(ChangeSet.Id changeSetId);
  }

  private final ChangeSetInfoFactory infoFactory;
  private final ReviewDb db;
  private final TopicControl.Factory topicControlFactory;
  private final AccountInfoCacheFactory aic;
  private final IdentifiedUser user;

  private final ChangeSet.Id changeSetId;

  private ChangeSetInfo changeSetInfo;
  private Topic topic;

  @Inject
  ChangeSetPublishDetailFactory(final ChangeSetInfoFactory infoFactory,
      final ReviewDb db,
      final AccountInfoCacheFactory.Factory accountInfoCacheFactory,
      final TopicControl.Factory topicControlFactory,
      final IdentifiedUser user,
      @Assisted final ChangeSet.Id changeSetId) {
    this.infoFactory = infoFactory;
    this.db = db;
    this.topicControlFactory = topicControlFactory;
    this.aic = accountInfoCacheFactory.create();
    this.user = user;

    this.changeSetId = changeSetId;
  }

  @Override
  public ChangeSetPublishDetail call() throws OrmException,
      ChangeSetInfoNotAvailableException, NoSuchTopicException,
      NoSuchEntityException, NoSuchChangeException {
    final Topic.Id topicId = changeSetId.getParentKey();
    final TopicControl control = topicControlFactory.validateFor(topicId);
    topic = control.getTopic();
    changeSetInfo = infoFactory.get(changeSetId);

    List<PermissionRange> allowed = Collections.emptyList();
    List<ChangeSetApproval> given = Collections.emptyList();

    if (topic.getStatus().isOpen()
        && changeSetId.equals(topic.currentChangeSetId())) {
      allowed = new ArrayList<PermissionRange>(control.getLabelRanges());
      Collections.sort(allowed);

      given = db.changeSetApprovals() //
          .byChangeSetUser(changeSetId, user.getAccountId()) //
          .toList();
    }

    aic.want(topic.getOwner());

    ChangeSetPublishDetail detail = new ChangeSetPublishDetail();
    detail.setChangeSetInfo(changeSetInfo);
    detail.setTopic(topic);

    detail.setLabels(allowed);
    detail.setGiven(given);
    detail.setAccounts(aic.create());

    final CanSubmitResult canSubmitResult = control.canSubmit(changeSetId);
    if (canSubmitResult == CanSubmitResult.OK) {
        detail.setCanSubmit(true);
    }

    final CanSubmitResult canStage = control.canStage(changeSetId);
    if (canStage == CanSubmitResult.OK) {
      detail.setCanStage(true);
    }

    if (topic.getStatus() == AbstractEntity.Status.STAGED
        && control.getRefControl().canStage()) {
      detail.setCanUnstage(true);
    }

    return detail;
  }
}
