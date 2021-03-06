/**
 * @license
 * Copyright (C) 2016 The Android Open Source Project
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

import * as MockInteractions from '@polymer/iron-test-helpers/mock-interactions';
import '../../../test/common-test-setup-karma';
import {GrDialog} from './gr-dialog';
import {isHidden, queryAndAssert} from '../../../test/test-utils';

const basicFixture = fixtureFromElement('gr-dialog');

suite('gr-dialog tests', () => {
  let element: GrDialog;

  setup(() => {
    element = basicFixture.instantiate();
  });

  test('events', () => {
    const confirm = sinon.stub();
    const cancel = sinon.stub();
    element.addEventListener('confirm', confirm);
    element.addEventListener('cancel', cancel);

    MockInteractions.tap(queryAndAssert(element, 'gr-button[primary]'));
    assert.equal(confirm.callCount, 1);

    MockInteractions.tap(queryAndAssert(element, 'gr-button:not([primary])'));
    assert.equal(cancel.callCount, 1);
  });

  test('confirmOnEnter', () => {
    element.confirmOnEnter = false;
    const handleConfirmStub = sinon.stub(element, '_handleConfirm');
    const handleKeydownSpy = sinon.spy(element, '_handleKeydown');
    MockInteractions.pressAndReleaseKeyOn(
      queryAndAssert(element, 'main'),
      13,
      null,
      'enter'
    );
    flush();

    assert.isTrue(handleKeydownSpy.called);
    assert.isFalse(handleConfirmStub.called);

    element.confirmOnEnter = true;
    MockInteractions.pressAndReleaseKeyOn(
      queryAndAssert(element, 'main'),
      13,
      null,
      'enter'
    );
    flush();

    assert.isTrue(handleConfirmStub.called);
  });

  test('resetFocus', () => {
    const focusStub = sinon.stub(element.$.confirm, 'focus');
    element.resetFocus();
    assert.isTrue(focusStub.calledOnce);
  });

  suite('tooltip', () => {
    test('tooltip not added by default', () => {
      assert.isNull(element.$.confirm.getAttribute('has-tooltip'));
    });

    test('tooltip added if confirm tooltip is passed', () => {
      element.confirmTooltip = 'confirm tooltip';
      flush();
      assert(element.$.confirm.getAttribute('has-tooltip'));
    });
  });

  test('empty cancel label hides cancel btn', () => {
    const cancelButton = queryAndAssert(element, '#cancel');
    assert.isFalse(isHidden(cancelButton));
    element.cancelLabel = '';
    flush();

    assert.isTrue(isHidden(cancelButton));
  });
});
