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

package com.google.gerrit.server.git;

import com.google.gerrit.reviewdb.AbstractEntity;
import com.google.gerrit.reviewdb.ApprovalCategory;
import com.google.gerrit.reviewdb.Branch;
import com.google.gerrit.reviewdb.Change;
import com.google.gerrit.reviewdb.ReviewDb;
import com.google.gerrit.server.git.MergeOp.MergeDelegate;
import com.google.gwtorm.client.OrmException;
import com.google.gwtorm.client.SchemaFactory;
import com.google.inject.Inject;

import java.util.List;

/**
 * MergeOp variation for staging merges.
 *
 */
public class StagingMergeDelegate implements MergeDelegate {
  /**
   * Factory interface for creating delegates.
   */
  public interface Factory {
    StagingMergeDelegate create();
  }

  private final SchemaFactory<ReviewDb> reviewDbFactory;

  @Inject
  public StagingMergeDelegate(final SchemaFactory<ReviewDb> reviewDbFactory) {
    this.reviewDbFactory = reviewDbFactory;
  }

  @Override
  public List<Change> createMergeList(final Branch.NameKey destBranch)
      throws MergeException {
    ReviewDb reviewDb = null;
    try {
      // Open the review database.
      reviewDb = reviewDbFactory.open();

      // List all changes with STAGING status in the destination branch.
      Branch.NameKey sourceBranch = StagingUtil.getSourceBranch(destBranch);
      List<Change> inStaging = reviewDb.changes().staging(sourceBranch).toList();
      return inStaging;
    } catch (OrmException e) {
      throw new MergeException("Cannot query the database", e);
    } finally {
      // Close the review database.
      if (reviewDb != null) {
        reviewDb.close();
      }
    }
  }

  @Override
  public ApprovalCategory.Id getRequiredApprovalCategory() {
    return ApprovalCategory.STAGING;
  }

  @Override
  public String getMessageForMergeStatus(final CommitMergeStatus status,
      final CodeReviewCommit commit) {
    switch (status) {
      case CLEAN_MERGE: {
        return
          "Change has been successfully merged into the staging branch.";
      }
      case CLEAN_PICK: {
        return
            "Change has been successfully cherry-picked to the staging branch as " + commit.name()
                + ".";
      }
      case PATH_CONFLICT: {
        return
            "Your change could not be merged due to a path conflict.\n"
                + "\n"
                + "Please rebase the change locally against the destination branch and upload a new patch set.\n"
                + "\n"
                + "If the conflict is with another change, wait until it gets integrated before rebasing.";
      }
      case CRISS_CROSS_MERGE: {
        return
            "Your change requires a recursive merge to resolve.\n"
                + "\n"
                + "Please rebase the change locally against the destination branch and upload a new patch set.\n"
                + "\n"
                + "If the conflict is with another change, wait until it gets integrated before rebasing.";
      }
      case CANNOT_CHERRY_PICK_ROOT: {
        return
            "Cannot cherry-pick an initial commit onto an existing branch.\n"
                + "\n"
                + "Please rebase the change locally against the destination branch and upload a new patch set.\n"
                + "\n"
                + "If the conflict is with another change, wait until it gets integrated before rebasing.";
      }
      case NOT_FAST_FORWARD: {
        return
            "Project policy requires all submissions to be a fast-forward.\n"
                + "\n"
                + "Please rebase the change locally against the destination branch and upload a new patch set.\n"
                + "\n"
                + "If the conflict is with another change, wait until it gets integrated before rebasing.";
      }
      default: {
        return null;
      }
    }
  }

  @Override
  public String toString() {
    return "staging";
  }

  @Override
  public AbstractEntity.Status getStatus() {
    return Change.Status.STAGED;
  }

  @Override
  public boolean rebuildStaging() {
    return false;
  }
}
