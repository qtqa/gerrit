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

package com.google.gerrit.reviewdb;

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
      StringKey<com.google.gwtorm.client.Key<?>> implements Comparable<NameKey> {
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
    public int compareTo(NameKey other) {
      return get().compareTo(other.get());
    }

    /** Parse a Project.NameKey out of a string representation. */
    public static NameKey parse(final String str) {
      final NameKey r = new NameKey();
      r.fromString(str);
      return r;
    }
  }

  public static class ApprovalFooterValue {
    String category;
    boolean hidden;

    protected ApprovalFooterValue() {
    }

    protected ApprovalFooterValue(final String category, final boolean on) {
      this.category = category;
      this.hidden = on;
    }

    public String getCategory() {
      return category;
    }

    public boolean getHidden() {
      return hidden;
    }
  }

  public static enum SubmitType {
    FAST_FORWARD_ONLY,

    MERGE_IF_NECESSARY,

    MERGE_ALWAYS,

    CHERRY_PICK;
  }

  protected NameKey name;

  protected String description;

  protected boolean useContributorAgreements;

  protected boolean useSignedOffBy;

  protected SubmitType submitType;

  protected NameKey parent;

  protected boolean requireChangeID;

  protected boolean allowTopicReview;

  protected boolean useContentMerge;

  protected boolean hideReviewedOn;

  protected boolean includeOnlyMaximumApprovals;

  protected List<ApprovalFooterValue> hiddenFooters;

  protected Project() {
    hiddenFooters = new ArrayList<Project.ApprovalFooterValue>();
  }

  public Project(Project.NameKey nameKey) {
    name = nameKey;
    submitType = SubmitType.MERGE_IF_NECESSARY;
    hiddenFooters = new ArrayList<Project.ApprovalFooterValue>();
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

  public boolean isUseContributorAgreements() {
    return useContributorAgreements;
  }

  public void setUseContributorAgreements(final boolean u) {
    useContributorAgreements = u;
  }

  public boolean isUseSignedOffBy() {
    return useSignedOffBy;
  }

  public boolean isUseContentMerge() {
    return useContentMerge;
  }

  public boolean isRequireChangeID() {
    return requireChangeID;
  }

  public boolean isAllowTopicReview() {
    return allowTopicReview;
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
      footers.put(value.getCategory(), value.getHidden());
    }
    return footers;
  }

  public void setUseSignedOffBy(final boolean sbo) {
    useSignedOffBy = sbo;
  }

  public void setUseContentMerge(final boolean cm) {
    useContentMerge = cm;
  }

  public void setRequireChangeID(final boolean cid) {
    requireChangeID = cid;
  }

  public void setAllowTopicReview(final boolean atr) {
    allowTopicReview = atr;
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

  public void addHiddenFooter(final String category, final boolean on) {
    for (ApprovalFooterValue value : hiddenFooters) {
      if (value.category.equals(category)) {
        value.hidden = on;
        return;
      }
    }

    hiddenFooters.add(new ApprovalFooterValue(category, on));
  }

  public void copySettingsFrom(final Project update) {
    description = update.description;
    useContributorAgreements = update.useContributorAgreements;
    useSignedOffBy = update.useSignedOffBy;
    useContentMerge = update.useContentMerge;
    requireChangeID = update.requireChangeID;
    allowTopicReview = update.allowTopicReview;
    submitType = update.submitType;
    includeOnlyMaximumApprovals = update.includeOnlyMaximumApprovals;
    hideReviewedOn = update.hideReviewedOn;
    hiddenFooters = update.hiddenFooters;
  }

  public Project.NameKey getParent() {
    return parent;
  }

  public String getParentName() {
    return parent != null ? parent.get() : null;
  }

  public void setParentName(String n) {
    parent = n != null ? new NameKey(n) : null;
  }
}
