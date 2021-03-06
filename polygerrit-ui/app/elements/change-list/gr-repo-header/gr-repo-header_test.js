/**
 * @license
 * Copyright (C) 2018 The Android Open Source Project
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

import '../../../test/common-test-setup-karma.js';
import './gr-repo-header.js';
import {GerritNav} from '../../core/gr-navigation/gr-navigation.js';
import {stubRestApi} from '../../../test/test-utils.js';

const basicFixture = fixtureFromElement('gr-repo-header');

suite('gr-repo-header tests', () => {
  let element;

  setup(() => {
    element = basicFixture.instantiate();
  });

  test('repoUrl reset once repo changed', () => {
    sinon.stub(GerritNav, 'getUrlForRepo').callsFake(
        repoName => `http://test.com/${repoName}`
    );
    assert.equal(element._repoUrl, undefined);
    element.repo = 'test';
    assert.equal(element._repoUrl, 'http://test.com/test');
  });

  test('webLinks set', () => {
    const repoRes = {
      web_links: [
        {
          name: 'gitiles',
          url: 'https://gerrit.test/g',
        },
      ],
    };

    stubRestApi('getRepo').returns(Promise.resolve(repoRes));

    assert.deepEqual(element._webLinks, []);

    element.repo = 'test';
    flush(() => {
      assert.deepEqual(element._webLinks, repoRes.web_links);
    });
  });
});
