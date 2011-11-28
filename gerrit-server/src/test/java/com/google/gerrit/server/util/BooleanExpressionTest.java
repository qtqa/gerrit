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
