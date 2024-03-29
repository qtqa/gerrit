// Copyright (C) 2024 The Android Open Source Project
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

package com.google.gerrit.server.restapi.config;

import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;

import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.index.Index;
import com.google.gerrit.index.IndexDefinition;
import com.google.gerrit.server.config.ConfigResource;
import com.google.gerrit.server.restapi.config.SnapshotIndexes.Input;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;

@RequiresCapability(GlobalCapability.MAINTAIN_SERVER)
@Singleton
public class SnapshotIndexes implements RestModifyView<ConfigResource, Input> {
  private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

  public static class Input {
    String id;
  }

  private final Collection<IndexDefinition<?, ?, ?>> defs;

  @Inject
  SnapshotIndexes(Collection<IndexDefinition<?, ?, ?>> defs) {
    this.defs = defs;
  }

  @Override
  public Response<?> apply(ConfigResource resource, Input input) throws IOException {
    String id = input.id;
    if (id == null) {
      id = LocalDateTime.now(ZoneId.systemDefault()).format(formatter);
    }
    for (IndexDefinition<?, ?, ?> def : defs) {
      for (Index<?, ?> index : def.getIndexCollection().getWriteIndexes()) {
        try {
          @SuppressWarnings("unused")
          var unused = index.snapshot(id);
        } catch (FileAlreadyExistsException e) {
          return Response.withStatusCode(SC_CONFLICT, "Snapshot with same ID already exists.");
        }
      }
    }
    SnapshotInfo info = new SnapshotInfo();
    info.id = id;
    return Response.ok(info);
  }
}
