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
package com.google.gerrit.sshd.commands;

import static com.google.gerrit.sshd.commands.StagingCommand.R_BUILDS;
import static com.google.gerrit.sshd.commands.StagingCommand.R_HEADS;

import com.google.gerrit.common.ChangeHookRunner;
import com.google.gerrit.common.data.ApprovalTypes;
import com.google.gerrit.reviewdb.ApprovalCategoryValue;
import com.google.gerrit.reviewdb.Branch;
import com.google.gerrit.reviewdb.Change;
import com.google.gerrit.reviewdb.ChangeSet;
import com.google.gerrit.reviewdb.PatchSet;
import com.google.gerrit.reviewdb.Project;
import com.google.gerrit.reviewdb.ReviewDb;
import com.google.gerrit.reviewdb.Topic;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.TopicUtil;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.MergeOp;
import com.google.gerrit.server.git.MergeQueue;
import com.google.gerrit.server.mail.AbandonedSender;
import com.google.gerrit.server.mail.BuildApprovedSender;
import com.google.gerrit.server.mail.BuildRejectedSender;
import com.google.gerrit.server.mail.EmailException;
import com.google.gerrit.server.patch.PublishComments;
import com.google.gerrit.server.project.CanSubmitResult;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.InvalidChangeOperationException;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gerrit.server.project.NoSuchTopicException;
import com.google.gerrit.server.workflow.FunctionState;
import com.google.gerrit.server.project.TopicControl;
import com.google.gerrit.server.workflow.TopicFunctionState;
import com.google.gerrit.sshd.BaseCommand;
import com.google.gerrit.sshd.commands.StagingCommand.BranchNotFoundException;
import com.google.gwtorm.client.AtomicUpdate;
import com.google.gwtorm.client.OrmException;
import com.google.inject.Inject;

import org.apache.sshd.server.Environment;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
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
import java.util.HashSet;
import java.util.List;

/**
 * A command to report pass or fail status for builds. When a build receives
 * pass status, the branch is updated with build ref and all open changes in
 * the build are marked as merged. When a build receives fail status, all
 * change in the build are marked as new and they need to be staged again.
 * <p>
 * For example, how to approve a build
 * $ ssh -p 29418 localhost gerrit staging-approve -p project -b master -i 123 -r=pass
 */
public class StagingApprove extends BaseCommand {
  private static final Logger log =
    LoggerFactory.getLogger(StagingApprove.class);

  private class MergeException extends Exception {
    private static final long serialVersionUID = 1L;

    public MergeException(String message) {
      super(message);
    }

    public MergeException(String message, Throwable throwable) {
      super(message, throwable);
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
  private PublishComments.Factory publishCommentsFactory;

  @Inject
  private ChangeControl.Factory changeControlFactory;

  @Inject
  private TopicControl.Factory topicControlFactory;

  @Inject
  private ApprovalTypes approvalTypes;

  @Inject
  private FunctionState.Factory functionStateFactory;

  @Inject
  private IdentifiedUser currentUser;

  @Inject
  private ChangeHookRunner hooks;

  @Inject
  private MergeQueue merger;

  @Inject
  private MergeOp.Factory opFactory;

  @Inject
  private TopicFunctionState.Factory topicFunctionStateFactory;

  @Inject
  private BuildApprovedSender.Factory buildApprovedFactory;

  @Inject
  private BuildRejectedSender.Factory buildRejectedFactory;

  @Inject
  private AbandonedSender.Factory abandonedSenderFactory;

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

  private List<PatchSet> toApprove;

  private Branch.NameKey destination;

  @Override
  public void start(final Environment env) {
    startThread(new CommandRunnable() {
      @Override
      public void run() throws Exception {
        parseCommandLine();
        StagingApprove.this.approve();
      }
    });
  }

  private void approve() throws UnloggedFailure {
    // Check result parameter value.
    final boolean passed;
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

      // Use current message or read it from stdin.
      prepareMessage();

      // If result is passed, check that the user has required access rights
      // to submit changes.
      if (passed) {
        validateSubmitRights();
        try {
          updateDestinationBranch(buildBranchNameKey);
        } catch (IOException e) {
          resetChangeStatus();
          throw e;
        } catch (MergeException e) {
          resetChangeStatus();
          throw e;
        }

        // Rebuild staging branch.
        ChangeUtil.rebuildStaging(destination, currentUser, db, git, opFactory,
            merger, hooks);
      }

      // Iterate through each open change and publish message.
      for (PatchSet patchSet : toApprove) {
        final PatchSet.Id patchSetId = patchSet.getId();

        // If change not in state INTEGRATING it will be abandoned
        final Change change = db.changes().get(patchSetId.getParentKey());
        if (change.getStatus() != Change.Status.INTEGRATING) {
          try {
            ChangeUtil.abandon(patchSetId, currentUser, getAbandonMessage(change), db,
                abandonedSenderFactory, hooks, true);
          } catch (EmailException e) {
            log.error("Cannot send email about abandoning change " + change.getId(), e);
          }
          continue;
        }

        // Publish message but only send mail if not passed
        publishMessage(patchSetId, !passed);

        if (passed) {
          // Set change status to merged.
          pass(patchSetId);
        } else {
          // Reset change status.
          reject(patchSetId);
        }
      }

    } catch (IOException e) {
      throw new UnloggedFailure(1, "fatal: Failed to update destination branch", e);
    } catch (OrmException e) {
      throw new UnloggedFailure(1, "fatal: Failed to access database", e);
    } catch (NoSuchChangeException e) {
      throw new UnloggedFailure(1, "fatal: Failed to validate access rights", e);
    } catch (BranchNotFoundException e) {
      throw new UnloggedFailure(1, "fatal: " + e.getMessage(), e);
    } catch (NoSuchRefException e) {
      throw new UnloggedFailure(1, "fatal: Failed to access change destination branch", e);
    } catch (MergeException e) {
      throw new UnloggedFailure(1, "fatal: " + e.getMessage(), e);
    } catch (InvalidChangeOperationException e) {
      throw new UnloggedFailure(1, "fatal: Failed to publish comments", e);
    } catch (IllegalStateException e) {
      throw new UnloggedFailure(1, "fatal: Changes are missing required approvals: " + e.getMessage(), e);
    } catch (NoSuchTopicException e) {
      throw new UnloggedFailure(1, "fatal: Invalid topic: " + e.getMessage(), e);
    } finally {
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
      if (!c.getDest().equals(destination) && c.getStatus() == Change.Status.MERGED) {
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

  private void openRepository(final String project) throws RepositoryNotFoundException {
    Project.NameKey projectNameKey = new Project.NameKey(project);
    git = gitManager.openRepository(projectNameKey);
  }

  private void validateSubmitRights() throws UnloggedFailure,
      NoSuchChangeException, OrmException, NoSuchTopicException {
    for (PatchSet patchSet : toApprove) {
      final Change.Id changeId = patchSet.getId().getParentKey();
      final Change change = db.changes().get(changeId);

      // Changes not in state INTEGRATING will be rejected later
      if (change.getStatus() != Change.Status.INTEGRATING) {
        continue;
      }

      final Topic.Id topicId = change.getTopicId();
      // Check only topic status for changes in topic.
      if (topicId != null) {
        // Change is part of a topic. Validate the topic with
        // TopicChangeControl.
        final TopicControl topicControl =
          topicControlFactory.validateFor(topicId);
        Topic topic = db.topics().get(topicId);
        // Only validate most current change set of topic
        ChangeSet changeSet = db.changeSets().get(topic.currentChangeSetId());
        CanSubmitResult result = topicControl.canSubmit(db, changeSet.getId(), approvalTypes, topicFunctionStateFactory);
        if (result != CanSubmitResult.OK) {
          throw new UnloggedFailure(1, result.getMessage());
        }
//        List<ChangeSet> changeSets = db.changeSets().byTopic(topicId).toList();
//        for (ChangeSet changeSet : changeSets) {
//          CanSubmitResult result =
//            topicControl.canSubmit(db, changeSet.getId(), approvalTypes,
//                topicFunctionStateFactory);
//          if (result != CanSubmitResult.OK) {
//            throw new UnloggedFailure(1, result.getMessage());
//          }
//        }
      } else {
        // Change is not part of a topic. Validate it with ChangeControl.
        final ChangeControl changeControl =
          changeControlFactory.validateFor(changeId);
        CanSubmitResult result =
          changeControl.canSubmit(patchSet.getId(), db, approvalTypes,
              functionStateFactory);
        if (result != CanSubmitResult.OK) {
          throw new UnloggedFailure(1, result.getMessage());
        }
      }
    }
  }

  private void publishMessage(final PatchSet.Id patchSetId, final boolean sendMail)
      throws NoSuchChangeException, OrmException, NoSuchRefException,
      IOException, InvalidChangeOperationException {
    if (message != null && message.length() > 0) {
      publishCommentsFactory.create(patchSetId, message,
          new HashSet<ApprovalCategoryValue.Id>(), sendMail).call();
    }
  }

  private void updateDestinationBranch(final Branch.NameKey buildBranchKey)
    throws IOException, MergeException {
    RevWalk rw = null;
    try {
      // Setup RevWalk.
      rw = new RevWalk(git);
      rw.sort(RevSort.TOPO);
      rw.sort(RevSort.COMMIT_TIME_DESC, true);

      // Prepare branch update. Set destination branch tip as old object id.
      RefUpdate branchUpdate = git.updateRef(destination.get());
      // Access tip of build branch.
      Ref buildRef = git.getRef(buildBranchKey.get());

      // Access commits from destination and build branches.
      RevCommit branchTip = rw.parseCommit(branchUpdate.getOldObjectId());
      RevCommit buildTip = rw.parseCommit(buildRef.getObjectId());

      // Setup branch update.
      branchUpdate.setForceUpdate(false);

      // We are updating old destination branch tip to build branch tip.
      branchUpdate.setNewObjectId(buildTip);

      // Make sure that the build tip is reachable from the branch tip.
      if (!rw.isMergedInto(branchTip, buildTip)) {
        throw new MergeException(destination.get() + " is not reachable from "
            + buildBranchKey.get());
      }

      // Update destination branch.
      switch (branchUpdate.update(rw)) {
        // Only fast-forward result is reported as success.
        case FAST_FORWARD:
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
            log.error("Failed to send change merged e-mails", e);
          }
          throw new MergeException("Could not fast-forward build to destination branch");
      }
    } finally {
      if (rw != null) {
        rw.dispose();
      }
    }
  }

  private void pass(final PatchSet.Id patchSetId) throws OrmException {
    // Update change status from INTEGRATING to MERGED.
    ChangeUtil.setIntegratingToMerged(patchSetId, currentUser, db);
    Change change = db.changes().get(patchSetId.getParentKey());
    Topic.Id topicId = change.getTopicId();
    if (topicId != null) {
      Topic topic = db.topics().get(topicId);
      if (topic.getStatus() != Topic.Status.MERGED) {
        TopicUtil.setIntegratingToMerged(topicId, db);
      }
    }
  }

  private void reject(final PatchSet.Id patchSetId) throws OrmException,
      IOException {
    // Remove staging approval and update status from INTEGRATING to NEW.
    ChangeUtil.rejectStagedChange(patchSetId, currentUser, db);
    Change change = db.changes().get(patchSetId.getParentKey());
    Topic.Id topicId = change.getTopicId();
    if (topicId != null) {
      Topic topic = db.topics().get(topicId);
      if (topic.getStatus() != Topic.Status.NEW) {
        TopicUtil.setIntegratingToNew(topicId, db);
      }
    }
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

  private void resetChangeStatus() throws MergeException {
    // Return changes to staging branch.
    for (PatchSet patchSet : toApprove) {
      try {
        // Reset status of changes.
        final PatchSet.Id patchSetId = patchSet.getId();
        final Change.Id changeId = patchSetId.getParentKey();
        AtomicUpdate<Change> atomicUpdate =
          new AtomicUpdate<Change>() {
          @Override
          public Change update(Change change) {
            if (change.getStatus() == Change.Status.INTEGRATING) {
              change.setStatus(Change.Status.STAGING);
              ChangeUtil.updated(change);
            }
            return change;
          }
        };
        db.changes().atomicUpdate(changeId, atomicUpdate);
      } catch (Exception e) {
        // Failed to reset change status.
      }
    }

    try {
      ChangeUtil.rebuildStaging(destination, currentUser, db, git, opFactory,
          merger, hooks);
    } catch (Exception e) {
      throw new MergeException("fatal: Failed to rebuild staging branch after failed fast-forward", e);
    }
  }

  private void sendBuildApprovedMails() throws OrmException, EmailException {
    for (PatchSet patchSet : toApprove) {
      final PatchSet.Id patchSetId = patchSet.getId();
      final Change.Id changeId = patchSetId.getParentKey();
      final Change change = db.changes().get(changeId);

      final BuildApprovedSender sender = buildApprovedFactory.create(change);
      sender.setBuildApprovedMessage(message);
      sender.setFrom(currentUser.getAccountId());
      sender.setPatchSet(patchSet);
      sender.send();
    }
  }

  private void sendBuildRejectedMails() throws OrmException, EmailException  {
    for (PatchSet patchSet : toApprove) {
      final PatchSet.Id patchSetId = patchSet.getId();
      final Change.Id changeId = patchSetId.getParentKey();
      final Change change = db.changes().get(changeId);

      final BuildRejectedSender sender = buildRejectedFactory.create(change);
      sender.setFrom(currentUser.getAccountId());
      sender.setPatchSet(patchSet);
      sender.send();
    }
  }
}
