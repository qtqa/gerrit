// Copyright (C) 2025 The Android Open Source Project
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

package com.google.gerrit.acceptance.rest.project;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.extensions.api.projects.DeleteChangesResult.FAILURE;
import static com.google.gerrit.extensions.api.projects.DeleteChangesResult.NOT_UNIQUE;
import static com.google.gerrit.extensions.api.projects.DeleteChangesResult.SUCCESS;
import static com.google.gerrit.testing.TestActionRefUpdateContext.openTestRefUpdateContext;

import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.NoHttpd;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.extensions.api.changes.CherryPickInput;
import com.google.gerrit.extensions.api.projects.DeleteChangesInput;
import com.google.gerrit.extensions.api.projects.DeleteChangesResult;
import com.google.gerrit.extensions.api.projects.ProjectApi;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.BatchUpdateOp;
import com.google.gerrit.server.update.ChangeContext;
import com.google.gerrit.server.update.context.RefUpdateContext;
import com.google.gerrit.server.util.time.TimeUtil;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.junit.Test;

@NoHttpd
public class DeleteChangesIT extends AbstractDaemonTest {

  private ProjectApi project() throws Exception {
    return gApi.projects().name(project.get());
  }

  @Test
  public void deleteChangesFailure() throws Exception {
    DeleteChangesInput deleteInput = new DeleteChangesInput();
    deleteInput.changes = List.of("Non-existing-change-1", "Non-existing-change-2");

    Map<DeleteChangesResult, Collection<String>> response = project().deleteChanges(deleteInput);
    assertThat(response).containsKey(FAILURE);
    assertThat(response.get(FAILURE))
        .containsExactly("Non-existing-change-1", "Non-existing-change-2");
  }

  @Test
  public void deleteChangesSuccess() throws Exception {
    PushOneCommit.Result c1 = createChange();
    PushOneCommit.Result c2 = createChange();
    assertThat(c1.getChange().change().getProject().get()).isNotNull();
    assertThat(c2.getChange().change().getProject().get()).isNotNull();
    DeleteChangesInput deleteInput = new DeleteChangesInput();
    deleteInput.changes = List.of(c1.getChangeId(), c2.getChangeId());
    Map<DeleteChangesResult, Collection<String>> response = project().deleteChanges(deleteInput);
    assertThat(response).containsKey(SUCCESS);
    assertThat(response.get(SUCCESS)).containsExactly(c1.getChangeId(), c2.getChangeId());
    assertThat(query(c1.getChangeId())).isEmpty();
    assertThat(query(c2.getChangeId())).isEmpty();
  }

  @Test
  public void deleteChangesSuccessFailureNotUnique() throws Exception {
    TestRepository<InMemoryRepository> repo = cloneProject(project);
    PushOneCommit.Result c1 = createChange(repo, "master", "Add a file", "foo", "content", null);
    PushOneCommit.Result c2 = createChange();

    // cherry pick a change to make a duplicate change for NOT_UNIQUE case.
    String newBranch = "Test-branch";
    createBranch(BranchNameKey.create(project, newBranch));
    CherryPickInput cpi = new CherryPickInput();
    cpi.destination = newBranch;
    gApi.changes().id(c1.getChangeId()).current().cherryPick(cpi);

    DeleteChangesInput deleteInput = new DeleteChangesInput();
    deleteInput.changes = List.of(c1.getChangeId(), c2.getChangeId(), "Non-existing-change-1");
    Map<DeleteChangesResult, Collection<String>> response = project().deleteChanges(deleteInput);
    assertThat(response).containsKey(FAILURE);
    assertThat(response.get(FAILURE)).containsExactly("Non-existing-change-1");
    assertThat(response).containsKey(SUCCESS);
    assertThat(response.get(SUCCESS)).containsExactly(c2.getChangeId());
    assertThat(response).containsKey(NOT_UNIQUE);
    assertThat(response.get(NOT_UNIQUE)).containsExactly(c1.getChangeId());
  }

  @Test
  public void deleteChangesStagedOrIntegratingFails() throws Exception {
    PushOneCommit.Result staged = createChange();
    PushOneCommit.Result integrating = createChange();
    setChangeStatus(staged.getChange().change().getId(), Change.Status.STAGED);
    setChangeStatus(integrating.getChange().change().getId(), Change.Status.INTEGRATING);

    DeleteChangesInput deleteInput = new DeleteChangesInput();
    deleteInput.changes = List.of(staged.getChangeId(), integrating.getChangeId());
    Map<DeleteChangesResult, Collection<String>> response = project().deleteChanges(deleteInput);
    assertThat(response).containsKey(FAILURE);
    assertThat(response.get(FAILURE))
        .containsExactly(staged.getChangeId(), integrating.getChangeId());
    assertThat(query(staged.getChangeId())).isNotEmpty();
    assertThat(query(integrating.getChangeId())).isNotEmpty();
  }

  private void setChangeStatus(Change.Id id, Change.Status newStatus) throws Exception {
    try (RefUpdateContext ctx = openTestRefUpdateContext()) {
      try (BatchUpdate batchUpdate =
          batchUpdateFactory.create(project, localCtx.getContext().getUser(), TimeUtil.now())) {
        batchUpdate.addOp(id, new ChangeStatusUpdateOp(newStatus));
        batchUpdate.execute();
      }
    }
  }

  private static class ChangeStatusUpdateOp implements BatchUpdateOp {
    private final Change.Status newStatus;

    ChangeStatusUpdateOp(Change.Status newStatus) {
      this.newStatus = newStatus;
    }

    @Override
    public boolean updateChange(ChangeContext ctx) throws Exception {
      Change change = ctx.getChange();
      PatchSet.Id currentPatchSetId = change.currentPatchSetId();
      ctx.getUpdate(currentPatchSetId).setStatus(newStatus);
      return true;
    }
  }
}
