/*
 * Copyright 2013 University of Chicago and Argonne National Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package exm.stc.common.lang;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import exm.stc.ast.antlr.ExMParser;
import exm.stc.common.exceptions.InvalidSyntaxException;
import exm.stc.common.exceptions.STCRuntimeError;
import exm.stc.common.lang.Types.PrimType;
import exm.stc.common.lang.Types.ScalarFutureType;
import exm.stc.common.lang.Types.Type;
import exm.stc.frontend.Context;

public class Operators {

  /**
   * Opcodes for operations operating on local variables
   */
  public static enum BuiltinOpcode {
    PLUS_INT, MINUS_INT, MULT_INT, DIV_INT, MOD_INT, PLUS_FLOAT, MINUS_FLOAT, 
    MULT_FLOAT, DIV_FLOAT, 
    /** Directory catenation (/): */ 
    DIRCAT, 
    NEGATE_INT, NEGATE_FLOAT, POW_INT, POW_FLOAT, 
    MAX_INT, MAX_FLOAT, MIN_INT, MIN_FLOAT, ABS_INT, ABS_FLOAT, 
    EQ_INT, NEQ_INT, GT_INT, LT_INT, GTE_INT, LTE_INT, 
    EQ_FLOAT, NEQ_FLOAT, GT_FLOAT, LT_FLOAT, GTE_FLOAT, LTE_FLOAT, 
    EQ_BOOL, NEQ_BOOL, EQ_STRING, NEQ_STRING, 
    NOT, AND, OR, XOR, STRCAT, SUBSTRING, 
    COPY_INT, COPY_FLOAT, COPY_BOOL, COPY_STRING, COPY_BLOB, COPY_VOID, 
    FLOOR, CEIL, ROUND, INTTOFLOAT, STRTOINT, INTTOSTR, STRTOFLOAT, FLOATTOSTR, 
    LOG, EXP, SQRT, IS_NAN,
    ASSERT_EQ, ASSERT, SPRINTF,
  }

  /** Map of <number type> -> ( <token type> -> <internal opcode> ) */
  private static final Map<Type, Map<Integer, BuiltinOpcode>> arithOps = 
                    new HashMap<Type, Map<Integer, BuiltinOpcode>>();
  
  /** Types of operations */
  private static final Map<BuiltinOpcode, OpType> optypes = 
      new HashMap<BuiltinOpcode, OpType>();
  

  static {
    fillArithOps();
  }

  /**
   * Load mapping from AST tags and arg types to actual op codes 
   */
  private static void fillArithOps() {
    for (PrimType numType : Arrays.asList(PrimType.FLOAT, PrimType.INT,
        PrimType.STRING, PrimType.BOOL)) {
      String opTypeName = getOpTypeName(numType);
      HashMap<Integer, BuiltinOpcode> opMapping = new HashMap<Integer, BuiltinOpcode>();


      OpType relOpType = new OpType(PrimType.BOOL, numType, numType);
      OpType closedOpType = new OpType(numType, numType, numType);
      
      // Want equality tests for all primitives
      BuiltinOpcode eq = BuiltinOpcode.valueOf("EQ_" + opTypeName);
      opMapping.put(ExMParser.EQUALS, eq);
      optypes.put(eq, relOpType);
      BuiltinOpcode neq = BuiltinOpcode.valueOf("NEQ_" + opTypeName);
      opMapping.put(ExMParser.NEQUALS, neq);
      optypes.put(neq, relOpType);

      if (numType == PrimType.STRING) {
        opMapping.put(ExMParser.PLUS, BuiltinOpcode.STRCAT);
        optypes.put(BuiltinOpcode.STRCAT, closedOpType);
        opMapping.put(ExMParser.DIV, BuiltinOpcode.DIRCAT);
        optypes.put(BuiltinOpcode.DIRCAT, closedOpType);
      }

      if (numType == PrimType.INT) {
        opMapping.put(ExMParser.INTDIV, BuiltinOpcode.DIV_INT);
        optypes.put(BuiltinOpcode.DIV_INT, closedOpType);
        opMapping.put(ExMParser.MOD, BuiltinOpcode.MOD_INT);
        optypes.put(BuiltinOpcode.MOD_INT, closedOpType);
      } else if (numType == PrimType.FLOAT) {
        opMapping.put(ExMParser.DIV, BuiltinOpcode.DIV_FLOAT);
        optypes.put(BuiltinOpcode.DIV_FLOAT, closedOpType);
      }

      OpType closeUnaryOpType = new OpType(numType, numType);
      if (numType == PrimType.INT || numType == PrimType.FLOAT) {
        BuiltinOpcode plus = BuiltinOpcode.valueOf("PLUS_" + opTypeName);
        opMapping.put(ExMParser.PLUS, plus);
        optypes.put(plus, closedOpType);
        BuiltinOpcode minus = BuiltinOpcode.valueOf("MINUS_" + opTypeName);
        opMapping.put(ExMParser.MINUS, minus);
        optypes.put(minus, closedOpType);
        BuiltinOpcode mult = BuiltinOpcode.valueOf("MULT_" + opTypeName);
        opMapping.put(ExMParser.MULT, mult);
        optypes.put(mult, closedOpType);
        BuiltinOpcode negate = BuiltinOpcode.valueOf("NEGATE_" + opTypeName);
        opMapping.put(ExMParser.NEGATE, negate);
        optypes.put(negate, closeUnaryOpType);
        BuiltinOpcode gt = BuiltinOpcode.valueOf("GT_" + opTypeName);
        opMapping.put(ExMParser.GT, gt);
        optypes.put(gt, relOpType);
        BuiltinOpcode gte = BuiltinOpcode.valueOf("GTE_" + opTypeName);
        opMapping.put(ExMParser.GTE, gte);
        optypes.put(gte, relOpType);
        BuiltinOpcode lt = BuiltinOpcode.valueOf("LT_" + opTypeName);
        opMapping.put(ExMParser.LT, lt);
        optypes.put(lt, relOpType);
        BuiltinOpcode lte = BuiltinOpcode.valueOf("LTE_" + opTypeName);
        opMapping.put(ExMParser.LTE, lte);
        optypes.put(lte, relOpType);
        BuiltinOpcode pow = BuiltinOpcode.valueOf("POW_" + opTypeName);
        opMapping.put(ExMParser.POW, pow);
        optypes.put(pow, new OpType(PrimType.FLOAT, numType, numType));
      }

      if (numType == PrimType.BOOL) {
        opMapping.put(ExMParser.NOT, BuiltinOpcode.NOT);
        optypes.put(BuiltinOpcode.NOT, closeUnaryOpType);
        opMapping.put(ExMParser.AND, BuiltinOpcode.AND);
        optypes.put(BuiltinOpcode.AND, closedOpType);
        opMapping.put(ExMParser.OR, BuiltinOpcode.OR);
        optypes.put(BuiltinOpcode.OR, closedOpType);
      }

      arithOps.put(new ScalarFutureType(numType), opMapping);
    }
  }

  private static String getOpTypeName(PrimType numType) {
    switch (numType) {
    case BOOL:
      return "BOOL";
    case INT:
      return "INT";
    case STRING:
      return "STRING";
    case FLOAT:
      return "FLOAT";
    default:
      throw new STCRuntimeError("mistake");
    }
  }

  public static BuiltinOpcode getArithBuiltin(Type argType, int tokenType) {
    Map<Integer, BuiltinOpcode> mp = arithOps.get(argType);
    if (mp == null) {
      return null;
    }
    return mp.get(tokenType);
  }
  
  public static OpType getBuiltinOpType(BuiltinOpcode op) {
    OpType t = optypes.get(op);
    if (t == null) {
      throw new STCRuntimeError("No type for builtin op " + op);
    }
    return t;
  }

  /**
   * If the operation is a copy operation
   * @param op
   * @return
   */
  public static boolean isCopy(BuiltinOpcode op) {
    return op == BuiltinOpcode.COPY_INT || op == BuiltinOpcode.COPY_BOOL
    || op == BuiltinOpcode.COPY_FLOAT || op == BuiltinOpcode.COPY_STRING
    || op == BuiltinOpcode.COPY_BLOB || op == BuiltinOpcode.COPY_VOID;
  }

  public static boolean isMinMaxOp(BuiltinOpcode localop) {
    return localop == BuiltinOpcode.MAX_FLOAT
        || localop == BuiltinOpcode.MAX_INT
        || localop == BuiltinOpcode.MIN_FLOAT
        || localop == BuiltinOpcode.MIN_INT;
  }

  /**
   * Keep track of which of the above functions are randomized or have side
   * effects, so we don't optimized these things out
   */
  private static HashSet<BuiltinOpcode> impureOps = new HashSet<BuiltinOpcode>();
  static {
    impureOps.add(BuiltinOpcode.ASSERT);
    impureOps.add(BuiltinOpcode.ASSERT_EQ);
  }

  /**
   * Return true if the operation is non-deterministic
   * or if it has a side-effect
   * @param op
   * @return
   */
  public static boolean isImpure(BuiltinOpcode op) {
    return impureOps.contains(op);
  }
  
  private static Set<BuiltinOpcode> commutative = 
        new HashSet<BuiltinOpcode>();
  static {
    commutative.add(BuiltinOpcode.PLUS_INT);
    commutative.add(BuiltinOpcode.PLUS_FLOAT);
    commutative.add(BuiltinOpcode.MULT_INT);
    commutative.add(BuiltinOpcode.MULT_FLOAT);
    commutative.add(BuiltinOpcode.MAX_FLOAT);
    commutative.add(BuiltinOpcode.MAX_INT);
    commutative.add(BuiltinOpcode.MIN_FLOAT);
    commutative.add(BuiltinOpcode.MIN_INT);
  }
  
  public static boolean isCommutative(BuiltinOpcode op) {
    return commutative.contains(op);
  }
  
  /** Ops which are equivalent to another with
   * reversed arguments.  Reverse arguments and swap
   * to another function name to get canoical version
   */
  private static Map<BuiltinOpcode, BuiltinOpcode> flippedOps
       = new HashMap<BuiltinOpcode, BuiltinOpcode>();
  
  public static boolean isFlippable(BuiltinOpcode op) {
    return flippedOps.containsKey(op);
  }

  public static BuiltinOpcode flippedOp(BuiltinOpcode op) {
    return flippedOps.get(op);
  }
  
  static {
    // e.g a > b is same as b < a
    // Make less than the canonical representation
    flippedOps.put(BuiltinOpcode.GT_FLOAT, BuiltinOpcode.LT_FLOAT);
    flippedOps.put(BuiltinOpcode.GTE_FLOAT, BuiltinOpcode.LTE_FLOAT);
    flippedOps.put(BuiltinOpcode.GT_INT, BuiltinOpcode.LT_INT);
    flippedOps.put(BuiltinOpcode.GTE_INT, BuiltinOpcode.LTE_INT);
  }
  
  /** represent type of builtin operators */
  public static class OpType {
    public final PrimType out;
    public final PrimType in[];
    private OpType(PrimType out, PrimType... in) {
      super();
      this.out = out;
      this.in = in;
    }
  }

  public static enum UpdateMode {
    MIN, SCALE, INCR;
  
    @SuppressWarnings("serial")
    private static final Map<String, UpdateMode> nameMap = new
              HashMap<String, UpdateMode>() {{
                put("min", MIN);
                put("scale", SCALE);
                put("incr", INCR);
              }};
    public static UpdateMode fromString(Context errContext, String modeName)
                                            throws InvalidSyntaxException {
      UpdateMode result = nameMap.get(modeName);
      
      if (result == null) {
        throw new InvalidSyntaxException(errContext, "invalid update mode: "
            + modeName + " valid options are: " + nameMap.values());
      }
      return result;
    }
  }

  public static boolean isShortCircuitable(BuiltinOpcode op) {
    return op == BuiltinOpcode.AND || op == BuiltinOpcode.OR;
  }

}
