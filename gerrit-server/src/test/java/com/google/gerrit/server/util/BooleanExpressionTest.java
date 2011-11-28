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

package com.google.gerrit.server.util;

import static org.junit.Assert.*;

import java.text.ParseException;
import java.util.HashMap;

import org.junit.Test;

public class BooleanExpressionTest {

  @Test
  public void testEvaluate() {

    try {
      assertTrue(new BooleanExpression("true && true && true").evaluate(null));
      assertTrue(new BooleanExpression("true || true || false").evaluate(null));
      assertTrue(new BooleanExpression("true || false || false").evaluate(null));
      assertTrue(new BooleanExpression("true || false || true").evaluate(null));
      assertTrue(new BooleanExpression("false || true || true").evaluate(null));
      assertTrue(new BooleanExpression("false || true || false").evaluate(null));
      assertFalse(new BooleanExpression("false || false || false").evaluate(null));
      assertFalse(new BooleanExpression("false && false || false").evaluate(null));
      assertFalse(new BooleanExpression("false && false && false").evaluate(null));
      assertTrue(new BooleanExpression("false && false || true").evaluate(null));
      assertTrue(new BooleanExpression("false = false").evaluate(null));
      assertTrue(new BooleanExpression("true = true").evaluate(null));
      assertFalse(new BooleanExpression("true = false").evaluate(null));
      assertFalse(new BooleanExpression("false = true").evaluate(null));

      HashMap<String,String> hashMap = new HashMap<String, String>();
      hashMap.put("SRVW", "-1");
      hashMap.put("reviewer", "qt_sanity_bot");

      assertTrue(new BooleanExpression("SRVW < 0 || reviewer != qt_sanity_bot").evaluate(hashMap));

    } catch (ParseException e) {
      fail(e.getMessage());
    }
  }

}
