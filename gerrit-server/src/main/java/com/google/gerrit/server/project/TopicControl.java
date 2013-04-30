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

package com.google.gerrit.server.project;

import com.google.gerrit.common.data.ApprovalTypes;
import com.google.gerrit.common.data.PermissionRange;
import com.google.gerrit.reviewdb.ChangeSet;
import com.google.gerrit.reviewdb.ChangeSetApproval;
import com.google.gerrit.reviewdb.Project;
import com.google.gerrit.reviewdb.ReviewDb;
import com.google.gerrit.reviewdb.Topic;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.workflow.TopicFunctionState;
import com.google.gwtorm.client.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;

import java.util.List;


/** Access control management for a user accessing a single topic. */
public class TopicControl {
  public static class GenericFactory {
    private final ProjectControl.GenericFactory projectControl;

    @Inject
    GenericFactory(ProjectControl.GenericFactory p) {
      projectControl = p;
    }

    public TopicControl controlFor(Topic topic, CurrentUser user)
        throws NoSuchTopicException {
      final Project.NameKey projectKey = topic.getProject();
      try {
        return projectControl.controlFor(projectKey, user).controlFor(topic);
      } catch (NoSuchProjectException e) {
        throw new NoSuchTopicException(topic.getId(), e);
      }
    }
  }

  public static class Factory {
    private final ProjectControl.Factory projectControl;
    private final Provider<ReviewDb> db;

    @Inject
    Factory(final ProjectControl.Factory p, final Provider<ReviewDb> d) {
      projectControl = p;
      db = d;
    }

    public TopicControl controlFor(final Topic.Id id)
        throws NoSuchTopicException {
      final Topic topic;
      try {
        topic = db.get().topics().get(id);
        if (topic == null) {
          throw new NoSuchTopicException(id);
        }
      } catch (OrmException e) {
        throw new NoSuchTopicException(id, e);
      }
      return controlFor(topic);
    }

    public TopicControl controlFor(final Topic topic)
        throws NoSuchTopicException {
      try {
        final Project.NameKey projectKey = topic.getProject();
        return projectControl.validateFor(projectKey).controlFor(topic);
      } catch (NoSuchProjectException e) {
        throw new NoSuchTopicException(topic.getId(), e);
      }
    }

    public TopicControl validateFor(final Topic.Id id)
        throws NoSuchTopicException {
      return validate(controlFor(id));
    }

    public TopicControl validateFor(final Topic topic)
        throws NoSuchTopicException {
      return validate(controlFor(topic));
    }

    private static TopicControl validate(final TopicControl c)
        throws NoSuchTopicException {
      if (!c.isVisible()) {
        throw new NoSuchTopicException(c.getTopic().getId());
      }
      return c;
    }
  }

  private final RefControl refControl;
  private final Topic topic;

  TopicControl(final RefControl r, final Topic t) {
    this.refControl = r;
    this.topic = t;
  }

  public TopicControl forUser(final CurrentUser who) {
    return new TopicControl(getRefControl().forUser(who), getTopic());
  }

  public RefControl getRefControl() {
    return refControl;
  }

  public CurrentUser getCurrentUser() {
    return getRefControl().getCurrentUser();
  }

  public ProjectControl getProjectControl() {
    return getRefControl().getProjectControl();
  }

  public Project getProject() {
    return getProjectControl().getProject();
  }

  public Topic getTopic() {
    return topic;
  }

  /** Can this user see this topic? */
  public boolean isVisible() {
    return getRefControl().isVisible();
  }

  /** Can this user abandon this topic? */
  public boolean canAbandon() {
    return isOwner() // owner (aka creator) of the change can abandon
        || getRefControl().isOwner() // branch owner can abandon
        || getProjectControl().isOwner() // project owner can abandon
        || getCurrentUser().isAdministrator() // site administers are god
    ;
  }

  /** Can this user defer this topic? */
  public boolean canDefer() {
    return isOwner() // owner (aka creator) of the change can defer
        || getRefControl().isOwner() // branch owner can defer
        || getProjectControl().isOwner() // project owner can defer
        || getCurrentUser().isAdministrator() // site administers are god
    ;
  }

  /** Can this user restore this topic? */
  public boolean canRestore() {
    // Anyone who can abandon or defer the change can restore it back
    return canAbandon() || canDefer();
  }

  /** All value ranges of any allowed label permission. */
  public List<PermissionRange> getLabelRanges() {
    return getRefControl().getLabelRanges();
  }

  /** The range of permitted values associated with a label permission. */
  public PermissionRange getRange(String permission) {
    return getRefControl().getRange(permission);
  }

  /** Can this user add a change set to this topic? */
  public boolean canAddChangeSet() {
    return getRefControl().canUpload();
  }

  /** Is this user the owner of the topic? */
  public boolean isOwner() {
    if (getCurrentUser() instanceof IdentifiedUser) {
      final IdentifiedUser i = (IdentifiedUser) getCurrentUser();
      return i.getAccountId().equals(topic.getOwner());
    }
    return false;
  }

  /** @return true if the user is allowed to remove this reviewer. */
  public boolean canRemoveReviewer(ChangeSetApproval approval) {
    if (getTopic().getStatus().isOpen()) {
      // A user can always remove themselves.
      //
      if (getCurrentUser() instanceof IdentifiedUser) {
        final IdentifiedUser i = (IdentifiedUser) getCurrentUser();
        if (i.getAccountId().equals(approval.getAccountId())) {
          return true; // can remove self
        }
      }

      // The change owner may remove any zero or positive score.
      //
      if (isOwner() && 0 <= approval.getValue()) {
        return true;
      }

      // The branch owner, project owner, site admin can remove anyone.
      //
      if (getRefControl().isOwner() // branch owner
          || getProjectControl().isOwner() // project owner
          || getCurrentUser().isAdministrator()) {
        return true;
      }
    }

    return false;
  }

  public CanSubmitResult canSubmit(ReviewDb db, ChangeSet.Id changeSetId,
      final ApprovalTypes approvalTypes,
      final TopicFunctionState.Factory functionStateFactory)
      throws NoSuchChangeException, OrmException {
    CanSubmitResult result = canSubmit(changeSetId);
    if (result != CanSubmitResult.OK) {
      return result;
    }

    return CanSubmitResult.OK;
  }

  public CanSubmitResult canSubmit(ChangeSet.Id changeSetId) {
    if (topic.getStatus().isClosed()) {
      return new CanSubmitResult("topic " + topic.getId() + " is closed");
    }
    if (!changeSetId.equals(topic.currentChangeSetId())) {
      return new CanSubmitResult("Change set " + changeSetId + " is not current");
    }
    if (!getRefControl().canSubmit()) {
      return new CanSubmitResult("User does not have permission to submit");
    }
    if (!(getCurrentUser() instanceof IdentifiedUser)) {
      return new CanSubmitResult("User is not signed-in");
    }
    return CanSubmitResult.OK;
  }

  public CanSubmitResult canStage(ChangeSet.Id changeSetId) {
    if (topic.getStatus().isClosed()) {
      return new CanSubmitResult("topic " + topic.getId() + " is closed");
    }
    if (!changeSetId.equals(topic.currentChangeSetId())) {
      return new CanSubmitResult("Change set " + changeSetId + " is not current");
    }
    if (!getRefControl().canBranchToStaging()) {
      return new CanSubmitResult("User does not have permission to merge to staging");
    }
    if (!(getCurrentUser() instanceof IdentifiedUser)) {
      return new CanSubmitResult("User is not signed-in");
    }
    return CanSubmitResult.OK;
  }

  public CanSubmitResult canStage(ReviewDb db, ChangeSet.Id changeSetId,
      final ChangeControl.Factory changeControlFactory,
      final ApprovalTypes approvalTypes,
      final TopicFunctionState.Factory functionStateFactory)
      throws NoSuchChangeException, OrmException {
    CanSubmitResult result = canStage(changeSetId);
    if (result != CanSubmitResult.OK) {
      return result;
    }

    return CanSubmitResult.OK;
  }
}
