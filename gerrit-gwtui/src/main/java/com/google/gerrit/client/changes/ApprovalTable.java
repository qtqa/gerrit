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

package com.google.gerrit.client.changes;

import com.google.gerrit.client.ErrorDialog;
import com.google.gerrit.client.FormatUtil;
import com.google.gerrit.client.Gerrit;
import com.google.gerrit.client.patches.PatchUtil;
import com.google.gerrit.client.rpc.GerritCallback;
import com.google.gerrit.client.ui.AccountDashboardLink;
import com.google.gerrit.client.ui.AddMemberBox;
import com.google.gerrit.common.data.AccountInfoCache;
import com.google.gerrit.common.data.ApprovalDetail;
import com.google.gerrit.common.data.ApprovalType;
import com.google.gerrit.common.data.ChangeDetail;
import com.google.gerrit.common.data.ChangeSetApprovalDetail;
import com.google.gerrit.common.data.PatchSetApprovalDetail;
import com.google.gerrit.common.data.ReviewerResult;
import com.google.gerrit.common.data.TopicDetail;
import com.google.gerrit.common.data.TopicReviewerResult;
import com.google.gerrit.reviewdb.Account;
import com.google.gerrit.reviewdb.ApprovalCategory;
import com.google.gerrit.reviewdb.ApprovalCategoryValue;
import com.google.gerrit.reviewdb.Change;
import com.google.gerrit.reviewdb.SetApproval;
import com.google.gerrit.reviewdb.Topic;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Grid;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.Panel;
import com.google.gwt.user.client.ui.PushButton;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.user.client.ui.HTMLTable.CellFormatter;
import com.google.gwtexpui.safehtml.client.SafeHtmlBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Displays a table of {@link ApprovalDetail} objects for a change record. */
public class ApprovalTable extends Composite {
  private final List<ApprovalType> types;
  private final Grid table;
  private final Widget missing;
  private final Panel addReviewer;
  private final AddMemberBox addMemberBox;
  private Change.Id changeId;
  private Topic.Id topicId;
  private AccountInfoCache accountCache = AccountInfoCache.empty();

  public ApprovalTable() {
    types = Gerrit.getConfig().getApprovalTypes().getApprovalTypes();
    table = new Grid(1, 3 + types.size());
    table.addStyleName(Gerrit.RESOURCES.css().infoTable());
    displayHeader();

    missing = new Widget() {
      {
        setElement(DOM.createElement("ul"));
      }
    };
    missing.setStyleName(Gerrit.RESOURCES.css().missingApprovalList());

    addReviewer = new FlowPanel();
    addReviewer.setStyleName(Gerrit.RESOURCES.css().addReviewer());
    addMemberBox = new AddMemberBox();
    addMemberBox.setAddButtonText(Util.C.approvalTableAddReviewer());
    addMemberBox.addClickHandler(new ClickHandler() {
      @Override
      public void onClick(final ClickEvent event) {
        doAddReviewer();
      }
    });
    addReviewer.add(addMemberBox);
    addReviewer.setVisible(false);

    final FlowPanel fp = new FlowPanel();
    fp.add(table);
    fp.add(missing);
    fp.add(addReviewer);
    initWidget(fp);

    setStyleName(Gerrit.RESOURCES.css().approvalTable());
  }

  private void displayHeader() {
    final CellFormatter fmt = table.getCellFormatter();
    int col = 0;

    table.setText(0, col, Util.C.approvalTableReviewer());
    fmt.setStyleName(0, col, Gerrit.RESOURCES.css().header());
    col++;

    table.clearCell(0, col);
    fmt.setStyleName(0, col, Gerrit.RESOURCES.css().header());
    col++;

    for (final ApprovalType t : types) {
      table.setText(0, col, t.getCategory().getName());
      fmt.setStyleName(0, col, Gerrit.RESOURCES.css().header());
      col++;
    }

    table.clearCell(0, col);
    fmt.setStyleName(0, col, Gerrit.RESOURCES.css().header());
    fmt.addStyleName(0, col, Gerrit.RESOURCES.css().rightmost());
    col++;
  }

  public void setAccountInfoCache(final AccountInfoCache aic) {
    assert aic != null;
    accountCache = aic;
  }

  private AccountDashboardLink link(final Account.Id id) {
    return AccountDashboardLink.link(accountCache, id);
  }

  void display(ChangeDetail detail) {
    List<PatchSetApprovalDetail> rows = detail.getApprovals();
    topicId = null;
    changeId = detail.getChange().getId();
    display(detail.getChange().getStatus().isOpen(), rows);
  }

  void display(TopicDetail detail) {
    List<ChangeSetApprovalDetail> rows = detail.getApprovals();
    topicId = detail.getTopic().getId();
    changeId = null;
    display(detail.getTopic().getStatus().isOpen(), rows);
  }

  private <T extends SetApproval<?>, U extends ApprovalDetail<T>>
  void display(final boolean open, final List<U> rows) {

    if (rows.isEmpty()) {
      table.setVisible(false);
    } else {
      table.resizeRows(1 + rows.size());
      for (int i = 0; i < rows.size(); i++) {
        displayRow(i + 1, rows.get(i));
      }
      table.setVisible(true);
    }

    addReviewer.setVisible(Gerrit.isSignedIn());
  }

  private void doAddReviewer() {
    final String nameEmail = addMemberBox.getText();
    if (nameEmail.length() == 0) {
      return;
    }

    addMemberBox.setEnabled(false);
    final List<String> reviewers = new ArrayList<String>();
    reviewers.add(nameEmail);

    // TODO repeated code, try to put together
    if ((changeId != null) && (topicId == null)) PatchUtil.DETAIL_SVC.addReviewers(changeId, reviewers, new GerritCallback<ReviewerResult>() {
      public void onSuccess(final ReviewerResult result) {
        addMemberBox.setEnabled(true);
        addMemberBox.setText("");

        if (!result.getErrors().isEmpty()) {
          final SafeHtmlBuilder r = new SafeHtmlBuilder();
          for (final ReviewerResult.Error e : result.getErrors()) {
            switch (e.getType()) {
              case ACCOUNT_NOT_FOUND:
                r.append(Util.M.accountNotFound(e.getName()));
                break;

              case ACCOUNT_INACTIVE:
                r.append(Util.M.accountInactive(e.getName()));
                break;

              case CHANGE_NOT_VISIBLE:
                r.append(Util.M.changeNotVisibleTo(e.getName()));
                break;

              default:
                r.append(e.getName());
                r.append(" - ");
                r.append(e.getType());
                r.br();
                break;
            }
          }
          new ErrorDialog(r).center();
        }

        final ChangeDetail r = result.getChange();
        if (r != null) {
          setAccountInfoCache(r.getAccounts());
          display(r);
        }
      }

      @Override
      public void onFailure(final Throwable caught) {
        addMemberBox.setEnabled(true);
        super.onFailure(caught);
      }
    });
    else if ((topicId != null) && (changeId == null)) Util.T_DETAIL_SVC.addTopicReviewers(topicId, reviewers, new GerritCallback<TopicReviewerResult>() {
      public void onSuccess(final TopicReviewerResult result) {
        addMemberBox.setEnabled(true);
        addMemberBox.setText("");

        if (!result.getErrors().isEmpty()) {
          final SafeHtmlBuilder r = new SafeHtmlBuilder();
          for (final ReviewerResult.Error e : result.getErrors()) {
            switch (e.getType()) {
              case ACCOUNT_NOT_FOUND:
                r.append(Util.M.accountNotFound(e.getName()));
                break;

              case ACCOUNT_INACTIVE:
                r.append(Util.M.accountInactive(e.getName()));
                break;

              case CHANGE_NOT_VISIBLE:
                r.append(Util.M.changeNotVisibleTo(e.getName()));
                break;

              default:
                r.append(e.getName());
                r.append(" - ");
                r.append(e.getType());
                r.br();
                break;
            }
          }
          new ErrorDialog(r).center();
        }

        final TopicDetail r = result.getTopic();
        if (r != null) {
          setAccountInfoCache(r.getAccounts());
          display(r);
        }
      }
      @Override
      public void onFailure(final Throwable caught) {
        addMemberBox.setEnabled(true);
        super.onFailure(caught);
      }
    });
  }

  private <T extends SetApproval<?>, U extends ApprovalDetail<T>> void displayRow(
      final int row, final U ad) {
    final CellFormatter fmt = table.getCellFormatter();
    final Account.Id aId = ad.getAccount();
    final Map<ApprovalCategory.Id, T> am = ad.getApprovalMap();
    final StringBuilder hint = new StringBuilder();
    int col = 0;

    table.setWidget(row, col++, link(aId));

    if (ad.canRemove()) {
      final PushButton remove = new PushButton( //
          new Image(Util.R.removeReviewerNormal()), //
          new Image(Util.R.removeReviewerPressed()));
      remove.setTitle(Util.M.removeReviewer( //
          FormatUtil.name(accountCache.get(aId))));
      remove.setStyleName(Gerrit.RESOURCES.css().removeReviewer());
      remove.addClickHandler(new ClickHandler() {
        @Override
        public void onClick(ClickEvent event) {
          doRemove(aId, remove);
        }
      });
      table.setWidget(row, col, remove);
    } else {
      table.clearCell(row, col);
    }
    fmt.setStyleName(row, col++, Gerrit.RESOURCES.css().removeReviewerCell());

    for (final ApprovalType type : types) {
      fmt.setStyleName(row, col, Gerrit.RESOURCES.css().approvalscore());

      final T ca = am.get(type.getCategory().getId());
      if (ca == null || ca.getValue() == 0) {
        table.clearCell(row, col);
        col++;
        continue;
      }

      final ApprovalCategoryValue acv = type.getValue(ca);
      if (acv != null) {
        if (hint.length() > 0) {
          hint.append("; ");
        }
        hint.append(acv.getName());
      }

      if (type.isMaxNegative(ca)) {
        table.setWidget(row, col, new Image(Gerrit.RESOURCES.redNot()));

      } else if (type.isMaxPositive(ca)) {
        table.setWidget(row, col, new Image(Gerrit.RESOURCES.greenCheck()));

      } else {
        String vstr = String.valueOf(ca.getValue());
        if (ca.getValue() > 0) {
          vstr = "+" + vstr;
          fmt.addStyleName(row, col, Gerrit.RESOURCES.css().posscore());
        } else {
          fmt.addStyleName(row, col, Gerrit.RESOURCES.css().negscore());
        }
        table.setText(row, col, vstr);
      }

      col++;
    }

    table.setText(row, col, hint.toString());
    fmt.setStyleName(row, col, Gerrit.RESOURCES.css().rightmost());
    fmt.addStyleName(row, col, Gerrit.RESOURCES.css().approvalhint());
    col++;
  }

  private void doRemove(final Account.Id aId, final PushButton remove) {
    remove.setEnabled(false);

    // TODO repeated code, try to put together
    if ((changeId != null) && (topicId == null)) PatchUtil.DETAIL_SVC.removeReviewer(changeId, aId, new GerritCallback<ReviewerResult>() {
      @Override
      public void onSuccess(ReviewerResult result) {
        if (result.getErrors().isEmpty()) {
          final ChangeDetail r = result.getChange();
              display(r);
        } else {
              new ErrorDialog(result.getErrors().get(0).toString()).center();
        }
      }

      @Override
      public void onFailure(final Throwable caught) {
        remove.setEnabled(true);
        super.onFailure(caught);
      }
    });
    else if ((topicId != null) && (changeId == null)) Util.T_DETAIL_SVC.removeTopicReviewer(topicId, aId, new GerritCallback<TopicReviewerResult>() {
      @Override
      public void onSuccess(TopicReviewerResult result) {
        if (result.getErrors().isEmpty()) {
          final TopicDetail r = result.getTopic();
          display(r);
        } else {
          final ReviewerResult.Error resultError =
              result.getErrors().get(0);
          String message;
          switch (resultError.getType()) {
            case REMOVE_NOT_PERMITTED:
              message = Util.C.approvalTableRemoveNotPermitted();
              break;
            case COULD_NOT_REMOVE:
            default:
              message = Util.C.approvalTableCouldNotRemove();
          }
          new ErrorDialog(message + " " + resultError.getName()).center();
        }
      }

      @Override
      public void onFailure(final Throwable caught) {
        remove.setEnabled(true);
        super.onFailure(caught);
      }
    });
  }
}
