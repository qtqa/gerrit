package com.google.gerrit.server.git;

import com.google.gerrit.reviewdb.AbstractEntity;
import com.google.gerrit.reviewdb.ApprovalCategory;
import com.google.gerrit.reviewdb.Change;
import com.google.gerrit.reviewdb.ReviewDb;
import com.google.gerrit.reviewdb.ApprovalCategory.Id;
import com.google.gerrit.reviewdb.Branch.NameKey;
import com.google.gerrit.server.git.MergeOp.MergeDelegate;
import com.google.gwtorm.client.OrmException;
import com.google.gwtorm.client.SchemaFactory;
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
      List<Change> inStaging = reviewDb.changes().submitted(destBranch).toList();
      return inStaging;
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
  public Id getRequiredApprovalCategory() {
    return ApprovalCategory.SUBMIT;
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
  public AbstractEntity.Status getStatus() {
    return Change.Status.MERGED;
  }

  @Override
  public boolean rebuildStaging() {
    return true;
  }
}
