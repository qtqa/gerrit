// Copyright (C) 2012 The Android Open Source Project
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

package com.google.gerrit.entities;

public class ProjectUtil {
  public static String sanitizeProjectName(String name) {
    name = stripGitSuffix(name);
    name = stripTrailingSlash(name);
    return name;
  }

  public static String stripGitSuffix(String name) {
    if (name.endsWith(".git")) {
      // Be nice and drop the trailing ".git" suffix, which we never keep
      // in our database, but clients might mistakenly provide anyway.
      //
      name = name.substring(0, name.length() - 4);
      name = stripTrailingSlash(name);
    }
    return name;
  }

  private static String stripTrailingSlash(String name) {
    while (name.endsWith("/")) {
      name = name.substring(0, name.length() - 1);
    }
    return name;
  }
}
