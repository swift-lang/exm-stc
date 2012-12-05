package exm.stc.frontend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import exm.stc.ast.SwiftAST;
import exm.stc.ast.antlr.ExMParser;
import exm.stc.ast.descriptor.ArrayElems;
import exm.stc.ast.descriptor.ArrayRange;
import exm.stc.ast.descriptor.FunctionCall;
import exm.stc.common.exceptions.STCRuntimeError;
import exm.stc.common.exceptions.TypeMismatchException;
import exm.stc.common.exceptions.UndefinedFunctionException;
import exm.stc.common.exceptions.UndefinedOperatorException;
import exm.stc.common.exceptions.UndefinedVariableException;
import exm.stc.common.exceptions.UserException;
import exm.stc.common.lang.Operators;
import exm.stc.common.lang.Operators.BuiltinOpcode;
import exm.stc.common.lang.Operators.OpType;
import exm.stc.common.lang.Types;
import exm.stc.common.lang.Types.ArrayType;
import exm.stc.common.lang.Types.ExprType;
import exm.stc.common.lang.Types.FunctionType;
import exm.stc.common.lang.Types.RefType;
import exm.stc.common.lang.Types.ScalarFutureType;
import exm.stc.common.lang.Types.ScalarUpdateableType;
import exm.stc.common.lang.Types.StructType;
import exm.stc.common.lang.Types.Type;
import exm.stc.common.lang.Types.UnionType;
import exm.stc.common.lang.Types.WildcardType;
import exm.stc.common.lang.Var;
import exm.stc.common.util.MultiMap;
import exm.stc.common.util.Pair;

/**
 * This module handles checking the internal consistency of expressions,
 * and inferring the types of expressions in the SwiftScript AST
 */
public class TypeChecker {
  
  /**
   * Determine the expected type of an expression. If the expression is valid,
   * then this will return the type of the expression. If it is invalid, 
   * it will throw an exception, or may return a type
   * 
   * TODO: we don't fully validate the expression type here, some is still
   * done in SwiftScript (e.g. checking type of function arguments)
   *
   * @param context
   *          the context in which the expression resides
   * @param tree
   *          the expression tree
   * @return
   * @throws UserException
   */
  public static ExprType findExprType(Context context, SwiftAST tree)
      throws UserException {
    // Memoize this function to avoid recalculating type
    ExprType cached = tree.getExprType();
    if (cached != null) {
      LogHelper.trace(context, "Expr has cached type " + cached.toString());
      return cached;
    } else {
      ExprType calcedType = uncachedFindExprType(context, tree);
      tree.setType(calcedType);
      LogHelper.trace(context, "Expr found type " + calcedType.toString());
      return calcedType;
    }
  }
  
  private static ExprType uncachedFindExprType(Context context, SwiftAST tree) 
      throws UserException {
    int token = tree.getType();
    switch (token) {
    case ExMParser.CALL_FUNCTION: {
      return callFunction(context, tree, true);
    }
    case ExMParser.VARIABLE: {
      Var var = context.getDeclaredVariable(tree.child(0).getText());
      if (var == null) {
        throw new UndefinedVariableException(context, "Variable "
            + tree.child(0).getText() + " is not defined");
      }
      Type exprType = var.type();
      if (Types.isScalarUpdateable(exprType)) {
        // Can coerce to future
        return new ExprType(UnionType.createUnionType(exprType,
                            ScalarUpdateableType.asScalarFuture(exprType)));
      }
      return new ExprType(exprType);
    }
    case ExMParser.INT_LITERAL:
        // interpret as float
      return new ExprType(UnionType.createUnionType(Types.F_INT,
                                                    Types.F_FLOAT));
    case ExMParser.FLOAT_LITERAL:
      return new ExprType(Types.F_FLOAT);
    case ExMParser.STRING_LITERAL:
      return new ExprType(Types.F_STRING);
    case ExMParser.BOOL_LITERAL:
      return new ExprType(Types.F_BOOL);
    case ExMParser.OPERATOR:
      return findOperatorResultType(context, tree);
    case ExMParser.STRUCT_LOAD:
      ExprType structTypeL = findExprType(context, tree.child(0));
      String fieldName = tree.child(1).getText();
      if (structTypeL.elems() != 1) {
        throw new TypeMismatchException(context,
            "Trying to lookup field on return value of function with"
                + " zero or multiple return values");
      }
      Type structType = structTypeL.get(0);
      Type fieldType;
      fieldType = findStructFieldType(context, fieldName, structType);
      

      if (fieldType == null) {
        throw new TypeMismatchException(context, "No field called " + fieldName
            + " in structure type " + ((StructType) structType).getTypeName());
      }
      if (Types.isStruct(structType)) {
        return new ExprType(fieldType);
      } else { assert(Types.isStructRef(structType));
        return new ExprType(dereferenceResultType(fieldType));
      }

    case ExMParser.ARRAY_LOAD:
      Type arrType = findSingleExprType(context, tree.child(0));

      List<Type> resultAlts = new ArrayList<Type>();
      for (Type arrAlt: UnionType.getAlternatives(arrType)) {
        if (Types.isArray(arrAlt) || Types.isArrayRef(arrAlt)) {
          Type memberType = Types.getArrayMemberType(arrAlt);

          // Depending on the member type of the array, the result type might be
          // the actual member type, or a reference to the member type
          resultAlts.add(dereferenceResultType(memberType));
        } else {
          throw new TypeMismatchException(context,
              "Trying to index into non-array expression of type "
                  + arrType.toString());
        }
      }
      return new ExprType(UnionType.makeUnion(resultAlts));
    case ExMParser.ARRAY_RANGE: {
      // Check the arguments for type validity
      ArrayRange ar = ArrayRange.fromAST(context, tree);
      ar.typeCheck(context);
      // Type is always the same: an array of integers
      return new ExprType(new ArrayType(Types.F_INT));
    }
    case ExMParser.ARRAY_ELEMS: {
      return ArrayElems.fromAST(context, tree).getType(context);
    }
    default:
      throw new STCRuntimeError("Unexpected token type in expression context: "
          + LogHelper.tokName(token));
    }
  }

  /**
   * Find the type of a particular field
   * @param context
   * @param fieldName
   * @param type a struct or a reference to a struct
   * @return
   * @throws TypeMismatchException if the type isn't a struct or struct ref, 
   *                or the field doesn't exist in the type 
   */
  public static Type findStructFieldType(Context context, String fieldName,
      Type type) throws TypeMismatchException {
    StructType structType;
    if (Types.isStruct(type)) {
      structType = ((StructType) type);
    } else if (Types.isStructRef(type)) {
      structType = ((StructType) (type.memberType()));
    } else {
      throw new TypeMismatchException(context, "Trying to access named field "
          + fieldName + " on non-struct expression of type " + type.toString());
    }
    Type fieldType = structType.getFieldTypeByName(fieldName);
    if (fieldType == null) {
      throw new TypeMismatchException(context, "Field named " + fieldName + 
          " does not exist in structure type " + structType.typeName());
    }
    return fieldType;
  }

  /**
   * Same as findExprType, but check that we don't have a multiple
   * value type
   * @param context
   * @param tree
   * @return
   * @throws UserException
   */
  public static Type findSingleExprType(Context context, SwiftAST tree) 
        throws UserException {
    ExprType typeL = findExprType(context, tree);
    if (typeL.elems() != 1) {
      throw new TypeMismatchException(context, "Expected expression to have "
          + " a single value, instead had " + typeL.elems() + " values");
    }
    return typeL.get(0);
  }
  
  private static String extractOpName(SwiftAST opTree) {
    int tok = opTree.child(0).getType();
    try {
      return ExMParser.tokenNames[tok].toLowerCase();
    } catch (IndexOutOfBoundsException ex) {
      throw new STCRuntimeError("Out of range token number: "
          + tok);
    }
  }

  private static ExprType findOperatorResultType(Context context, SwiftAST tree)
      throws TypeMismatchException, UserException {
    String opName = extractOpName(tree);

    ArrayList<Type> argTypes = new ArrayList<Type>();

    List<BuiltinOpcode> opcodes = getBuiltInFromOpTree(context, tree, argTypes);
    assert(opcodes != null);
    assert(opcodes.size() > 0);
    
    // Handle possibility of multiple return types
    List<Type> alternatives = new ArrayList<Type>(opcodes.size());
    for (BuiltinOpcode opcode: opcodes) {
      OpType opType = Operators.getBuiltinOpType(opcode);
      assert(opType != null);
      
      checkOp(context, opName, opType, argTypes);
      alternatives.add(new ScalarFutureType(opType.out));
    }
    return new ExprType(UnionType.makeUnion(alternatives));
  }

  private static void checkOp(Context context, String opName, OpType opType,
      ArrayList<Type> argTypes) throws TypeMismatchException {
    if (opType.in.length != argTypes.size()) {
      throw new STCRuntimeError("Op " +
            opName + " expected "
            + opType.in.length + " arguments but " +
            + argTypes.size() + " were in AST");
    }
 
    // TODO: maybe not needed?  Keep now to find internal errors?
    for (int i = 0; i < argTypes.size(); i++) {
      Type exp = new ScalarFutureType(opType.in[i]);
      Type act = argTypes.get(i);
      if (!act.assignableTo(exp)) {
        throw new TypeMismatchException(context, "Expected type " +
            exp.toString() + " in argument " + i + " to operator " +
            opName + " but found type "
            + act.toString());
 
      }
    }
  }

  public static BuiltinOpcode getBuiltInFromOpTree(Context context,
      SwiftAST tree, Type outType) throws TypeMismatchException, UserException {
    List<BuiltinOpcode> ops = getBuiltInFromOpTree(context, tree, outType, null);
    assert(ops.size() != 0); // Should be caught earlier
    if (ops.size() > 1) {
      LogHelper.debug(context, "Ambiguous operator: " + ops);
    }
    return ops.get(0);
  }
  
  public static List<BuiltinOpcode> getBuiltInFromOpTree(Context context,
      SwiftAST tree, List<Type> argTypes) throws TypeMismatchException, UserException {
    return getBuiltInFromOpTree(context, tree, null, argTypes);
  }

  /**
   *
   * @param context
   * @param tree
   * @param expectedResult
   * @param outType if not null, only return operators with this output type
   * @param argTypes Put the argument types of the operator in this list if not null
   * @return the possible builtin operator codes
   * @throws TypeMismatchException
   * @throws UserException
   */
  private static List<BuiltinOpcode> getBuiltInFromOpTree(Context context, SwiftAST tree,
          Type outType, List<Type> argTypes)
          throws TypeMismatchException, UserException {
    assert(tree.getType() == ExMParser.OPERATOR);
    assert(tree.getChildCount() >= 1);
    int opTok = tree.child(0).getType();
    String opName = extractOpName(tree);
 
    List<Type> possibleAllArgTypes = findOperatorArgTypes(context,
                                        tree, opName, argTypes);
                                                                        
    ArrayList<BuiltinOpcode> ops = new ArrayList<BuiltinOpcode>();
    for (Type allArgType: possibleAllArgTypes) {
      BuiltinOpcode op = Operators.getArithBuiltin(allArgType, opTok);
      if (op != null) {
        if (outType != null) {
          OpType opType = Operators.getBuiltinOpType(op);
          if (new ScalarFutureType(opType.out).equals(outType) ) {
            ops.add(op);
          }
        } else {
          ops.add(op);
        }
      }
    }
    if (ops.isEmpty()) {
      String msg = "Operator " + opName + " not supported for any of these possible " + 
          "input types:" + possibleAllArgTypes.toString();
      if (outType != null) {
        msg += " and output type " + outType;
      }
      throw new UndefinedOperatorException(context,
          msg);
    }
    return ops;
  }


  /**
   * Find the argument type of the operators to a function
   * assuming all operators take all the same argument types.
   * Preference order of types determined by first expression's 
   * union type order
   * @param context
   * @param tree
   * @param opName
   * @param argTypes used to return arg types of function, null for no return
   * @return
   * @throws TypeMismatchException
   * @throws UserException
   */
  private static List<Type> findOperatorArgTypes(Context context, SwiftAST tree,
      String opName, List<Type> argTypes)
          throws TypeMismatchException, UserException {
    
    int argcount = tree.getChildCount() - 1;
    if (argcount == 0) {
      throw new TypeMismatchException(context,
          "provided no arguments to operator " +
          opName);
    }
    if (argTypes == null) {
      argTypes = new ArrayList<Type>(argcount);
    }
    for (SwiftAST argTree: tree.children(1)) {
      argTypes.add(findSingleExprType(context, argTree));
    }

    // We assume that all arguments to operator should have same type.
    return typeIntersection(argTypes);
  }

  /**
   * Find the intersection of a list of types where each element
   * is either a UnionType or a different non-union type
   * @param types
   * @return a list of types in intersection, in order of appearance in
   *         first type
   */
  public static List<Type> typeIntersection(List<Type> types) {
    if(types.size() == 0) {
      return Collections.<Type>singletonList(new WildcardType());
    }
    // Shortcircuit common cases
    if (types.size() == 1 ||
        (types.size() == 2 && types.get(0).equals(types.get(1)))) {
      return UnionType.getAlternatives(types.get(0));
    }
    
    boolean sawWildcard = true;
    Set<Type> intersection = null;
    for (Type argType: types) {
      if (Types.isWildcard(argType)) {
        // do nothing
        sawWildcard = true;
      } else if (intersection == null) {
        intersection = new HashSet<Type>();
        intersection.addAll(UnionType.getAlternatives(argType));
      } else {
        Iterator<Type> it = intersection.iterator();
        while (it.hasNext()) {
          Type t1 = it.next();
          boolean compatible = false;
          for (Type t2: UnionType.getAlternatives(argType)) {
            // Check to see if types are compatible
            Map<String, Type> requiredMappings = t2.matchTypeVars(t1);
            if (requiredMappings != null && requiredMappings.size() == 0) {
              compatible = true;
            }
            requiredMappings = t1.matchTypeVars(t2);
            if (requiredMappings != null && requiredMappings.size() == 0) {
              compatible = true;
            }
          } 
          
          if (!compatible) {
            it.remove();
          }
        }
      }
    }
    
    if (intersection == null) {
      assert(sawWildcard);
      return Collections.<Type>singletonList(new WildcardType());
    }
    
    // Make sure alternatives in original order
    ArrayList<Type> result = new ArrayList<Type>();
    for (Type alt: UnionType.getAlternatives(types.get(0))) {
      if (intersection.contains(alt)) {
        result.add(alt);
      }
    }
    return result;
  }


  /**
   * The type of the internal variable that will be created from an array
   * dereference
   *
   * @param memberType
   *          the type of array memebrs for the array being dereferenced
   * @return
   */
  public static Type dereferenceResultType(Type memberType) {
    Type resultType;
    if (Types.isScalarFuture(memberType)) {
      resultType = memberType;
    } else if (Types.isArray(memberType) || Types.isStruct(memberType)) {
      resultType = new RefType(memberType);
    } else {
      throw new STCRuntimeError("Unexpected array member type"
          + memberType.toString());
    }
    return resultType;
  }

  private static TypeMismatchException argumentTypeException(Context context,
      int argPos, Type expType, Type actType, String errContext) {
    return new TypeMismatchException(context, "Expected argument " +
        (argPos + 1) + " to have one of the following types: " 
        + expType.typeName() + ", but had type: " + actType.typeName() 
        + errContext);
  }

  /**
   * 
   * @param formalArgT
   * @param argExprT
   * @return (selected type of argument, selected type of variable)
   */
  public static Pair<Type, Type> 
        whichAlternativeType(Type formalArgT, Type argExprT) {
    /*
     * Handles cases where both function formal argument type and expression type
     * are union types.  In case of multiple possibilities prefers picking first
     * alternative in expression type, followed by first formal argument alternative
     */
    for (Type argExprAlt: UnionType.getAlternatives(argExprT)) {
      // Handle if argument type is union.
      for (Type formalArgAlt: UnionType.getAlternatives(formalArgT)) {
        if (compatibleArgTypes(formalArgAlt, argExprAlt)) {
          return Pair.create(formalArgAlt, argExprAlt);
        }
      }
    }
    return null; // if no alternatives
  }

  /**
   * Check if an expression type can be used for function argument
   * @param argType non-polymorphic function argument type
   * @param exprType type of argument expression
   * @return true if compatible
   */
  public static boolean compatibleArgTypes(Type argType,
      Type exprType) {
    if (exprType.equals(argType)) {
      // Obviously ok if types are exactly the same
      return true;
    } else if (Types.isRefTo(exprType, argType)) {
      // We can block on reference, so we can transform type here
      return true;
    } else {
      return false;
    }
  }
  
  private static ExprType callFunction(Context context, SwiftAST tree,
          boolean noWarn) throws UndefinedFunctionException, UserException {
    FunctionCall f = FunctionCall.fromAST(context, tree, false);
    List<FunctionType> alts = concretiseFunctionCall(context, 
              f.function(), f.type(), f.args(), noWarn);
    if (alts.size() == 1) {
      // Function type determined entirely by input type
      return new ExprType(alts.get(0).getOutputs());
    } else {
      // Ambiguous type variable binding (based on inputs)
      assert(alts.size() >= 2);
      int numOutputs = f.type().getOutputs().size(); 
      if (numOutputs == 0) {
        return new ExprType(Collections.<Type>emptyList());
      } else {
        // Turn into a list of UnionTypes
        List<Type> altOutputs = new ArrayList<Type>();
        for (int out = 0; out < numOutputs; out++) {
          List<Type> altOutput = new ArrayList<Type>();
          for (FunctionType ft: alts) {
            altOutput.add(ft.getOutputs().get(out));
          }
          altOutputs.add(UnionType.makeUnion(altOutput));
        }
        return new ExprType(altOutputs);
      }
    }
  }

  public static FunctionType concretiseFunctionCall(Context context,
      String function, FunctionType abstractType, List<SwiftAST> args,
      List<Var> outputs, boolean firstCall) throws UserException {
    List<Type> outTs = new ArrayList<Type>(outputs.size());
    for (Var output: outputs) {
      outTs.add(output.type());
    }
    List<FunctionType> alts = concretiseFunctionCall(context, function,
                                      abstractType, args, firstCall);
    assert(alts.size() > 0);
    for (FunctionType alt: alts) {
      assert(alt.getOutputs().size() == outputs.size());
      boolean match = true;
      for (int i = 0; i < outTs.size(); i++) {
        if (!alt.getOutputs().get(i).equals(outTs.get(i))) {
          match = false;
          break;
        }
      }
      LogHelper.trace(context, "Call " + function + " alternative "
          + " function type " + alt + " match: " + match);
      // Choose first viable alternative
      if (match) {
        return alt;
      }
    }
    throw new TypeMismatchException(context, "Could not find consistent" +
            " binding for type variables.  Viable function signatures based on" +
            " input arguments were: " + alts + " but output types were " + outTs);
  }

  /**
   * @param context
   * @param abstractType function type with varargs, typevars, union types
   * @param args input argument expressions
   * @param noWarn don't issue warnings
   * @return list of possible concrete function types with varargs, typevars
   *          and union type args removed
   * @throws UserException 
   */
  public static List<FunctionType> concretiseFunctionCall(Context context,
          String func, FunctionType abstractType,
          List<SwiftAST> args, boolean noWarn) throws UserException {
    List<Type> argTypes = new ArrayList<Type>(args.size());
    for (SwiftAST arg: args) {
      argTypes.add(findSingleExprType(context, arg));
    }
    
    // Expand varargs
    List<Type> expandedInputs = expandVarargs(context, abstractType,
                              func, args.size());
    
    // Narrow down possible bindings - choose union types
    // find possible typevar bindings
    List<Type> specificInputs = new ArrayList<Type>(args.size());
    MultiMap<String, Type> tvConstraints = new MultiMap<String, Type>(); 
    for (int i = 0; i < args.size(); i++) {
      Type exp = expandedInputs.get(i);
      Type act = argTypes.get(i);
      // a more specific type than expected
      Type exp2;
      exp2 = narrowArgType(context, func, i, exp, act, tvConstraints);
      specificInputs.add(exp2);
    }
    LogHelper.trace(context, "Call " + func + " specificInputs: " +
            specificInputs + " possible bindings: " + tvConstraints);
    
    // Narrow down type variable bindings depending on constraints
    Map<String, List<Type>> bindings = unifyTypeVarConstraints(context,
              func, abstractType.getTypeVars(), tvConstraints, noWarn);
    
    LogHelper.trace(context, "Call " + func + " unified bindings: " +
                             tvConstraints);
    
    List<FunctionType> possibilities = findPossibleFunctionTypes(context,
        func, abstractType, specificInputs, bindings);
    
    LogHelper.trace(context, "Call " + func + " possible concrete types: " +
                             possibilities);

    if (possibilities.size() == 0) {
      throw new TypeMismatchException(context, "Arguments for call to " +
          "function " + func + " were incompatible with function " +
          "type.  Function input types were: " + abstractType.getInputs() +
          ", argument types were " + argTypes);
    }
    
    return possibilities;
  }

  /**
   * Narrow down the possible argument types for a function call
   * @param context
   * @param func
   * @param arg number of argument
   * @param formalArgT Formal argument type from abstract function type
   * @param argExprT Type of argument expression for function
   * @param tvConstrains Filled in with constraints for each type variable
   * @return
   * @throws TypeMismatchException
   */
  private static Type narrowArgType(Context context, String func, int arg,
          Type formalArgT, Type argExprT,
          MultiMap<String, Type> tvConstrains)
          throws TypeMismatchException {
    if (formalArgT.hasTypeVar()) {
      return checkFunArgTV(context, func, arg, formalArgT, argExprT,
              tvConstrains);
    } else {
      return checkFunArg(context, func, arg, formalArgT, argExprT).val1;
    }
  }

  /**
   * Check function argument type
   * Returns a tuple indicating which formal argument type is selected and
   * what type the input argument expression should be interpreted as having.
   * Does not handle type variables
   * @param context
   * @param function
   * @param argNum
   * @param formalArgT
   * @param argExprT
   * @return (selected formal argument type, selected argument expression type)
   * @throws TypeMismatchException
   */
  public static Pair<Type, Type> checkFunArg(Context context,
      String function, int argNum, Type formalArgT,
      Type argExprT) throws TypeMismatchException {
    assert(!formalArgT.hasTypeVar());
    Pair<Type, Type> res = whichAlternativeType(formalArgT, argExprT);
    if (res == null) {
      throw argumentTypeException(context, argNum, formalArgT, argExprT, 
                                             " in call to function " + function);
    }
    return res;
  }

  /**
   * Check function argument type
   * Returns which formal argument type is selected.
   * Only handles case where formalArgT has a type variable
   * @param context
   * @param func
   * @param arg
   * @param formalArgT
   * @param argExprT
   * @param tvConstraints fill in constraints for type variables
   * @return
   * @throws TypeMismatchException
   */
  private static Type checkFunArgTV(Context context, String func, int arg,
          Type formalArgT, Type argExprT,
          MultiMap<String, Type> tvConstraints)
          throws TypeMismatchException {
    // TODO: for now handle a few special cases only
    if (Types.isUnion(formalArgT)) {
      throw new STCRuntimeError("Unions with type var not supported yet");
    } 
    
    if (Types.isRef(formalArgT)) {
      // Will be dereferenced
      argExprT = argExprT.memberType();
    }
    if (Types.isUnion(argExprT)) {
      List<Map<String, Type>> possible = new ArrayList<Map<String,Type>>(); 
      for (Type alt: UnionType.getAlternatives(argExprT)) {
        possible.add(formalArgT.matchTypeVars(alt));
      }
      // Sanity check: ensure that all bind the same type variables
      for (Map<String, Type> m: possible) {
        assert(m.keySet().equals(possible.get(0).keySet()));
      }
      
      for (String boundVar: possible.get(0).keySet()) {
        List<Type> choices = new ArrayList<Type>();
        for (Map<String, Type> m: possible) {
          Type t = m.get(boundVar);
          assert(!Types.isUnion(t)); // Shouldn't be union inside union
          choices.add(t);
        }
        tvConstraints.put(boundVar, UnionType.makeUnion(choices));
      }
    } else {
      tvConstraints.putAll(formalArgT.matchTypeVars(argExprT));
    }
    // Leave type var
    return formalArgT;
  }

  private static List<FunctionType> findPossibleFunctionTypes(Context context,
      String function, FunctionType abstractType,
      List<Type> specificInputs, Map<String, List<Type>> bindings)
      throws TypeMismatchException {
    List<String> typeVars = new ArrayList<String>(abstractType.getTypeVars());
    
    // Handle case where type variables are unbound
    for (ListIterator<String> it = typeVars.listIterator(); it.hasNext(); ) {
      if (!bindings.containsKey(it.next())) {
        it.remove();
      }
    }
    
    int currChoices[] = new int[typeVars.size()]; // initialized to zero
    
    List<FunctionType> possibilities = new ArrayList<FunctionType>();
    while (true) {
      Map<String, Type> currBinding = new HashMap<String, Type>();
      for (int i = 0; i < currChoices.length; i++) {
        String tv = typeVars.get(i);
        currBinding.put(tv, bindings.get(tv).get(currChoices[i]));
      }
      
      possibilities.add(constructFunctionType(abstractType, specificInputs,
                        currBinding));
      
      int pos = currChoices.length - 1;
      while (pos >= 0 &&
            currChoices[pos] >= bindings.get(typeVars.get(pos)).size() - 1) {
        pos--;
      }
      if (pos < 0) {
        break;
      } else {
        currChoices[pos]++;
        for (int i = pos + 1; i < currChoices.length; i++) {
          currChoices[i] = 0;
        }
      }
    }
    return possibilities;
  }

  private static FunctionType constructFunctionType(FunctionType abstractType,
      List<Type> inputs, Map<String, Type> binding) {
    List<Type> concreteInputs = bindTypeVariables(inputs, binding);
    List<Type> concreteOutputs = bindTypeVariables(
                                    abstractType.getOutputs(), binding);
    return new FunctionType(concreteInputs, concreteOutputs, false);
  }

  private static List<Type> expandVarargs(Context context, 
       FunctionType abstractType, String function, int numArgs)
           throws TypeMismatchException {
    List<Type> abstractInputs = abstractType.getInputs();
    if (abstractType.hasVarargs()) {
      if (numArgs < abstractInputs.size() - 1) {
        throw new TypeMismatchException(context,  "Too few arguments in "
            + " call to function " + function + ": expected >= " 
            + (abstractInputs.size() - 1) + " but got " + numArgs);
      }
    } else if (abstractInputs.size() != numArgs) {
      throw new TypeMismatchException(context,  "Wrong number of arguments in "
          + " call to function " + function + ": expected " 
          + abstractInputs.size() + " but got " + numArgs);
    }
    
    if (abstractType.hasVarargs()) {
      List<Type> expandedInputs;
      expandedInputs = new ArrayList<Type>();
      expandedInputs.addAll(abstractInputs.subList(0,
                            abstractInputs.size() - 1));
      Type varArgType = abstractInputs.get(abstractInputs.size() - 1);
      for (int i = abstractInputs.size() - 1; i < numArgs; i++) { 
        expandedInputs.add(varArgType);
      }
      return expandedInputs;
    } else {
      return abstractInputs;
    }
  }

  private static List<Type> bindTypeVariables(List<Type> types,
          Map<String, Type> binding) {
    List<Type> res = new ArrayList<Type>(types.size());
    for (Type type: types) {
      res.add(type.bindTypeVars(binding));
    }
    return res;
  }

  public static void checkCopy(Context context, Type srctype, Type dsttype)
      throws TypeMismatchException {
    if (!srctype.equals(dsttype)) {
      throw new TypeMismatchException(context, "Type mismatch: copying from "
          + srctype.toString() + " to " + dsttype.toString());
    }
  }

  /**
   * Checks whether rValType can be assigned to lValType
   * @param context
   * @param rValType
   * @param lValType
   * @param lValName
   * @return returns rValType, or if rValType is a union, return chosen 
   *          member of union
   * @throws TypeMismatchException
   */
  public static Type checkAssignment(Context context, Type rValType,
      Type lValType, String lValName) throws TypeMismatchException {
    for (Type altRValType: UnionType.getAlternatives(rValType)) {
      if (altRValType.assignableTo(lValType)
          || (Types.isRef(lValType) &&
                altRValType.assignableTo(lValType.memberType()))
          || (Types.isRef(altRValType) &&
                altRValType.memberType().assignableTo(lValType))) {
        return altRValType;
      }
    }
    throw new TypeMismatchException(context, "Cannot assign to "
        + lValName + ": LVal has type "
        + lValType.toString() + " but RVal has type " + rValType.toString());
  }

  /**
   * Create dictionary with null type var bindings
   * @param ftype
   * @return
   */
  public static MultiMap<String, Type> typeVarBindings(FunctionType ftype) {
    return new MultiMap<String, Type>();
  }
  
  /**
   * @param candidates MultiMap, with possible bindings for each type variable
   * @return map of type variable name to possible types.  If list is null,
   *    this means no constraint on type variable 
   * @throws TypeMismatchException if no viable binding
   */
  public static Map<String, List<Type>> unifyTypeVarConstraints(
      Context context, String function, List<String> typeVars,
      MultiMap<String, Type> candidates, boolean noWarn)
      throws TypeMismatchException {
    Map<String, List<Type>> possible = new HashMap<String, List<Type>>();
    /* Check whether type variables were left unbound */
    for (String typeVar: typeVars) {
      List<Type> cands = candidates.get(typeVar);
      if (cands == null || cands.size() == 0) {
        if (!noWarn) {
          LogHelper.warn(context, "Type variable " + typeVar + " for call to " +
                "function " + function + " was unbound");
        }
      } else {
        List<Type> intersection = typeIntersection(cands);
        if (intersection.size() == 0) {
          throw new TypeMismatchException(context, 
              "Type variable " + typeVar + " for call to function " +
              function + " could not be bound to concrete type: no " +
              "intersection between " +
              "types: " + candidates);
        }
        possible.put(typeVar, intersection);
      }
    }
    return possible;
  }
}
