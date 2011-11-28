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

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BooleanExpression {

  private enum Token {
    IDENTIFIER, NUMBER, TRUE, FALSE, LEFT_PARENTHESIS, RIGHT_PARENTHESIS, LOGICAL_AND, LOGICAL_OR, GREATER_THAN, GREATER_THAN_OR_EQUAL_TO, EQUAL_TO, NOT_EQUAL_TO, LESS_THAN, LESS_THAN_OR_EQUAL_TO, UNKNOWN, END_OF_INPUT, MINUS
  }

  private static final int O_IDENTIFIER = -1;
  private static final int O_NUMBER = -2;
  private static final int O_TRUE = -3;
  private static final int O_FALSE = -4;
  private static final int O_AND = -5;
  private static final int O_OR = -6;
  private static final int O_EQUAL = -7;
  private static final int O_GT = -8;
  private static final int O_LT = -9;
  private static final int O_LTOREQUAL = -10;
  private static final int O_GTOREQUAL = -11;
  private static final int O_NOTEQUAL = -12;
  private static final int O_NONE = -1000;

  private List<Integer> program = new ArrayList<Integer>();
  private List<String> identifiers = new ArrayList<String>();
  private List<Integer> numbers = new ArrayList<Integer>();

  private String input;
  private int position;
  private String value;

  private Token currentToken;

  /**
   * Constructor.
   *
   * @param input Filter expression
   */
  public BooleanExpression(String input) throws ParseException {
    this.input = input;
    position = 0;
    nextToken();
    parseExpression();
  }

  public boolean evaluate(Map<String, String> arguments) {
    String[] stack = new String[16];
    int ppos = 0;
    int spos = 0;
    while (ppos < program.size()) {
      int o = program.get(ppos);
      if (o == O_IDENTIFIER) {
        stack[spos++] = identifiers.get(program.get(++ppos));
      } else if (o == O_NUMBER) {
        stack[spos++] = Integer.toString(numbers.get(program.get(++ppos)));
      } else if (isLogicalOperator(o)) {
        String b = stack[--spos];
        String a = stack[--spos];
        if (!isBooleanLiteral(a)) {
          throw new IllegalArgumentException(a + " is not a boolean expression");
        }
        if (!isBooleanLiteral(b)) {
          throw new IllegalArgumentException(b + " is not a boolean expression");
        }
        if (o == O_AND) {
          stack[spos++] = logicalAnd(a, b);
        } else if (o == O_OR) {
          stack[spos++] = logicalOr(a, b);
        }
      } else if (isComparatorOperator(o)) {
        String b = stack[--spos];
        String a = stack[--spos];
        if (arguments != null && arguments.get(a) != null) {
          a = arguments.get(a);
        }
        if (arguments != null && arguments.get(b) != null) {
          b = arguments.get(b);
        }
        if (o == O_EQUAL) {
          stack[spos++] = Boolean.toString(a.equals(b));
        } else if (o == O_NOTEQUAL) {
          stack[spos++] = Boolean.toString(!a.equals(b));
        } else {
          // Only integer numbers can be compared
          if (!isNumber(a) || !isNumber(b)) {
            stack[spos++] = "false";
          } else {
            int ai = Integer.parseInt(a);
            int bi = Integer.parseInt(b);
            if (o == O_GT) {
              stack[spos++] = Boolean.toString(ai > bi);
            } else if (o == O_GTOREQUAL) {
              stack[spos++] = Boolean.toString(ai >= bi);
            } else if (o == O_LT) {
              stack[spos++] = Boolean.toString(ai < bi);
            } else if (o == O_LTOREQUAL) {
              stack[spos++] = Boolean.toString(ai <= bi);
            }
          }
        }
      } else if (o == O_TRUE) {
        stack[spos++] = "true";
      } else if (o == O_FALSE) {
        stack[spos++] = "false";
      }
      ppos++;
    }

    return "true".equals(stack[spos - 1]);
  }

  private boolean isBooleanLiteral(String value) {
    if ("true".equals(value) || "false".equals(value)) {
      return true;
    }
    return false;
  }

  private boolean isNumber(String value) {
    try {
      Integer.parseInt(value);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private String logicalAnd(String a, String b) {
    if ("true".equals(a) && "true".equals(b)) {
      return "true";
    }
    return "false";
  }

  private String logicalOr(String a, String b) {
    if ("true".equals(a) || "true".equals(b)) {
      return "true";
    }
    return "false";
  }

  private boolean isLogicalOperator(int o) {
    return (o == O_AND || o == O_OR);
  }

  private boolean isComparatorOperator(int o) {
    return (o == O_EQUAL || o == O_NOTEQUAL || o == O_GT || o == O_GTOREQUAL || o == O_LT || o == O_LTOREQUAL);
  }

  private void nextToken() {
    currentToken = Token.END_OF_INPUT;
    if (position == input.length()) {
      return;
    }
    char c = input.charAt(position);
    while (Character.isWhitespace(c)) {
      position++;
      if (position == input.length()) {
        return;
      }
      c = input.charAt(position);
    }
    if (Character.isLetter(c)) {
      parseIdentifier();
    } else if (Character.isDigit(c)) {
      parseNumber();
    } else {
      currentToken = Token.UNKNOWN;
      if (c == '(') {
        currentToken = Token.LEFT_PARENTHESIS;
      } else if (c == ')') {
        currentToken = Token.RIGHT_PARENTHESIS;
      } else if (c == '>') {
        currentToken = Token.GREATER_THAN;
        position++;
        if (position == input.length()) {
          return;
        }
        c = input.charAt(position);
        if (c == '=') {
          currentToken = Token.GREATER_THAN_OR_EQUAL_TO;
        }
      } else if (c == '<') {
        currentToken = Token.LESS_THAN;
        position++;
        if (position == input.length()) {
          return;
        }
        c = input.charAt(position);
        if (c == '=') {
          currentToken = Token.LESS_THAN_OR_EQUAL_TO;
        }
      } else if (c == '&') {
        position++;
        if (position == input.length()) {
          return;
        }
        c = input.charAt(position);
        if (c == '&') {
          currentToken = Token.LOGICAL_AND;
        }
      } else if (c == '|') {
        position++;
        if (position == input.length()) {
          return;
        }
        c = input.charAt(position);
        if (c == '|') {
          currentToken = Token.LOGICAL_OR;
        }
      } else if (c == '=') {
        currentToken = Token.EQUAL_TO;
      } else if (c == '!') {
        position++;
        if (position == input.length()) {
          return;
        }
        c = input.charAt(position);
        if (c == '=') {
          currentToken = Token.NOT_EQUAL_TO;
        }
      } else if (c == '-') {
        currentToken = Token.MINUS;
      }
      if (currentToken != Token.UNKNOWN) {
        position++;
      }
    }
  }

  private void parseIdentifier() {
    int s = position;
    char c = input.charAt(position);
    currentToken = Token.IDENTIFIER;
    while (Character.isLetter(c) || c == '-' || c == '.' || c == '_') {
      position++;
      if (position == input.length()) {
        break;
      }
      c = input.charAt(position);
    }
    value = input.substring(s, position);
    if ("true".equalsIgnoreCase(value)) {
      currentToken = Token.TRUE;
    } else if ("false".equalsIgnoreCase(value)) {
      currentToken = Token.FALSE;
    }
  }

  private void parseNumber() {
    int s = position;
    char c = input.charAt(position);
    currentToken = Token.NUMBER;
    while (Character.isDigit(c)) {
      position++;
      if (position == input.length()) {
        break;
      }
      c = input.charAt(position);
    }
    value = input.substring(s, position);
  }

  private void parseExpression() throws ParseException {
    parseTerm();
    Token token = currentToken;
    while (token == Token.LOGICAL_AND || token == Token.LOGICAL_OR) {
      nextToken();
      parseTerm();
      program.add(toOperation(token));
      token = currentToken;
    }
  }

  private Integer toOperation(Token token) {

    int result = O_NONE;

    switch (token) {
      case LOGICAL_AND:
        result = O_AND;
        break;
      case LOGICAL_OR:
        result = O_OR;
        break;
      case EQUAL_TO:
        result = O_EQUAL;
        break;
      case NOT_EQUAL_TO:
        result = O_NOTEQUAL;
        break;
      case GREATER_THAN:
        result = O_GT;
        break;
      case GREATER_THAN_OR_EQUAL_TO:
        result = O_GTOREQUAL;
        break;
      case LESS_THAN:
        result = O_LT;
        break;
      case LESS_THAN_OR_EQUAL_TO:
        result = O_LTOREQUAL;
        break;
      case TRUE:
        result = O_TRUE;
        break;
      case FALSE:
        result = O_FALSE;
        break;
    }

    return result;

  }

  private void parseTerm() throws ParseException {
    parseElement();
    Token token = currentToken;
    while (token == Token.EQUAL_TO || token == Token.NOT_EQUAL_TO || token == Token.GREATER_THAN
        || token == Token.GREATER_THAN_OR_EQUAL_TO || token == Token.LESS_THAN
        || token == Token.LESS_THAN_OR_EQUAL_TO) {
      nextToken();
      parseElement();
      program.add(toOperation(token));
      token = currentToken;
    }
  }

  private void parseElement() throws ParseException {
    if (currentToken == Token.LEFT_PARENTHESIS) {
      nextToken();
      parseExpression();
      if (currentToken != Token.RIGHT_PARENTHESIS) {
        throw new ParseException("Expected ')'", position);
      }
      nextToken();
    } else if (currentToken == Token.TRUE || currentToken == Token.FALSE) {
      program.add(toOperation(currentToken));
      nextToken();
    } else if (currentToken == Token.IDENTIFIER) {
      program.add(O_IDENTIFIER);
      program.add(identifiers.size());
      identifiers.add(value);
      nextToken();
    } else if (currentToken == Token.NUMBER) {
      program.add(O_NUMBER);
      program.add(numbers.size());
      numbers.add(Integer.parseInt(value));
      nextToken();
    } else if (currentToken == Token.MINUS) {
      nextToken();
      if (currentToken == Token.NUMBER) {
        program.add(O_NUMBER);
        program.add(numbers.size());
        numbers.add(-Integer.parseInt(value));
      }
      nextToken();
    }
    else {
      throw new ParseException("Unknown token", position);
    }

  }
}
