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

package com.google.gerrit.client.patches;

import com.google.gerrit.client.ErrorDialog;
import com.google.gerrit.client.Gerrit;
import com.google.gerrit.client.changes.ChangeApi;
import com.google.gerrit.client.changes.ChangeInfo;
import com.google.gerrit.client.changes.ChangeScreen;
import com.google.gerrit.client.changes.PatchTable;
import com.google.gerrit.client.changes.ChangeInfo.ApprovalInfo;
import com.google.gerrit.client.changes.ChangeInfo.LabelInfo;
import com.google.gerrit.client.changes.StageFailureDialog;
import com.google.gerrit.client.changes.SubmitFailureDialog;
import com.google.gerrit.client.changes.SubmitInfo;
import com.google.gerrit.client.changes.Util;
import com.google.gerrit.client.patches.AbstractPatchContentTable.CommentList;
import com.google.gerrit.client.patches.AbstractPatchContentTable.Move;
import com.google.gerrit.client.projects.ConfigInfoCache;
import com.google.gerrit.client.rpc.CallbackGroup;
import com.google.gerrit.client.rpc.GerritCallback;
import com.google.gerrit.client.rpc.Natives;
import com.google.gerrit.client.rpc.RestApi;
import com.google.gerrit.client.rpc.ScreenLoadCallback;
import com.google.gerrit.client.ui.ContentTableKeyNavigation;
import com.google.gerrit.client.ui.Diff;
import com.google.gerrit.client.ui.SmallHeading;
import com.google.gerrit.common.PageLinks;
import com.google.gerrit.common.data.PatchScript;
import com.google.gerrit.common.data.PatchSetDetail;
import com.google.gerrit.common.data.PatchSetPublishDetail;
import com.google.gerrit.reviewdb.client.AccountDiffPreference;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Patch;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSet.Id;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.KeyPressEvent;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.FormPanel;
import com.google.gwt.user.client.ui.Panel;
import com.google.gwt.user.client.ui.RadioButton;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.user.client.ui.FormPanel.SubmitEvent;
import com.google.gwtexpui.globalkey.client.KeyCommand;
import com.google.gwtexpui.globalkey.client.NpTextArea;
import com.google.gwtexpui.safehtml.client.SafeHtml;
import com.google.gwtexpui.safehtml.client.SafeHtmlBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AllInOnePatchScreen extends AbstractPatchScreen implements
    Diff.Delegate, ClickHandler {


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
        keysNavigation.add(new NextFileCmd(0, ']', PatchUtil.C.nextFileHelp()));
        keysNavigation.add(new PrevFileCmd(0, '[', PatchUtil.C
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
        // Returns false if no more chunks found -> try next file
        if ( !contentTable.moveToNextChunk(contentTable.getCurrentRow())) {
          onFileNext(false, Move.CHUNK_FIRST);
        }
      }
    }

    @Override
    protected void onChunkPrev() {
      if (contentTable != null) {
        // Returns false if no more chunks found -> try previous file
        if ( !contentTable.moveToPrevChunk(contentTable.getCurrentRow())) {
          onFilePrev(false, Move.CHUNK_LAST);
        }
      }
    }

    @Override
    protected void onCommentNext() {
      if (contentTable != null) {
        // Returns false if no more comments found -> try next file
        if (!contentTable.moveToNextComment(contentTable.getCurrentRow())) {
          onFileNext(false, Move.COMMENT_FIRST);
        }
      }
    }

    @Override
    protected void onCommentPrev() {
      if (contentTable != null) {
        // Returns false if no more comments found -> try previous file
        if (!contentTable.moveToPrevComment(contentTable.getCurrentRow())) {
          onFilePrev(false, Move.COMMENT_LAST);
        }
      }
    }

    @Override
    protected void onTop() {
      if (!diffs.isEmpty()) {
        contentTable.hideCursor();
        for (int index = 0; index < diffs.size(); index++) {
          Diff diff = diffs.get(index);
          if (diff.isVisible()) {
            this.diff = diff;
            contentTable = diff.getContentTable();
            contentTable.moveToTop();
            break;
          }
        }
        contentTable.showCursor();
      }
    }

    @Override
    protected void onBottom() {
      if (!diffs.isEmpty()) {
        contentTable.hideCursor();
        for (int index = diffs.size()-1; index >= 0; index--) {
          Diff diff = diffs.get(index);
          if (diff.isVisible()) {
            this.diff = diff;
            contentTable = diff.getContentTable();
            contentTable.moveToBottom();
            break;
          }
        }
        contentTable.showCursor();
      }
    }

    protected void onFileNext() {
      onFileNext(true, Move.LINE_FIRST);
    }

    protected void onFileNext(boolean scrollTop, Move moveTo) {
      contentTable.hideCursor();
      final Diff diff = getNextDiff(moveTo);
      if (diff != null) {
        this.diff = diff;
        contentTable = diff.getContentTable();
        if (scrollTop) {
          Window.scrollTo(0, diff.getAbsoluteTop());
        }
        contentTable.moveTo(moveTo);
      }
      contentTable.showCursor();
    }

    protected void onFilePrev() {
      onFilePrev(true, Move.LINE_FIRST);
    }

    protected void onFilePrev(boolean scrollTop, Move moveTo) {
      contentTable.hideCursor();
      final Diff diff = getPrevDiff(moveTo);
      if (diff != null) {
        this.diff = diff;
        contentTable = diff.getContentTable();
        if (scrollTop) {
          Window.scrollTo(0, diff.getAbsoluteTop());
        }
        contentTable.moveTo(moveTo);
      }
      contentTable.showCursor();
    }

    @Override
    protected void onInsertComment() {
      if (contentTable != null) {
        contentTable.moveToActiveRow();
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
        if (contentTable.isOnLastRow()) {
          onFileNext(false, Move.LINE_FIRST);
        } else {
          contentTable.onDown();
        }
      }
    }

    @Override
    protected void onOpen() {
      if (contentTable != null) {
        contentTable.moveToActiveRow();
        contentTable.onOpenCurrent();
      }
    }

    @Override
    protected void onPrev() {
      if (contentTable != null) {
        if (contentTable.isOnFirstRow()) {
          onFilePrev(false, Move.LINE_LAST);
        } else {
          contentTable.onUp();
        }
      }
    }

    @Override
    protected void onPublishComments() {
      if (approvalButtons.iterator().hasNext()) {
        Window.scrollTo(0, approvalButtons.iterator().next().getAbsoluteTop());
      }
      message.setFocus(true);
    }
  }

  private enum Action { NOOP, SUBMIT, STAGE };

  private boolean intralineFailure;
  private FlowPanel files;
  private Panel approvalPanel;
  private static SavedState lastState;
  private String revision;
  private Collection<ValueRadioButton> approvalButtons;
  private NpTextArea message;
  private Button send;
  private Button stage;
  private Button submit;
  private Button cancel;
  boolean saveStateOnUnload = false;
  private KeyNavigation keyNavigation;
  private List<Diff> diffs;
  private Diff.Factory diffFactory;
  private Id id;
  private boolean changeInCI = false;

  ChangeInfo change;

  public AllInOnePatchScreen(final Patch.Key id,
      final PatchSetDetail detail, final PatchTable patchTable,
      final TopView top, final PatchSet.Id baseId,
      AbstractPatchScreen.Type patchScreenType) {
    super(id, detail, patchTable, top, baseId);

    setPatchId(id.getParentKey());
    diffs = new ArrayList<Diff>();
    keyNavigation = new KeyNavigation(this);
    keyNavigation.addNavigationKey(new UpToChangeCommand(id.getParentKey(), 0, 'u'));

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
  public void onShowView() {
    super.onShowView();
    if (intralineFailure) {
      intralineFailure = false;
      new ErrorDialog(PatchUtil.C.intralineFailure()).show();
    }
  }

  public void refresh(final boolean isFirst) {
    files.clear();
    diffs.clear();
    keyNavigation.clear();
    if (approvalPanel != null) {
      approvalPanel.clear();
    }
    loadDiffs();

    CallbackGroup cbs = new CallbackGroup();
    ConfigInfoCache.get(patchSetDetail.getProject(),
        cbs.add(new AsyncCallback<ConfigInfoCache.Entry>() {
          @Override
          public void onSuccess(ConfigInfoCache.Entry result) {
            commentLinkProcessor = result.getCommentLinkProcessor();
            for (int i = 0; i < diffs.size(); ++i) {
              final Diff dff = diffs.get(i);
              dff.getContentTable().setCommentLinkProcessor(commentLinkProcessor);
            }
            updateCommitInformation(patchKey);
            setTheme(result.getTheme());
          }

          @Override
          public void onFailure(Throwable caught) {
            // Handled by ScreenLoadCallback.onFailure.
          }
        }));

    if (Gerrit.isSignedIn()) {
      Util.DETAIL_SVC.patchSetPublishDetail(getPatchId(), cbs.addGwtjsonrpc(
          new ScreenLoadCallback<PatchSetPublishDetail>(this) {
            @Override
            protected void preDisplay(final PatchSetPublishDetail result) {
              send.setEnabled(true);
              if (result.getChange().getStatus().isOpen()) {
                initApprovals(approvalPanel);
              }
              if (lastState != null && getPatchId().equals(lastState.patchSetId)) {
                message.setText(lastState.message);
              }
              revision = result.getPatchSetInfo().getRevId();
              changeInCI = result.getChange().getStatus().isCI();

              stage.setVisible(result.canStage());
              submit.setVisible(result.canSubmit());
              if (Gerrit.getConfig().testChangeMerge()) {
                stage.setEnabled(result.getChange().isMergeable());
                submit.setEnabled(result.getChange().isMergeable());
              }
            }
          }));
    }

    updateHistory(patchKey);
  }

  @Override
  public void registerKeys() {
    super.registerKeys();
    keyNavigation.setRegisterKeys(false);
    keyNavigation.setRegisterKeys(true);
  }

  @Override
  public void remove(CommentEditorPanel panel) {
  }

  public int getPatchIndex() {
    return 0;
  }

  private Id getPatchId() {
    return id;
  }

  private void setPatchId(Id id) {
    this.id = id;
  }

  private Diff getNextDiff(Move moveTo) {
    if (keyNavigation.getDiff() == null) {
      if (!diffs.isEmpty()) {
        return diffs.get(0);
      } else {
        return null;
      }
    } else {
      int index = diffs.indexOf(keyNavigation.getDiff()) + 1;
      while (index < diffs.size()) {
        final Diff diff = diffs.get(index);
        if (diff.isVisible()) {
          switch (moveTo) {
            case CHUNK_FIRST:
              if (diff.getContentTable().hasEdits()
                  || diff.getContentTable().hasComments()) {
                return diff;
              }
              break;
            case COMMENT_FIRST:
              if (diff.getContentTable().hasComments()) {
                return diff;
              }
              break;
            default:
                return diff;
          }
        }
        index++;
      }
      return null;
    }
  }

  private Diff getPrevDiff(Move moveTo) {
    if (keyNavigation.getDiff() == null) {
      if (!diffs.isEmpty()) {
        return diffs.get(0);
      } else {
        return null;
      }
    } else {
      int index = diffs.indexOf(keyNavigation.getDiff()) -1 ;
      while (index >= 0) {
        final Diff diff = diffs.get(index);
        if (diff.isVisible()) {
          switch (moveTo) {
            case CHUNK_LAST:
              if (diff.getContentTable().hasEdits()) {
                return diff;
              }
              break;
            case COMMENT_LAST:
              if (diff.getContentTable().hasComments()) {
                return diff;
              }
              break;
            default:
                return diff;
          }
        }
        index--;
      }
      return null;
    }
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
      CallbackGroup cbs = new CallbackGroup();
      ChangeApi.revision(getPatchId()).view("review")
          .get(cbs.add(new AsyncCallback<ChangeInfo>() {
            @Override
            public void onSuccess(ChangeInfo result) {
              result.init();
              setChange(result);
            }

            @Override
            public void onFailure(Throwable caught) {
              // Handled by ScreenLoadCallback.onFailure().
            }
          }));

      Util.DETAIL_SVC.patchSetDetail(getPatchId(), cbs.addGwtjsonrpc(
          new ScreenLoadCallback<PatchSetDetail>(this) {

            @Override
            protected void preDisplay(PatchSetDetail result) {
              patchSetDetail = result;

              if (fileList == null) {
                fileList = new PatchTable(prefs);
                fileList.display(getSideA(), result);
                fileList.movePointerToLast();
              }

              if (!result.getPatches().isEmpty()) {
                patchKey = new Patch.Key(result.getPatches().get(0).getKey()
                    .getParentKey(), Patch.ALL);
              }
              refresh(true);
            }
          }));

    } else {
      refresh(true);
    }
  }

  private void updateCommitInformation(final Patch.Key patchKey) {
    final Change.Id cid = patchKey.getParentKey().getParentKey();

    setWindowTitle(cid.toString());
    final String subject = patchSetDetail.getInfo().getSubject();
    setPageTitle(cid.toString() + ": " + subject);

    if (idSideB.equals(patchSetDetail.getPatchSet().getId())) {
      commitMessageBlock.setVisible(true);
      if (commentLinkProcessor != null) {
        commitMessageBlock.display(patchSetDetail.getInfo().getMessage(), commentLinkProcessor);
      }
    } else {
      commitMessageBlock.setVisible(false);
      Util.DETAIL_SVC.patchSetDetail(idSideB,
          new GerritCallback<PatchSetDetail>() {
            @Override
            public void onSuccess(PatchSetDetail result) {
              commitMessageBlock.setVisible(true);
              if (commentLinkProcessor != null) {
                commitMessageBlock.display(result.getInfo().getMessage(), commentLinkProcessor);
              }
            }
          });
    }
  }

  private void updateHistory(final Patch.Key patchKey) {
    PatchUtil.DETAIL_SVC.patchScript(patchKey, idSideA, idSideB,
        settingsPanel.getValue(), new GerritCallback<PatchScript>() {
          @Override
          public void onSuccess(final PatchScript result) {
            historyTable.display(result.getHistory());
          }
        });
  }

  void setChange(ChangeInfo result) {
    change = result;
  }

  @Override
  protected void onInitUI() {
    super.onInitUI();

    Window.scrollTo(0, getAbsoluteTop());

    createNavs(null);
    createContentPanel();
    files = new FlowPanel();
    contentPanel.add(files);

    add(topNav);
    add(contentPanel);
    add(bottomNav);

    if (Gerrit.isSignedIn()) {
      saveStateOnUnload = true;
      approvalButtons = new ArrayList<ValueRadioButton>();

      final FormPanel form = new FormPanel();
      final FlowPanel body = new FlowPanel();
      form.setWidget(body);
      form.addSubmitHandler(new FormPanel.SubmitHandler() {
        @Override
        public void onSubmit(final SubmitEvent event) {
          event.cancel();
        }
      });
      add(form);

      approvalPanel = new FlowPanel();
      body.add(approvalPanel);
      initMessage(body);

      final FlowPanel buttonRow = new FlowPanel();
      buttonRow.setStyleName(Gerrit.RESOURCES.css().patchSetActions());
      body.add(buttonRow);

      send = new Button(Util.C.buttonPublishCommentsSend());
      send.addClickHandler(this);
      buttonRow.add(send);

      stage = new Button(Util.C.buttonPublishStagingSend());
      stage.addClickHandler(this);
      buttonRow.add(stage);

      submit = new Button(Util.C.buttonPublishSubmitSend());
      submit.addClickHandler(this);
      buttonRow.add(submit);

      cancel = new Button(Util.C.buttonPublishCommentsCancel());
      cancel.addClickHandler(this);
      buttonRow.add(cancel);
    }

    keyNavigation.initializeKeys();
  }

  @Override
  protected void onLoad() {
    super.onLoad();

    keyNavigation.setRegisterKeys(true);
    loadFileList();

    if (!isCurrentView()) {
      display();
    }
  }

  @Override
  protected void onUnload() {
    super.onUnload();
    keyNavigation.setRegisterKeys(false);
    lastState = saveStateOnUnload ? new SavedState(this) : null;
  }

  protected void update(AccountDiffPreference dp) {
    refresh(false);
  }

  void setReviewedByCurrentUser(boolean reviewed) {
    if (fileList != null) {
      fileList.updateReviewedStatus(patchKey, reviewed);
    }
  }

  private void initMessage(final Panel body) {
    body.add(new SmallHeading(Util.C.headingCoverMessage()));

    final VerticalPanel mwrap = new VerticalPanel();
    mwrap.setStyleName(Gerrit.RESOURCES.css().coverMessage());
    body.add(mwrap);

    message = new NpTextArea();
    message.setCharacterWidth(60);
    message.setVisibleLines(10);
    message.setSpellCheck(true);
    mwrap.add(message);
  }

  private void initApprovals(Panel body) {
    for (String labelName : change.labels()) {
      initLabel(labelName, body);
    }
  }

  private void initLabel(String labelName, Panel body) {
    if (!change.has_permitted_labels()) {
      return;
    }
    JsArrayString nativeValues = change.permitted_values(labelName);
    if (nativeValues == null || nativeValues.length() == 0) {
      return;
    }
    List<String> values = new ArrayList<String>(nativeValues.length());
    for (int i = 0; i < nativeValues.length(); i++) {
      values.add(nativeValues.get(i));
    }
    Collections.reverse(values);
    LabelInfo label = change.label(labelName);

    body.add(new SmallHeading(label.name() + ":"));

    VerticalPanel vp = new VerticalPanel();
    vp.setStyleName(Gerrit.RESOURCES.css().labelList());

    Short prior = null;
    if (label.all() != null) {
      for (ApprovalInfo app : Natives.asList(label.all())) {
        if (app._account_id() == Gerrit.getUserAccount().getId().get()) {
          prior = app.value();
          break;
        }
      }
    }

    for (String value : values) {
      ValueRadioButton b = new ValueRadioButton(label, value);
      SafeHtml buf = new SafeHtmlBuilder().append(b.format());
      buf = commentLinkProcessor.apply(buf);
      SafeHtml.set(b, buf);

      if (lastState != null && getPatchId().equals(lastState.patchSetId)
          && lastState.approvals.containsKey(label.name())) {
        b.setValue(lastState.approvals.get(label.name()) == value);
      } else {
        b.setValue(b.parseValue() == (prior != null ? prior : 0));
      }

      approvalButtons.add(b);
      vp.add(b);
    }
    body.add(vp);
  }

  @Override
  public void onClick(final ClickEvent event) {
    final Widget sender = (Widget) event.getSource();
    if (send == sender) {
      onSend(Action.NOOP);
    } else if (stage == sender) {
      onSend(Action.STAGE);
    } else if (submit == sender) {
      onSend(Action.SUBMIT);
    } else if (cancel == sender) {
      saveStateOnUnload = false;
      goChange();
    }
  }

  private void onSend(final Action action) {
    ReviewInput data = ReviewInput.create();
    data.message(ChangeApi.emptyToNull(message.getText().trim()));
    data.init();
    for (final ValueRadioButton b : approvalButtons) {
      if (b.getValue()) {
        data.label(b.label.name(), b.parseValue());
      }
    }
    data.changeReviewable(!changeInCI);

    enableForm(false);
    new RestApi("/changes/")
      .id(String.valueOf(getPatchId().getParentKey().get()))
      .view("revisions").id(revision).view("review")
      .post(data, new GerritCallback<ReviewInput>() {
          @Override
          public void onSuccess(ReviewInput result) {
            if (action == Action.SUBMIT) {
              submit();
            } else if (action == Action.STAGE) {
              stage();
            } else {
              saveStateOnUnload = false;
              if (!result.getMessage().isEmpty()) {
                new ErrorDialog(result.getMessage()).center();
              }
              goChange();
            }
          }

          @Override
          public void onFailure(Throwable caught) {
            super.onFailure(caught);
            enableForm(true);
          }
        });
  }

  private static class ReviewInput extends JavaScriptObject {
    static ReviewInput create() {
      return (ReviewInput) createObject();
    }

    final native void message(String m) /*-{ if (m) this.message=m; }-*/;
    final native void label(String n, short v) /*-{ this.labels[n]=v; }-*/;
    final native void init() /*-{
      this.labels = {};
      this.strict_labels = true;
      this.drafts = 'PUBLISH';
    }-*/;
    final native void changeReviewable(boolean b) /*-{ this.change_reviewable=b; }-*/;

    public final native String getMessage() /*-{ return this.message; }-*/;

    protected ReviewInput() {
    }
  }

  private void stage() {
    ChangeApi.stage(getPatchId().getParentKey().get(), revision,
      new GerritCallback<SubmitInfo>() {
          public void onSuccess(SubmitInfo result) {
            saveStateOnUnload = false;
            goChange();
          }

          @Override
          public void onFailure(Throwable err) {
            if (StageFailureDialog.isConflict(err)) {
              new StageFailureDialog(err.getMessage()).center();
            } else {
              super.onFailure(err);
            }
            goChange();
          }
        });
  }

  private void submit() {
    ChangeApi.submit(getPatchId().getParentKey().get(), revision,
      new GerritCallback<SubmitInfo>() {
          public void onSuccess(SubmitInfo result) {
            saveStateOnUnload = false;
            goChange();
          }

          @Override
          public void onFailure(Throwable err) {
            if (SubmitFailureDialog.isConflict(err)) {
              new SubmitFailureDialog(err.getMessage()).center();
            } else {
              super.onFailure(err);
            }
            goChange();
          }
        });
  }

  private static class ValueRadioButton extends RadioButton {
    final LabelInfo label;
    final String value;

    ValueRadioButton(LabelInfo label, String value) {
      super(label.name());
      this.label = label;
      this.value = value;
    }

    String format() {
      return new StringBuilder().append(value).append(' ')
          .append(label.value_text(value)).toString();
    }

    short parseValue() {
      String value = this.value;
      if (value.startsWith(" ") || value.startsWith("+")) {
        value = value.substring(1);
      }
      return Short.parseShort(value);
    }
  }

  private void goChange() {
    final Change.Id ck = getPatchId().getParentKey();
    Gerrit.display(PageLinks.toChange(ck), new ChangeScreen(ck));
  }

  private void enableForm(final boolean enabled) {
    for (final ValueRadioButton approvalButton : approvalButtons) {
      approvalButton.setEnabled(enabled);
    }
    message.setEnabled(enabled);
    send.setEnabled(enabled);
    stage.setEnabled(enabled);
    submit.setEnabled(enabled);
    cancel.setEnabled(enabled);
  }

  private static class SavedState {
    final PatchSet.Id patchSetId;
    final String message;
    final Map<String, String> approvals;

    SavedState(final AllInOnePatchScreen p) {
      patchSetId = p.getPatchId();
      message = p.message.getText();
      approvals = new HashMap<String, String>();
      for (final ValueRadioButton b : p.approvalButtons) {
        if (b.getValue()) {
          approvals.put(b.label.name(), b.value);
        }
      }
    }
  }
}

