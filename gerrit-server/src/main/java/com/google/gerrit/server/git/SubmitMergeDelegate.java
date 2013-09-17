// Copyright (C) 2011 The Android Open Source Project
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
package com.google.gerrit.server.git;

import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.reviewdb.client.Branch.NameKey;
import com.google.gerrit.server.git.MergeOp.MergeDelegate;
import static com.google.gerrit.server.git.MergeUtil.getSubmitter;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;

import java.util.List;

/**
 * MergeOp variation for submit merges.
 *
 */
public class SubmitMergeDelegate implements MergeDelegate {
  /**
   * Factory interface for creating delegates.
   */
  public interface Factory {
    SubmitMergeDelegate create();
  }

  private final SchemaFactory<ReviewDb> reviewDbFactory;

  @Inject
  public SubmitMergeDelegate(final SchemaFactory<ReviewDb> reviewDbFactory) {
    this.reviewDbFactory = reviewDbFactory;
  }

  @Override
  public List<Change> createMergeList(NameKey destBranch) throws MergeException {
    ReviewDb reviewDb = null;
    try {
      // Open review database.
      reviewDb = reviewDbFactory.open();

      // List all submitted changes in the destination branch.
      List<Change> inSubmitted = reviewDb.changes().submitted(destBranch).toList();
      return inSubmitted;
    } catch (OrmException e) {
      throw new MergeException("Cannot query the database", e);
    } finally {
      if (reviewDb != null) {
        // Close the review database.
        reviewDb.close();
      }
    }
  }

  @Override
  public PatchSetApproval.LabelId getRequiredApprovalCategory() {
    return PatchSetApproval.LabelId.SUBMIT;
  }

  @Override
  public String getMessageForMergeStatus(CommitMergeStatus status,
      CodeReviewCommit commit) {
    // Use default messages.
    return null;
  }

  @Override
  public String toString() {
    return "submit";
  }

  @Override
  public Change.Status getStatus() {
    return Change.Status.MERGED;
  }

  @Override
  public boolean needsRebuildStaging() {
    return true;
  }

  @Override
  public boolean isRequiredApproval(PatchSetApproval psa) {
    return psa.isSubmit();
  }

  @Override
  public PatchSetApproval getApproval(final ReviewDb reviewDb, final PatchSet.Id c) {
    return getSubmitter(reviewDb, c);
  }
}

