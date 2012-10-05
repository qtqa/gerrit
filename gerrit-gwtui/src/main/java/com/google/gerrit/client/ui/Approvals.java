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
import com.google.gerrit.common.data.ApprovalType;
import com.google.gerrit.common.data.ApprovalTypes;
import com.google.gerrit.common.data.ChangeDetail;
import com.google.gerrit.common.data.PatchSetPublishDetail;
import com.google.gerrit.common.data.PermissionRange;
import com.google.gerrit.reviewdb.ApprovalCategory;
import com.google.gerrit.reviewdb.ApprovalCategoryValue;
import com.google.gerrit.reviewdb.Change;
import com.google.gerrit.reviewdb.PatchSet;
import com.google.gerrit.reviewdb.PatchSetApproval;
import com.google.gerrit.client.changes.Util;
import com.google.gerrit.client.patches.PatchUtil;
import com.google.gerrit.client.rpc.GerritCallback;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.DisclosurePanel;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Panel;
import com.google.gwt.user.client.ui.RadioButton;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwtjsonrpc.client.VoidResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Approvals extends Composite {
  public interface Delegate {
    void onCancel();
    void onPublish();
    void onStage();
    void onSubmit();
  }

  private static class SavedState {
    final PatchSet.Id patchSetId;
    final Map<ApprovalCategory.Id, ApprovalCategoryValue> approvalValues;

    SavedState(final Approvals approvals) {
      this.patchSetId = approvals.patchSetId;
      approvalValues = new HashMap<ApprovalCategory.Id, ApprovalCategoryValue>();
      for (final ValueRadioButton b : approvals.approvalButtons) {
        if (b.getValue()) {
          approvalValues.put(b.value.getCategoryId(), b.value);
        }
      }
    }
  }

  private static class ValueRadioButton extends RadioButton {
    final ApprovalCategoryValue value;

    ValueRadioButton(final ApprovalCategoryValue v, final String label) {
      super(label);
      value = v;
    }
  }

  private static SavedState lastState;
  private boolean saveState = true;
  private final Panel body;
  private final PatchSet.Id patchSetId;
  private Collection<ValueRadioButton> approvalButtons;
  private Message message;

  private FlowPanel actionsPanel;
  private Set<Delegate> delegates;

  public Approvals(final PatchSet.Id patchSetId) {
    this.patchSetId = patchSetId;
    body = new FlowPanel();
    approvalButtons = new ArrayList<ValueRadioButton>();
    message = new Message(patchSetId);

    actionsPanel = new FlowPanel();
    actionsPanel.setStyleName(Gerrit.RESOURCES.css().patchSetActions());
    delegates = new HashSet<Approvals.Delegate>();

    initWidget(body);
  }

  public void addDelegate(final Delegate delegate) {
    delegates.add(delegate);
  }

  public Map<ApprovalCategory.Id, ApprovalCategoryValue.Id> getValues() {
    HashMap<ApprovalCategory.Id, ApprovalCategoryValue.Id> values =
      new HashMap<ApprovalCategory.Id, ApprovalCategoryValue.Id>();
    for (final ValueRadioButton b : approvalButtons) {
      if (b.getValue()) {
        values.put(b.value.getCategoryId(), b.value.getId());
      }
    }

    return values;
  }

  public void load() {
    Util.DETAIL_SVC.patchSetPublishDetail(patchSetId,
        new GerritCallback<PatchSetPublishDetail>() {
          @Override
          public void onSuccess(final PatchSetPublishDetail result) {
            if (result.getChange().getStatus().isOpen()) {
              initApprovals(result);
              body.add(message);
              populateActions(result);
            }
          }
        });
  }

  public void removeDelegate(final Delegate delegate) {
    delegates.remove(delegate);
  }

  public void setSaveState(boolean saveState) {
    this.saveState = saveState;
    message.setSaveState(saveState);
  }

  private void initApprovals(final PatchSetPublishDetail r) {
    ApprovalTypes types = Gerrit.getConfig().getApprovalTypes();
    for (PermissionRange range : r.getLabels()) {
      ApprovalType type = types.byLabel(range.getLabel());
      if (type != null) {
        // Legacy type, use radio buttons.
        initApprovalType(r, body, type, range);
      } else {
        // TODO Newer style label.
      }
    }
  }

  private void initApprovalType(final PatchSetPublishDetail r,
      final Panel body, final ApprovalType ct, final PermissionRange range) {
    final VerticalPanel vp = new VerticalPanel();
    vp.setStyleName(Gerrit.RESOURCES.css().approvalCategoryList());
    final List<ApprovalCategoryValue> lst =
        new ArrayList<ApprovalCategoryValue>(ct.getValues());
    Collections.reverse(lst);
    final ApprovalCategory.Id catId = ct.getCategory().getId();
    final PatchSetApproval prior = r.getChangeApproval(catId);

    for (final ApprovalCategoryValue buttonValue : lst) {
      if (!range.contains(buttonValue.getValue())) {
        continue;
      }

      final ValueRadioButton b =
          new ValueRadioButton(buttonValue, ct.getCategory().getName());
      b.setText(buttonValue.format());

      if (lastState != null && patchSetId.equals(lastState.patchSetId)
          && lastState.approvalValues.containsKey(buttonValue.getCategoryId())) {
        b.setValue(lastState.approvalValues.get(buttonValue.getCategoryId()).equals(
            buttonValue));
      } else {
        b.setValue(prior != null ? buttonValue.getValue() == prior.getValue()
            : buttonValue.getValue() == 0);
      }

      approvalButtons.add(b);
      vp.add(b);
    }
    DisclosurePanel atp = new DisclosurePanel(ct.getCategory().getName());
    atp.setContent(vp);
    atp.setOpen(!ApprovalCategory.SANITY_REVIEW.equals(ct
        .getCategory().getId()));
    body.add(atp);
  }

  private void populateActions(final PatchSetPublishDetail result) {
    final boolean isOpen = result.getChange().getStatus().isOpen();
    final boolean isNew = result.getChange().getStatus() == Change.Status.NEW;

    {
      final Button button = new Button(Util.C.buttonPublishCommentsSend());
      button.addClickHandler(new ClickHandler() {
        @Override
        public void onClick(ClickEvent event) {
          publishComments(new GerritCallback<VoidResult>() {
            @Override
            public void onSuccess(VoidResult result) {
              setSaveState(false);
              for (Delegate delegate : delegates) {
                delegate.onPublish();
              }
            }
          });
        }
      });
      actionsPanel.add(button);
    }

    if (isNew && result.canStage()) {
      final Button button = new Button(Util.C.buttonPublishStagingSend());
      button.addClickHandler(new ClickHandler() {
        @Override
        public void onClick(ClickEvent event) {
          publishComments(new GerritCallback<VoidResult>() {
            @Override
            public void onSuccess(VoidResult result) {
              stage();
              setSaveState(false);
            }
          });
        }
      });
      actionsPanel.add(button);
    }

    if (isOpen && result.canSubmit()) {
      final Button button = new Button(Util.C.buttonPublishSubmitSend());
      button.addClickHandler(new ClickHandler() {
        @Override
        public void onClick(ClickEvent event) {
          publishComments(new GerritCallback<VoidResult>() {
            @Override
            public void onSuccess(VoidResult result) {
              submit();
              setSaveState(false);
            }
          });
        }
      });
      actionsPanel.add(button);
    }

    {
      final Button button = new Button(Util.C.buttonPublishCommentsCancel());
      button.addClickHandler(new ClickHandler() {
        @Override
        public void onClick(ClickEvent event) {
          setSaveState(false);
          for (Delegate delegate : delegates) {
            delegate.onCancel();
          }
        }
      });
      actionsPanel.add(button);
    }

    body.add(actionsPanel);
  }

  private void publishComments(final GerritCallback<VoidResult> callback) {
    PatchUtil.DETAIL_SVC.publishComments(patchSetId, message.getText().trim(),
        new HashSet<ApprovalCategoryValue.Id>(getValues().values()), callback);
  }

  private void stage() {
    Util.MANAGE_SVC.stage(patchSetId, new GerritCallback<ChangeDetail>() {
      @Override
      public void onSuccess(ChangeDetail result) {
        for (Delegate delegate : delegates) {
          delegate.onStage();
        }
      }
    });
  }

  private void submit() {
    Util.MANAGE_SVC.submit(patchSetId,
        new GerritCallback<ChangeDetail>() {
          public void onSuccess(ChangeDetail result) {
            for (Delegate delegate : delegates) {
              delegate.onSubmit();
            }
          }
        });
  }

  @Override
  protected void onUnload() {
    lastState = saveState ? new SavedState(this) : null;
  }
}
