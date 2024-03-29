// Copyright (C) 2023 The Android Open Source Project
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

package com.google.gerrit.server.config;

import com.google.gerrit.extensions.restapi.RestView;
import com.google.gerrit.index.Index;
import com.google.gerrit.index.IndexDefinition;
import com.google.inject.TypeLiteral;

public class IndexResource extends ConfigResource {
  public static final TypeLiteral<RestView<IndexResource>> INDEX_KIND = new TypeLiteral<>() {};

  private IndexDefinition<?, ?, ?> def;

  public IndexResource(IndexDefinition<?, ?, ?> def) {
    this.def = def;
  }

  public IndexDefinition<?, ?, ? extends Index<?, ?>> getIndexDefinition() {
    return def;
  }
}
