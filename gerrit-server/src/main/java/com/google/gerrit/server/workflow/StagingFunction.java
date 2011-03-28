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
package com.google.gerrit.server.workflow;

import com.google.gerrit.common.data.ApprovalType;
import com.google.gerrit.common.data.Permission;
import com.google.gerrit.reviewdb.Change;
import com.google.gerrit.server.CurrentUser;

/**
 * Staging category function. Checks that the user has staging access and
 * that the change has status NEW.
 *
 */
public class StagingFunction extends CategoryFunction {
  public static String NAME = "Staging";

  @Override
  public void run(final ApprovalType at, final FunctionState state) {
    state.valid(at, valid(state));
  }

  @Override
  public boolean isValid(final CurrentUser user, final ApprovalType at,
      final FunctionState state) {
    return !state.controlFor(user).getRange(Permission.forLabel(NAME)).isEmpty();
  }

  private static boolean valid(final FunctionState state) {
    if (state.getChange().getStatus() != Change.Status.NEW) {
      return false;
    }
    for (final ApprovalType t : state.getApprovalTypes()) {
      if (!state.isValid(t)) {
        return false;
      }
    }
    return true;
  }
}
