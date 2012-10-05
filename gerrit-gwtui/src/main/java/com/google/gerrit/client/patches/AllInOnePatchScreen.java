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

import com.google.gerrit.client.Dispatcher;
import com.google.gerrit.client.ErrorDialog;
import com.google.gerrit.client.Gerrit;
import com.google.gerrit.client.RpcStatus;
import com.google.gerrit.client.changes.ChangeScreen;
import com.google.gerrit.client.changes.PatchTable;
import com.google.gerrit.client.changes.Util;
import com.google.gerrit.client.patches.AbstractPatchContentTable.CommentList;
import com.google.gerrit.client.rpc.GerritCallback;
import com.google.gerrit.client.ui.Approvals;
import com.google.gerrit.client.ui.ContentTableKeyNavigation;
import com.google.gerrit.client.ui.Diff;
import com.google.gerrit.common.PageLinks;
import com.google.gerrit.common.data.PatchScript;
import com.google.gerrit.common.data.PatchSetDetail;
import com.google.gerrit.reviewdb.AccountDiffPreference;
import com.google.gerrit.reviewdb.Change;
import com.google.gerrit.reviewdb.Patch;
import com.google.gerrit.reviewdb.PatchSet;
import com.google.gerrit.reviewdb.PatchSet.Id;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.event.dom.client.KeyPressEvent;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Panel;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwtexpui.globalkey.client.KeyCommand;
import com.google.gwtjsonrpc.client.VoidResult;

import java.util.ArrayList;
import java.util.List;

public class AllInOnePatchScreen extends AbstractPatchScreen implements
    Diff.Delegate, Approvals.Delegate {
  private class KeyNavigation extends ContentTableKeyNavigation {
    private class NextFileCmd extends KeyCommand {
      public NextFileCmd(int mask, char key, String help) {
        super(mask, key, help);
      }

      @Override
      public void onKeyPress(final KeyPressEvent event) {
        onFileNext();
      }
    }
    private class PrevFileCmd extends KeyCommand {
      public PrevFileCmd(int mask, char key, String help) {
        super(mask, key, help);
      }

      @Override
      public void onKeyPress(final KeyPressEvent event) {
        onFilePrev();
      }
    }

    private Diff diff;

    private AbstractPatchContentTable contentTable;

    public KeyNavigation(Widget parent) {
      super(parent);
    }

    public void clear() {
      diff = null;
      contentTable = null;
    }

    public Diff getDiff() {
      return diff;
    }

    @Override
    public void initializeKeys() {
      if (!initialized) {
        keysNavigation.add(new NextFileCmd(0, 'w', PatchUtil.C.nextFileHelp()));
        keysNavigation.add(new PrevFileCmd(0, 'q', PatchUtil.C
            .previousFileHelp()));
        super.initializeKeys();
      }
    }

    public void setDiff(Diff diff) {
      this.diff = diff;
      if (diff != null) {
        contentTable = diff.getContentTable();
        contentTable.ensurePointerVisible();
      } else {
        contentTable = null;
      }
    }

    @Override
    protected void onChunkNext() {
      if (contentTable != null) {
        contentTable.ensurePointerVisible();
        contentTable.moveToNextChunk(contentTable.getCurrentRow());
      }
    }

    @Override
    protected void onChunkPrev() {
      if (contentTable != null) {
        contentTable.ensurePointerVisible();
        contentTable.moveToPrevChunk(contentTable.getCurrentRow());
      }
    }

    @Override
    protected void onCommentNext() {
      if (contentTable != null) {
        contentTable.ensurePointerVisible();
        contentTable.moveToNextComment(contentTable.getCurrentRow());
      }
    }

    @Override
    protected void onCommentPrev() {
      if (contentTable != null) {
        contentTable.ensurePointerVisible();
        contentTable.moveToPrevComment(contentTable.getCurrentRow());
      }
    }

    protected void onFileNext() {
      contentTable.hideCursor();
      diff = getNextDiff();
      contentTable = diff.getContentTable();
      Window.scrollTo(0, diff.getAbsoluteTop());
      contentTable.ensurePointerVisible();
      contentTable.showCursor();
    }

    protected void onFilePrev() {
      contentTable.hideCursor();
      diff = getPrevDiff();
      contentTable = diff.getContentTable();
      Window.scrollTo(0, diff.getAbsoluteTop());
      contentTable.ensurePointerVisible();
      contentTable.showCursor();
    }

    @Override
    protected void onInsertComment() {
      if (contentTable != null) {
        contentTable.ensurePointerVisible();
        for (int row = contentTable.getCurrentRow(); 0 <= row; row--) {
          final Object item = contentTable.getRowItem(row);
          if (item instanceof PatchLine) {
            contentTable.onInsertComment((PatchLine) item);
            return;
          } else if (item instanceof CommentList) {
            continue;
          } else {
            return;
          }
        }
      }
    }

    @Override
    protected void onNext() {
      if (contentTable != null) {
        contentTable.ensurePointerVisible();
        contentTable.onDown();
      }
    }

    @Override
    protected void onOpen() {
      if (contentTable != null) {
        contentTable.ensurePointerVisible();
        contentTable.onOpenCurrent();
      }
    }

    @Override
    protected void onPrev() {
      if (contentTable != null) {
        contentTable.ensurePointerVisible();
        contentTable.onUp();
      }
    }

    @Override
    protected void onPublishComments() {
      final PatchSet.Id id = patchKey.getParentKey();
      Gerrit.display(Dispatcher.toPublish(id));
    }
  }

  private boolean intralineFailure;
  private VerticalPanel files;
  private Panel approvalPanel;
  private Approvals approvals;
  private KeyNavigation keyNavigation;
  private List<Diff> diffs;
  private Diff.Factory diffFactory;
  private Id id;

  public AllInOnePatchScreen(final PatchSet.Id patchSetId,
      final PatchSetDetail detail, final PatchTable patchTable,
      AbstractPatchScreen.Type patchScreenType) {
    super(null, patchSetId, detail, patchTable);
    setPatchId(patchSetId);
    diffs = new ArrayList<Diff>();
    keyNavigation = new KeyNavigation(this);
    keyNavigation.addNavigationKey(new UpToChangeCommand(patchSetId, 0, 'u'));

    if (patchScreenType == AbstractPatchScreen.Type.SIDE_BY_SIDE) {
      diffFactory = new Diff.SideBySideFactory();
    } else {
      diffFactory = new Diff.UnifiedFactory();
    }
  }

  public PatchScreen.Type getPatchScreenType() {
    return diffFactory.getType();
  }

  @Override
  public void notifyDraftDelta(int delta) {
    lastScript = null;
  }

  @Override
  public void onCancel() {
    goChange();
  }

  @Override
  public void onFocus(final Diff diff) {
    if (diff != keyNavigation.getDiff()) {
      keyNavigation.getDiff().getContentTable().hideCursor();
    }
    keyNavigation.setDiff(diff);
  }

  @Override
  public void onLoad(final Diff diff) {
    intralineFailure = diff.hasIntralineFailure();
    if (keyNavigation.getDiff() == null) {
      keyNavigation.setDiff(diff);
    }
    diffs.add(diff);

    // Delay displaying diff if previous diffs are not loaded yet.
    for (Widget widget : files) {
      final Diff df = (Diff) widget;
      if (df.isLoaded()) {
        if (!df.isVisible()) {
          df.setVisible(true);
        }
      } else {
        break;
      }
    }
  }

  @Override
  public void onPublish() {
    goChange();
  }

  @Override
  public void onShowView() {
    super.onShowView();
    if (intralineFailure) {
      intralineFailure = false;
      new ErrorDialog(PatchUtil.C.intralineFailure()).show();
    }
  }

  @Override
  public void onStage() {
    goChange();
  }

  @Override
  public void onSubmit() {
    goChange();
  }

  public void refresh(final boolean isFirst) {
    files.clear();
    diffs.clear();
    keyNavigation.clear();
    loadDiffs();
    // historyTable.enableAll(true);
  }

  @Override
  public void registerKeys() {
    super.registerKeys();
    keyNavigation.setRegisterKeys(false);
    keyNavigation.setRegisterKeys(true);
  }

  @Override
  public void remove(CommentEditorPanel panel) {
    lastScript = null;
  }

  private Id getPatchId() {
    return id;
  }

  private void setPatchId(Id id) {
    this.id = id;
  }

  private boolean canReuse(AccountDiffPreference dp, PatchScript last) {
    if (last.getDiffPrefs().getIgnoreWhitespace() != dp.getIgnoreWhitespace()) {
      // Whitespace ignore setting requires server computation.
      return false;
    }

    final int ctx = dp.getContext();
    if (ctx == AccountDiffPreference.WHOLE_FILE_CONTEXT
        && !last.getA().isWholeFile()) {
      // We don't have the entire file here, so we can't render it.
      return false;
    }

    if (last.getDiffPrefs().getContext() < ctx && !last.getA().isWholeFile()) {
      // We don't have sufficient context.
      return false;
    }

    if (dp.isSyntaxHighlighting() && !last.getA().isWholeFile()) {
      // We need the whole file to syntax highlight accurately.
      return false;
    }

    return true;
  }

  private Diff getNextDiff() {
    if (keyNavigation.getDiff() == null) {
      if (!diffs.isEmpty()) {
        return diffs.get(0);
      } else {
        return null;
      }
    } else {
      int index = diffs.indexOf(keyNavigation.getDiff());
      if (index + 1 < diffs.size()) {
        return diffs.get(index + 1);
      } else {
        return diffs.get(0);
      }
    }
  }

  private Diff getPrevDiff() {
    if (keyNavigation.getDiff() == null) {
      if (!diffs.isEmpty()) {
        return diffs.get(0);
      } else {
        return null;
      }
    } else {
      int index = diffs.indexOf(keyNavigation.getDiff());
      if (index - 1 >= 0) {
        return diffs.get(index - 1);
      } else {
        return diffs.get(diffs.size() - 1);
      }
    }
  }

  private void goChange() {
    final Change.Id ck = idSideB.getParentKey();
    Gerrit.display(PageLinks.toChange(ck), new ChangeScreen(ck));
  }

  private void loadDiffs() {
    final List<Patch> patchList = fileList.getPatchList();
    for (int i = 0; i < patchList.size(); ++i) {
      final Patch patch = patchList.get(i);
      Diff diff =
          diffFactory.createDiff(patch.getKey(), idSideA, idSideB,
              settingsPanel.getValue());
      diff.addDelegate(this);
      diff.setVisible(false);
      files.add(diff);
      diff.load();
    }
  }

  private void loadFileList() {
    if (patchSetDetail == null) {
      Util.DETAIL_SVC.patchSetDetail(getPatchId(),
          new GerritCallback<PatchSetDetail>() {
            @Override
            public void onSuccess(PatchSetDetail result) {
              patchSetDetail = result;
              setSideB(patchSetDetail.getPatchSet().getId());

              if (fileList == null) {
                fileList = new PatchTable(prefs);
                fileList.display(result);
              }

              if (!result.getPatches().isEmpty()) {
                patchKey = result.getPatches().get(0).getKey();
              }
              loadDiffs();
              updateCommitInformation(patchKey);
              updateHistory(patchKey);
            }
          });
    } else {
      loadDiffs();
    }
  }

  private void updateCommitInformation(final Patch.Key patchKey) {
    final Change.Id cid = patchKey.getParentKey().getParentKey();

    setWindowTitle(cid.toString());
    final String subject = patchSetDetail.getInfo().getSubject();
    setPageTitle(cid.toString() + ": " + subject);

    if (idSideB.equals(patchSetDetail.getPatchSet().getId())) {
      commitMessageBlock.setVisible(true);
      commitMessageBlock.display(patchSetDetail.getInfo().getMessage());
    } else {
      commitMessageBlock.setVisible(false);
      Util.DETAIL_SVC.patchSetDetail(idSideB,
          new GerritCallback<PatchSetDetail>() {
            @Override
            public void onSuccess(PatchSetDetail result) {
              commitMessageBlock.setVisible(true);
              commitMessageBlock.display(result.getInfo().getMessage());
            }
          });
    }
  }

  private void updateHistory(final Patch.Key patchKey) {
    // historyTable.display(script.getHistory());
    PatchUtil.DETAIL_SVC.patchScript(patchKey, idSideA, idSideB,
        settingsPanel.getValue(), new GerritCallback<PatchScript>() {
          @Override
          public void onSuccess(final PatchScript result) {
            historyTable.display(result.getHistory());
          }
        });
  }

  @Override
  protected void onInitUI() {
    super.onInitUI();

    Window.scrollTo(0, getAbsoluteTop());

    files = new VerticalPanel();

    approvalPanel = new FlowPanel();
    approvals = new Approvals(getPatchId());
    approvals.addDelegate(this);
    approvalPanel.add(approvals);

    add(files);
    if (Gerrit.isSignedIn()) {
      add(approvalPanel);
    }

    keyNavigation.initializeKeys();
  }

  @Override
  protected void onLoad() {
    super.onLoad();

    keyNavigation.setRegisterKeys(true);
    loadFileList();
    if (Gerrit.isSignedIn()) {
      approvals.load();
    }

    if (!isCurrentView()) {
      display();
    }
  }

  @Override
  protected void onUnload() {
    super.onUnload();
    keyNavigation.setRegisterKeys(false);
  }

  protected void update(AccountDiffPreference dp) {
    if (lastScript != null && canReuse(dp, lastScript)) {
      lastScript.setDiffPrefs(dp);
      RpcStatus.INSTANCE.onRpcStart(null);
      settingsPanel.setEnabled(false);
      Scheduler.get().scheduleDeferred(new ScheduledCommand() {
        @Override
        public void execute() {
          try {
            // onResult(lastScript, false /* not the first time */);
          } finally {
            RpcStatus.INSTANCE.onRpcComplete(null);
          }
        }
      });
    } else {
      refresh(false);
    }
  }

  void setReviewedByCurrentUser(boolean reviewed) {
    if (fileList != null) {
      fileList.updateReviewedStatus(patchKey, reviewed);
    }

    PatchUtil.DETAIL_SVC.setReviewedByCurrentUser(patchKey, reviewed,
        new AsyncCallback<VoidResult>() {
          @Override
          public void onFailure(Throwable arg0) {
            // nop
          }

          @Override
          public void onSuccess(VoidResult result) {
            // nop
          }
        });
  }
}
