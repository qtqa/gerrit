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

package com.google.gerrit.server.query.account;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.server.index.account.AccountSchemaDefinitions;
import com.google.gerrit.testing.ConfigSuite;
import com.google.gerrit.testing.InMemoryModule;
import com.google.gerrit.testing.IndexConfig;
import com.google.gerrit.testing.IndexVersions;
import com.google.inject.Guice;
import com.google.inject.Injector;
import java.util.Map;
import org.eclipse.jgit.lib.Config;

public class FakeQueryAccountsTest extends AbstractQueryAccountsTest {
  @ConfigSuite.Default
  public static Config defaultConfig() {
    return IndexConfig.createForFake();
  }

  @ConfigSuite.Configs
  public static Map<String, Config> againstPreviousIndexVersion() {
    // the current schema version is already tested by the inherited default config suite
    ImmutableList<Integer> schemaVersions =
        IndexVersions.getWithoutLatest(AccountSchemaDefinitions.INSTANCE);
    return IndexVersions.asConfigMap(
        AccountSchemaDefinitions.INSTANCE, schemaVersions, "againstIndexVersion", defaultConfig());
  }

  @Override
  protected Injector createInjector() {
    Config fakeConfig = new Config(config);
    InMemoryModule.setDefaults(fakeConfig);
    fakeConfig.setString("index", null, "type", "fake");
    return Guice.createInjector(new InMemoryModule(fakeConfig));
  }
}
