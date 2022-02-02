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
import '@polymer/iron-selector/iron-selector';
import '../../shared/gr-button/gr-button';
import {sharedStyles} from '../../../styles/shared-styles';
import {css, html, nothing, LitElement} from 'lit';
import {customElement, property, query, state} from 'lit/decorators';
import {ifDefined} from 'lit/directives/if-defined';
import {IronSelectorElement} from '@polymer/iron-selector/iron-selector';
import {
  LabelNameToValueMap,
  LabelNameToInfoMap,
  QuickLabelInfo,
  DetailedLabelInfo,
} from '../../../types/common';
import {assertIsDefined, hasOwnProperty} from '../../../utils/common-util';

export interface Label {
  name: string;
  value: string | null;
}

// TODO(TS): add description to explain what this is after moving
// gr-label-scores to ts
export interface LabelValuesMap {
  [key: number]: number;
}

declare global {
  interface HTMLElementTagNameMap {
    'gr-label-score-row': GrLabelScoreRow;
  }
}

@customElement('gr-label-score-row')
export class GrLabelScoreRow extends LitElement {
  /**
   * Fired when any label is changed.
   *
   * @event labels-changed
   */

  @query('#labelSelector')
  labelSelector?: IronSelectorElement;

  @property({type: Object})
  label: Label | undefined | null;

  @property({type: Object})
  labels?: LabelNameToInfoMap;

  @property({type: String, reflect: true})
  name?: string;

  @property({type: Object})
  permittedLabels: LabelNameToValueMap | undefined | null;

  @property({type: Object})
  labelValues?: LabelValuesMap;

  @state()
  private selectedValueText = 'No value selected';

  static override get styles() {
    return [
      sharedStyles,
      css`
        .labelNameCell,
        .buttonsCell,
        .selectedValueCell {
          padding: var(--spacing-s) var(--spacing-m);
          display: table-cell;
        }
        /* We want the :hover highlight to extend to the border of the dialog. */
        .labelNameCell {
          padding-left: var(--spacing-xl);
        }
        .selectedValueCell {
          padding-right: var(--spacing-xl);
        }
        /* This is a trick to let the selectedValueCell take the remaining width. */
        .labelNameCell,
        .buttonsCell {
          white-space: nowrap;
        }
        .selectedValueCell {
          width: 75%;
        }
        .labelMessage {
          color: var(--deemphasized-text-color);
        }
        gr-button {
          min-width: 42px;
          box-sizing: border-box;
        }
        gr-button::part(paper-button) {
          background-color: var(
            --button-background-color,
            var(--table-header-background-color)
          );
          padding: 0 var(--spacing-m);
        }
        gr-button[vote='max'].iron-selected {
          --button-background-color: var(--vote-color-approved);
        }
        gr-button[vote='positive'].iron-selected {
          --button-background-color: var(--vote-color-recommended);
        }
        gr-button[vote='min'].iron-selected {
          --button-background-color: var(--vote-color-rejected);
        }
        gr-button[vote='negative'].iron-selected {
          --button-background-color: var(--vote-color-disliked);
        }
        gr-button[vote='neutral'].iron-selected {
          --button-background-color: var(--vote-color-neutral);
        }
        gr-button[vote='positive'].iron-selected::part(paper-button) {
          border-color: var(--vote-outline-recommended);
        }
        gr-button[vote='negative'].iron-selected::part(paper-button) {
          border-color: var(--vote-outline-disliked);
        }
        gr-button > gr-tooltip-content {
          margin: 0px -10px;
          padding: 0px 10px;
        }
        .placeholder {
          display: inline-block;
          width: 42px;
          height: 1px;
        }
        .placeholder::before {
          content: ' ';
        }
        .selectedValueCell {
          color: var(--deemphasized-text-color);
          font-style: italic;
        }
        .selectedValueCell.hidden {
          display: none;
        }
        @media only screen and (max-width: 50em) {
          .selectedValueCell {
            display: none;
          }
        }
      `,
    ];
  }

  override render() {
    return html`
      <span class="labelNameCell" id="labelName" aria-hidden="true"
        >${this.label?.name ?? ''}</span
      >
      ${this.renderButtonsCell()} ${this.renderSelectedValue()}
    `;
  }

  private renderButtonsCell() {
    return html`
      <div class="buttonsCell">
        ${this.renderBlankItems('start')} ${this.renderLabelSelector()}
        ${this.renderBlankItems('end')}
        ${!this._computeAnyPermittedLabelValues()
          ? html` <span class="labelMessage">
              You don't have permission to edit this label.
            </span>`
          : nothing}
      </div>
    `;
  }

  private renderBlankItems(position: string) {
    const blankItems = this._computeBlankItems(position);
    return blankItems.map(
      _value => html`
        <span class="placeholder" data-label="${this.label?.name ?? ''}">
        </span>
      `
    );
  }

  private renderLabelSelector() {
    return html`
      <iron-selector
        id="labelSelector"
        attr-for-selected="data-value"
        ?hidden="${!this._computeAnyPermittedLabelValues()}"
        selected="${ifDefined(this._computeLabelValue())}"
        @selected-item-changed=${this.setSelectedValueText}
        role="radiogroup"
        aria-labelledby="labelName"
      >
        ${this.renderPermittedLabels()}
      </iron-selector>
    `;
  }

  private renderPermittedLabels() {
    const items = this.computePermittedLabelValues();
    return items.map(
      (value, index) => html`
        <gr-button
          role="radio"
          vote="${this._computeVoteAttribute(
            Number(value),
            index,
            items.length
          )}"
          title="${ifDefined(this.computeLabelValueTitle(value))}"
          data-name="${ifDefined(this.label?.name)}"
          data-value="${value}"
          aria-label="${value}"
          voteChip
        >
          <gr-tooltip-content
            has-tooltip
            light-tooltip
            title="${ifDefined(this.computeLabelValueTitle(value))}"
          >
            ${value}
          </gr-tooltip-content>
        </gr-button>
      `
    );
  }

  private renderSelectedValue() {
    return html`
      <div class="selectedValueCell ${this.computeHiddenClass()}">
        <span id="selectedValueLabel">${this.selectedValueText}</span>
      </div>
    `;
  }

  get selectedItem(): IronSelectorElement | undefined {
    if (!this.labelSelector) {
      return undefined;
    }
    return this.labelSelector.selectedItem as IronSelectorElement;
  }

  get selectedValue() {
    if (!this.labelSelector) {
      return undefined;
    }
    return this.labelSelector.selected;
  }

  setSelectedValue(value: string) {
    // The selector may not be present if it’s not at the latest patch set.
    if (!this.labelSelector) {
      return;
    }
    this.labelSelector.select(value);
  }

  // Private but used in tests.
  _computeBlankItems(side: string) {
    if (
      !this.label ||
      !this.permittedLabels?.[this.label.name] ||
      !this.permittedLabels[this.label.name].length ||
      !this.labelValues ||
      !Object.keys(this.labelValues).length
    ) {
      return [];
    }
    const permittedLabel = this.permittedLabels[this.label.name];
    const startPosition = this.labelValues[Number(permittedLabel[0])];
    if (side === 'start') {
      return new Array(startPosition).fill('');
    }
    const endPosition =
      this.labelValues[Number(permittedLabel[permittedLabel.length - 1])];
    const length = Object.keys(this.labelValues).length - endPosition - 1;
    return new Array(length).fill('');
  }

  private getLabelValue() {
    assertIsDefined(this.labels);
    assertIsDefined(this.label);
    assertIsDefined(this.permittedLabels);
    if (this.label.value) {
      return this.label.value;
    } else if (
      hasOwnProperty(this.labels[this.label.name], 'default_value') &&
      hasOwnProperty(this.permittedLabels, this.label.name)
    ) {
      // default_value is an int, convert it to string label, e.g. "+1".
      return this.permittedLabels[this.label.name].find(
        value =>
          Number(value) ===
          (this.labels![this.label!.name] as QuickLabelInfo).default_value
      );
    }
    return;
  }

  /**
   * Private but used in tests.
   * Maps the label value to exactly one of: min, max, positive, negative,
   * neutral. Used for the 'vote' attribute, because we don't want to
   * interfere with <iron-selector> using the 'class' attribute for setting
   * 'iron-selected'.
   */
  _computeVoteAttribute(value: number, index: number, totalItems: number) {
    if (value < 0 && index === 0) {
      return 'min';
    } else if (value < 0) {
      return 'negative';
    } else if (value > 0 && index === totalItems - 1) {
      return 'max';
    } else if (value > 0) {
      return 'positive';
    } else {
      return 'neutral';
    }
  }

  // Private but used in tests.
  _computeLabelValue() {
    // Polymer 2+ undefined check
    if (!this.labels || !this.permittedLabels || !this.label) {
      return undefined;
    }

    if (!this.labels[this.label.name]) {
      return undefined;
    }
    const labelValue = this.getLabelValue();
    const permittedLabel = this.permittedLabels[this.label.name];
    const len = permittedLabel ? permittedLabel.length : 0;
    for (let i = 0; i < len; i++) {
      const val = permittedLabel[i];
      if (val === labelValue) {
        return val;
      }
    }
    return undefined;
  }

  private setSelectedValueText = (e: Event) => {
    // Needed because when the selected item changes, it first changes to
    // nothing and then to the new item.
    const selectedItem = (e.target as IronSelectorElement)
      .selectedItem as HTMLElement;
    if (!selectedItem) {
      return;
    }
    if (!this.labelSelector?.items) {
      return;
    }
    for (const item of this.labelSelector.items) {
      if (selectedItem === item) {
        item.setAttribute('aria-checked', 'true');
      } else {
        item.removeAttribute('aria-checked');
      }
    }
    this.selectedValueText = selectedItem.getAttribute('title') || '';
    const name = selectedItem.dataset['name'];
    const value = selectedItem.dataset['value'];
    this.dispatchEvent(
      new CustomEvent('labels-changed', {
        detail: {name, value},
        bubbles: true,
        composed: true,
      })
    );
  };

  _computeAnyPermittedLabelValues() {
    return (
      this.permittedLabels &&
      this.label &&
      hasOwnProperty(this.permittedLabels, this.label.name) &&
      this.permittedLabels[this.label.name].length
    );
  }

  private computeHiddenClass() {
    return !this._computeAnyPermittedLabelValues() ? 'hidden' : '';
  }

  private computePermittedLabelValues() {
    if (!this.permittedLabels || !this.label) {
      return [];
    }

    return this.permittedLabels[this.label.name] || [];
  }

  private computeLabelValueTitle(value: string) {
    if (!this.labels || !this.label) return '';
    const label = this.labels[this.label.name];
    if (label && (label as DetailedLabelInfo).values) {
      // TODO(TS): maybe add a type guard for DetailedLabelInfo and
      // QuickLabelInfo
      return (label as DetailedLabelInfo).values![value];
    } else {
      return '';
    }
  }
}
