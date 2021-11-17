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
import '../../../scripts/bundled-polymer.js';

import '../../../styles/gr-table-styles.js';
import '../../../styles/shared-styles.js';
import '../../shared/gr-dialog/gr-dialog.js';
import '../../shared/gr-list-view/gr-list-view.js';
import '../../shared/gr-overlay/gr-overlay.js';
import '../../shared/gr-rest-api-interface/gr-rest-api-interface.js';
import '../gr-create-group-dialog/gr-create-group-dialog.js';
import {mixinBehaviors} from '@polymer/polymer/lib/legacy/class.js';
import {GestureEventListeners} from '@polymer/polymer/lib/mixins/gesture-event-listeners.js';
import {LegacyElementMixin} from '@polymer/polymer/lib/legacy/legacy-element-mixin.js';
import {PolymerElement} from '@polymer/polymer/polymer-element.js';
import {htmlTemplate} from './gr-admin-group-list_html.js';
import {ListViewBehavior} from '../../../behaviors/gr-list-view-behavior/gr-list-view-behavior.js';
import {GerritNav} from '../../core/gr-navigation/gr-navigation.js';

/**
 * @appliesMixin ListViewMixin
 * @extends Polymer.Element
 */
class GrAdminGroupList extends mixinBehaviors( [
  ListViewBehavior,
], GestureEventListeners(
    LegacyElementMixin(
        PolymerElement))) {
  static get template() { return htmlTemplate; }

  static get is() { return 'gr-admin-group-list'; }

  static get properties() {
    return {
    /**
     * URL params passed from the router.
     */
      params: {
        type: Object,
        observer: '_paramsChanged',
      },

      /**
       * Offset of currently visible query results.
       */
      _offset: Number,
      _path: {
        type: String,
        readOnly: true,
        value: '/admin/groups',
      },
      _hasNewGroupName: Boolean,
      _createNewCapability: {
        type: Boolean,
        value: false,
      },
      _groups: Array,

      /**
       * Because  we request one more than the groupsPerPage, _shownGroups
       * may be one less than _groups.
       * */
      _shownGroups: {
        type: Array,
        computed: 'computeShownItems(_groups)',
      },

      _groupsPerPage: {
        type: Number,
        value: 25,
      },

      _loading: {
        type: Boolean,
        value: true,
      },
      _filter: String,
    };
  }

  /** @override */
  attached() {
    super.attached();
    this._getCreateGroupCapability();
    this.dispatchEvent(new CustomEvent('title-change', {
      detail: {title: 'Groups'},
      composed: true, bubbles: true,
    }));
    this._maybeOpenCreateOverlay(this.params);
  }

  _paramsChanged(params) {
    this._loading = true;
    this._filter = this.getFilterValue(params);
    this._offset = this.getOffsetValue(params);

    return this._getGroups(this._filter, this._groupsPerPage,
        this._offset);
  }

  /**
   * Opens the create overlay if the route has a hash 'create'
   *
   * @param {!Object} params
   */
  _maybeOpenCreateOverlay(params) {
    if (params && params.openCreateModal) {
      this.$.createOverlay.open();
    }
  }

  /**
   * Generates groups link (/admin/groups/<uuid>)
   *
   * @param {string} id
   */
  _computeGroupUrl(id) {
    return GerritNav.getUrlForGroup(decodeURIComponent(id));
  }

  _getCreateGroupCapability() {
    return this.$.restAPI.getAccount().then(account => {
      if (!account) { return; }
      return this.$.restAPI.getAccountCapabilities(['createGroup'])
          .then(capabilities => {
            if (capabilities.createGroup) {
              this._createNewCapability = true;
            }
          });
    });
  }

  _getGroups(filter, groupsPerPage, offset) {
    this._groups = [];
    return this.$.restAPI.getGroups(filter, groupsPerPage, offset)
        .then(groups => {
          if (!groups) {
            return;
          }
          this._groups = Object.keys(groups)
              .map(key => {
                const group = groups[key];
                group.name = key;
                return group;
              });
          this._loading = false;
        });
  }

  _refreshGroupsList() {
    this.$.restAPI.invalidateGroupsCache();
    return this._getGroups(this._filter, this._groupsPerPage,
        this._offset);
  }

  _handleCreateGroup() {
    this.$.createNewModal.handleCreateGroup().then(() => {
      this._refreshGroupsList();
    });
  }

  _handleCloseCreate() {
    this.$.createOverlay.close();
  }

  _handleCreateClicked() {
    this.$.createOverlay.open();
  }

  _visibleToAll(item) {
    return item.options.visible_to_all === true ? 'Y' : 'N';
  }
}

customElements.define(GrAdminGroupList.is, GrAdminGroupList);