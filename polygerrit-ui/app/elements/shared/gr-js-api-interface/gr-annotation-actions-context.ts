/**
 * @license
 * Copyright (C) 2017 The Android Open Source Project
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

import {GrAnnotation} from '../../diff/gr-diff-highlight/gr-annotation';
import {GrStyleObject} from '../../plugins/gr-styles-api/gr-styles-api';
import {PatchSetNum} from '../../../types/common';
import {GrDiffLine} from '../../diff/gr-diff/gr-diff-line';

/**
 * Used to create a context for GrAnnotationActionsInterface.
 *
 * @param contentEl The DIV.contentText element of the line
 * content to apply the annotation to using annotateRange.
 * @param lineNumberEl The TD element of the line number to
 * apply the annotation to using annotateLineNumber.
 * @param line The line object.
 * @param path The file path (eg: /COMMIT_MSG').
 * @param changeNum The Gerrit change number.
 * @param patchNum The Gerrit patch number.
 */
export class GrAnnotationActionsContext {
  private _contentEl: HTMLElement;

  private _lineNumberEl: HTMLElement;

  line: GrDiffLine;

  path: string;

  changeNum: number;

  patchNum: number;

  constructor(
    contentEl: HTMLElement,
    lineNumberEl: HTMLElement,
    line: GrDiffLine,
    path: string,
    changeNum: string,
    patchNum: PatchSetNum
  ) {
    this._contentEl = contentEl;
    this._lineNumberEl = lineNumberEl;

    this.line = line;
    this.path = path;
    this.changeNum = Number(changeNum);
    this.patchNum = Number(patchNum);
    if (isNaN(this.changeNum) || isNaN(this.patchNum)) {
      console.error('invalid parameters');
    }
  }

  /**
   * Method to add annotations to a content line.
   *
   * @param offset The char offset where the update starts.
   * @param length The number of chars that the update covers.
   * @param styleObject The style object for the range.
   * @param side The side of the update. ('left' or 'right')
   */
  annotateRange(
    offset: number,
    length: number,
    styleObject: GrStyleObject,
    side: string
  ) {
    if (this._contentEl && this._contentEl.getAttribute('data-side') === side) {
      GrAnnotation.annotateElement(
        this._contentEl,
        offset,
        length,
        styleObject.getClassName(this._contentEl)
      );
    }
  }

  /**
   * Method to add a CSS class to the line number TD element.
   *
   * @param styleObject The style object for the range.
   * @param side The side of the update. ('left' or 'right')
   */
  annotateLineNumber(styleObject: GrStyleObject, side: string) {
    if (this._lineNumberEl && this._lineNumberEl.classList.contains(side)) {
      styleObject.apply(this._lineNumberEl);
    }
  }
}
