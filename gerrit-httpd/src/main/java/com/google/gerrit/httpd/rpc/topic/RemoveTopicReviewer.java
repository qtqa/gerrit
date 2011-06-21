// Copyright (C) 2011 The Android Open Source Project
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

import com.google.gerrit.common.data.TopicReviewerResult;
import com.google.gerrit.httpd.rpc.Handler;
import com.google.gerrit.reviewdb.Account;
import com.google.gerrit.reviewdb.ChangeSetApproval;
import com.google.gerrit.reviewdb.ReviewDb;
import com.google.gerrit.reviewdb.Topic;
import com.google.gerrit.server.project.TopicControl;
import com.google.gwtorm.client.OrmException;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import java.util.ArrayList;
import java.util.List;

/**
 * Implement the remote logic that removes a reviewer from a change.
 */
class RemoveTopicReviewer extends Handler<TopicReviewerResult> {
  interface Factory {
    RemoveTopicReviewer create(Topic.Id topicId, Account.Id reviewerId);
  }

  private final Account.Id reviewerId;
  private final TopicControl.Factory topicControlFactory;
  private final ReviewDb db;
  private final Topic.Id topicId;
  private final TopicDetailFactory.Factory topicDetailFactory;

  @Inject
  RemoveTopicReviewer(final ReviewDb db, final TopicControl.Factory topicControlFactory,
      final TopicDetailFactory.Factory topicDetailFactory,
      @Assisted Topic.Id topicId, @Assisted Account.Id reviewerId) {
    this.db = db;
    this.topicControlFactory = topicControlFactory;
    this.topicId = topicId;
    this.reviewerId = reviewerId;
    this.topicDetailFactory = topicDetailFactory;
  }

  @Override
  public TopicReviewerResult call() throws Exception {
    TopicReviewerResult result = new TopicReviewerResult();
    TopicControl ctl = topicControlFactory.validateFor(topicId);
    boolean permitted = true;

    List<ChangeSetApproval> toDelete = new ArrayList<ChangeSetApproval>();
    for (ChangeSetApproval csa : db.changeSetApprovals().byTopic(topicId)) {
      if (csa.getAccountId().equals(reviewerId)) {
        if (ctl.canRemoveReviewer(csa)) {
          toDelete.add(csa);
        } else {
          permitted = false;
          break;
        }
      }
    }

    if (permitted) {
      try {
        db.changeSetApprovals().delete(toDelete);
      } catch (OrmException ex) {
        result.addError(new TopicReviewerResult.Error(
            TopicReviewerResult.Error.Type.COULD_NOT_REMOVE,
            "Could not remove reviewer " + reviewerId));
      }
    } else {
      result.addError(new TopicReviewerResult.Error(
          TopicReviewerResult.Error.Type.COULD_NOT_REMOVE,
          "Not allowed to remove reviewer " + reviewerId));
    }

    // Note: call setTopic() after the deletion has been made or it will still
    // contain the reviewer we want to delete.
    result.setTopic(topicDetailFactory.create(topicId).call());
    return result;
  }

}
