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
package com.google.gerrit.sshd.commands;

import static com.google.gerrit.sshd.commands.StagingCommand.R_BUILDS;
import static com.google.gerrit.sshd.commands.StagingCommand.R_HEADS;

import com.google.common.collect.Lists;
import com.google.gerrit.common.ChangeHookRunner;
import com.google.gerrit.common.data.LabelTypes;
import com.google.gerrit.common.errors.EmailException;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.ChangeMessage;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.Project.SubmitType;
import com.google.gerrit.reviewdb.client.RevId;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.PostReview.NotifyHandling;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.MergeQueue;
import com.google.gerrit.server.mail.AbandonedSender;
import com.google.gerrit.server.mail.BuildApprovedSender;
import com.google.gerrit.server.mail.BuildRejectedSender;
import com.google.gerrit.server.mail.CommentSender;
import com.google.gerrit.server.mail.ReplyToChangeSender;
import com.google.gerrit.server.patch.PatchSetInfoFactory;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.ProjectControl;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.gerrit.sshd.commands.StagingCommand.BranchNotFoundException;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * A command to report pass or fail status for builds. When a build receives
 * pass status, the branch is updated with build ref and all open changes in
 * the build are marked as merged. When a build receives fail status, all
 * change in the build are marked as new and they need to be staged again.
 * <p>
 * For example, how to approve a build
 * $ ssh -p 29418 localhost gerrit staging-approve -p project -b master -i 123 -r=pass
 */
@CommandMetaData(name = "staging-approve", descr = "Report pass or fail status for builds.")
public class StagingApprove extends SshCommand {
  private static final Logger log =
    LoggerFactory.getLogger(StagingApprove.class);

  private class MergeException extends Exception {
    private static final long serialVersionUID = 1L;

    public MergeException(String message) {
      super(message);
    }
  }

  /** Parameter value for pass result.  */
  private static final String PASS = "pass";
  /** Parameter value for fail result. */
  private static final String FAIL = "fail";
  /** Parameter value for stdin message. */
  private static final String STDIN_MESSAGE = "-";

  @Inject
  private GitRepositoryManager gitManager;

  @Inject
  private ReviewDb db;

  @Inject
  private ChangeControl.Factory changeControlFactory;

  @Inject
  private ProjectControl.Factory projectControlFactory;

  @Inject
  private IdentifiedUser currentUser;

  @Inject
  private ChangeHookRunner hooks;

  @Inject
  private MergeQueue merger;

  @Inject
  private BuildApprovedSender.Factory buildApprovedFactory;

  @Inject
  private BuildRejectedSender.Factory buildRejectedFactory;

  @Inject
  private AbandonedSender.Factory abandonedSenderFactory;

  @Inject
  private CommentSender.Factory commentSenderFactory;

  @Inject
  private PatchSetInfoFactory patchSetInfoFactory;

  @Inject
  private GitReferenceUpdated gitRefUpdated;

  @Option(name = "--project", aliases = {"-p"},
      required = true, usage = "project name")
  private String project;

  @Option(name = "--build-id", aliases = {"-i"},
      required = true, usage = "build branch containing changes, e.g. refs/builds/123 or 123")
  private String buildBranch;

  @Option(name = "--result", aliases = {"-r"},
      required = true, usage = "pass or fail")
  private String result;

  @Option(name = "--message", aliases = {"-m"}, metaVar ="-|MESSAGE",
      usage = "message added to all changes")
  private String message;

  @Option(name = "--branch", aliases = {"-b"}, metaVar = "BRANCH",
      required = true, usage = "destination branch, e.g. refs/heads/master or just master")
  private String branch;

  private Repository git;

  private List<Map.Entry<PatchSet,RevCommit>> toApprove;

  private Branch.NameKey destination;

  private List<ReplyToChangeSender> emailMessages;

  @Override
  protected void run() throws UnloggedFailure {
    StagingApprove.this.approve();
  }

  private void approve() throws UnloggedFailure {
    // Check result parameter value.
    boolean passed;
    if (result.toLowerCase().equals(PASS)) {
      passed = true;
    } else if (result.toLowerCase().equals(FAIL)) {
      passed = false;
    } else {
      // A valid result parameter value was not used.
      throw new UnloggedFailure(1,
          "fatal: result argument accepts only value pass or fail.");
    }

    final PrintWriter stdout = toPrintWriter(out);

    // Name key for the build branch.
    Branch.NameKey buildBranchNameKey =
      StagingCommand.getNameKey(project, R_BUILDS, buildBranch);

    destination = StagingCommand.getNameKey(project, R_HEADS, branch);

    try {
      openRepository(project);

      // Initialize and populate open changes list.
      toApprove = StagingCommand.openChanges(git, db, buildBranchNameKey,
          destination);

      // Notify user that build did not have any open changes. The build has
      // already been approved.
      if (toApprove.isEmpty()) {
        throw new UnloggedFailure(1, "No open changes in the build branch");
      }

      // If result is passed, check that the user has required access rights
      // to push changes.
      if (passed) {
        validatePushRights(project);
      }

      // Create list for possible email notification messages sent to user at the end
      emailMessages = new ArrayList<ReplyToChangeSender>();

      // Use current message or read it from stdin.
      prepareMessage();

      // Start database transaction and execute all database operations
      // before doing any merges on git. If git merge fails we can rollback
      // everything.
      //
      // Parameter for this function is used for nothing, so null is fine
      db.changes().beginTransaction(null);

      // Iterate through each open change and publish message.
      for (Map.Entry<PatchSet,RevCommit> item : toApprove) {
        PatchSet patchSet = item.getKey();
        PatchSet.Id patchSetId = patchSet.getId();

        // If change not in state INTEGRATING it will be abandoned
        final Change change = db.changes().get(patchSetId.getParentKey());
        if (change.getStatus() != Change.Status.INTEGRATING) {
          abandonMessage(patchSet, getAbandonMessage(change));
          abandon(patchSetId);
          continue;
        }

        // Create a new patchset for merged commit to be consistent
        // with cherry-pick submit behavior
        if (passed && isSubmitTypeCherryPick(project)) {
          patchSet = createNewPatchSetForMergedCommit(change, patchSet, item.getValue());
          patchSetId = patchSet.getId();
        }

        // Publish message but only send mail if not passed
        publishMessage(patchSet, !passed);

        if (passed) {
          // Set change status to merged.
          pass(patchSetId);
        } else {
          // Reset change status.
          reject(patchSetId);
        }
      }

      // Fast forward destination branch to build branch
      if (passed) {
        updateDestinationBranch(buildBranchNameKey);
      }

      db.commit();

      // Send email notifications, log errors
      for (ReplyToChangeSender item : emailMessages) {
        try {
          item.send();
        } catch (EmailException e) {
          log.error("Cannot send comments by email", e);
        }
      }

      if (passed) {
        // Rebuild staging branch.
        try {
          ChangeUtil.rebuildStaging(destination, currentUser, db, git, merger, hooks);
        } catch (Exception e) {
          log.error("Failed to update staging branch", e);
        }
      }
    } catch (IOException e) {
      throw new UnloggedFailure(1, "fatal: " + e.getMessage(), e);
    } catch (OrmException e) {
      throw new UnloggedFailure(1, "fatal: Failed to access database", e);
    } catch (MergeException e) {
      throw new UnloggedFailure(1, "fatal: Failed to update destination branch", e);
    } catch (NoSuchProjectException e) { // Invalid project name passed
      throw new UnloggedFailure(1, "fatal: Failed to access project", e);
    } catch (BranchNotFoundException e) { // Invalid branch name passed
      throw new UnloggedFailure(1, "fatal: " + e.getMessage(), e);
    } finally {
      try {
        db.rollback();
      } catch (OrmException e) {
        log.error("Failed to roll back transaction", e);
      }
      stdout.flush();
      if (git != null) {
        git.close();
      }
    }
  }

  private String getAbandonMessage(Change change) throws OrmException {
    // Search all changes from database where change id matches to incoming one.
    // Pick first merged one where destination doesn't match and use that for
    // abandon message.
    String source_branch = "other branch"; // This is used if branch name not found
    List<Change> changes = db.changes().byKey(change.getKey()).toList();
    for (Change c : changes) {
      if (!c.getDest().equals(destination)
          && c.getStatus() == Change.Status.MERGED) {
        source_branch = "branch '"
            + StagingCommand.getShortNameKey(project, R_HEADS, c.getDest().get()).get()
            + "'";
        break;
      }
    }
    String abandonMessage =
        "This change has been abandoned because it was already integrated in "
        + source_branch + " which was merged into branch '"
        + destination.getShortName() +"'.";
    // Add original message also to help solving the problem
    if (message != null && message.length() > 0) {
      abandonMessage += "\n\n" + message;
    }
    return abandonMessage;
  }

  private void openRepository(final String project) throws IOException {
    Project.NameKey projectNameKey = new Project.NameKey(project);
    git = gitManager.openRepository(projectNameKey);
  }

  private void validatePushRights(final String project) throws UnloggedFailure,
      NoSuchProjectException {
    Project.NameKey projectNameKey = new Project.NameKey(project);
    final ProjectControl projectControl = projectControlFactory.validateFor(projectNameKey);
    if (!projectControl.controlForRef(destination).canUpdate()) {
      throw new UnloggedFailure(1, "No Push right to " + destination);
    }
  }

  private void abandonMessage(final PatchSet patchSet, final String msg)
      throws OrmException {
    createChangeMessage(patchSet, true, msg, true);
  }

  private void publishMessage(final PatchSet patchSet, final boolean sendMail)
      throws OrmException {
    createChangeMessage(patchSet, sendMail, message, false);
  }

  private void createChangeMessage(final PatchSet patchSet, final boolean sendMail,
      final String msg, final boolean isAbandon)
      throws OrmException {
    if (msg != null && msg.length() > 0) {
      final PatchSet.Id patchSetId = patchSet.getId();
      Change change = db.changes().get(patchSetId.getParentKey());
      final ChangeMessage cmsg =
          new ChangeMessage(new ChangeMessage.Key(change.getId(),
              ChangeUtil.messageUUID(db)), currentUser.getAccountId(), patchSetId);
      cmsg.setMessage(msg);
      db.changeMessages().insert(Collections.singleton(cmsg));
      if (sendMail) {
        ReplyToChangeSender rtcs = null;
        if (!isAbandon) {
          rtcs = commentSenderFactory.create(NotifyHandling.ALL, change);
        } else {
          rtcs = abandonedSenderFactory.create(change);
        }
        rtcs.setFrom(currentUser.getAccountId());
        rtcs.setPatchSet(patchSet);
        rtcs.setChangeMessage(cmsg);
        emailMessages.add(rtcs);
      }
    }
  }

  private void updateDestinationBranch(final Branch.NameKey buildBranchKey)
    throws IOException, MergeException {
    RevWalk revWalk = null;
    try {
      // Setup RevWalk.
      revWalk = new RevWalk(git);
      revWalk.sort(RevSort.TOPO);
      revWalk.sort(RevSort.COMMIT_TIME_DESC, true);

      // Prepare branch update. Set destination branch tip as old object id.
      RefUpdate branchUpdate = git.updateRef(destination.get());
      // Access tip of build branch.
      Ref buildRef = git.getRef(buildBranchKey.get());

      // Access commits from destination and build branches.
      RevCommit branchTip = revWalk.parseCommit(branchUpdate.getOldObjectId());
      RevCommit buildTip = revWalk.parseCommit(buildRef.getObjectId());

      // Setup branch update.
      branchUpdate.setForceUpdate(false);

      // We are updating old destination branch tip to build branch tip.
      branchUpdate.setNewObjectId(buildTip);

      // Make sure that the build tip is reachable from the branch tip.
      if (!revWalk.isMergedInto(branchTip, buildTip)) {
        throw new MergeException(destination.get() + " is not reachable from "
            + buildBranchKey.get());
      }

      // Update destination branch.
      RefUpdate.Result result = branchUpdate.update(revWalk);
      switch (result) {
        // Only fast-forward result is reported as success.
        case FAST_FORWARD:
          gitRefUpdated.fire(destination.getParentKey(), branchUpdate);
          hooks.doRefUpdatedHook(destination, branchUpdate,
              currentUser.getAccount());
          try {
            sendBuildApprovedMails();
          } catch (Exception e) {
            log.error("Failed to send change merged e-mails", e);
          }
          break;
        default:
          try {
            sendBuildRejectedMails();
          } catch (Exception e) {
            log.error("Failed to send change rejected e-mails", e);
          }
          throw new MergeException("Could not push build to destination branch");
      }
    } finally {
      if (revWalk != null) {
        revWalk.dispose();
      }
    }
  }

  private void pass(final PatchSet.Id patchSetId) throws OrmException {
    // Update change status from INTEGRATING to MERGED.
    ChangeUtil.setIntegratingToMerged(patchSetId, currentUser, db);
  }

  private void reject(final PatchSet.Id patchSetId) throws OrmException {
    // Remove staging approval and update status from INTEGRATING to NEW.
    ChangeUtil.rejectStagedChange(patchSetId, currentUser, db);
  }

  private void abandon(final PatchSet.Id patchSetId) throws OrmException {
    // Update change status from INTEGRATING to ABANDONED.
    ChangeUtil.setIntegratingToAbandoned(patchSetId, currentUser, db);
  }

  private void prepareMessage() throws IOException {
    // No message given.
    if (message == null) {
      return;
    }

    // User will submit message through stdin.
    if (message.equals(STDIN_MESSAGE)) {
      // Clear stdin indicator.
      message = "";

      // Read message from stdin.
      BufferedReader stdin
        = new BufferedReader(new InputStreamReader(in, "UTF-8"));
      String line;
      while ((line = stdin.readLine()) != null) {
        message += line + "\n";
      }
    } // Else, use current message value.
  }

  private void sendBuildApprovedMails() throws OrmException, EmailException, NoSuchChangeException {
    for (Entry<PatchSet, RevCommit> item : toApprove) {
      final PatchSet patchSet = item.getKey();
      final PatchSet.Id patchSetId = patchSet.getId();
      final Change.Id changeId = patchSetId.getParentKey();
      final Change change = db.changes().get(changeId);

      final ChangeControl changeControl =
          changeControlFactory.validateFor(changeId);
      final LabelTypes lt = changeControl.getLabelTypes();

      final BuildApprovedSender sender = buildApprovedFactory.create(lt, change);
      sender.setBuildApprovedMessage(message);
      sender.setFrom(currentUser.getAccountId());
      sender.setPatchSet(patchSet);
      sender.send();
    }
  }

  private void sendBuildRejectedMails() throws OrmException, EmailException {
    for (Entry<PatchSet, RevCommit> item : toApprove) {
      final PatchSet patchSet = item.getKey();
      final PatchSet.Id patchSetId = patchSet.getId();
      final Change.Id changeId = patchSetId.getParentKey();
      final Change change = db.changes().get(changeId);

      final BuildRejectedSender sender = buildRejectedFactory.create(change);
      sender.setFrom(currentUser.getAccountId());
      sender.setPatchSet(patchSet);
      sender.send();
    }
  }

  private List<PatchSetApproval> getApprovalsForCommit(final PatchSet.Id psid) {
    try {
      List<PatchSetApproval> approvalList =
          db.patchSetApprovals().byPatchSet(psid).toList();
      Collections.sort(approvalList, new Comparator<PatchSetApproval>() {
        @Override
        public int compare(final PatchSetApproval a, final PatchSetApproval b) {
          return a.getGranted().compareTo(b.getGranted());
        }
      });
      return approvalList;
    } catch (OrmException e) {
      log.error("Can't read approval records for " + psid, e);
      return Collections.emptyList();
    }
  }

  private PatchSet createNewPatchSetForMergedCommit(final Change change,
      final PatchSet patchSet, final RevCommit newCommit)
          throws OrmException, IOException {
    final PatchSet.Id patchSetId
      = ChangeUtil.nextPatchSetId(git, change.currentPatchSetId());
    PatchSet newPatchSet = new PatchSet(patchSetId);
    newPatchSet.setCreatedOn(new Timestamp(System.currentTimeMillis()));
    newPatchSet.setUploader(currentUser.getAccountId());
    newPatchSet.setRevision(new RevId(newCommit.getId().name()));

    ChangeUtil.insertAncestors(db, newPatchSet.getId(), newCommit);
    db.patchSets().insert(Collections.singleton(newPatchSet));
    change.setCurrentPatchSet(
        patchSetInfoFactory.get(newCommit, newPatchSet.getId()));
    db.changes().update(Collections.singletonList(change));

    final List<PatchSetApproval> approvals = Lists.newArrayList();
    for (PatchSetApproval a : getApprovalsForCommit(patchSet.getId())) {
      approvals.add(new PatchSetApproval(newPatchSet.getId(), a));
    }
    db.patchSetApprovals().insert(approvals);

    final RefUpdate ru;
    ru = git.updateRef(newPatchSet.getRefName());
    ru.setExpectedOldObjectId(ObjectId.zeroId());
    ru.setNewObjectId(newCommit);
    ru.disableRefLog();
    final RevWalk rw = new RevWalk(git);
    if (ru.update(rw) != RefUpdate.Result.NEW) {
        throw new IOException(String.format(
            "Failed to create ref %s in %s: %s", newPatchSet.getRefName(), change
                .getDest().getParentKey().get(), ru.getResult()));
    }
    return newPatchSet;
  }

  private boolean isSubmitTypeCherryPick(final String project) throws NoSuchProjectException {
    Project.NameKey projectNameKey = new Project.NameKey(project);
    final ProjectControl projectControl = projectControlFactory.validateFor(projectNameKey);
    return projectControl.getProject().getSubmitType().equals(SubmitType.CHERRY_PICK);
  }
}

