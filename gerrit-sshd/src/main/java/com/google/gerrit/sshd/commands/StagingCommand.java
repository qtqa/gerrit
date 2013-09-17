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

import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RevId;
import com.google.gerrit.reviewdb.server.PatchSetAccess;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gwtorm.server.OrmException;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.FooterKey;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Constants and utility methods for staging commands.
 *
 */
public class StagingCommand {
  public static class BranchNotFoundException extends Exception {
    private static final long serialVersionUID = 1L;
    public BranchNotFoundException(final String message) {
      super(message);
    }
  }

  public static class UpdateRefException extends Exception {
    private static final long serialVersionUID = 1L;
    public UpdateRefException(final String message) {
      super(message);
    }
  }

  /** Prefix for head refs. */
  public static final String R_HEADS = "refs/heads/";
  /** Prefix for build refs. */
  public static final String R_BUILDS = "refs/builds/";
  /** Prefix for staging refs. */
  public static final String R_STAGING = "refs/staging/";

  /** Private constructor. This class should not be instantiated. */
  private StagingCommand() {
  }

  /**
   * Creates a branch key including ref prefix.
   * @param project Project for the branch key.
   * @param prefix Expected prefix.
   * @param branch Branch name with or without prefix.
   * @return Branch name key with prefix.
   */
  public static Branch.NameKey getNameKey(final String project,
      final String prefix, final String branch) {
    final Project.NameKey projectKey = getProjectKey(project);
    if (branch.startsWith(prefix)) {
      return new Branch.NameKey(projectKey, branch);
    } else {
      return new Branch.NameKey(projectKey, prefix + branch);
    }
  }

  /**
   * Creates a branch key without any prefix.
   * @param project Project for the branch key.
   * @param prefix Prefix to remove.
   * @param branch Branch name with or without prefix.
   * @return Branch name key without prefix.
   */
  public static Branch.NameKey getShortNameKey(final String project,
      final String prefix, final String branch) {
    final Project.NameKey projectKey = getProjectKey(project);
    if (branch.startsWith(prefix)) {
      return new Branch.NameKey(projectKey, branch.substring(prefix.length()));
    } else {
      return new Branch.NameKey(projectKey, branch);
    }
  }

  public static Branch.NameKey getDestinationKey(final Branch.NameKey key) {
    final String branch = key.get();
    if (branch.startsWith(R_STAGING)) {
      return new Branch.NameKey(key.getParentKey(),
          R_HEADS + branch.substring(R_STAGING.length()));
    } else if (branch.startsWith(R_BUILDS)) {
      return new Branch.NameKey(key.getParentKey(),
          R_HEADS + branch.substring(R_BUILDS.length()));
    } else {
      return key;
    }
  }

  public static Project.NameKey getProjectKey(final String project) {
    String projectName = project;
    if (project.endsWith(Constants.DOT_GIT_EXT)) {
      projectName = project.substring(0, //
          project.length() - Constants.DOT_GIT_EXT.length());
    }
    return new Project.NameKey(projectName);
  }

  /**
   * Lists open changes in a branch.
   * @param git jGit Repository. Must be open.
   * @param db ReviewDb of a Gerrit site.
   * @param branch Branch to search for open changes.
   * @param destination Destination branch for changes.
   * @return List of open changes.
   * @throws IOException Thrown by Repository or RevWalk if repository is not
   *         accessible.
   * @throws OrmException Thrown if ReviewDb is not accessible.
   */
  public static List<Map.Entry<PatchSet,RevCommit>> openChanges(Repository git, ReviewDb db,
      final Branch.NameKey branch, final Branch.NameKey destination)
          throws IOException, OrmException, BranchNotFoundException {
    List<Map.Entry<PatchSet,RevCommit>> open = new ArrayList<Map.Entry<PatchSet, RevCommit>>();
    PatchSetAccess patchSetAccess = db.patchSets();

    RevWalk revWalk = new RevWalk(git);

    try {
      Ref ref = git.getRef(branch.get());
      if (ref == null) {
        throw new BranchNotFoundException("No such branch: " + branch);
      }
      RevCommit firstCommit = revWalk.parseCommit(ref.getObjectId());
      revWalk.markStart(firstCommit);
      // Read destination HEAD to make it the walker end point
      Ref refDest = git.getRef(destination.get());
      if (refDest != null) {
        revWalk.markUninteresting(revWalk.parseCommit(refDest.getObjectId()));
      }
      Iterator<RevCommit> i = revWalk.iterator();

      final String changeIdFooter = "Change-Id";
      FooterKey changeIdKey = new FooterKey(changeIdFooter);
      while (i.hasNext()) {
        RevCommit commit = i.next();
        RevId revId = new RevId(ObjectId.toString(commit));
        List<String> changeIds = commit.getFooterLines(changeIdKey);

        if (changeIds.isEmpty()) {
          // No Change-Id footer available. Search by patch set revision.
          List<PatchSet> patchSets = patchSetAccess.byRevision(revId).toList();
          for (PatchSet patchSet : patchSets) {
            Change.Id changeId = patchSet.getId().getParentKey();
            final Change change = db.changes().get(changeId);
            if (change.getStatus().isOpen()) {
              open.add(new AbstractMap.SimpleEntry<PatchSet,RevCommit>(patchSet, commit));
              break;
            }
          }
        } else {
          final Branch.NameKey destinationKey =
            getNameKey(destination.getParentKey().get(), R_HEADS,
                destination.get());

          // Change-Id footer found in commit message. Search by Change-Id
          // value.  There is only 1 Change-Id footer allowed.
          List<Change> changes =
            db.changes().byKey(Change.Key.parse(changeIds.get(0))).toList();
          for (Change change : changes) {
            if (!change.getDest().equals(destinationKey)) {
              continue;
            }
            if (change.getStatus().isOpen()) {
              open.add(new AbstractMap.SimpleEntry<PatchSet,RevCommit>(patchSetAccess
                  .get(change.currentPatchSetId()), commit));
            }
          }
        }
      }
    } finally {
      revWalk.dispose();
    }
    return open;
  }
}

