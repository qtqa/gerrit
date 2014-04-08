// Copyright (C) 2008 The Android Open Source Project
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

package com.google.gerrit.reviewdb.client;

import com.google.gwtorm.client.Column;
import com.google.gwtorm.client.StringKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Projects match a source code repository managed by Gerrit */
public final class Project {
  /** Project name key */
  public static class NameKey extends
      StringKey<com.google.gwtorm.client.Key<?>>{
    private static final long serialVersionUID = 1L;

    @Column(id = 1)
    protected String name;

    protected NameKey() {
    }

    public NameKey(final String n) {
      name = n;
    }

    @Override
    public String get() {
      return name;
    }

    @Override
    protected void set(String newValue) {
      name = newValue;
    }

    @Override
    public int hashCode() {
      return get().hashCode();
    }

    @Override
    public boolean equals(Object b) {
      if (b instanceof NameKey) {
        return get().equals(((NameKey) b).get());
      }
      return false;
    }

    /** Parse a Project.NameKey out of a string representation. */
    public static NameKey parse(final String str) {
      final NameKey r = new NameKey();
      r.fromString(str);
      return r;
    }
  }

  public static class ApprovalFooterValue {
    String label;
    boolean hidden;

    protected ApprovalFooterValue() {
    }

    protected ApprovalFooterValue(final String label, final boolean on) {
      this.label = label;
      this.hidden = on;
    }

    public String getLabel() {
      return label;
    }

    public boolean getHidden() {
      return hidden;
    }
  }

  public static enum SubmitType {
    FAST_FORWARD_ONLY,

    MERGE_IF_NECESSARY,

    REBASE_IF_NECESSARY,

    MERGE_ALWAYS,

    CHERRY_PICK;
  }

  public static enum State {
    ACTIVE,

    READ_ONLY,

    HIDDEN;
  }

  public static enum InheritableBoolean {
    TRUE,
    FALSE,
    INHERIT;
  }

  protected NameKey name;

  protected String description;

  protected InheritableBoolean useContributorAgreements;

  protected InheritableBoolean useSignedOffBy;

  protected SubmitType submitType;

  protected State state;

  protected NameKey parent;

  protected InheritableBoolean requireChangeID;

  protected InheritableBoolean useContentMerge;

  protected String defaultDashboardId;

  protected String localDefaultDashboardId;

  protected String themeName;

  protected boolean hideReviewedOn;

  protected boolean includeOnlyMaximumApprovals;

  protected List<ApprovalFooterValue> hiddenFooters;

  protected Project() {
    hiddenFooters = new ArrayList<Project.ApprovalFooterValue>();
  }

  public Project(Project.NameKey nameKey) {
    name = nameKey;
    submitType = SubmitType.MERGE_IF_NECESSARY;
    state = State.ACTIVE;
    hiddenFooters = new ArrayList<Project.ApprovalFooterValue>();
    useContributorAgreements = InheritableBoolean.INHERIT;
    useSignedOffBy = InheritableBoolean.INHERIT;
    requireChangeID = InheritableBoolean.INHERIT;
    useContentMerge = InheritableBoolean.INHERIT;
  }

  public Project.NameKey getNameKey() {
    return name;
  }

  public String getName() {
    return name != null ? name.get() : null;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(final String d) {
    description = d;
  }

  public InheritableBoolean getUseContributorAgreements() {
    return useContributorAgreements;
  }

  public InheritableBoolean getUseSignedOffBy() {
    return useSignedOffBy;
  }

  public InheritableBoolean getUseContentMerge() {
    return useContentMerge;
  }

  public InheritableBoolean getRequireChangeID() {
    return requireChangeID;
  }

  public void setUseContributorAgreements(final InheritableBoolean u) {
    useContributorAgreements = u;
  }

  public boolean isHideReviewedOn() {
    return hideReviewedOn;
  }

  public boolean isIncludeOnlyMaxApproval() {
    return includeOnlyMaximumApprovals;
  }

  public Map<String, Boolean> getHiddenFooters() {
    HashMap<String, Boolean> footers = new HashMap<String, Boolean>();
    for (ApprovalFooterValue value : hiddenFooters) {
      footers.put(value.getLabel(), value.getHidden());
    }
    return footers;
  }

  public void setUseSignedOffBy(final InheritableBoolean sbo) {
    useSignedOffBy = sbo;
  }

  public void setUseContentMerge(final InheritableBoolean cm) {
    useContentMerge = cm;
  }

  public void setRequireChangeID(final InheritableBoolean cid) {
    requireChangeID = cid;
  }

  public void setHideReviewedOn(boolean includeReviewedOn) {
    this.hideReviewedOn = includeReviewedOn;
  }

  public void setIncludeOnlyMaxApproval(boolean includeOnlyMaxApproval) {
    this.includeOnlyMaximumApprovals = includeOnlyMaxApproval;
  }

  public SubmitType getSubmitType() {
    return submitType;
  }

  public void setSubmitType(final SubmitType type) {
    submitType = type;
  }

  public State getState() {
    return state;
  }

  public void setState(final State newState) {
    state = newState;
  }

  public void addHiddenFooter(final String label, final boolean on) {
    for (ApprovalFooterValue value : hiddenFooters) {
      if (value.label.equals(label)) {
        value.hidden = on;
        return;
      }
    }

    hiddenFooters.add(new ApprovalFooterValue(label, on));
  }

  public String getDefaultDashboard() {
    return defaultDashboardId;
  }

  public void setDefaultDashboard(final String defaultDashboardId) {
    this.defaultDashboardId = defaultDashboardId;
  }

  public String getLocalDefaultDashboard() {
    return localDefaultDashboardId;
  }

  public void setLocalDefaultDashboard(final String localDefaultDashboardId) {
    this.localDefaultDashboardId = localDefaultDashboardId;
  }

  public String getThemeName() {
    return themeName;
  }

  public void setThemeName(final String themeName) {
    this.themeName = themeName;
  }

  public void copySettingsFrom(final Project update) {
    description = update.description;
    useContributorAgreements = update.useContributorAgreements;
    useSignedOffBy = update.useSignedOffBy;
    useContentMerge = update.useContentMerge;
    requireChangeID = update.requireChangeID;
    submitType = update.submitType;
    state = update.state;
    includeOnlyMaximumApprovals = update.includeOnlyMaximumApprovals;
    hideReviewedOn = update.hideReviewedOn;
    hiddenFooters = update.hiddenFooters;
  }

  /**
   * Returns the name key of the parent project.
   *
   * @return name key of the parent project, <code>null</code> if this project
   *         is the wild project, <code>null</code> or the name key of the wild
   *         project if this project is a direct child of the wild project
   */
  public Project.NameKey getParent() {
    return parent;
  }

  /**
   * Returns the name key of the parent project.
   *
   * @param allProjectsName name key of the wild project
   * @return name key of the parent project, <code>null</code> if this project
   *         is the wild project
   */
  public Project.NameKey getParent(final Project.NameKey allProjectsName) {
    if (parent != null) {
      return parent;
    }

    if (name.equals(allProjectsName)) {
      return null;
    }

    return allProjectsName;
  }

  public String getParentName() {
    return parent != null ? parent.get() : null;
  }

  public void setParentName(String n) {
    parent = n != null ? new NameKey(n) : null;
  }

  public void setParentName(NameKey n) {
    parent = n;
  }
}
