// Copyright (C) 2021 The Android Open Source Project
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
package com.google.gerrit.server.notedb;

import static com.google.common.base.Preconditions.checkState;
import static com.google.gerrit.entities.ChangeMessage.ACCOUNT_TEMPLATE_REGEX;
import static com.google.gerrit.server.notedb.ChangeNoteUtil.FOOTER_ASSIGNEE;
import static com.google.gerrit.server.notedb.ChangeNoteUtil.FOOTER_ATTENTION;
import static com.google.gerrit.server.notedb.ChangeNoteUtil.FOOTER_LABEL;
import static com.google.gerrit.server.notedb.ChangeNoteUtil.FOOTER_REAL_USER;
import static com.google.gerrit.server.notedb.ChangeNoteUtil.FOOTER_SUBMITTED_WITH;
import static com.google.gerrit.server.notedb.ChangeNoteUtil.FOOTER_TAG;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.gerrit.common.UsedAt;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.HumanComment;
import com.google.gerrit.entities.PatchSetApproval;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.entities.SubmitRecord;
import com.google.gerrit.git.RefUpdateUtil;
import com.google.gerrit.server.ChangeMessagesUtil;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.account.externalids.ExternalId;
import com.google.gerrit.server.notedb.ChangeNoteUtil.CommitMessageRange;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.file.PackInserter;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.FooterLine;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.util.RawParseUtils;

/**
 * Rewrites ('backfills') commit history of change in NoteDb to not contain user data. Only fixes
 * known cases, rewriting commits case by case.
 *
 * <p>The cases where we used to put user data in NoteDb can be found by
 * https://gerrit-review.googlesource.com/q/hashtag:user-data-cleanup
 *
 * <p>As opposed to {@link NoteDbRewriter} implementations, which target a specific change and are
 * used by REST endpoints, this rewriter is used as standalone tool, that bulk backfills changes by
 * project.
 */
@UsedAt(UsedAt.Project.GOOGLE)
@Singleton
public class CommitRewriter {
  /** Options to run {@link #backfillProject}. */
  public static class RunOptions {
    /** Whether to rewrite the commit history or only find refs that need to be fixed. */
    public boolean dryRun = true;
    /**
     * Whether to verify that resulting commits contain user data for the accounts that are linked
     * to a change, see {@link #verifyCommit}, {@link #collectAccounts}.
     */
    public boolean verifyCommits = true;
    /** Whether to compute and output the diff of the commit history for the backfilled refs. */
    public boolean outputDiff = true;
  }

  /** Result of the backfill run for a project. */
  public static class BackfillResult {

    /** If the run for the project was successful. */
    public boolean ok;

    /**
     * Refs that were fixed by the run/ would be fixed if in --dry-run, together with their commit
     * history diff. Diff is empty if --output-diff is false.
     */
    public Map<String, List<String>> fixedRefDiff = new HashMap<>();

    /**
     * Refs that still contain user data after the backfill run. Only filled if --verify-commits,
     * see {@link #verifyCommit}
     */
    public List<String> refsStillInvalidAfterFix = new ArrayList<>();

    /** Refs, failed to backfill by the run. */
    public List<String> refsFailedToFix = new ArrayList<>();
  }

  private final ChangeNotes.Factory changeNotesFactory;
  private final AccountCache accountCache;
  private DiffAlgorithm diffAlgorithm = new HistogramDiff();

  @Inject
  public CommitRewriter(ChangeNotes.Factory changeNotesFactory, AccountCache accountCache) {
    this.changeNotesFactory = changeNotesFactory;
    this.accountCache = accountCache;
  }

  /**
   * Rewrites commit history of {@link RefNames#changeMetaRef}s in single {@code repo}. Only
   * rewrites branch if necessary, i.e. if there were any commits that contained user data.
   *
   * <p>See {@link RunOptions} for the execution and output options.
   *
   * @param project project to backfill
   * @param repo repo to backfill
   * @param options {@link RunOptions} to control how the run is executed.
   * @return BackfillResult
   */
  public BackfillResult backfillProject(
      Project.NameKey project, Repository repo, RunOptions options) {
    BackfillResult result = new BackfillResult();
    result.ok = true;
    try (RevWalk revWalk = new RevWalk(repo);
        ObjectInserter ins = newPackInserter(repo)) {
      BatchRefUpdate bru = repo.getRefDatabase().newBatchUpdate();
      bru.setAllowNonFastForwards(true);
      for (Ref ref : repo.getRefDatabase().getRefsByPrefix(RefNames.REFS_CHANGES)) {
        Change.Id changeId = Change.Id.fromRef(ref.getName());
        if (changeId == null || !ref.getName().equals(RefNames.changeMetaRef(changeId))) {
          continue;
        }

        ChangeNotes changeNotes = changeNotesFactory.create(project, changeId);
        ImmutableSet<AccountState> accountsInChange =
            options.verifyCommits ? collectAccounts(changeNotes) : ImmutableSet.of();
        try {
          ChangeFixProgress changeFixProgress =
              backfillChange(revWalk, ins, ref, accountsInChange, options);
          if (changeFixProgress.anyFixesApplied) {
            bru.addCommand(
                new ReceiveCommand(ref.getObjectId(), changeFixProgress.newTipId, ref.getName()));
            result.fixedRefDiff.put(ref.getName(), changeFixProgress.commitDiffs);
          }

          if (!changeFixProgress.isValidAfterFix) {
            result.refsStillInvalidAfterFix.add(ref.getName());
          }
        } catch (ConfigInvalidException | IOException e) {
          result.refsFailedToFix.add(ref.getName());
        }
      }

      if (!bru.getCommands().isEmpty()) {
        if (!options.dryRun) {
          ins.flush();
          RefUpdateUtil.executeChecked(bru, revWalk);
        }
      }
    } catch (IOException e) {
      result.ok = false;
    }

    return result;
  }

  /**
   * Retrieves accounts, that are associated with a change (e.g. reviewers, commenters, etc.). These
   * accounts are used to verify that commits do not contain user data. See {@link #verifyCommit}
   *
   * @param changeNotes {@link ChangeNotes} of the change to retrieve associated accounts from.
   * @return {@link AccountState} of accounts, that are associated with the change.
   */
  private ImmutableSet<AccountState> collectAccounts(ChangeNotes changeNotes) {
    Set<Account.Id> accounts = new HashSet<>();
    accounts.add(changeNotes.getChange().getOwner());
    for (PatchSetApproval patchSetApproval : changeNotes.getApprovals().values()) {
      accounts.add(patchSetApproval.accountId());
      accounts.add(patchSetApproval.realAccountId());
    }
    accounts.addAll(changeNotes.getAllPastReviewers());
    accounts.addAll(changeNotes.getPastAssignees());
    changeNotes
        .getAttentionSetUpdates()
        .forEach(attentionSetUpdate -> accounts.add(attentionSetUpdate.account()));
    for (SubmitRecord submitRecord : changeNotes.getSubmitRecords()) {
      accounts.addAll(
          submitRecord.labels.stream()
              .map(label -> label.appliedBy)
              .filter(Objects::nonNull)
              .collect(Collectors.toSet()));
    }
    for (HumanComment comment : changeNotes.getHumanComments().values()) {
      accounts.add(comment.author.getId());
      accounts.add(comment.getRealAuthor().getId());
    }
    return ImmutableSet.copyOf(accountCache.get(accounts).values());
  }

  /** Verifies that the commit does not contain user data of accounts in {@code accounts}. */
  private boolean verifyCommit(
      String commitMessage, PersonIdent author, Collection<AccountState> accounts) {
    for (AccountState accountState : accounts) {
      Account account = accountState.account();
      if (commitMessage.contains(account.getName())) {
        return false;
      }
      if (account.fullName() != null && commitMessage.contains(account.fullName())) {
        return false;
      }
      if (account.displayName() != null && commitMessage.contains(account.displayName())) {
        return false;
      }
      if (account.preferredEmail() != null && commitMessage.contains(account.preferredEmail())) {
        return false;
      }
      if (accountState.userName().isPresent()
          && commitMessage.contains(accountState.userName().get())) {
        return false;
      }
      Stream<String> allEmails =
          accountState.externalIds().stream().map(ExternalId::email).filter(Objects::nonNull);
      if (allEmails.anyMatch(email -> commitMessage.contains(email))) {
        return false;
      }
      if (author.toString().contains(account.getName())) {
        return false;
      }
    }
    return true;
  }

  /**
   * Walks the ref history from oldest update to the most recent update, fixing the commits that
   * contain user data case by case. Commit history is rewritten from the first commit, that needs
   * to be updated, for all subsequent updates. The new ref tip is returned in {@link
   * ChangeFixProgress#newTipId}.
   */
  public ChangeFixProgress backfillChange(
      RevWalk revWalk,
      ObjectInserter inserter,
      Ref ref,
      ImmutableSet<AccountState> accountsInChange,
      RunOptions options)
      throws IOException, ConfigInvalidException {

    ObjectId oldTip = ref.getObjectId();
    // Walk from the first commit of the branch.
    revWalk.reset();
    revWalk.markStart(revWalk.parseCommit(oldTip));
    revWalk.sort(RevSort.TOPO);

    revWalk.sort(RevSort.REVERSE);

    RevCommit originalCommit;

    boolean rewriteStarted = false;
    ChangeFixProgress changeFixProgress = new ChangeFixProgress();
    while ((originalCommit = revWalk.next()) != null) {

      changeFixProgress.updateAuthorId = parseIdent(originalCommit.getAuthorIdent());
      PersonIdent fixedAuthorIdent =
          getFixedIdent(originalCommit.getAuthorIdent(), changeFixProgress.updateAuthorId);
      Optional<String> fixedCommitMessage = fixedCommitMessage(originalCommit, changeFixProgress);
      String commitMessage =
          fixedCommitMessage.isPresent()
              ? fixedCommitMessage.get()
              : originalCommit.getFullMessage();
      if (options.verifyCommits) {
        changeFixProgress.isValidAfterFix &=
            verifyCommit(commitMessage, fixedAuthorIdent, accountsInChange);
      }
      boolean needsFix =
          !fixedAuthorIdent.equals(originalCommit.getAuthorIdent())
              || fixedCommitMessage.isPresent();

      if (!rewriteStarted && !needsFix) {
        changeFixProgress.newTipId = originalCommit;
        continue;
      }
      rewriteStarted = true;
      changeFixProgress.anyFixesApplied = true;
      CommitBuilder cb = new CommitBuilder();
      if (changeFixProgress.newTipId != null) {
        cb.setParentId(changeFixProgress.newTipId);
      }
      cb.setTreeId(originalCommit.getTree());
      cb.setMessage(commitMessage);
      cb.setAuthor(fixedAuthorIdent);
      cb.setCommitter(originalCommit.getCommitterIdent());
      cb.setEncoding(originalCommit.getEncoding());
      byte[] newCommitContent = cb.build();
      checkCommitModification(originalCommit, newCommitContent);
      changeFixProgress.newTipId = inserter.insert(Constants.OBJ_COMMIT, newCommitContent);
      // Only compute diff if the content of the commit was actually changed.
      if (options.outputDiff && needsFix) {
        String diff = computeDiff(originalCommit.getRawBuffer(), newCommitContent);
        checkState(
            !Strings.isNullOrEmpty(diff),
            "Expected diff for commit %s of ref %s",
            originalCommit.getId(),
            ref.getName());
        changeFixProgress.commitDiffs.add(diff);
      }
    }
    return changeFixProgress;
  }

  /**
   * In NoteDb, all the meta information is stored in footer lines. If we accidentally drop some of
   * the footer lines, the original meta information will be lost, and the change might become
   * unparsable.
   *
   * <p>While we can not verify the entire commit content, we at least make sure that the resulting
   * commit has the same author, committer and footer lines are in the same order and contain same
   * footer keys as the original commit.
   *
   * <p>Commit message and footer values might have been rewritten.
   */
  private void checkCommitModification(RevCommit originalCommit, byte[] newCommitContent)
      throws IOException {
    RevCommit newCommit = RevCommit.parse(newCommitContent);
    PersonIdent newAuthorIdent = newCommit.getAuthorIdent();
    PersonIdent originalAuthorIdent = originalCommit.getAuthorIdent();
    // The new commit must have same author and committer ident as the original commit.
    if (!verifyPersonIdent(newAuthorIdent, originalAuthorIdent)) {
      throw new IllegalStateException(
          String.format(
              "New author %s does not match original author %s",
              newAuthorIdent.toExternalString(), originalAuthorIdent.toExternalString()));
    }
    PersonIdent newCommitterIdent = newCommit.getCommitterIdent();
    PersonIdent originalCommitterIdent = originalCommit.getCommitterIdent();
    if (!verifyPersonIdent(newCommitterIdent, originalCommitterIdent)) {
      throw new IllegalStateException(
          String.format(
              "New committer %s does not match original committer %s",
              newCommitterIdent.toExternalString(), originalCommitterIdent.toExternalString()));
    }

    List<FooterLine> newFooterLines = newCommit.getFooterLines();
    List<FooterLine> originalFooterLines = originalCommit.getFooterLines();
    // Number and order of footer lines must remain the same, the value may have changed.
    if (newFooterLines.size() != originalFooterLines.size()) {
      String diff = computeDiff(originalCommit.getRawBuffer(), newCommitContent);
      throw new IllegalStateException(
          String.format(
              "Expected footer lines in new commit to match original footer lines. Diff %s", diff));
    }
    for (int i = 0; i < newFooterLines.size(); i++) {
      FooterLine newFooterLine = newFooterLines.get(i);
      FooterLine originalFooterLine = originalFooterLines.get(i);
      if (!newFooterLine.getKey().equals(originalFooterLine.getKey())) {
        String diff = computeDiff(originalCommit.getRawBuffer(), newCommitContent);
        throw new IllegalStateException(
            String.format(
                "Expected footer lines in new commit to match original footer lines. Diff %s",
                diff));
      }
    }
  }

  private boolean verifyPersonIdent(PersonIdent newIdent, PersonIdent originalIdent) {
    return newIdent.getTimeZoneOffset() == originalIdent.getTimeZoneOffset()
        && newIdent.getWhen().equals(originalIdent.getWhen())
        && newIdent.getEmailAddress().equals(originalIdent.getEmailAddress());
  }

  private Optional<String> fixAssigneeChangeMessage(
      Account.Id oldAssignee, Account.Id newAssignee, String originalChangeMessage) {
    if (Strings.isNullOrEmpty(originalChangeMessage)) {
      return Optional.empty();
    }
    Pattern assigneeDeletedPattern = Pattern.compile("Assignee deleted: (.*)");
    Matcher assigneeDeletedMatcher = assigneeDeletedPattern.matcher(originalChangeMessage);
    if (assigneeDeletedMatcher.matches()) {
      if (!assigneeDeletedMatcher.group(1).matches(ACCOUNT_TEMPLATE_REGEX)) {
        return Optional.of(
            "Assignee deleted: " + ChangeMessagesUtil.getAccountTemplate(oldAssignee));
      }
      return Optional.empty();
    }
    Pattern assigneeAddedPattern = Pattern.compile("Assignee added: (.*)");
    Matcher assigneeAddedMatcher = assigneeAddedPattern.matcher(originalChangeMessage);
    if (assigneeAddedMatcher.matches()) {
      if (!assigneeAddedMatcher.group(1).matches(ACCOUNT_TEMPLATE_REGEX)) {
        return Optional.of("Assignee added: " + ChangeMessagesUtil.getAccountTemplate(newAssignee));
      }
      return Optional.empty();
    }
    Pattern assigneeChangedPattern = Pattern.compile("Assignee changed from: (.*) to: (.*)");
    Matcher assigneeChangedMatcher = assigneeChangedPattern.matcher(originalChangeMessage);
    if (assigneeChangedMatcher.matches()) {
      if (!assigneeChangedMatcher.group(1).matches(ACCOUNT_TEMPLATE_REGEX)) {
        return Optional.of(
            String.format(
                "Assignee changed from: %s to: %s",
                ChangeMessagesUtil.getAccountTemplate(oldAssignee),
                ChangeMessagesUtil.getAccountTemplate(newAssignee)));
      }
      return Optional.empty();
    }
    return Optional.empty();
  }

  private Optional<String> fixReviewerChangeMessage(String originalChangeMessage) {
    if (Strings.isNullOrEmpty(originalChangeMessage)) {
      return Optional.empty();
    }
    Pattern removedReviewer = Pattern.compile("Removed (cc|reviewer) (.*) .*");
    Matcher matcher = removedReviewer.matcher(originalChangeMessage);
    if (matcher.matches() && !matcher.group(2).matches(ACCOUNT_TEMPLATE_REGEX)) {
      // Since we do not use change messages for reviewer updates on UI, it does not matter what we
      // rewrite it to.
      return Optional.of(originalChangeMessage.substring(0, matcher.end(1)));
    }
    return Optional.empty();
  }

  private Optional<String> fixRemoveVoteChangeMessage(
      Account.Id reviewer, String originalChangeMessage) {
    if (Strings.isNullOrEmpty(originalChangeMessage)) {
      return Optional.empty();
    }
    Pattern removedVotePattern = Pattern.compile("Removed (.*) by (.*)");
    Matcher matcher = removedVotePattern.matcher(originalChangeMessage);
    if (matcher.matches() && !matcher.group(2).matches(ACCOUNT_TEMPLATE_REGEX)) {
      return Optional.of(
          String.format(
              "Removed %s by %s",
              matcher.group(1), ChangeMessagesUtil.getAccountTemplate(reviewer)));
    }
    return Optional.empty();
  }

  private Optional<String> fixDeleteChangeMessageCommitMessage(String originalChangeMessage) {
    if (Strings.isNullOrEmpty(originalChangeMessage)) {
      return Optional.empty();
    }
    Pattern removedChangeMessage =
        Pattern.compile("Change message removed by: (.*)(\nReason: .*)?");
    Matcher matcher = removedChangeMessage.matcher(originalChangeMessage);
    if (matcher.matches() && !matcher.group(1).matches(ACCOUNT_TEMPLATE_REGEX)) {
      String fixedMessage = "Change message removed";
      if (matcher.group(2) != null) {
        fixedMessage += matcher.group(2);
      }
      return Optional.of(fixedMessage);
    }
    return Optional.empty();
  }

  private Optional<String> fixSubmitChangeMessage(String originalChangeMessage) {
    if (Strings.isNullOrEmpty(originalChangeMessage)) {
      return Optional.empty();
    }
    Pattern submittedPattern = Pattern.compile("Change has been successfully (.*) by (.*)");
    Matcher matcher = submittedPattern.matcher(originalChangeMessage);
    if (matcher.matches()) {
      // See https://gerrit-review.googlesource.com/c/gerrit/+/272654
      return Optional.of(originalChangeMessage.substring(0, matcher.end(1)));
    }
    return Optional.empty();
  }

  /**
   * Rewrites a code owners change message.
   *
   * @param originalMessage the original change message
   * @return the updated change message
   */
  private Optional<String> fixCodeOwnersChangeMessage(String originalMessage) {
    // TODO(mariasavtchouk): backfill this case
    return Optional.empty();
  }

  /**
   * Fixes commit body case by case, so it does not contain user data. Returns fixed commit message,
   * or {@link Optional#empty} if no fixes were applied.
   */
  private Optional<String> fixedCommitMessage(RevCommit revCommit, ChangeFixProgress fixProgress)
      throws ConfigInvalidException {
    byte[] raw = revCommit.getRawBuffer();
    Charset enc = RawParseUtils.parseEncoding(raw);
    Optional<CommitMessageRange> commitMessageRange =
        ChangeNoteUtil.parseCommitMessageRange(revCommit);
    if (!commitMessageRange.isPresent()) {
      throw new ConfigInvalidException("Failed to parse commit message " + revCommit.getName());
    }
    String changeSubject =
        RawParseUtils.decode(
            enc,
            raw,
            commitMessageRange.get().subjectStart(),
            commitMessageRange.get().subjectEnd());
    Optional<String> fixedChangeMessage = Optional.empty();
    String originalChangeMessage = null;
    if (commitMessageRange.isPresent() && commitMessageRange.get().hasChangeMessage()) {
      originalChangeMessage =
          RawParseUtils.decode(
                  enc,
                  raw,
                  commitMessageRange.get().changeMessageStart(),
                  commitMessageRange.get().changeMessageEnd() + 1)
              .trim();
    }
    List<FooterLine> footerLines = revCommit.getFooterLines();
    StringBuilder footerLinesBuilder = new StringBuilder();
    boolean anyFootersFixed = false;
    for (FooterLine fl : footerLines) {
      String footerKey = fl.getKey();
      String footerValue = fl.getValue();
      if (footerKey.equals(FOOTER_TAG.getName())) {
        if (footerValue.equals(ChangeMessagesUtil.TAG_MERGED)) {
          fixedChangeMessage = fixSubmitChangeMessage(originalChangeMessage);
        }
      } else if (footerKey.equalsIgnoreCase(FOOTER_ASSIGNEE.getName())) {
        Account.Id oldAssignee = fixProgress.assigneeId;
        FixIdentResult fixedAssignee = null;
        if (footerValue.equals("")) {
          fixProgress.assigneeId = null;
        } else {
          fixedAssignee = getFixedIdentString(footerValue);
          fixProgress.assigneeId = fixedAssignee.accountId;
        }
        fixedChangeMessage =
            fixAssigneeChangeMessage(oldAssignee, fixProgress.assigneeId, originalChangeMessage);
        if (fixedAssignee != null && fixedAssignee.fixedIdentString.isPresent()) {
          addFooter(footerLinesBuilder, footerKey, fixedAssignee.fixedIdentString.get());
          anyFootersFixed = true;
          continue;
        }
      } else if (Arrays.stream(ReviewerStateInternal.values())
          .filter(state -> footerKey.equalsIgnoreCase(state.getFooterKey().getName()))
          .findAny()
          .isPresent()) {
        fixedChangeMessage = fixReviewerChangeMessage(originalChangeMessage);
        FixIdentResult fixedReviewer = getFixedIdentString(footerValue);
        if (fixedReviewer.fixedIdentString.isPresent()) {
          addFooter(footerLinesBuilder, footerKey, fixedReviewer.fixedIdentString.get());
          anyFootersFixed = true;
          continue;
        }
      } else if (footerKey.equalsIgnoreCase(FOOTER_REAL_USER.getName())) {
        FixIdentResult fixedRealUser = getFixedIdentString(footerValue);
        if (fixedRealUser.fixedIdentString.isPresent()) {
          addFooter(footerLinesBuilder, footerKey, fixedRealUser.fixedIdentString.get());
          anyFootersFixed = true;
          continue;
        }
      } else if (footerKey.equalsIgnoreCase(FOOTER_LABEL.getName())) {
        int voterIdentStart = footerValue.indexOf(' ');
        FixIdentResult fixedVoter = null;
        if (voterIdentStart > 0) {
          String originalIdentString = footerValue.substring(voterIdentStart + 1);
          fixedVoter = getFixedIdentString(originalIdentString);
        }
        fixedChangeMessage =
            fixRemoveVoteChangeMessage(
                fixedVoter == null ? fixProgress.updateAuthorId : fixedVoter.accountId,
                originalChangeMessage);
        if (fixedVoter != null && fixedVoter.fixedIdentString.isPresent()) {
          String fixedLabelVote =
              footerValue.substring(0, voterIdentStart) + " " + fixedVoter.fixedIdentString.get();
          addFooter(footerLinesBuilder, footerKey, fixedLabelVote);
          anyFootersFixed = true;
          continue;
        }
      } else if (footerKey.equalsIgnoreCase(FOOTER_SUBMITTED_WITH.getName())) {
        // TODO(mariasavtchouk): backfill this case

      } else if (footerKey.equalsIgnoreCase(FOOTER_ATTENTION.getName())) {
        // TODO(mariasavtchouk): backfill this case
      }
      addFooter(footerLinesBuilder, footerKey, footerValue);
    }

    if (!fixedChangeMessage.isPresent()) {
      fixedChangeMessage = fixDeleteChangeMessageCommitMessage(originalChangeMessage);
    }
    if (!fixedChangeMessage.isPresent()) {
      fixedChangeMessage = fixCodeOwnersChangeMessage(originalChangeMessage);
    }
    if (!anyFootersFixed && !fixedChangeMessage.isPresent()) {
      return Optional.empty();
    }
    StringBuilder fixedCommitBuilder = new StringBuilder();
    fixedCommitBuilder.append(changeSubject);
    fixedCommitBuilder.append("\n\n");
    if (commitMessageRange.get().hasChangeMessage()) {
      fixedCommitBuilder.append(
          fixedChangeMessage.isPresent() ? fixedChangeMessage.get() : originalChangeMessage);
      fixedCommitBuilder.append("\n\n");
    }
    fixedCommitBuilder.append(footerLinesBuilder);
    return Optional.of(fixedCommitBuilder.toString());
  }

  private static StringBuilder addFooter(StringBuilder sb, String footer, String value) {
    sb.append(footer).append(":");
    if (!Strings.isNullOrEmpty(value)) {
      sb.append(" ").append(value);
    }
    sb.append('\n');
    return sb;
  }

  private Account.Id parseIdent(PersonIdent ident) throws ConfigInvalidException {
    return NoteDbUtil.parseIdent(ident)
        .orElseThrow(
            () -> new ConfigInvalidException("field to parse id: " + ident.getEmailAddress()));
  }

  /**
   * Fixes {@code originalIdent} so it does not contain user data, see {@link
   * ChangeNoteUtil#getAccountIdAsUsername}.
   */
  private PersonIdent getFixedIdent(PersonIdent originalIdent, Account.Id identAccount) {
    return new PersonIdent(
        ChangeNoteUtil.getAccountIdAsUsername(identAccount),
        originalIdent.getEmailAddress(),
        originalIdent.getWhen(),
        originalIdent.getTimeZone());
  }

  /**
   * Parses {@code originalIdentString} and applies the fix, so it does not contain user data, see
   * {@link ChangeNoteUtil#appendAccountIdIdentString}.
   *
   * @param originalIdentString ident to apply the fix to.
   * @return {@link FixIdentResult}, with {@link FixIdentResult#accountId} parsed from {@code
   *     originalIdentString} and {@link FixIdentResult#fixedIdentString} if the fix was applied.
   * @throws ConfigInvalidException if could not parse {@link FixIdentResult#accountId} from {@code
   *     originalIdentString}
   */
  private FixIdentResult getFixedIdentString(String originalIdentString)
      throws ConfigInvalidException {
    FixIdentResult fixIdentResult = new FixIdentResult();
    PersonIdent originalIdent = RawParseUtils.parsePersonIdent(originalIdentString);
    fixIdentResult.accountId = parseIdent(originalIdent);
    String fixedIdentString =
        ChangeNoteUtil.formatAccountIdentString(
            fixIdentResult.accountId, originalIdent.getEmailAddress());
    fixIdentResult.fixedIdentString =
        fixedIdentString.equals(originalIdentString)
            ? Optional.empty()
            : Optional.of(fixedIdentString);
    return fixIdentResult;
  }

  /**
   * Cuts tree and parent lines from raw unparsed commit body, so they are not included in diff
   * comparison.
   *
   * @param b raw unparsed commit body, see {@link RevCommit#getRawBuffer()}.
   *     <p>For parsing, see {@link RawParseUtils#author}, {@link RawParseUtils#commitMessage}, etc.
   * @return raw unparsed commit body, without tree and parent lines.
   */
  public static byte[] cutTreeAndParents(byte[] b) {
    final int sz = b.length;
    int ptr = 46; // skip the "tree ..." line.
    while (ptr < sz && b[ptr] == 'p') ptr += 48; // skip this parent.
    return Arrays.copyOfRange(b, ptr, b.length + 1);
  }

  private String computeDiff(byte[] oldCommit, byte[] newCommit) throws IOException {
    RawText oldBody = new RawText(cutTreeAndParents(oldCommit));
    RawText newBody = new RawText(cutTreeAndParents(newCommit));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    EditList diff = diffAlgorithm.diff(RawTextComparator.DEFAULT, oldBody, newBody);
    try (DiffFormatter fmt = new DiffFormatter(out)) {
      // Do not show any unchanged lines, since it is not interesting
      fmt.setContext(0);
      fmt.format(diff, oldBody, newBody);
      fmt.flush();
      return out.toString();
    }
  }

  private static ObjectInserter newPackInserter(Repository repo) {
    if (!(repo instanceof FileRepository)) {
      return repo.newObjectInserter();
    }
    PackInserter ins = ((FileRepository) repo).getObjectDatabase().newPackInserter();
    ins.checkExisting(false);
    return ins;
  }

  /**
   * Parsed and fixed {@link PersonIdent} string, formatted as {@link
   * ChangeNoteUtil#appendAccountIdIdentString}
   */
  private static class FixIdentResult {

    /** {@link com.google.gerrit.entities.Account.Id} parsed from PersonIdent string. */
    Account.Id accountId;
    /**
     * Fixed ident string, that does not contain user data, or {@link Optional#empty} if fix was not
     * required.
     */
    Optional<String> fixedIdentString;
  }

  /**
   * Holds the state of change rewrite progress. Rewrite goes from the oldest commit to the most
   * recent update.
   */
  private static class ChangeFixProgress {
    /** Assignee at current commit update. */
    Account.Id assigneeId = null;

    /** Author of the current commit update. */
    Account.Id updateAuthorId = null;

    /** Id of the current commit in rewriter walk. */
    ObjectId newTipId = null;
    /** If any commits were rewritten by the rewriter. */
    boolean anyFixesApplied = false;

    /**
     * Whether all commits seen by the rewriter with the fixes applied passed the verification, see
     * {@link #verifyCommit}.
     */
    boolean isValidAfterFix = true;

    List<String> commitDiffs = new ArrayList<>();
  }
}
