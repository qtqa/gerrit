/**
 * @license
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @desc Tab names for primary tabs on change view page.
 */
export enum PrimaryTab {
  FILES = 'files',
  /**
   * When renaming this, the links in UrlFormatter must be updated.
   */
  COMMENT_THREADS = 'comments',
  FINDINGS = 'findings',
}

/**
 * @desc Tab names for secondary tabs on change view page.
 */
export enum SecondaryTab {
  CHANGE_LOG = '_changeLog',
}

/**
 * @desc Tag names of change log messages.
 */
export enum MessageTag {
  TAG_DELETE_REVIEWER = 'autogenerated:gerrit:deleteReviewer',
  TAG_NEW_PATCHSET = 'autogenerated:gerrit:newPatchSet',
  TAG_NEW_WIP_PATCHSET = 'autogenerated:gerrit:newWipPatchSet',
  TAG_REVIEWER_UPDATE = 'autogenerated:gerrit:reviewerUpdate',
  TAG_SET_PRIVATE = 'autogenerated:gerrit:setPrivate',
  TAG_UNSET_PRIVATE = 'autogenerated:gerrit:unsetPrivate',
  TAG_SET_READY = 'autogenerated:gerrit:setReadyForReview',
  TAG_SET_WIP = 'autogenerated:gerrit:setWorkInProgress',
  TAG_SET_ASSIGNEE = 'autogenerated:gerrit:setAssignee',
  TAG_UNSET_ASSIGNEE = 'autogenerated:gerrit:deleteAssignee',
}

/**
 * @desc Modes for gr-diff-cursor
 * The scroll behavior for the cursor. Values are 'never' and
 * 'keep-visible'. 'keep-visible' will only scroll if the cursor is beyond
 * the viewport.
 */
export enum ScrollMode {
  KEEP_VISIBLE = 'keep-visible',
  NEVER = 'never',
}

/**
 * @desc Specifies status for a change
 */
export enum ChangeStatus {
  ABANDONED = 'ABANDONED',
  MERGED = 'MERGED',
  NEW = 'NEW',
  DEFERRED = 'DEFERRED',
  INTEGRATING = 'INTEGRATING',
  STAGED = 'STAGED',
}

/**
 * @desc Special file paths
 */
export enum SpecialFilePath {
  PATCHSET_LEVEL_COMMENTS = '/PATCHSET_LEVEL',
  COMMIT_MESSAGE = '/COMMIT_MSG',
  MERGE_LIST = '/MERGE_LIST',
}

/**
 * @desc The reviewer state
 */
export enum RequirementStatus {
  OK = 'OK',
  NOT_READY = 'NOT_READY',
  RULE_ERROR = 'RULE_ERROR',
}

/**
 * @desc The reviewer state
 */
export enum ReviewerState {
  REVIEWER = 'REVIEWER',
  CC = 'CC',
  REMOVED = 'REMOVED',
}

/**
 * @desc The patchset kind
 */
export enum RevisionKind {
  REWORK = 'REWORK',
  TRIVIAL_REBASE = 'TRIVIAL_REBASE',
  MERGE_FIRST_PARENT_UPDATE = 'MERGE_FIRST_PARENT_UPDATE',
  NO_CODE_CHANGE = 'NO_CODE_CHANGE',
  NO_CHANGE = 'NO_CHANGE',
}

/**
 * @desc The status of fixing the problem
 */
export enum ProblemInfoStatus {
  FIXED = 'FIXED',
  FIX_FAILED = 'FIX_FAILED',
}

/**
 * @desc The status of the file
 */
export enum FileInfoStatus {
  ADDED = 'A',
  DELETED = 'D',
  RENAMED = 'R',
  COPIED = 'C',
  REWRITTEN = 'W',
  // Modifed = 'M', // but API not set it if the file was modified
  UNMODIFIED = 'U', // Not returned by BE, but added by UI for certain files
}

/**
 * @desc The status of the file
 */
export enum GpgKeyInfoStatus {
  BAD = 'BAD',
  OK = 'OK',
  TRUSTED = 'TRUSTED',
}

/**
 * @desc Used for server config of accounts
 */
export enum DefaultDisplayNameConfig {
  USERNAME = 'USERNAME',
  FIRST_NAME = 'FIRST_NAME',
  FULL_NAME = 'FULL_NAME',
}

/**
 * @desc The state of the projects
 */
export enum ProjectState {
  ACTIVE = 'ACTIVE',
  READ_ONLY = 'READ_ONLY',
  HIDDEN = 'HIDDEN',
}

export enum Side {
  LEFT = 'left',
  RIGHT = 'right',
}

/**
 * The type in ConfigParameterInfo entity.
 * https://gerrit-review.googlesource.com/Documentation/rest-api-projects.html#config-parameter-info
 */
export enum ConfigParameterInfoType {
  // Should be kept in sync with
  // gerrit/java/com/google/gerrit/extensions/api/projects/ProjectConfigEntryType.java.
  STRING = 'STRING',
  INT = 'INT',
  LONG = 'LONG',
  BOOLEAN = 'BOOLEAN',
  LIST = 'LIST',
  ARRAY = 'ARRAY',
}

/**
 * All supported submit types.
 * https://gerrit-review.googlesource.com/Documentation/rest-api-projects.html#submit-type-info
 */
export enum SubmitType {
  MERGE_IF_NECESSARY = 'MERGE_IF_NECESSARY',
  FAST_FORWARD_ONLY = 'FAST_FORWARD_ONLY',
  REBASE_IF_NECESSARY = 'REBASE_IF_NECESSARY',
  REBASE_ALWAYS = 'REBASE_ALWAYS',
  MERGE_ALWAYS = 'MERGE_ALWAYS ',
  CHERRY_PICK = 'CHERRY_PICK',
  INHERIT = 'INHERIT',
}

/**
 * https://gerrit-review.googlesource.com/Documentation/rest-api-changes.html#mergeable-info
 */
export enum MergeStrategy {
  RECURSIVE = 'recursive',
  RESOLVE = 'resolve',
  SIMPLE_TWO_WAY_IN_CORE = 'simple-two-way-in-core',
  OURS = 'ours',
  THEIRS = 'theirs',
}

/*
 * Enum for possible configured value in InheritedBooleanInfo.
 * https://gerrit-review.googlesource.com/Documentation/rest-api-projects.html#inherited-boolean-info
 */
export enum InheritedBooleanInfoConfiguredValue {
  TRUE = 'TRUE',
  FALSE = 'FALSE',
  INHERITED = 'INHERITED',
}

export enum AccountTag {
  SERVICE_USER = 'SERVICE_USER',
}

/**
 * Enum for possible PermissionRuleInfo actions
 * https://gerrit-review.googlesource.com/Documentation/rest-api-access.html#permission-info
 */
export enum PermissionAction {
  ALLOW = 'ALLOW',
  DENY = 'DENY',
  BLOCK = 'BLOCK',
  // Special values for global capabilities
  INTERACTIVE = 'INTERACTIVE',
  BATCH = 'BATCH',
}

/**
 * This capability allows users to use the thread pool reserved for 'Non-Interactive Users'.
 * https://gerrit-review.googlesource.com/Documentation/access-control.html#capability_priority
 */
export enum UserPriority {
  BATCH = 'BATCH',
  INTERACTIVE = 'INTERACTIVE',
}

/**
 * Enum for all http methods used in Gerrit.
 */
export enum HttpMethod {
  HEAD = 'HEAD',
  POST = 'POST',
  GET = 'GET',
  DELETE = 'DELETE',
  PUT = 'PUT',
}

/**
 * The side on which the comment was added
 * https://gerrit-review.googlesource.com/Documentation/rest-api-changes.html#comment-info
 */
export enum CommentSide {
  REVISION = 'REVISION',
  PARENT = 'PARENT',
}

/**
 * Allowed app themes
 * https://gerrit-review.googlesource.com/Documentation/rest-api-accounts.html#preferences-input
 */
export enum AppTheme {
  DARK = 'DARK',
  LIGHT = 'LIGHT',
}

/**
 * Date formats in preferences
 * https://gerrit-review.googlesource.com/Documentation/rest-api-accounts.html#preferences-input
 */
export enum DateFormat {
  STD = 'STD',
  US = 'US',
  ISO = 'ISO',
  EURO = 'EURO',
  UK = 'UK',
}

/**
 * Time formats in preferences
 * https://gerrit-review.googlesource.com/Documentation/rest-api-accounts.html#preferences-input
 */
export enum TimeFormat {
  HHMM_12 = 'HHMM_12',
  HHMM_24 = 'HHMM_24',
}

/**
 * Diff type in preferences
 * https://gerrit-review.googlesource.com/Documentation/rest-api-accounts.html#preferences-input
 */
export enum DiffViewMode {
  SIDE_BY_SIDE = 'SIDE_BY_SIDE',
  UNIFIED = 'UNIFIED_DIFF',
}

/**
 * The type of email strategy to use.
 * https://gerrit-review.googlesource.com/Documentation/rest-api-accounts.html#preferences-input
 */
export enum EmailStrategy {
  ENABLED = 'ENABLED',
  CC_ON_OWN_COMMENTS = 'CC_ON_OWN_COMMENTS',
  ATTENTION_SET_ONLY = 'ATTENTION_SET_ONLY',
  DISABLED = 'DISABLED',
}

/**
 * The type of email format to use.
 * Doesn't mentioned in doc, but exists in Java class GeneralPreferencesInfo.
 */

export enum EmailFormat {
  PLAINTEXT = 'PLAINTEXT',
  HTML_PLAINTEXT = 'HTML_PLAINTEXT',
}

/**
 * The base which should be pre-selected in the 'Diff Against' drop-down list when the change screen is opened for a merge commit
 * https://gerrit-review.googlesource.com/Documentation/rest-api-accounts.html#preferences-input
 */
export enum DefaultBase {
  AUTO_MERGE = 'AUTO_MERGE',
  FIRST_PARENT = 'FIRST_PARENT',
}

/**
 * Whether whitespace changes should be ignored and if yes, which whitespace changes should be ignored
 * https://gerrit-review.googlesource.com/Documentation/rest-api-accounts.html#diff-preferences-input
 */
export enum IgnoreWhitespaceType {
  IGNORE_NONE = 'IGNORE_NONE',
  IGNORE_TRAILING = 'IGNORE_TRAILING',
  IGNORE_LEADING_AND_TRAILING = 'IGNORE_LEADING_AND_TRAILING',
  IGNORE_ALL = 'IGNORE_ALL',
}

/**
 * how draft comments are handled
 */
export enum DraftsAction {
  PUBLISH = 'PUBLISH',
  PUBLISH_ALL_REVISIONS = 'PUBLISH_ALL_REVISIONS',
  KEEP = 'KEEP',
}

export enum NotifyType {
  NONE = 'NONE',
  OWNER = 'OWNER',
  OWNER_REVIEWERS = 'OWNER_REVIEWERS',
  ALL = 'ALL',
}

/**
 * The authentication type that is configured on the server.
 * https://gerrit-review.googlesource.com/Documentation/rest-api-config.html#auth-info
 */
export enum AuthType {
  OPENID = 'OPENID',
  OPENID_SSO = 'OPENID_SSO',
  OAUTH = 'OAUTH',
  HTTP = 'HTTP',
  HTTP_LDAP = 'HTTP_LDAP',
  CLIENT_SSL_CERT_LDAP = 'CLIENT_SSL_CERT_LDAP',
  LDAP = 'LDAP',
  LDAP_BIND = 'LDAP_BIND',
  CUSTOM_EXTENSION = 'CUSTOM_EXTENSION',
  DEVELOPMENT_BECOME_ANY_ACCOUNT = 'DEVELOPMENT_BECOME_ANY_ACCOUNT',
}

/**
 * Controls visibility of other users' dashboard pages and completion suggestions to web users
 * https://gerrit-review.googlesource.com/Documentation/config-gerrit.html#accounts.visibility
 */
export enum AccountsVisibility {
  ALL = 'ALL',
  SAME_GROUP = 'SAME_GROUP',
  VISIBLE_GROUP = 'VISIBLE_GROUP',
  NONE = 'NONE',
}

/**
 * Account fields that are editable
 * https://gerrit-review.googlesource.com/Documentation/rest-api-config.html#auth-info
 */
export enum EditableAccountField {
  FULL_NAME = 'FULL_NAME',
  USER_NAME = 'USER_NAME',
  REGISTER_NEW_EMAIL = 'REGISTER_NEW_EMAIL',
}

/**
 * This setting determines when Gerrit computes if a change is mergeable or not.
 * https://gerrit-review.googlesource.com/Documentation/config-gerrit.html#change.mergeabilityComputationBehavior
 */
export enum MergeabilityComputationBehavior {
  API_REF_UPDATED_AND_CHANGE_REINDEX = 'API_REF_UPDATED_AND_CHANGE_REINDEX',
  REF_UPDATED_AND_CHANGE_REINDEX = 'REF_UPDATED_AND_CHANGE_REINDEX',
  NEVER = 'NEVER',
}
