// Copyright (C) 2008 The Android Open Source Project
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

package com.google.gerrit.common.data;

import com.google.gerrit.reviewdb.Account;
import com.google.gerrit.reviewdb.ApprovalCategory;
import com.google.gerrit.reviewdb.SetApproval;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class ApprovalDetail<T extends SetApproval<?>> {
  static final Timestamp EG_0 = new Timestamp(0);
  static final Timestamp EG_D = new Timestamp(Long.MAX_VALUE);

  protected Account.Id account;
  protected List<T> approvals;
  protected boolean canRemove;


  protected ApprovalDetail() {
  }

  protected ApprovalDetail(final Account.Id id) {
    account = id;
    approvals = new ArrayList<T>();
  }

  public Account.Id getAccount() {
    return account;
  }

  public boolean canRemove() {
    return canRemove;
  }

  public void setCanRemove(boolean removeable) {
    canRemove = removeable;
  }

  public List<T> getPatchSetApprovals() {
    return approvals;
  }

  public List<T> getSetApprovals() {
    return approvals;
  }

  public T getPatchSetApproval(ApprovalCategory.Id category) {
    for (T psa : approvals) {
      if (psa.getCategoryId().equals(category)) {
        return psa;
      }
    }
    return null;
  }


  public abstract Map<ApprovalCategory.Id, T> getApprovalMap();

  public abstract void sortFirst();

  public abstract void add(final T ca);
}
