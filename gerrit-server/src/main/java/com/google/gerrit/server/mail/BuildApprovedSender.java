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

package com.google.gerrit.server.mail;

import com.google.gerrit.common.data.LabelType;
import com.google.gerrit.common.data.LabelTypes;
import com.google.gerrit.common.data.LabelValue;
import com.google.gerrit.common.errors.EmailException;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.AccountProjectWatch.NotifyType;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

/** Send notice about a change successfully merged. */
public class BuildApprovedSender extends ReplyToChangeSender {
  public static interface Factory {
    public BuildApprovedSender create(LabelTypes lt, Change change);
  }

  private final LabelTypes labelTypes;
  private String buildApprovedMessage;

  @Inject
  public BuildApprovedSender(EmailArguments ea, @Assisted LabelTypes lt, @Assisted Change c) throws NoSuchChangeException, OrmException {
    super(ea, c, "build-approved");
    labelTypes = lt;
  }

  public void setBuildApprovedMessage(final String m) {
    buildApprovedMessage = m;
  }

  @Override
  protected void init() throws EmailException {
    super.init();

    ccAllApprovals();
    bccStarredBy();
    includeWatchers(NotifyType.ALL_COMMENTS);
    includeWatchers(NotifyType.SUBMITTED_CHANGES);
  }

  @Override
  protected void formatChange() throws EmailException {
    appendText(velocifyFile("BuildApproved.vm"));
  }

  public String getApprovals() {
    try {
      final Map<Account.Id, Map<String, PatchSetApproval>> pos =
          new HashMap<Account.Id, Map<String, PatchSetApproval>>();

      final Map<Account.Id, Map<String, PatchSetApproval>> neg =
          new HashMap<Account.Id, Map<String, PatchSetApproval>>();

      for (PatchSetApproval ca : args.db.get().patchSetApprovals()
          .byPatchSet(patchSet.getId())) {
        if (ca.isSubmit() || ca.isStaged()) {
          continue;
        }

        if (ca.getValue() > 0) {
          insert(pos, ca);
        } else if (ca.getValue() < 0) {
          insert(neg, ca);
        }
      }

      return format("Approvals", pos) + format("Objections", neg);
    } catch (OrmException err) {
      // Don't list the approvals
    }
    return "";
  }

  public String getBuildApprovedMessage() {
    if (buildApprovedMessage == null) {
      return "";
    }
    StringBuilder txt = new StringBuilder();
    txt.append("Message:\n");
    BufferedReader r = new BufferedReader(new StringReader(buildApprovedMessage));
    String l;
    try {
      l = r.readLine();
      while (l != null) {
        txt.append("  "); // Indent 2 spaces for each line in message
        txt.append(l);
        txt.append('\n');
        l = r.readLine();
      }
    } catch (IOException e) {
      // Ignore
    } finally {
      try {
        r.close();
      } catch (IOException e) {
          // Ignore
      }
    }
    txt.append('\n');
    return txt.toString();
  }

  private String format(final String type,
      final Map<Account.Id, Map<String, PatchSetApproval>> list) {
    StringBuilder txt = new StringBuilder();
    if (list.isEmpty()) {
      return "";
    }
    txt.append(type + ":\n");
    for (final Map.Entry<Account.Id, Map<String, PatchSetApproval>> ent : list.entrySet()) {
      final Map<String, PatchSetApproval> l = ent.getValue();
      txt.append("  ");
      txt.append(getNameFor(ent.getKey())); // Account name
      txt.append(": ");
      boolean first = true;
      for (LabelType at : labelTypes.getLabelTypes()) {
        final PatchSetApproval ca = l.get(at.getName());
        if (ca == null) {
          continue;
        }

        if (first) {
          first = false;
        } else {
          txt.append("; ");
        }

        LabelValue v = at.getValue(ca);
        if (v != null) {
          txt.append(v.getText());
        } else {
          txt.append(at.getName());
          txt.append("=");
          if (ca.getValue() > 0) {
            txt.append("+");
          }
          txt.append("" + ca.getValue());
        }
      }
      txt.append("\n");
    }
    txt.append("\n");
    return txt.toString();
  }

  private void insert(
      final Map<Account.Id, Map<String, PatchSetApproval>> list,
      final PatchSetApproval ca) {
    Map<String, PatchSetApproval> m = list.get(ca.getAccountId());
    LabelType lt = labelTypes.byLabel(ca.getLabelId());
    if (m == null) {
      m = new HashMap<String, PatchSetApproval>();
      list.put(ca.getAccountId(), m);
    }
    m.put(lt.getName(), ca);
  }
}
