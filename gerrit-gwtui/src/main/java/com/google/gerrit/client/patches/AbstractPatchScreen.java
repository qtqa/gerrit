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
package com.google.gerrit.client.patches;

import com.google.gerrit.client.changes.CommitMessageBlock;
import com.google.gerrit.client.changes.PatchTable;
import com.google.gerrit.client.ui.ListenableAccountDiffPreference;
import com.google.gerrit.client.ui.Screen;
import com.google.gerrit.common.data.PatchScript;
import com.google.gerrit.common.data.PatchSetDetail;
import com.google.gerrit.prettify.client.ClientSideFormatter;
import com.google.gerrit.prettify.common.PrettyFactory;
import com.google.gerrit.reviewdb.AccountDiffPreference;
import com.google.gerrit.reviewdb.Patch;
import com.google.gerrit.reviewdb.PatchSet;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.user.client.ui.DisclosurePanel;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.VerticalPanel;

public abstract class AbstractPatchScreen extends Screen implements
    CommentEditorContainer {
  static final PrettyFactory PRETTY = ClientSideFormatter.FACTORY;

  /**
   * How this patch should be displayed in the patch screen.
   */
  public static enum Type {
    UNIFIED, SIDE_BY_SIDE
  }

  // Which patch set id's are being diff'ed
  protected static PatchSet.Id diffSideA = null;
  protected static PatchSet.Id diffSideB = null;
  protected PatchSet.Id idSideA;
  protected PatchSet.Id idSideB;
  protected HistoryTable historyTable;
  protected FlowPanel topPanel;
  protected CommitMessageBlock commitMessageBlock;
  protected PatchSetDetail patchSetDetail;
  protected PatchTable fileList;
  protected Patch.Key patchKey;
  protected PatchScriptSettingsPanel settingsPanel;
  protected ListenableAccountDiffPreference prefs;
  protected PatchScript lastScript;

  public AbstractPatchScreen(final Patch.Key patchKey, final PatchSet.Id id,
      final PatchSetDetail patchSetDetail, final PatchTable patchTable) {
    this.patchKey = patchKey;
    this.patchSetDetail = patchSetDetail;
    fileList = patchTable;

    if (patchTable != null) {
      diffSideA = patchTable.getPatchSetIdToCompareWith();
    } else {
      diffSideA = null;
    }

    idSideA = diffSideA; // null here means we're diff'ing from the Base
    idSideB = diffSideB != null ? diffSideB : id;

    prefs = fileList != null ? fileList.getPreferences() :
      new ListenableAccountDiffPreference();
    prefs.addValueChangeHandler(
        new ValueChangeHandler<AccountDiffPreference>() {
          @Override
          public void onValueChange(ValueChangeEvent<AccountDiffPreference> event) {
            update(event.getValue());
          }
    });

    settingsPanel = new PatchScriptSettingsPanel(prefs);
  }

  @Override
  protected void onInitUI() {
    super.onInitUI();

    historyTable = new HistoryTable(this);
    commitMessageBlock = new CommitMessageBlock();

    // Pre-topview style top layout.
    VerticalPanel vp = new VerticalPanel();
    DisclosurePanel historyPanel =
      new DisclosurePanel(PatchUtil.C.patchHistoryTitle());
    historyPanel.add(historyTable);
    vp.add(historyPanel);
    vp.add(settingsPanel);

    HorizontalPanel hp = new HorizontalPanel();
    hp.setWidth("100%");
    hp.add(vp);
    hp.add(commitMessageBlock);

    add(hp);
  }

  PatchSet.Id getSideA() {
    return diffSideA;
  }

  void setSideA(PatchSet.Id id) {
    diffSideA = id;
    idSideA = id;
    if (fileList != null) {
      fileList.setPatchSetIdToCompareWith(id);
    }
  }

  PatchSet.Id getSideB() {
    return diffSideB;
  }

  void setSideB(PatchSet.Id id) {
    diffSideB = id;
    idSideB = id;
  }

  public Patch.Key getPatchKey() {
    return patchKey;
  }

  abstract void refresh(boolean first);
  abstract public AbstractPatchScreen.Type getPatchScreenType();
  abstract protected void update(AccountDiffPreference dp);

  public PatchSetDetail getPatchSetDetail() {
    return patchSetDetail;
  }
  public PatchTable getFileList() {
    return fileList;
  }
}
