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

import com.google.gerrit.common.data.ApprovalType;
import com.google.gerrit.common.data.ApprovalTypes;
import com.google.gerrit.common.data.TopicReviewerResult;
import com.google.gerrit.httpd.rpc.Handler;
import com.google.gerrit.reviewdb.Account;
import com.google.gerrit.reviewdb.ApprovalCategory;
import com.google.gerrit.reviewdb.ReviewDb;
import com.google.gerrit.reviewdb.Topic;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.TopicUtil;
import com.google.gerrit.server.account.AccountResolver;
import com.google.gerrit.server.mail.AddReviewerSender;
import com.google.gerrit.server.project.TopicControl;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class AddTopicReviewer extends Handler<TopicReviewerResult> {
  interface Factory {
    AddTopicReviewer create(Topic.Id topicId, Collection<String> nameOrEmails);
  }

  private final AddReviewerSender.Factory addReviewerSenderFactory;
  private final AccountResolver accountResolver;
  private final TopicControl.Factory topicControlFactory;
  private final TopicDetailFactory.Factory topicDetailFactory;
  private final ReviewDb db;
  private final IdentifiedUser currentUser;
  private final IdentifiedUser.GenericFactory identifiedUserFactory;
  private final ApprovalCategory.Id addReviewerCategoryId;

  private final Topic.Id topicId;
  private final Collection<String> reviewers;

  @Inject
  AddTopicReviewer(final AddReviewerSender.Factory addReviewerSenderFactory,
      final AccountResolver accountResolver,
      final TopicControl.Factory topicControlFactory, final ReviewDb db,
      final IdentifiedUser.GenericFactory identifiedUserFactory,
      final IdentifiedUser currentUser, final ApprovalTypes approvalTypes,
      final TopicDetailFactory.Factory topicDetailFactory,
      @Assisted final Topic.Id topicId,
      @Assisted final Collection<String> nameOrEmails) {
    this.addReviewerSenderFactory = addReviewerSenderFactory;
    this.accountResolver = accountResolver;
    this.db = db;
    this.topicControlFactory = topicControlFactory;
    this.identifiedUserFactory = identifiedUserFactory;
    this.currentUser = currentUser;
    this.topicDetailFactory = topicDetailFactory;

    final List<ApprovalType> allTypes = approvalTypes.getApprovalTypes();
    addReviewerCategoryId =
        allTypes.get(allTypes.size() - 1).getCategory().getId();

    this.topicId = topicId;
    this.reviewers = nameOrEmails;
  }

  @Override
  public TopicReviewerResult call() throws Exception {
    final Set<Account.Id> reviewerIds = new HashSet<Account.Id>();
    final TopicControl control = topicControlFactory.validateFor(topicId);

    final TopicReviewerResult result = new TopicReviewerResult();
    for (final String nameOrEmail : reviewers) {
      final Account account = accountResolver.find(nameOrEmail);
      if (account == null) {
        result.addError(new TopicReviewerResult.Error(
            TopicReviewerResult.Error.Type.ACCOUNT_NOT_FOUND, nameOrEmail));
        continue;
      }
      if (!account.isActive()) {
        result.addError(new TopicReviewerResult.Error(
            TopicReviewerResult.Error.Type.ACCOUNT_INACTIVE, nameOrEmail));
        continue;
      }

      final IdentifiedUser user = identifiedUserFactory.create(account.getId());
      if (!control.forUser(user).isVisible()) {
        result.addError(new TopicReviewerResult.Error(
            TopicReviewerResult.Error.Type.CHANGE_NOT_VISIBLE, nameOrEmail));
        continue;
      }

      reviewerIds.add(account.getId());
    }

    if (reviewerIds.isEmpty()) {
      return result;
    }

    TopicUtil.addReviewers(reviewerIds, db, control, addReviewerCategoryId, currentUser, addReviewerSenderFactory);

    result.setTopic(topicDetailFactory.create(topicId).call());
    return result;
  }
}
