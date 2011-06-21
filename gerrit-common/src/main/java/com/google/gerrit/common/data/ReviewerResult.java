// Copyright (C) 2009 The Android Open Source Project
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

package com.google.gerrit.common.data;


import java.util.ArrayList;
import java.util.List;

/**
 * Result from adding or removing a reviewer from a change.
 */
public class ReviewerResult extends ReviewerResultError {
  protected List<Error> errors;
  protected ChangeDetail change;

  public ReviewerResult() {
    errors = new ArrayList<Error>();
  }

  public void addError(final Error e) {
    errors.add(e);
  }

  public List<Error> getErrors() {
    return errors;
  }

  public ChangeDetail getChange() {
    return change;
  }

  public void setChange(final ChangeDetail d) {
    change = d;
  }
}
