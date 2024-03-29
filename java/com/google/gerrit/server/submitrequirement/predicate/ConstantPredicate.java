// Copyright (C) 2021 The Android Open Source Project
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

package com.google.gerrit.server.submitrequirement.predicate;

import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.SubmitRequirementPredicate;
import com.google.inject.Singleton;

/**
 * A submit requirement predicate (can only be used in submit requirement expressions) that always
 * evaluates to {@code true} if the value is equal to "true" or false otherwise.
 */
@Singleton
public class ConstantPredicate extends SubmitRequirementPredicate {
  public ConstantPredicate(String value) {
    super("is", value);
  }

  @Override
  public boolean match(ChangeData object) {
    return "true".equals(value);
  }

  @Override
  public int getCost() {
    return 1;
  }
}
