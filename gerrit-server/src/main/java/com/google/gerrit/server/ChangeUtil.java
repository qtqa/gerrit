// Copyright (C) 2009 The Android Open Source Project
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

package com.google.gerrit.server;

import static com.google.gerrit.reviewdb.ApprovalCategory.STAGING;
import static com.google.gerrit.reviewdb.ApprovalCategory.SUBMIT;

import com.google.gerrit.common.ChangeHookRunner;
import com.google.gerrit.reviewdb.Account;
import com.google.gerrit.reviewdb.ApprovalCategory;
import com.google.gerrit.reviewdb.Branch;
import com.google.gerrit.reviewdb.Change;
import com.google.gerrit.reviewdb.ChangeMessage;
import com.google.gerrit.reviewdb.ChangeSet;
import com.google.gerrit.reviewdb.ChangeSetElement;
import com.google.gerrit.reviewdb.PatchSet;
import com.google.gerrit.reviewdb.PatchSetApproval;
import com.google.gerrit.reviewdb.PatchSetInfo;
import com.google.gerrit.reviewdb.RevId;
import com.google.gerrit.reviewdb.ReviewDb;
import com.google.gerrit.reviewdb.Topic;
import com.google.gerrit.reviewdb.TrackingId;
import com.google.gerrit.server.config.TrackingFooter;
import com.google.gerrit.server.config.TrackingFooters;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.MergeOp;
import com.google.gerrit.server.git.MergeQueue;
import com.google.gerrit.server.git.ReplicationQueue;
import com.google.gerrit.server.git.StagingUtil;
import com.google.gerrit.server.mail.AbandonedSender;
import com.google.gerrit.server.mail.DeferredSender;
import com.google.gerrit.server.mail.EmailException;
import com.google.gerrit.server.mail.RestoredSender;
import com.google.gerrit.server.mail.RevertedSender;
import com.google.gerrit.server.patch.PatchSetInfoFactory;
import com.google.gerrit.server.patch.PatchSetInfoNotAvailableException;
import com.google.gerrit.server.project.InvalidChangeOperationException;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gwtorm.client.AtomicUpdate;
import com.google.gwtorm.client.OrmConcurrencyException;
import com.google.gwtorm.client.OrmException;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.FooterLine;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.Base64;
import org.eclipse.jgit.util.NB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

public class ChangeUtil {
  private static int uuidPrefix;
  private static int uuidSeq;
  private static final Logger log = LoggerFactory
      .getLogger(ChangeUtil.class);

  /**
   * Generate a new unique identifier for change message entities.
   *
   * @param db the database connection, used to increment the change message
   *        allocation sequence.
   * @return the new unique identifier.
   * @throws OrmException the database couldn't be incremented.
   */
  public static String messageUUID(final ReviewDb db) throws OrmException {
    final byte[] raw = new byte[8];
    fill(raw, db);
    return Base64.encodeBytes(raw);
  }

  private static synchronized void fill(byte[] raw, ReviewDb db)
      throws OrmException {
    if (uuidSeq == 0) {
      uuidPrefix = db.nextChangeMessageId();
      uuidSeq = Integer.MAX_VALUE;
    }
    NB.encodeInt32(raw, 0, uuidPrefix);
    NB.encodeInt32(raw, 4, uuidSeq--);
  }

  public static void touch(final Change change, ReviewDb db)
      throws OrmException {
    try {
      updated(change);
      db.changes().update(Collections.singleton(change));
    } catch (OrmConcurrencyException e) {
      // Ignore a concurrent update, we just wanted to tag it as newer.
    }
  }

  public static void updated(final Change c) {
    c.resetLastUpdatedOn();
    computeSortKey(c);
  }

  public static void updateTrackingIds(ReviewDb db, Change change,
      TrackingFooters trackingFooters, List<FooterLine> footerLines)
      throws OrmException {
    if (trackingFooters.getTrackingFooters().isEmpty() || footerLines.isEmpty()) {
      return;
    }

    final Set<TrackingId> want = new HashSet<TrackingId>();
    final Set<TrackingId> have = new HashSet<TrackingId>( //
        db.trackingIds().byChange(change.getId()).toList());

    for (final TrackingFooter footer : trackingFooters.getTrackingFooters()) {
      for (final FooterLine footerLine : footerLines) {
        if (footerLine.matches(footer.footerKey())) {
          // supporting multiple tracking-ids on a single line
          final Matcher m = footer.match().matcher(footerLine.getValue());
          while (m.find()) {
            if (m.group().isEmpty()) {
              continue;
            }

            String idstr;
            if (m.groupCount() > 0) {
              idstr = m.group(1);
            } else {
              idstr = m.group();
            }

            if (idstr.isEmpty()) {
              continue;
            }
            if (idstr.length() > TrackingId.TRACKING_ID_MAX_CHAR) {
              continue;
            }

            want.add(new TrackingId(change.getId(), idstr, footer.system()));
          }
        }
      }
    }

    // Only insert the rows we don't have, and delete rows we don't match.
    //
    final Set<TrackingId> toInsert = new HashSet<TrackingId>(want);
    final Set<TrackingId> toDelete = new HashSet<TrackingId>(have);

    toInsert.removeAll(have);
    toDelete.removeAll(want);

    db.trackingIds().insert(toInsert);
    db.trackingIds().delete(toDelete);
  }

  public static void submit(final PatchSet.Id patchSetId,
      final IdentifiedUser user, final ReviewDb db,
      final MergeOp.Factory opFactory, final MergeQueue merger)
      throws OrmException {
    final Change.Id changeId = patchSetId.getParentKey();
    final PatchSetApproval approval = createSubmitApproval(patchSetId, user, db);

    db.patchSetApprovals().upsert(Collections.singleton(approval));

    final Change updatedChange = db.changes().atomicUpdate(changeId,
        new AtomicUpdate<Change>() {
      @Override
      public Change update(Change change) {
        if (change.getStatus() == Change.Status.NEW
            || change.getStatus() == Change.Status.STAGED
            || change.getStatus() == Change.Status.STAGING
            || change.getStatus() == Change.Status.INTEGRATING) {
          change.setStatus(Change.Status.SUBMITTED);
          ChangeUtil.updated(change);
        }
        return change;
      }
    });

    if (updatedChange.getStatus() == Change.Status.SUBMITTED) {
      merger.merge(opFactory, updatedChange.getDest());
    }
  }

  public static PatchSetApproval createSubmitApproval(
      final PatchSet.Id patchSetId, final IdentifiedUser user, final ReviewDb db
      ) throws OrmException {
    final List<PatchSetApproval> allApprovals =
        new ArrayList<PatchSetApproval>(db.patchSetApprovals().byPatchSet(
            patchSetId).toList());

    final PatchSetApproval.Key akey =
        new PatchSetApproval.Key(patchSetId, user.getAccountId(), SUBMIT);

    for (final PatchSetApproval approval : allApprovals) {
      if (akey.equals(approval.getKey())) {
        approval.setValue((short) 1);
        approval.setGranted();
        return approval;
      }
    }
    return new PatchSetApproval(akey, (short) 1);
  }

  public static void abandon(final PatchSet.Id patchSetId,
      final IdentifiedUser user, final String message, final ReviewDb db,
      final AbandonedSender.Factory senderFactory,
      final ChangeHookRunner hooks) throws NoSuchChangeException,
      InvalidChangeOperationException, EmailException, OrmException {
    abandon(patchSetId, user, message, db, senderFactory, hooks, true);
  }

  public static void abandon(final PatchSet.Id patchSetId,
      final IdentifiedUser user, final String message, final ReviewDb db,
      final AbandonedSender.Factory senderFactory,
      final ChangeHookRunner hooks, final boolean sendMail) throws NoSuchChangeException,
      InvalidChangeOperationException, EmailException, OrmException {
    final Change.Id changeId = patchSetId.getParentKey();
    final PatchSet patch = db.patchSets().get(patchSetId);
    if (patch == null) {
      throw new NoSuchChangeException(changeId);
    }

    final ChangeMessage cmsg =
        new ChangeMessage(new ChangeMessage.Key(changeId, ChangeUtil
            .messageUUID(db)), user.getAccountId());
    final StringBuilder msgBuf =
        new StringBuilder("Patch Set " + patchSetId.get() + ": Abandoned");
    if (message != null && message.length() > 0) {
      msgBuf.append("\n\n");
      msgBuf.append(message);
    }
    cmsg.setMessage(msgBuf.toString());

    final Change updatedChange = db.changes().atomicUpdate(changeId,
        new AtomicUpdate<Change>() {
      @Override
      public Change update(Change change) {
        if ((change.getStatus().isOpen()
            || change.getStatus() == Change.Status.DEFERRED)
            && change.currentPatchSetId().equals(patchSetId)) {
          change.setStatus(Change.Status.ABANDONED);
          ChangeUtil.updated(change);
          return change;
        } else {
          return null;
        }
      }
    });

    if (updatedChange == null) {
      throw new InvalidChangeOperationException(
          "Change is no longer open or patchset is not latest");
    }

    db.changeMessages().insert(Collections.singleton(cmsg));

    final List<PatchSetApproval> approvals =
        db.patchSetApprovals().byChange(changeId).toList();
    for (PatchSetApproval a : approvals) {
      a.cache(updatedChange);
    }
    db.patchSetApprovals().update(approvals);

    if (senderFactory != null) {
      // Email the reviewers
      final AbandonedSender cm = senderFactory.create(updatedChange);
      cm.setFrom(user.getAccountId());
      cm.setChangeMessage(cmsg);
      cm.send();
    } else {
      log.error("Cannot send email when restoring a change belonging to topic.");
    }

    hooks.doChangeAbandonedHook(updatedChange, user.getAccount(), message);
  }

  public static void defer(final PatchSet.Id patchSetId,
      final IdentifiedUser user, final String message, final ReviewDb db,
      final DeferredSender.Factory senderFactory,
      final ChangeHookRunner hooks) throws NoSuchChangeException,
      InvalidChangeOperationException, EmailException, OrmException {
    defer(patchSetId, user, message, db, senderFactory, hooks, true);
  }

  public static void defer(final PatchSet.Id patchSetId,
      final IdentifiedUser user, final String message, final ReviewDb db,
      final DeferredSender.Factory senderFactory,
      final ChangeHookRunner hooks, final boolean sendMail) throws NoSuchChangeException,
      InvalidChangeOperationException, EmailException, OrmException {
    final Change.Id changeId = patchSetId.getParentKey();
    final PatchSet patch = db.patchSets().get(patchSetId);
    if (patch == null) {
      throw new NoSuchChangeException(changeId);
    }

    final ChangeMessage cmsg =
        new ChangeMessage(new ChangeMessage.Key(changeId, ChangeUtil
            .messageUUID(db)), user.getAccountId());
    final StringBuilder msgBuf =
        new StringBuilder("Patch Set " + patchSetId.get() + ": Deferred");
    if (message != null && message.length() > 0) {
      msgBuf.append("\n\n");
      msgBuf.append(message);
    }
    cmsg.setMessage(msgBuf.toString());

    final Change updatedChange = db.changes().atomicUpdate(changeId,
        new AtomicUpdate<Change>() {
      @Override
      public Change update(Change change) {
        if ((change.getStatus().isOpen()
            || change.getStatus() == Change.Status.ABANDONED)
            && change.currentPatchSetId().equals(patchSetId)) {
          change.setStatus(Change.Status.DEFERRED);
          ChangeUtil.updated(change);
          return change;
        } else {
          return null;
        }
      }
    });

    if (updatedChange == null) {
      throw new InvalidChangeOperationException(
          "Change is no longer open or patchset is not latest");
    }

    db.changeMessages().insert(Collections.singleton(cmsg));

    final List<PatchSetApproval> approvals =
        db.patchSetApprovals().byChange(changeId).toList();
    for (PatchSetApproval a : approvals) {
      a.cache(updatedChange);
    }
    db.patchSetApprovals().update(approvals);

    if (senderFactory != null) {
      // Email the reviewers
      final DeferredSender cm = senderFactory.create(updatedChange);
      cm.setFrom(user.getAccountId());
      cm.setChangeMessage(cmsg);
      cm.send();
    } else {
      log.error("Cannot send email when deferring a change.");
    }

    hooks.doChangeDeferredHook(updatedChange, user.getAccount(), message);
  }

  public static void revert(final PatchSet.Id patchSetId,
      final IdentifiedUser user, final String message, final ReviewDb db,
      final RevertedSender.Factory revertedSenderFactory,
      final ChangeHookRunner hooks, GitRepositoryManager gitManager,
      final PatchSetInfoFactory patchSetInfoFactory,
      final ReplicationQueue replication, PersonIdent myIdent)
      throws NoSuchChangeException, EmailException, OrmException,
      MissingObjectException, IncorrectObjectTypeException, IOException,
      PatchSetInfoNotAvailableException {
    revert(patchSetId, user, message, db, revertedSenderFactory, hooks,
        gitManager, patchSetInfoFactory, replication, myIdent, true,
        null, null, null, 0);
  }

  public static void revert(final PatchSet.Id patchSetId,
      final IdentifiedUser user, final String message, final ReviewDb db,
      final RevertedSender.Factory revertedSenderFactory,
      final ChangeHookRunner hooks, GitRepositoryManager gitManager,
      final PatchSetInfoFactory patchSetInfoFactory,
      final ReplicationQueue replication, PersonIdent myIdent,
      final boolean sendmail, final ChangeSet.Id csi,
      final String topic, final Topic.Id tId,
      final int position) throws NoSuchChangeException,
      EmailException, OrmException, MissingObjectException,
      IncorrectObjectTypeException, IOException, PatchSetInfoNotAvailableException {

    final Change.Id changeId = patchSetId.getParentKey();
    final PatchSet patch = db.patchSets().get(patchSetId);
    if (patch == null) {
      throw new NoSuchChangeException(changeId);
    }

    final Repository git;
    try {
      git = gitManager.openRepository(db.changes().get(changeId).getProject());
    } catch (RepositoryNotFoundException e) {
      throw new NoSuchChangeException(changeId, e);
    };

    final RevWalk revWalk = new RevWalk(git);
    try {
      RevCommit commitToRevert =
          revWalk.parseCommit(ObjectId.fromString(patch.getRevision().get()));

      PersonIdent authorIdent =
          user.newCommitterIdent(myIdent.getWhen(), myIdent.getTimeZone());

      RevCommit parentToCommitToRevert = commitToRevert.getParent(0);
      revWalk.parseHeaders(parentToCommitToRevert);

      CommitBuilder revertCommit = new CommitBuilder();
      revertCommit.addParentId(commitToRevert);
      revertCommit.setTreeId(parentToCommitToRevert.getTree());
      revertCommit.setAuthor(authorIdent);
      revertCommit.setCommitter(myIdent);
      revertCommit.setMessage(message);

      final ObjectInserter oi = git.newObjectInserter();;
      ObjectId id;
      try {
        id = oi.insert(revertCommit);
        oi.flush();
      } finally {
        oi.release();
      }

      Change.Key changeKey = new Change.Key("I" + id.name());
      final Change change =
          new Change(changeKey, new Change.Id(db.nextChangeId()),
              user.getAccountId(), db.changes().get(changeId).getDest());
      change.nextPatchSetId();
      if (csi != null) {
        change.setTopic(topic);
        change.setTopicId(tId);

        final ChangeSetElement.Key cseKey = new ChangeSetElement.Key(change.getId(), csi);
        final ChangeSetElement cse = new ChangeSetElement(cseKey, position);
        db.changeSetElements().insert(Collections.singleton(cse));
      }

      final PatchSet ps = new PatchSet(change.currPatchSetId());
      ps.setCreatedOn(change.getCreatedOn());
      ps.setUploader(user.getAccountId());
      ps.setRevision(new RevId(id.getName()));

      db.patchSets().insert(Collections.singleton(ps));

      final PatchSetInfo info =
          patchSetInfoFactory.get(revWalk.parseCommit(id), ps.getId());
      change.setCurrentPatchSet(info);
      ChangeUtil.updated(change);
      db.changes().insert(Collections.singleton(change));

      final RefUpdate ru = git.updateRef(ps.getRefName());
      ru.setNewObjectId(id);
      ru.disableRefLog();
      if (ru.update(revWalk) != RefUpdate.Result.NEW) {
        throw new IOException("Failed to create ref " + ps.getRefName()
            + " in " + git.getDirectory() + ": " + ru.getResult());
      }
      replication.scheduleUpdate(db.changes().get(changeId).getProject(),
          ru.getName());

      final ChangeMessage cmsg =
          new ChangeMessage(new ChangeMessage.Key(changeId,
              ChangeUtil.messageUUID(db)), user.getAccountId());
      final StringBuilder msgBuf =
          new StringBuilder("Patch Set " + patchSetId.get() + ": Reverted");
      msgBuf.append("\n\n");
      msgBuf.append("This patchset was reverted in change: " + changeKey.get());

      cmsg.setMessage(msgBuf.toString());
      db.changeMessages().insert(Collections.singleton(cmsg));

      if (sendmail) {
        final RevertedSender cm = revertedSenderFactory.create(change);
        cm.setFrom(user.getAccountId());
        cm.setChangeMessage(cmsg);
        cm.send();
      }

      hooks.doPatchsetCreatedHook(change, ps);
    } finally {
      revWalk.release();
      git.close();
    }
  }

  public static void restore(final PatchSet.Id patchSetId,
      final IdentifiedUser user, final String message, final ReviewDb db,
      final RestoredSender.Factory senderFactory,
      final ChangeHookRunner hooks) throws NoSuchChangeException,
      InvalidChangeOperationException, EmailException, OrmException {
    restore(patchSetId, user, message, db, senderFactory, hooks, true);
  }

  public static void restore(final PatchSet.Id patchSetId,
      final IdentifiedUser user, final String message, final ReviewDb db,
      final RestoredSender.Factory senderFactory,
      final ChangeHookRunner hooks, final boolean sendMail) throws NoSuchChangeException,
      InvalidChangeOperationException, EmailException, OrmException {
    final Change.Id changeId = patchSetId.getParentKey();
    final PatchSet patch = db.patchSets().get(patchSetId);
    if (patch == null) {
      throw new NoSuchChangeException(changeId);
    }

    final ChangeMessage cmsg =
        new ChangeMessage(new ChangeMessage.Key(changeId, ChangeUtil
            .messageUUID(db)), user.getAccountId());
    final StringBuilder msgBuf =
        new StringBuilder("Patch Set " + patchSetId.get() + ": Restored");
    if (message != null && message.length() > 0) {
      msgBuf.append("\n\n");
      msgBuf.append(message);
    }
    cmsg.setMessage(msgBuf.toString());

    final Change updatedChange = db.changes().atomicUpdate(changeId,
        new AtomicUpdate<Change>() {
      @Override
      public Change update(Change change) {
        if ((change.getStatus() == Change.Status.ABANDONED ||
            change.getStatus() == Change.Status.DEFERRED)
            && change.currentPatchSetId().equals(patchSetId)) {
          change.setStatus(Change.Status.NEW);
          ChangeUtil.updated(change);
          return change;
        } else {
          return null;
        }
      }
    });

    if (updatedChange == null) {
      throw new InvalidChangeOperationException(
          "Change is not abandoned/deferred or patchset is not latest");
    }

    db.changeMessages().insert(Collections.singleton(cmsg));

    final List<PatchSetApproval> approvals =
        db.patchSetApprovals().byChange(changeId).toList();
    for (PatchSetApproval a : approvals) {
      a.cache(updatedChange);
    }
    db.patchSetApprovals().update(approvals);

    if (senderFactory != null) {
      // Email the reviewers
      final RestoredSender cm = senderFactory.create(updatedChange);
      cm.setFrom(user.getAccountId());
      cm.setChangeMessage(cmsg);
      cm.send();
    } else {
      log.error("Cannot send email!");
    }

    hooks.doChangeRestoreHook(updatedChange, user.getAccount(), message);
  }

  public static Set<Account.Id> addReviewers(final Set<Account.Id> reviewerIds, final ReviewDb db,
      final PatchSet.Id psid, final ApprovalCategory.Id addReviewerCategoryId,
      final IdentifiedUser currentUser) throws OrmException {
    // Add the reviewers to the database
    //
    final Set<Account.Id> added = new HashSet<Account.Id>();
    final List<PatchSetApproval> toInsert = new ArrayList<PatchSetApproval>();

    for (final Account.Id reviewer : reviewerIds) {
      if (!exists(psid, reviewer, db)) {
        // This reviewer has not entered an approval for this topic yet.
        //
        final PatchSetApproval myca = dummyApproval(psid, reviewer, addReviewerCategoryId);
        toInsert.add(myca);
        added.add(reviewer);
      }
    }
    db.patchSetApprovals().insert(toInsert);
    return added;
  }

  private static boolean exists(final PatchSet.Id patchSetId,
      final Account.Id reviewerId, final ReviewDb db) throws OrmException {
    return db.patchSetApprovals().byPatchSetUser(patchSetId, reviewerId)
        .iterator().hasNext();
  }

  private static PatchSetApproval dummyApproval(final PatchSet.Id patchSetId,
      final Account.Id reviewerId, ApprovalCategory.Id addReviewerCategoryId) {
    return new PatchSetApproval(new PatchSetApproval.Key(patchSetId,
        reviewerId, addReviewerCategoryId), (short) 0);
  }

  public static PatchSetApproval createStagingApproval(PatchSet.Id patchSetId,
      IdentifiedUser user, ReviewDb db)
  throws OrmException {
    // Get all existing approvals for this patch set.
    final List<PatchSetApproval> allApprovals =
        new ArrayList<PatchSetApproval>(db.patchSetApprovals().byPatchSet(
            patchSetId).toList());

    // Key for staging approval.
    final PatchSetApproval.Key akey =
        new PatchSetApproval.Key(patchSetId, user.getAccountId(), STAGING);

    // Search existing approvals for a staging approval.
    for (final PatchSetApproval approval : allApprovals) {
      if (akey.equals(approval.getKey())) {
        // Existing approval found.
        approval.setValue((short) 1);
        approval.setGranted();
        return approval;
      }
    }

    // Create a new approval.
    return new PatchSetApproval(akey, (short) 1);
  }

  /**
   * Creates a staging removing PatchSetApproval. Caller of this method needs
   * to update the database to remove tha staging approval.
   *
   * @param patchSetId Patch set ID.
   * @param user User taking this action.
   * @param db Review database.
   * @return Existing patch set approval or null if the patch set does not
   *         have a staging approval.
   * @throws OrmException
   */
  public static PatchSetApproval removeStagingApproval(PatchSet.Id patchSetId,
      IdentifiedUser user, ReviewDb db) throws OrmException {
    // Get current approvals.
    final List<PatchSetApproval> allApprovals =
      new ArrayList<PatchSetApproval>(db.patchSetApprovals().byPatchSet(
          patchSetId).toList());

    // Key for staging approvals.
    final PatchSetApproval.Key akey =
        new PatchSetApproval.Key(patchSetId, user.getAccountId(), STAGING);

    // Find existing staging approval. If there are several, first one is
    // enough.
    for (final PatchSetApproval approval : allApprovals) {
      if (akey.equals(approval.getKey())) {
        approval.setValue((short) 0);
        approval.setGranted();
        return approval;
      }
    }

    return null;
  }

  public static String sortKey(long lastUpdated, int id){
    // The encoding uses minutes since Wed Oct 1 00:00:00 2008 UTC.
    // We overrun approximately 4,085 years later, so ~6093.
    //
    final long lastUpdatedOn = (lastUpdated / 1000L) - 1222819200L;
    final StringBuilder r = new StringBuilder(16);
    r.setLength(16);
    formatHexInt(r, 0, (int) (lastUpdatedOn / 60));
    formatHexInt(r, 8, id);
    return r.toString();
  }

  public static void computeSortKey(final Change c) {
    long lastUpdated = c.getLastUpdatedOn().getTime();
    int id = c.getId().get();
    c.setSortKey(sortKey(lastUpdated, id));
  }

  /**
   * Moves a change to staging branch.
   *
   * @param mergeFactory Merge factory for creating merge operators.
   * @param patchSetId Id of the patch set that is to be merged.
   * @param user User taking this action.
   * @param db Review database.
   * @param merger Merge queue.
   * @param git Git repository.
   * @throws OrmException Thrown if access to Review Db fails.
   * @throws IOException Thrown if access to Git repository fails.
   * @throws NoSuchRefException Thrown if source branch does not exist.
   */
  public static void moveToStaging(MergeOp.Factory mergeFactory,
      PatchSet.Id patchSetId, IdentifiedUser user, ReviewDb db,
      MergeQueue merger, Repository git, ChangeHookRunner hooks)
        throws OrmException, IOException, NoSuchRefException {
    // Create and insert staging approval to the database.
    final PatchSetApproval approval =
      createStagingApproval(patchSetId, user, db);
    db.patchSetApprovals().upsert(Collections.singleton(approval));

    // Change change state from NEW to STAGING.
    final Change.Id changeId = patchSetId.getParentKey();
    AtomicUpdate<Change> atomicUpdate =
      getUpdateToState(Change.Status.NEW, Change.Status.STAGING);
    final Change change = db.changes().atomicUpdate(changeId, atomicUpdate);

    // Check if staging branch exists. Create the staging branch if it does not
    // exist.
    final Branch.NameKey stagingBranch =
      StagingUtil.getStagingBranch(change.getDest());
    if (!StagingUtil.branchExists(git, stagingBranch)) {
      StagingUtil.createStagingBranch(git, change.getDest());
    }

    // Activate the merge queue.
    merger.merge(mergeFactory, stagingBranch);
  }

  /**
   * Removes a commit from staging branch. Status of the change in reset to
   * NEW.
   *
   * @param patchSetId Patch set to be removed from staging.
   * @param user User taking this action.
   * @param db Review database.
   * @throws OrmException If review database cannot be accessed.
   */
  public static void rejectStagedChange(PatchSet.Id patchSetId,
      IdentifiedUser user, ReviewDb db) throws OrmException {
    // Delete all STAGING approvals for the patch set.
    final PatchSetApproval.Key stagingKey =
      new PatchSetApproval.Key(patchSetId, user.getAccountId(), STAGING);
    db.patchSetApprovals().deleteKeys(Collections.singleton(stagingKey));

    // Set change state to NEW.
    final Change.Id changeId = patchSetId.getParentKey();
    AtomicUpdate<Change> atomicUpdate =
      new AtomicUpdate<Change>() {
      @Override
      public Change update(Change change) {
        if (change.getStatus() == Change.Status.INTEGRATING
            || change.getStatus() == Change.Status.STAGED) {
          change.setStatus(Change.Status.NEW);
          ChangeUtil.updated(change);
        }
        return change;
      }
    };
    db.changes().atomicUpdate(changeId, atomicUpdate);
  }

  /**
   * Moves change from integrating to merged. Only database is updated.
   *
   * @param patchSetId Patch set id for accessing the change.
   * @param user User taking the action.
   * @param db Review database.
   * @throws OrmException Thrown, if access to review database fails.
   */
  public static void setIntegratingToMerged(PatchSet.Id patchSetId, IdentifiedUser user,
      ReviewDb db) throws OrmException {
    final Change.Id changeId = patchSetId.getParentKey();
    AtomicUpdate<Change> atomicUpdate =
      getUpdateToState(Change.Status.INTEGRATING, Change.Status.MERGED);
    Change change = db.changes().atomicUpdate(changeId, atomicUpdate);
    List<PatchSetApproval> approvals = db.patchSetApprovals().byChange(changeId).toList();
    for (PatchSetApproval a : approvals) {
      a.cache(change);
    }
    db.patchSetApprovals().update(approvals);
  }

  /**
   * Merges an already merged change once more to staging. This method should
   * be used when an update in main branch causes the staging branch to be
   * updated.
   *
   * @param mergeFactory Merge operator.
   * @param patchSetId Patch set ID.
   * @param user User taking the action.
   * @param db Review database.
   * @param merger Merge queue.
   * @param git Git repository.
   * @throws OrmException Thrown, if review database cannot be accessed.
   * @throws IOException Thrown, if Git repository cannot be accessed.
   * @throws NoSuchRefException Thrown, if source branch is not available.
   */
  public static void restage(MergeOp.Factory mergeFactory,
      PatchSet.Id patchSetId, IdentifiedUser user, ReviewDb db,
      MergeQueue merger, Repository git) throws OrmException, IOException,
      NoSuchRefException {
    // In order to make the patch set visible to merge queue, move it from
    // STAGED to STAGING state.
    final Change.Id changeId = patchSetId.getParentKey();
    AtomicUpdate<Change> atomicUpdate =
      getUpdateToState(Change.Status.STAGED, Change.Status.STAGING);
    final Change change = db.changes().atomicUpdate(changeId, atomicUpdate);

    // Check if staging branch exists. Create a new staging branch if it does
    // not exist.
    final Branch.NameKey stagingBranch =
      StagingUtil.getStagingBranch(change.getDest());
    if (!StagingUtil.branchExists(git, stagingBranch)) {
      StagingUtil.createStagingBranch(git, change.getDest());
    }

    // Activate the merge queue.
    merger.merge(mergeFactory, stagingBranch);
  }

  /**
   * Reset the staging branch. This method should be called if some change
   * is removed from staging branch. For example, this method is called after
   * abandoning a change.
   *
   * @param branch Destination branch. E.g. refs/heads/master
   * @param user User taking this action.
   * @param db Review database.
   * @param git Git repository.
   * @param mergeFactory Merge operator factory.
   * @param merger Merge queue.
   * @param ChangeHookRunner Hooks runner. Ref update will be send as part
   *        the rebuild.
   * @throws OrmException Thrown, if review database cannot be accessed.
   * @throws IOException Thrown, if Git repository cannot be accessed.
   * @throws NoSuchRefException Thrown, if destination branch is not available.
   */
  public static void rebuildStaging(Branch.NameKey branch, IdentifiedUser user,
      ReviewDb db, Repository git, MergeOp.Factory mergeFactory,
      MergeQueue merger, ChangeHookRunner hooks)
      throws OrmException, IOException, NoSuchRefException {
    final Branch.NameKey stagingBranch = StagingUtil.getStagingBranch(branch);

    // Start staging branch from scratch.
    Ref ref = git.getRef(stagingBranch.get());
    ObjectId oldTip = null;
    if (ref != null) {
      oldTip = ref.getObjectId();
    }
    StagingUtil.createStagingBranch(git, branch);
    ref = git.getRef(branch.get());
    ObjectId newTip = null;
    if (ref != null) {
      newTip = ref.getObjectId();
    }

    if (oldTip != null && newTip != null && !oldTip.equals(newTip)) {
      hooks.doRefUpdatedHook(branch, oldTip, newTip, user.getAccount());
    }

    // Loop through all changes with status STAGED.
    List<Change> staged = db.changes().staged(branch).toList();
    for (Change change : staged) {
      final PatchSet.Id currentPatchSet = change.currentPatchSetId();
      final Change.Id changeId = currentPatchSet.getParentKey();

      // Reset status to STAGING.
      AtomicUpdate<Change> atomicUpdate =
        getUpdateToState(Change.Status.STAGED, Change.Status.STAGING);
      db.changes().atomicUpdate(changeId, atomicUpdate);
    }

    // Merge all changes.
    merger.merge(mergeFactory, stagingBranch);
  }

  public static void setIntegrating(PatchSet.Id patchSetId, ReviewDb db)
      throws OrmException {
    final Change.Id changeId = patchSetId.getParentKey();
    AtomicUpdate<Change> atomicUpdate = getUpdateToState(Change.Status.STAGED,
        Change.Status.INTEGRATING);
    db.changes().atomicUpdate(changeId, atomicUpdate);
  }

  private static AtomicUpdate<Change> getUpdateToState(final Change.Status from,
      final Change.Status to) {
    return new AtomicUpdate<Change>() {
      @Override
      public Change update(Change change) {
        if (change.getStatus() == from) {
          change.setStatus(to);
          ChangeUtil.updated(change);
        }
        return change;
      }
    };
  }

  private static final char[] hexchar =
      {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', //
          'a', 'b', 'c', 'd', 'e', 'f'};

  private static void formatHexInt(final StringBuilder dst, final int p, int w) {
    int o = p + 7;
    while (o >= p && w != 0) {
      dst.setCharAt(o--, hexchar[w & 0xf]);
      w >>>= 4;
    }
    while (o >= p) {
      dst.setCharAt(o--, '0');
    }
  }
}
