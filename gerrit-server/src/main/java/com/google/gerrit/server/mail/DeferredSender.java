// Copyright (C) 2009 The Android Open Source Project,
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

package com.google.gerrit.server.mail;

import com.google.gerrit.common.errors.EmailException;
import com.google.gerrit.reviewdb.client.AccountProjectWatch.NotifyType;
import com.google.gerrit.reviewdb.client.Change;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

/** Send notice about a change being deferred by its owner. */
public class DeferredSender extends ReplyToChangeSender {
  public static interface Factory extends
      ReplyToChangeSender.Factory<DeferredSender> {
    DeferredSender create(Change change);
  }

  @Inject
  public DeferredSender(EmailArguments ea, @Assisted Change c) {
    super(ea, c, "defer");
  }

  @Override
  protected void init() throws EmailException {
    super.init();

    ccAllApprovals();
    bccStarredBy();
    includeWatchers(NotifyType.DEFERRED_CHANGES);
    includeWatchers(NotifyType.ALL_COMMENTS);
  }

  @Override
  protected void formatChange() throws EmailException {
    appendText(velocifyFile("Deferred.vm"));
  }
}
