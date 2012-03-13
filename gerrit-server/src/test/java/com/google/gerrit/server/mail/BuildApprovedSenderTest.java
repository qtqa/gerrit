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
