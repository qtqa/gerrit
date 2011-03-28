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
package com.google.gerrit.sshd.commands;

import static com.google.gerrit.sshd.commands.StagingCommand.R_BUILDS;

import com.google.gerrit.common.ChangeHookRunner;
import com.google.gerrit.common.data.ApprovalTypes;
import com.google.gerrit.reviewdb.ApprovalCategoryValue;
import com.google.gerrit.reviewdb.Branch;
import com.google.gerrit.reviewdb.Change;
import com.google.gerrit.reviewdb.PatchSet;
import com.google.gerrit.reviewdb.Project;
import com.google.gerrit.reviewdb.ReviewDb;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.MergeOp;
import com.google.gerrit.server.git.MergeQueue;
import com.google.gerrit.server.patch.PublishComments;
import com.google.gerrit.server.project.CanSubmitResult;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.InvalidChangeOperationException;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gerrit.server.project.NoSuchRefException;
import com.google.gerrit.server.workflow.FunctionState;
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

    try {
      openRepository(project);

      // Initialize and populate open changes list.
      toApprove = StagingCommand.openChanges(git, db, buildBranchNameKey.get());

      // Notify user that build did not have any open changes. The build has
      // already been approved.
      if (toApprove.isEmpty()) {
        throw new UnloggedFailure(1, "No open changes in the build branch");
      }

      // Validate change status and destination branch.
      validateChanges();

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

      // Use current message or read it from stdin.
      prepareMessage();

      // Iterate through each open change and publish message.
      for (PatchSet patchSet : toApprove) {
        final PatchSet.Id patchSetId = patchSet.getId();
        publishMessage(patchSetId);

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
    } finally {
      stdout.flush();
      if (git != null) {
        git.close();
      }
    }
  }

  private void validateChanges() throws OrmException, UnloggedFailure {
    for (PatchSet patchSet : toApprove) {
      Change change = db.changes().get(patchSet.getId().getParentKey());

      // All changes must originate from the same destination branch.
      if (destination == null) {
        destination = change.getDest();
      } else if (!destination.get().equals(change.getDest().get())) {
        throw new UnloggedFailure(1,
            "All changes in build must belong to same destination branch."
            + " (" + destination + " != " + change.getDest() + ")");
      }

      // All changes must be in state INTEGRATING.
      if (change.getStatus() != Change.Status.INTEGRATING) {
        throw new UnloggedFailure(1,
            "Change not in INTEGRATING state (" + change.getKey() + ")");
      }
    }
  }

  private void openRepository(final String project) throws RepositoryNotFoundException {
    Project.NameKey projectNameKey = new Project.NameKey(project);
    git = gitManager.openRepository(projectNameKey);
  }

  private void validateSubmitRights() throws UnloggedFailure,
      NoSuchChangeException, OrmException {
    for (PatchSet patchSet : toApprove) {
      final Change.Id changeId = patchSet.getId().getParentKey();
      final ChangeControl changeControl =
        changeControlFactory.validateFor(changeId);

      CanSubmitResult result =
        changeControl.canSubmit(patchSet.getId(), db, approvalTypes, functionStateFactory);

      if (result != CanSubmitResult.OK) {
        throw new UnloggedFailure(1, result.getMessage());
      }
    }
  }

  private void publishMessage(final PatchSet.Id patchSetId)
      throws NoSuchChangeException, OrmException, NoSuchRefException,
      IOException, InvalidChangeOperationException {
    if (message != null && message.length() > 0) {
      publishCommentsFactory.create(patchSetId, message,
          new HashSet<ApprovalCategoryValue.Id>()).call();
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
          break;
        default:
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
  }

  private void reject(final PatchSet.Id patchSetId) throws OrmException,
      IOException {
    // Remove staging approval and update status from INTEGRATING to NEW.
    ChangeUtil.rejectStagedChange(patchSetId, currentUser, db);
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
}
