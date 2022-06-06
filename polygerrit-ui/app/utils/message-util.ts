/**
 * @license
 * Copyright (C) 2021 The Android Open Source Project
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

import {MessageTag} from '../constants/constants';
import {ChangeId, ChangeMessageInfo} from '../types/common';

function getRevertChangeIdFromMessage(msg: ChangeMessageInfo): ChangeId {
  const REVERT_REGEX = /^Created a revert of this change as (.*)$/;
  const changeId = msg.message.match(REVERT_REGEX)?.[1];
  if (!changeId) throw new Error('revert changeId not found');
  return changeId as ChangeId;
}

export function getRevertCreatedChangeIds(messages: ChangeMessageInfo[]) {
  try {
    return messages
      .filter(m => m.tag === MessageTag.TAG_REVERT)
      .map(m => getRevertChangeIdFromMessage(m));
  }
  catch(err) {
    return [];
  }
}
