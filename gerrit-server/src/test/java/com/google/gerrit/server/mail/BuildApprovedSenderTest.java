// Copyright (C) 2012 Digia Plc and/or its subsidiary(-ies).
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
package com.google.gerrit.server.mail;

import static org.junit.Assert.*;

import org.junit.Test;

public class BuildApprovedSenderTest {

  @Test
  public void testGetBuildApprovedMessage() {

    String message = "This is a\nmessage\nover several\nlines with indented\nrows";
    String expected = "Message:\n  This is a\n  message\n  over several\n  lines with indented\n  rows\n\n";


    BuildApprovedSender sender = new BuildApprovedSender(null, null, null);
    assertEquals("", sender.getBuildApprovedMessage());
    sender.setBuildApprovedMessage(message);
    assertEquals(expected, sender.getBuildApprovedMessage());
  }

}
