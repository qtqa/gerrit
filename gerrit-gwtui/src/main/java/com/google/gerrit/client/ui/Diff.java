// Copyright (C) 2011 The Android Open Source Project
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
package com.google.gerrit.client.ui;

import com.google.gerrit.client.Gerrit;
import com.google.gerrit.client.changes.PatchTable;
import com.google.gerrit.client.patches.AbstractPatchContentTable;
import com.google.gerrit.client.patches.PatchScreen;
import com.google.gerrit.client.patches.PatchUtil;
import com.google.gerrit.client.patches.SideBySideTable;
import com.google.gerrit.client.patches.UnifiedDiffTable;
import com.google.gerrit.client.rpc.GerritCallback;
import com.google.gerrit.common.data.PatchScript;
import com.google.gerrit.reviewdb.AccountDiffPreference;
import com.google.gerrit.reviewdb.Patch;
import com.google.gerrit.reviewdb.PatchSet;
import com.google.gerrit.reviewdb.PatchSet.Id;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Panel;
import com.google.gwt.user.client.ui.VerticalPanel;

import java.util.HashSet;
import java.util.Set;

public abstract class Diff extends Composite implements
    AbstractPatchContentTable.Delegate {
  public interface Delegate {
    void onFocus(Diff diff);
    void onLoad(Diff diff);
  }
  public interface Factory {
    Diff createDiff(Patch.Key patchKey, Id sideA, Id sideB,
        AccountDiffPreference prefs);
    PatchScreen.Type getType();
  }
  public static class SideBySide extends Diff {
    public SideBySide(Patch.Key patchKey, Id sideA,
        Id sideB, AccountDiffPreference prefs) {
      super(patchKey, sideA, sideB, prefs);
    }

    @Override
    protected AbstractPatchContentTable createContentTable() {
      return new SideBySideTable();
    }

    @Override
    protected PatchScreen.Type getPatchScreenType() {
      return PatchScreen.Type.SIDE_BY_SIDE;
    }
  }
  public static class SideBySideFactory implements Factory {
    public Diff createDiff(Patch.Key patchKey, Id sideA, Id sideB,
        AccountDiffPreference prefs) {
      return new SideBySide(patchKey, sideA, sideB, prefs);
    }

    public PatchScreen.Type getType() {
      return PatchScreen.Type.SIDE_BY_SIDE;
    }
  }
  public static class Unified extends Diff {
    public Unified(Patch.Key patchKey, Id sideA,
        Id sideB, AccountDiffPreference prefs) {
      super(patchKey, sideA, sideB, prefs);
      setShowFileName(false);
    }

    @Override
    protected AbstractPatchContentTable createContentTable() {
      return new UnifiedDiffTable();
    }

    @Override
    protected PatchScreen.Type getPatchScreenType() {
      return PatchScreen.Type.UNIFIED;
    }
  }
  public static class UnifiedFactory implements Factory {
    public Diff createDiff(Patch.Key patchKey, Id sideA, Id sideB,
        AccountDiffPreference prefs) {
      return new Unified(patchKey, sideA, sideB, prefs);
    }

    public PatchScreen.Type getType() {
      return PatchScreen.Type.UNIFIED;
    }
  }

  private final Patch.Key patchKey;
  private final PatchSet.Id sideA;
  private final PatchSet.Id sideB;
  private AccountDiffPreference diffPreference;
  private final Panel body;
  private AbstractPatchContentTable contentTable;
  private boolean intralineFailure = false;
  private Set<Delegate> delegates;
  private boolean loaded = false;
  private boolean showFileName = true;

  public Diff(final Patch.Key patchKey,
      final PatchSet.Id sideA, final PatchSet.Id sideB,
      final AccountDiffPreference prefs) {
    this.patchKey = patchKey;
    this.sideA = sideA;
    this.sideB = sideB;
    this.diffPreference = prefs;
    delegates = new HashSet<Diff.Delegate>();

    body = new VerticalPanel();
    body.setStyleName(Gerrit.RESOURCES.css().patchContentTable());

    initWidget(body);
  }

  @Override
  public void onClick() {
    for (Delegate delegate : delegates) {
      delegate.onFocus(this);
    }
  }

  public void addDelegate(final Delegate delegate) {
    delegates.add(delegate);
  }

  public AbstractPatchContentTable getContentTable() {
    return contentTable;
  }

  public boolean hasIntralineFailure() {
    return intralineFailure;
  }

  public boolean isLoaded() {
    return loaded;
  }

  public void load() {
    PatchUtil.DETAIL_SVC.patchScript(patchKey, sideA, sideB,
        diffPreference, new GerritCallback<PatchScript>() {
          @Override
          public void onSuccess(final PatchScript result) {
            updateContent(result);
            intralineFailure = result.hasIntralineFailure();
          }
        });
  }

  public void removeDelegate(final Delegate delegate) {
    delegates.remove(delegate);
  }

  private void updateContent(final PatchScript patchScript) {
    contentTable.display(patchKey, sideA, sideB, patchScript);
    contentTable.display(patchScript.getCommentDetail(), true);
  }

  protected abstract AbstractPatchContentTable createContentTable();

  protected abstract PatchScreen.Type getPatchScreenType();

  @Override
  protected void onLoad() {
    super.onLoad();

    if (showFileName) {
      final String path = PatchTable.getDisplayFileName(patchKey);
      final Label fileNameLabel = new Label(path);
      fileNameLabel.setStyleName(Gerrit.RESOURCES.css().diffFileName());
      body.add(fileNameLabel);
    }
    contentTable = createContentTable();
    contentTable.addDelegate(this);
    body.add(contentTable);

    loaded = true;
    for (Delegate delegate : delegates) {
      delegate.onLoad(this);
    }
  }

  protected void setShowFileName(final boolean showFileName) {
    this.showFileName = showFileName;
  }
}
