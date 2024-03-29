// Copyright (C) 2017 The Android Open Source Project
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

package com.google.gerrit.server.index.group;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gerrit.entities.AccountGroup;

/** Interface for indexing an internal Gerrit group. */
public interface GroupIndexer {

  /**
   * Synchronously index a group.
   *
   * @param uuid group UUID to index.
   */
  void index(AccountGroup.UUID uuid);

  /**
   * Synchronously reindex a group if it is stale.
   *
   * @param uuid group UUID to index.
   * @return whether the group was reindexed
   */
  @CanIgnoreReturnValue
  boolean reindexIfStale(AccountGroup.UUID uuid);
}
