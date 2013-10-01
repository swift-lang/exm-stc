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
/**
 * This module handles the higher-level logic of generating Turbine 
 * code. More mechanical aspects of code generation are handled in 
 * the classes in the exm.tclbackend.tree module
 */
package exm.stc.tclbackend;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import exm.stc.common.CompilerBackend;
import exm.stc.common.Logging;
import exm.stc.common.Settings;
import exm.stc.common.TclFunRef;
import exm.stc.common.exceptions.InvalidOptionException;
import exm.stc.common.exceptions.STCFatal;
import exm.stc.common.exceptions.STCRuntimeError;
import exm.stc.common.exceptions.UserException;
import exm.stc.common.lang.Arg;
import exm.stc.common.lang.ForeignFunctions;
import exm.stc.common.lang.ForeignFunctions.TclOpTemplate;
import exm.stc.common.lang.CompileTimeArgs;
import exm.stc.common.lang.Constants;
import exm.stc.common.lang.ExecContext;
import exm.stc.common.lang.Operators.BuiltinOpcode;
import exm.stc.common.lang.Operators.UpdateMode;
import exm.stc.common.lang.PassedVar;
import exm.stc.common.lang.Redirects;
import exm.stc.common.lang.RefCounting;
import exm.stc.common.lang.RefCounting.RefCountType;
import exm.stc.common.lang.TaskMode;
import exm.stc.common.lang.TaskProp.TaskPropKey;
import exm.stc.common.lang.TaskProp.TaskProps;
import exm.stc.common.lang.Types;
import exm.stc.common.lang.Types.ArrayInfo;
import exm.stc.common.lang.Types.FileKind;
import exm.stc.common.lang.Types.FunctionType;
import exm.stc.common.lang.Types.PrimType;
import exm.stc.common.lang.Types.StructType;
import exm.stc.common.lang.Types.Type;
import exm.stc.common.lang.Var;
import exm.stc.common.lang.Var.VarCount;
import exm.stc.common.lang.Var.Alloc;
import exm.stc.common.util.MultiMap;
import exm.stc.common.util.Pair;
import exm.stc.tclbackend.Turbine.CacheMode;
import exm.stc.tclbackend.Turbine.RuleProps;
import exm.stc.tclbackend.Turbine.StackFrameType;
import exm.stc.tclbackend.Turbine.TypeName;
import exm.stc.tclbackend.tree.Command;
import exm.stc.tclbackend.tree.Comment;
import exm.stc.tclbackend.tree.DictFor;
import exm.stc.tclbackend.tree.Expand;
import exm.stc.tclbackend.tree.Expression;
import exm.stc.tclbackend.tree.Expression.ExprContext;
import exm.stc.tclbackend.tree.ForEach;
import exm.stc.tclbackend.tree.ForLoop;
import exm.stc.tclbackend.tree.If;
import exm.stc.tclbackend.tree.LiteralFloat;
import exm.stc.tclbackend.tree.LiteralInt;
import exm.stc.tclbackend.tree.PackageRequire;
import exm.stc.tclbackend.tree.Proc;
import exm.stc.tclbackend.tree.Sequence;
import exm.stc.tclbackend.tree.SetVariable;
import exm.stc.tclbackend.tree.Switch;
import exm.stc.tclbackend.tree.TclExpr;
import exm.stc.tclbackend.tree.TclList;
import exm.stc.tclbackend.tree.TclString;
import exm.stc.tclbackend.tree.TclTarget;
import exm.stc.tclbackend.tree.TclTree;
import exm.stc.tclbackend.tree.Text;
import exm.stc.tclbackend.tree.Token;
import exm.stc.tclbackend.tree.Value;
import exm.stc.ui.ExitCode;

public class TurbineGenerator implements CompilerBackend {

  /** 
     This prevents duplicate "lappend auto_path" statements
     We use a List because these should stay in order  
   */
  private final List<String> autoPaths = new ArrayList<String>();
  
  private static final String TCLTMP_SPLITLEN = "tcltmp:splitlen";
  private static final String TCLTMP_SPLITEND = "tcltmp:splitend";
  private static final String TCLTMP_CONTAINER_SIZE = "tcltmp:container_sz";
  private static final String TCLTMP_ARRAY_CONTENTS = "tcltmp:contents";
  private static final String TCLTMP_RANGE_LO = "tcltmp:lo";
  private static final Value TCLTMP_RANGE_LO_V = new Value(TCLTMP_RANGE_LO);
  private static final String TCLTMP_RANGE_HI = "tcltmp:hi";
  private static final Value TCLTMP_RANGE_HI_V = new Value(TCLTMP_RANGE_HI);
  private static final String TCLTMP_RANGE_INC = "tcltmp:inc";
  private static final Value TCLTMP_RANGE_INC_V = new Value(TCLTMP_RANGE_INC);
  private static final String TCLTMP_ITERSLEFT = "tcltmp:itersleft";
  private static final String TCLTMP_ITERSTOTAL = "tcltmp:iterstotal";
  private static final String TCLTMP_ITERS = "tcltmp:iters";
  private static final String TCLTMP_UNPACKED = "tcltmp:unpacked";
  private static final String TCLTMP_INIT_REFCOUNT = "tcltmp:init_rc";
  
  private static final String MAIN_FUNCTION_NAME = "swift:main";
  private static final String CONSTINIT_FUNCTION_NAME = "swift:constants";

  final String timestamp;
  final Logger logger;

  /**
     Our output Tcl
     Convenience reference to bottom of pointStack
   */
  Sequence tree = new Sequence();


  /**
   * For function that initializes globals
   */
  Sequence globInit = new Sequence();

  /**
     Stack for previous values of point
   */
  Deque<Sequence> pointStack = new ArrayDeque<Sequence>();
  
  /**
   * Stack for name of loop functions
   */
  Deque<String> loopNameStack = new ArrayDeque<String>();

  /**
   * Stack for what context we're in. 
   */
  Deque<ExecContext> execContextStack = new ArrayDeque<ExecContext>();

  String turbineVersion = Settings.get(Settings.TURBINE_VERSION);

  HashSet<String> usedTclFunctionNames = new HashSet<String>();

  
  private final TurbineStructs structTypes = new TurbineStructs();
  
  /**
   * TCL symbol names for builtins
   * Swift function name -> TCL proc name
   */
  private final HashMap<String, TclFunRef> builtinSymbols =
                      new HashMap<String, TclFunRef>();

  /**
     If true, enable debug comments in Tcl source
   */
  boolean debuggerComments = false;

  public TurbineGenerator(Logger logger, String timestamp)
  {
    this.logger = logger;
    this.timestamp = timestamp;
    pointStack.push(tree);
    
    execContextStack.push(ExecContext.CONTROL);

    if (Settings.get("DEBUGGER") == "COMMENTS")
      debuggerComments = true;
  }

  @Override
  public void header()
  {
    //String[] rpaths = Settings.getRpaths();
    File input_file   = new File(Settings.get(Settings.INPUT_FILENAME));
    File output_file  = new File(Settings.get(Settings.OUTPUT_FILENAME));
    tree.add(new Text(""));
    tree.add(new Comment("Generated by stc version " + Settings.get(Settings.STC_VERSION)));
    tree.add(new Comment("date                    : " + timestamp));
    tree.add(new Comment("Turbine version         : " + this.turbineVersion));
    tree.add(new Comment("Input filename          : " + input_file.getAbsolutePath() ));    
    tree.add(new Comment("Output filename         : " + output_file.getAbsolutePath() ));
    tree.add(new Comment("STC home                : " + Settings.get(Settings.STC_HOME)) );
    tree.add(new Comment("Turbine home            : " + Settings.get(Settings.TURBINE_HOME)) );
    tree.add(new Comment("Compiler settings:"));
    for (String key: Settings.getKeys()) {
      tree.add(new Comment(String.format("%-30s: %s", key, Settings.get(key))));
    }
    tree.add(new Text(""));
    
    tree.add(new Comment("Metadata:"));
    for (Pair<String, String> kv: Settings.getMetadata()) {
      tree.add(new Comment(String.format("%-30s: %s", kv.val1, kv.val2)));
    }
    
    tree.add(new Text(""));

    addAutoPaths();
    
    tree.add(new Command("package require turbine", turbineVersion));
    tree.add(new Command("namespace import turbine::*"));
    tree.add(new Text(""));
    
    Proc globInitProc = new Proc(CONSTINIT_FUNCTION_NAME, usedTclFunctionNames,
                              new ArrayList<String>(), globInit);
    globInit.add(Turbine.turbineLog("function:"+CONSTINIT_FUNCTION_NAME));
    tree.add(globInitProc);
  }

  private void addAutoPaths() {
    String[] rpaths = Settings.getRpaths();
    // Uniquify: 
    for (String rpath : rpaths) 
      if (rpath.length() > 0)
        if (! autoPaths.contains(rpath))
          autoPaths.add(rpath);
    if (autoPaths.size() > 0)
      tree.add(new Comment("rpath entries"));
    // Add Tcl, put path in quotes
    for (String p : autoPaths) 
      tree.add(new Command("lappend auto_path \"" + p + "\""));
  }
  
  @Override
  public void turbineStartup()
  {
    tree.add(new Command("turbine::defaults"));
    tree.add(new Command("turbine::init $engines $servers"));
    try {
      if (Settings.getBoolean(Settings.EXPERIMENTAL_REFCOUNTING)) {
        tree.add(Turbine.enableReferenceCounting());
      }
    } catch (InvalidOptionException e) {
      throw new STCRuntimeError(e.getMessage());
    }
    
    // Initialize struct types
    tree.append(structTypeDeclarations());
    
    // Insert code to check versions
    tree.add(Turbine.checkConstants());
    
    tree.append(compileTimeArgs());

    tree.add(new Command("turbine::start " + MAIN_FUNCTION_NAME +
                                        " " + CONSTINIT_FUNCTION_NAME));
    tree.add(new Command("turbine::finalize"));
  }

  private Sequence compileTimeArgs() {
    Map<String, String> args = CompileTimeArgs.getCompileTimeArgs();
    Sequence seq = new Sequence();
    if (!args.isEmpty()) {
      for (String key: args.keySet()) {
        TclString argName = new TclString(key, true);
        TclString argVal = new TclString(args.get(key), true);
        seq.add(Turbine.addConstantArg(argName, argVal));
      }
    }
    return seq;
  }
  
  @Override
  public void declareStructType(StructType st) {
    structTypes.newType(st);
  }
  
  private Sequence structTypeDeclarations() {
    Sequence result = new Sequence();
    
    for (Pair<Integer, StructType> type: structTypes.getTypeList()) {
      int typeId = type.val1;
      StructType st = type.val2;
      List<Pair<String, Type>> fields = structTypes.getFields(st);
     
      // Tcl expression list describing fields;
      List<Expression> fieldInfo = new ArrayList<Expression>();
      for (Pair<String, Type> field: fields) {
        // Field name, then reference type
        fieldInfo.add(new TclString(field.val1, true));
        fieldInfo.add(refRepresentationType(field.val2, true));
      }
      
      Command decl = Turbine.declareStructType(new LiteralInt(typeId),
                                      new TclString(st.getTypeName()),
                                      new TclList(fieldInfo));
      result.add(decl);
    }
    return result;
  }
  
  private TypeName structTypeName(Type type, boolean creation) {
    assert(Types.isStruct(type));
    if (creation) {
      // Don't include subtype
      return Turbine.ADLB_STRUCT_REF_TYPE;
    } else {
      // Need to include subtype
      int structTypeId = structTypes.getIDForType((StructType)type);
      // Form type name through concatenation
      return Turbine.structTypeName(structTypeId);
    }
  }

  @Override
  public void declare(List<VarDecl> decls) {
    List<VarDecl> batchedFiles = new ArrayList<VarDecl>();
    List<TclList> batched = new ArrayList<TclList>();
    List<String> batchedVarNames = new ArrayList<String>();
    
    for (VarDecl decl: decls) {
      Var var = decl.var;
      Arg initReaders = decl.initReaders;
      Arg initWriters = decl.initWriters;
      Type t = var.type();
      assert(var.mapping() == null || Types.isMappable(t));
      if (var.storage() == Alloc.ALIAS) {
        assert(initReaders == null && initWriters == null);
        pointStack.peek().add(new Comment("Alias " + var.name() + " with type " +
                              t.toString() + " was defined"));
        continue;
      }
     
      // Check that init refcounts are valid
      assert(RefCounting.hasReadRefCount(var) ^ initReaders == null);
      assert(RefCounting.hasWriteRefCount(var) ^ initWriters == null);
  
      if (var.storage() == Alloc.GLOBAL_CONST) {
        // If global, it should already be in TCL global scope, just need to
        // make sure that we've imported it
        pointStack.peek().add(Turbine.makeTCLGlobal(prefixVar(var)));
        continue;
      }
      
  
      try {
        if (!Settings.getBoolean(Settings.EXPERIMENTAL_REFCOUNTING)) {
          // Have initial* set to regular amount to avoid bugs with reference counting
          initReaders = Arg.ONE;
        }
      } catch (InvalidOptionException e) {
        throw new STCRuntimeError(e.getMessage());
      }
      
      String tclVarName = prefixVar(var);
      if (Types.isFile(t)) {
        batchedFiles.add(decl);
      } else if (Types.isPrimFuture(t) || Types.isPrimUpdateable(t) ||
          Types.isArray(t) || Types.isRef(t) || Types.isBag(t)) {
        List<Expression> createArgs = new ArrayList<Expression>();
        // Data type
        createArgs.add(representationType(t, true));
        // Subscript and value type for containers only
        if (Types.isArray(t)) {
          createArgs.add(Turbine.ADLB_INT_TYPE); // key
          createArgs.add(arrayValueType(t,true)); // value
        } else if (Types.isBag(t)) {
          createArgs.add(bagValueType(t, true));
        }
        if (initReaders != null)
          createArgs.add(argToExpr(initReaders));
        if (initWriters != null)
          createArgs.add(argToExpr(initWriters));
        batched.add(new TclList(createArgs));
        batchedVarNames.add(tclVarName);
      } else if (Types.isStruct(t)) {
        // don't allocate in data store
        pointStack.peek().add(Turbine.allocateStruct(prefixVar(var)));
      } else if (Types.isPrimValue(t)) {
        assert(var.storage() == Alloc.LOCAL);
        pointStack.peek().add(new Comment("Value " + var.name() + " with type " +
                              var.type().toString() + " was defined"));
        // don't need to do anything
      } else {
        throw new STCRuntimeError("Code generation not supported for declaration " +
        		"of type: " + t.typeName());
      }
    }
    
    if (!batched.isEmpty()) {
      pointStack.peek().add(Turbine.batchDeclare(batchedVarNames, batched));

      // Log in small batches to avoid turbine log limitations
      // and overly long lines
      final int logBatch = 5;
      for (int start = 0; start < batchedVarNames.size(); start += logBatch) {
        List<Expression> logExprs = new ArrayList<Expression>();
        logExprs.add(new Token("allocated"));
        int end = Math.min(batchedVarNames.size(), start + logBatch);
        for (String tclVarName: batchedVarNames.subList(start, end)) {
          logExprs.add(new Token(" " + tclVarName + "=<"));
          logExprs.add(new Value(tclVarName));
          logExprs.add(new Token(">"));
        }
        TclString msg = new TclString(logExprs, ExprContext.VALUE_STRING);
        pointStack.peek().add(Turbine.log(msg));
      }
    }
    
    // Allocate files after so that mapped args are visible
    for (VarDecl file: batchedFiles) {
      // TODO: allocate filename/signal vars in batch and build file
      //      handle here
      allocateFile(file.var, file.initReaders);
    }
    
    for (VarDecl decl: decls) {
      // Store the name->TD in the stack
      if (decl.var.storage() == Alloc.STACK && !noStackVars()) {
        Command s = Turbine.storeInStack(decl.var.name(), prefixVar(decl.var));
        // Store the name->TD in the stack
        pointStack.peek().add(s);
      }
    }
  }

  /**
   * @param t
   * @return ADLB representation type
   */
  private TypeName representationType(Type t, boolean creation) {
    if (Types.isScalarFuture(t) || Types.isScalarUpdateable(t)) {
      switch (t.primType()) {
        case INT:
        case BOOL:
        case VOID:
          return Turbine.ADLB_INT_TYPE;
        case BLOB:
          return Turbine.ADLB_BLOB_TYPE;
        case FLOAT:
          return Turbine.ADLB_FLOAT_TYPE;
        case STRING:
          return Turbine.ADLB_STRING_TYPE;
        default:
          throw new STCRuntimeError("Unknown ADLB representation for "
              + t);
      }
    } else if (Types.isRef(t)) {
      return refRepresentationType(t.memberType(), creation);
    } else if (Types.isArray(t)) {
      return Turbine.ADLB_CONTAINER_TYPE;
    } else if (Types.isBag(t)) {
      return Turbine.ADLB_MULTISET_TYPE;
    } else {
      throw new STCRuntimeError("Unknown ADLB representation type for " + t);
    }
  }

  private TypeName refRepresentationType(Type memberType, boolean creation) {
      if (Types.isStruct(memberType)) {
        return structTypeName(memberType, creation);
      } else if (Types.isFile(memberType)) {
    	return Turbine.ADLB_FILE_REF_TYPE;
      } else {
        return Turbine.ADLB_REF_TYPE;
      }
  }

  private TypeName arrayValueType(Var arr, boolean creation) {
    return arrayValueType(arr.type(), creation);
  }
  
  private TypeName arrayValueType(Type arrType, boolean creation) {
    return refRepresentationType(Types.arrayMemberType(arrType), creation);
  }
  
  private TypeName bagValueType(Type bagType, boolean creation) {
    return refRepresentationType(Types.bagElemType(bagType), creation);
  }
  
  private void allocateFile(Var var, Arg initReaders) {
    Value mapExpr = (var.mapping() == null) ? 
                    null : varToExpr(var.mapping());
    pointStack.peek().add(
        Turbine.allocateFile(mapExpr, prefixVar(var),
                             argToExpr(initReaders)));
  }

  @Override
  public void decrWriters(Var var, Arg amount) {
    assert(RefCounting.hasWriteRefCount(var));
    assert(!Types.isStruct(var.type()));
    // Close array by removing the slot we created at startup
    decrementWriters(Arrays.asList(var), argToExpr(amount));
  }
  
  @Override
  public void decrRef(Var var, Arg amount) {
    assert(RefCounting.hasReadRefCount(var));
    assert(!Types.isStruct(var.type()));
    decrementReaders(Arrays.asList(var), argToExpr(amount));
  }
  
  @Override
  public void incrRef(Var var, Arg amount) {
    assert(RefCounting.hasReadRefCount(var));
    assert(amount.isImmediateInt());
    assert(!Types.isStruct(var.type()));
    incrementReaders(Arrays.asList(var), argToExpr(amount));
  }
  
  @Override
  public void incrWriters(Var var, Arg amount) {
    assert(RefCounting.hasWriteRefCount(var));
    assert(amount.isImmediateInt());
    assert(!Types.isStruct(var.type()));
    incrementWriters(Arrays.asList(var), argToExpr(amount));
  }
  
  /**
   * Set target=addressof(src)
   */
  @Override
  public void assignReference(Var target, Var src) {
    assert(Types.isRef(target.type()));
    assert(target.type().memberType().equals(src.type()));
    if (Types.isStructRef((target.type()))) {
      pointStack.peek().add(Turbine.structRefSet(
          varToExpr(target), varToExpr(src),
          structTypeName(target.type(), false)));
    } else if (Types.isFileRef(target.type())) {
    	pointStack.peek().add(Turbine.fileRefSet(
    	          varToExpr(target), varToExpr(src)));
    } else {
      pointStack.peek().add(Turbine.refSet(
          varToExpr(target), varToExpr(src)));
    }
  }


  @Override
  public void makeAlias(Var dst, Var src) {
    assert(src.type().equals(dst.type()));
    assert(dst.storage() == Alloc.ALIAS);
    pointStack.peek().add(new SetVariable(prefixVar(dst),
        varToExpr(src)));
  }

  @Override
  public void assignInt(Var target, Arg src) {
    assert(src.isImmediateInt());
    if (!Types.isInt(target.type())) {
      throw new STCRuntimeError("Expected variable to be int, "
          + " but was " + target.type().toString());
    }

    pointStack.peek().add(Turbine.integerSet(
        varToExpr(target), argToExpr(src)));
  }

  @Override
  public void retrieveInt(Var target, Var source, Arg decr) {
    assert(Types.isIntVal(target));
    assert(Types.isInt(source.type()));
    assert(decr.isImmediateInt());
    if (decr.equals(Arg.ZERO)) {
      pointStack.peek().add(Turbine.integerGet(prefixVar(target),
                                  varToExpr(source)));
    } else {
      pointStack.peek().add(Turbine.integerDecrGet(prefixVar(target),
                            varToExpr(source), argToExpr(decr)));
    }
  }

  @Override
  public void assignBool(Var target, Arg src) {
    assert(src.isImmediateBool());
    if (!Types.isBool(target.type())) {
      throw new STCRuntimeError("Expected variable to be bool, "
          + " but was " + target.type().toString());
    }

    pointStack.peek().add(Turbine.integerSet(
        varToExpr(target), argToExpr(src)));
  }

  @Override
  public void retrieveBool(Var target, Var source, Arg decr) {
    assert(Types.isBoolVal(target));
    assert(Types.isBool(source.type()));

    assert(decr.isImmediateInt());
    if (decr.equals(Arg.ZERO)) {
      pointStack.peek().add(Turbine.integerGet(prefixVar(target),
          varToExpr(source)));
    } else {
      pointStack.peek().add(Turbine.integerDecrGet(prefixVar(target),
          varToExpr(source), argToExpr(decr)));
    }
  }
  
  @Override
  public void assignVoid(Var target, Arg src) {
    assert(Types.isVoid(target.type()));
    assert(Types.isVoidVal(src.type()));
    pointStack.peek().add(Turbine.voidSet(varToExpr(target)));
  }

  @Override
  public void retrieveVoid(Var target, Var source, Arg decr) {
    assert(Types.isVoidVal(target));
    assert(Types.isVoid(source.type()));
    assert(decr.isImmediateInt());
    
    // Don't actually need to retrieve value as it has no contents
    pointStack.peek().add(new SetVariable(prefixVar(target),
                          Turbine.VOID_DUMMY_VAL));

    if (!decr.equals(Arg.ZERO)) {
      decrRef(source, decr);
    }
  }

  @Override
  public void assignFloat(Var target, Arg src) {
    assert(src.isImmediateFloat());
    if (!Types.isFloat(target.type())) {
      throw new STCRuntimeError("Expected variable to be float, "
          + " but was " + target.type().toString());
    }

    pointStack.peek().add(Turbine.floatSet(
          varToExpr(target), argToExpr(src)));
  }

  @Override
  public void retrieveFloat(Var target, Var source, Arg decr) {
    assert(Types.isFloatVal(target));
    assert(Types.isFloat(source));

    assert(decr.isImmediateInt());
    if (decr.equals(Arg.ZERO)) {
      pointStack.peek().add(Turbine.floatGet(prefixVar(target),
                                                    varToExpr(source)));
    } else {
      pointStack.peek().add(Turbine.floatDecrGet(prefixVar(target),
          varToExpr(source), argToExpr(decr)));
    }
  }

  @Override
  public void assignString(Var target, Arg src) {
    assert(src.isImmediateString());
    if (!Types.isString(target.type())) {
      throw new STCRuntimeError("Expected variable to be string, "
          + " but was " + target.type().toString());
    }

    pointStack.peek().add(Turbine.stringSet(
        varToExpr(target), argToExpr(src)));
  }

  @Override
  public void retrieveString(Var target, Var source, Arg decr) {
    assert(Types.isString(source));
    assert(Types.isStringVal(target));
    assert(decr.isImmediateInt());
    if (decr.equals(Arg.ZERO)) {
      pointStack.peek().add(Turbine.stringGet(prefixVar(target),
                                                      varToExpr(source)));
    } else {
      pointStack.peek().add(Turbine.stringDecrGet(prefixVar(target),
          varToExpr(source), argToExpr(decr)));
    }
  }

  @Override
  public void assignBlob(Var target, Arg src) {
    assert(Types.isBlob(target.type()));
    assert(src.isImmediateBlob());
    pointStack.peek().add(Turbine.blobSet(varToExpr(target),
                                          argToExpr(src)));
  }

  @Override
  public void retrieveBlob(Var target, Var src, Arg decr) {
    assert(Types.isBlobVal(target));
    assert(Types.isBlob(src.type()));
    assert(decr.isImmediateInt());
    if (decr.equals(Arg.ZERO)) {
      pointStack.peek().add(Turbine.blobGet(prefixVar(target),
                                                  varToExpr(src)));
    } else {
      pointStack.peek().add(Turbine.blobDecrGet(prefixVar(target),
                                      varToExpr(src), argToExpr(decr)));
    }
  }

  @Override
  public void freeBlob(Var blobVal) {
    assert(Types.isBlobVal(blobVal));
    pointStack.peek().add(Turbine.freeLocalBlob(varToExpr(blobVal)));
  }

  @Override
  public void assignFile(Var target, Arg src) {
    assert(Types.isFile(target.type()));
    assert(Types.isFileVal(src.type()));
    pointStack.peek().add(Turbine.fileSet(varToExpr(target),
                            prefixVar(src.getVar())));
  }

  @Override
  public void retrieveFile(Var target, Var src, Arg decr) {
    assert(Types.isFile(src));
    assert(Types.isFileVal(target));
    assert(decr.isImmediateInt());
    if (decr.equals(Arg.ZERO)) {
      pointStack.peek().add(Turbine.fileGet(prefixVar(target), varToExpr(src)));
    } else {
      pointStack.peek().add(Turbine.fileDecrGet(prefixVar(target),
          varToExpr(src), argToExpr(decr)));
    }
  }

  @Override
  public void decrLocalFileRef(Var localFile) {
    assert(Types.isFileVal(localFile));
    pointStack.peek().add(Turbine.decrLocalFileRef(prefixVar(localFile)));
  }
  
  @Override
  public void getFileName(Var filename, Var file) {
    assert(Types.isString(filename.type()));
    assert(filename.storage() == Alloc.ALIAS);
    assert(Types.isFile(file.type()));
    
    SetVariable cmd = new SetVariable(prefixVar(filename),
                          Turbine.getFileName(varToExpr(file)));
    pointStack.peek().add(cmd);
  }
  
  @Override
  public void getLocalFileName(Var filename, Var file) {
    assert(Types.isStringVal(filename));
    assert(Types.isFileVal(file));
    pointStack.peek().add(new SetVariable(prefixVar(filename),
                        Turbine.localFilePath(varToExpr(file))));
  }
  
  @Override
  public void isMapped(Var isMapped, Var file) {
    assert(Types.isFile(file));
    assert(Types.isBoolVal(isMapped));
    pointStack.peek().add(Turbine.isMapped(prefixVar(isMapped),
                                           varToExpr(file)));
  }
  
  @Override
  public void setFilenameVal(Var file, Arg filenameVal) {
    assert(Types.isFile(file.type()));
    assert(filenameVal.isImmediateString());
    pointStack.peek().add(Turbine.setFilenameVal(varToExpr(file),
              argToExpr(filenameVal)));
  }
  
  @Override
  public void chooseTmpFilename(Var filenameVal) {
    assert(Types.isStringVal(filenameVal));
    pointStack.peek().add(Turbine.mkTemp(prefixVar(filenameVal)));
  }

  @Override
  public void initLocalOutputFile(Var localFile, Arg filenameVal, Arg isMapped) {
    assert(Types.isFileVal(localFile));
    FileKind fileKind = localFile.type().fileKind();
    assert(filenameVal.isImmediateString());
    assert(isMapped.isImmediateBool());
    assert(isMapped.isVar() || isMapped.getBoolLit() ||
        fileKind.supportsTmpImmediate()) : "Can't give unmapped file of kind " 
      + fileKind.typeName() + " a generated temporary file name";
    
    // Initialize refcount to 1 if unmapped, or 2 if mapped so that the file
    // isn't deleted upon the block finishing
    Sequence ifMapped = new Sequence(), ifUnmapped = new Sequence();
    ifMapped.add(new SetVariable(TCLTMP_INIT_REFCOUNT, LiteralInt.TWO));
    ifUnmapped.add(new SetVariable(TCLTMP_INIT_REFCOUNT, LiteralInt.ONE));
    
    if (isMapped.isBoolVal()) {
      if (isMapped.getBoolLit()) {
        pointStack.peek().append(ifMapped);  
      } else {
        pointStack.peek().append(ifUnmapped);
      }
    } else {
      pointStack.peek().add(new If(argToExpr(isMapped), ifMapped, ifUnmapped)); 
    }
    pointStack.peek().add(Turbine.createLocalFile(prefixVar(localFile),
             argToExpr(filenameVal), new Value(TCLTMP_INIT_REFCOUNT)));
  }
  
  @Override
  public void copyFileContents(Var dst, Var src) {
    assert(Types.isFileVal(dst));
    assert(Types.isFileVal(src));
    FileKind dstKind = dst.type().fileKind();
    assert(dstKind.supportsPhysicalCopy());
    FileKind srcKind = src.type().fileKind();
    assert(srcKind.supportsPhysicalCopy());
    if (dstKind == FileKind.LOCAL_FS &&
        srcKind == FileKind.LOCAL_FS) {
      pointStack.peek().add(Turbine.copyFileContents(varToExpr(dst),
                                                     varToExpr(src)));
    } else {
      throw new STCRuntimeError("Don't know how to copy " + srcKind + " -> "
                                                                  + dstKind);
    }
  }
  
  @Override
  public void localOp(BuiltinOpcode op, Var out,
                                            List<Arg> in) {
    ArrayList<Expression> argExpr = new ArrayList<Expression>(in.size());
    for (Arg a: in) {
      argExpr.add(argToExpr(a));
    }

    pointStack.peek().add(BuiltinOps.genLocalOpTcl(op, out, in, argExpr));
  }
  
  @Override
  public void asyncOp(BuiltinOpcode op, Var out, List<Arg> in,
                      TaskProps props) {
    assert(props != null);
    props.assertInternalTypesValid();
    
    //TODO: for time being, share code with built-in function generation
    TclFunRef fn = BuiltinOps.getBuiltinOpImpl(op);
    if (fn == null) {
      List<String> impls = ForeignFunctions.findOpImpl(op);
      
      // It should be impossible for there to be no implementation for a function
      // like this
      assert(impls != null) : op;
      assert(impls.size() > 0) : op;
      
      if (impls.size() > 1) {
        Logging.getSTCLogger().warn("Multiple implementations for operation " +
            op + ": " + impls.toString());
      }
      fn = builtinSymbols.get(impls.get(0));
    }
    
    List<Var> outL = (out == null) ? 
          Arrays.<Var>asList() : Arrays.asList(out);

    builtinFunctionCall("operator: " + op.toString(), fn, 
                        in, outL, props);
  }

  @Override
  public void dereferenceInt(Var target, Var src) {
    assert(Types.isInt(target.type()));
    assert(Types.isIntRef(src));
    Command deref = Turbine.dereferenceInteger(varToExpr(target),
                                               varToExpr(src));
    pointStack.peek().add(deref);
  }

  @Override
  public void dereferenceBool(Var target, Var src) {
    assert(Types.isBool(target.type()));
    assert(Types.isBoolRef(src));
    Command deref = Turbine.dereferenceInteger(varToExpr(target),
                                               varToExpr(src));
    pointStack.peek().add(deref);
  }

  @Override
  public void dereferenceFloat(Var target, Var src) {
    assert(Types.isFloat(target));
    assert(Types.isFloatRef(src));
    Command deref = Turbine.dereferenceFloat(varToExpr(target),
                                             varToExpr(src));
    pointStack.peek().add(deref);
  }

  @Override
  public void dereferenceString(Var target, Var src) {
    assert(Types.isString(target));
    assert(Types.isStringRef(src));
    Command deref = Turbine.dereferenceString(varToExpr(target), 
                                              varToExpr(src));
    pointStack.peek().add(deref);
  }

  @Override
  public void dereferenceBlob(Var target, Var src) {
    assert(Types.isBlob(target));
    assert(Types.isBlobRef(src));
    Command deref = Turbine.dereferenceBlob(varToExpr(target), varToExpr(src));
    pointStack.peek().add(deref);
  }
  
  @Override
  public void dereferenceFile(Var target, Var src) {
    assert(Types.isFile(target));
    assert(Types.isFileRef(src));
    Command deref = Turbine.dereferenceFile(varToExpr(target),
                                            varToExpr(src));
    pointStack.peek().add(deref);
  }

  @Override
  public void retrieveRef(Var target, Var src, Arg decr) {
    assert(Types.isRef(src.type()));
    assert(Types.isAssignableRefTo(src.type(), target.type()));
    assert(decr.isImmediateInt());
    TclTree deref;
    if (Types.isStructRef(src.type())) {
      if (decr.equals(Arg.ZERO)) {
        deref = Turbine.structRefGet(prefixVar(target), varToExpr(src));
      } else {
        deref = Turbine.structRefDecrGet(prefixVar(target), varToExpr(src),
                                      argToExpr(decr));
      }
    } else if (Types.isFileRef(src.type())) {
      if (decr.equals(Arg.ZERO)) {
        deref = Turbine.fileRefGet(prefixVar(target), varToExpr(src));
      } else {
        deref = Turbine.fileRefDecrGet(prefixVar(target), varToExpr(src),
            argToExpr(decr));
      }
    } else {
      if (decr.equals(Arg.ZERO)) {
        deref = Turbine.refGet(prefixVar(target), varToExpr(src));
      } else {
        deref = Turbine.refDecrGet(prefixVar(target), varToExpr(src),
                                    argToExpr(decr));
      }
    }
    pointStack.peek().add(deref);
  }

  @Override
  public void arrayCreateNestedFuture(Var arrayResult,
      Var array, Var ix) {
    assert(Types.isArray(array.type()));
    assert(Types.isArrayRef(arrayResult.type()));
    assert(Types.isArrayKeyFuture(array, ix));
    assert(arrayResult.storage() != Alloc.ALIAS);
    TclTree t = Turbine.containerCreateNested(
        varToExpr(arrayResult), varToExpr(array),
        varToExpr(ix), arrayValueType(arrayResult, true));
    pointStack.peek().add(t);
  }

  @Override
  public void arrayRefCreateNestedFuture(Var arrayResult,
      Var outerArray, Var arrayRefVar, Var ix) {
    assert(Types.isArrayRef(arrayRefVar.type()));
    assert(Types.isArrayRef(arrayResult.type()));
    assert(arrayResult.storage() != Alloc.ALIAS);
    assert(Types.isArrayKeyFuture(arrayRefVar, ix));

    TclTree t = Turbine.containerRefCreateNested(
        varToExpr(arrayResult), varToExpr(arrayRefVar),
        varToExpr(ix), varToExpr(outerArray),
        refRepresentationType(arrayResult.type().memberType(), false));
    pointStack.peek().add(t);
  }


  @Override
  public void arrayCreateNestedImm(Var arrayResult, Var array, Arg ix,
        Arg callerReadRefs, Arg callerWriteRefs) {
    assert(Types.isArray(array.type()));
    assert(Types.isArray(arrayResult.type()));
    assert(arrayResult.storage() == Alloc.ALIAS);
    assert(Types.isArrayKeyVal(array, ix));
    assert(callerReadRefs.isImmediateInt());
    assert(callerWriteRefs.isImmediateInt());
    
    TclTree t = Turbine.containerCreateNestedImmIx(
        prefixVar(arrayResult), varToExpr(array), argToExpr(ix),
        arrayValueType(arrayResult, false), argToExpr(callerReadRefs),
        argToExpr(callerWriteRefs));
    pointStack.peek().add(t);
  }

  @Override
  public void arrayRefCreateNestedImm(Var arrayResult,
      Var outerArray, Var array, Arg ix) {
    assert(Types.isArrayRef(array.type()));
    assert(Types.isArrayRef(arrayResult.type()));
    assert(arrayResult.storage() != Alloc.ALIAS);
    assert(Types.isArrayKeyVal(array, ix));

    TclTree t = Turbine.containerRefCreateNestedImmIx(
        varToExpr(arrayResult), varToExpr(array),
        argToExpr(ix), varToExpr(outerArray),
        arrayValueType(arrayResult.type(), true));
    pointStack.peek().add(t);
  }

  @Override
  public void builtinFunctionCall(String function,
          List<Arg> inputs, List<Var> outputs, TaskProps props) {
    assert(props != null);
    props.assertInternalTypesValid();
    logger.debug("call builtin: " + function);
    TclFunRef tclf = builtinSymbols.get(function);
    assert tclf != null : "Builtin " + function + "not found";
    ForeignFunctions.getTaskMode(function).checkSpawn(execContextStack.peek());

    builtinFunctionCall(function, tclf, inputs, outputs, props);
  }

  private void builtinFunctionCall(String function, TclFunRef tclf,
      List<Arg> inputs, List<Var> outputs, TaskProps props) {
    assert(props != null);
    props.assertInternalTypesValid();
    
    TclList iList = TclUtil.tclListOfArgs(inputs);
    TclList oList = TclUtil.tclListOfVariables(outputs);
    
    if (tclf == null) {
      //should have all builtins in symbols
      throw new STCRuntimeError("call to undefined builtin function "
          + function);
    }

    // Properties can be null
    Arg priority = props.get(TaskPropKey.PRIORITY);
    TclTarget target = TclTarget.fromArg(props.get(TaskPropKey.LOCATION));
    Expression parExpr = TclUtil.argToExpr(props.get(TaskPropKey.PARALLELISM),
                                           true);

    setPriority(priority);
    
    Token tclFunction = new Token(tclf.pkg + "::" + tclf.symbol);
    List<Expression> funcArgs = new ArrayList<Expression>();
    funcArgs.add(oList);
    funcArgs.add(iList);
    funcArgs.addAll(Turbine.ruleKeywordArgs(target, parExpr));
    Command c = new Command(tclFunction, funcArgs);
    pointStack.peek().add(c);
    
    clearPriority(priority);
  }

  @Override
  public void builtinLocalFunctionCall(String functionName,
          List<Arg> inputs, List<Var> outputs) {
    TclOpTemplate template = ForeignFunctions.getInlineTemplate(functionName);
    assert(template != null);
    
    List<TclTree> result = TclTemplateProcessor.processTemplate(template,
                                                        inputs, outputs);
    
    Command cmd = new Command(result.toArray(new TclTree[result.size()]));
    pointStack.peek().add(cmd);
  }

  
  
  @Override
  public void functionCall(String function,
              List<Arg> inputs, List<Var> outputs,
              List<Boolean> blocking, TaskMode mode, TaskProps props)  {
    props.assertInternalTypesValid();
    
    List<Value> blockOn = new ArrayList<Value>();
    Set<Var> alreadyBlocking = new HashSet<Var>();
    for (int i = 0; i < inputs.size(); i++) {
      Arg arg = inputs.get(i);
      if (arg.isVar()) {
        Var v = arg.getVar();
        if (blocking.get(i) && !alreadyBlocking.contains(v)) {
          blockOn.add(varToExpr(v));
          alreadyBlocking.add(v);
        }
      }
    }

    String swiftFuncName = TclNamer.swiftFuncName(function);
    if (mode == TaskMode.CONTROL || mode == TaskMode.LOCAL ||
        mode == TaskMode.LOCAL_CONTROL) {
      List<Expression> args = new ArrayList<Expression>();
      args.addAll(TclUtil.varsToExpr(outputs));
      args.addAll(TclUtil.argsToExpr(inputs));
      List<Expression> action = buildAction(swiftFuncName, args);
      
      Sequence rule = Turbine.rule(function, blockOn, action, mode,
                       execContextStack.peek(), buildRuleProps(props));
      pointStack.peek().append(rule);
      
    } else if (mode == TaskMode.SYNC) {
      // Calling synchronously, can't guarantee anything blocks
      assert blockOn.size() == 0 : function + ": " + blockOn;
      
      List<Expression> inVars = TclUtil.argsToExpr(inputs);
      List<Expression> outVars = TclUtil.varsToExpr(outputs);
      
      pointStack.peek().add(Turbine.callFunctionSync(
          swiftFuncName, outVars, inVars));
    } else {
      throw new STCRuntimeError("Unexpected mode: " + mode);
    }
  }

  private RuleProps buildRuleProps(TaskProps props) {
    Expression priority = TclUtil.argToExpr(
                    props.get(TaskPropKey.PRIORITY), true);
    TclTarget target = TclTarget.fromArg(props.get(TaskPropKey.LOCATION));
    Expression parallelism = TclUtil.argToExpr(
                      props.get(TaskPropKey.PARALLELISM), true);
    RuleProps ruleProps = new RuleProps(target, parallelism, priority);
    return ruleProps;
  }

  @Override
  public void runExternal(String cmd, List<Arg> args,
          List<Arg> inFiles, List<Var> outFiles, 
          Redirects<Arg> redirects,
          boolean hasSideEffects, boolean deterministic) {
    for (Arg inFile: inFiles) {
      assert(inFile.isVar());
      assert(Types.isFileVal(inFile.type()));
    }
    
    List<Expression> tclArgs = new ArrayList<Expression>(args.size());
    List<Expression> logMsg = new ArrayList<Expression>();
    logMsg.add(new Token("exec: " + cmd));
    
    for (int argNum = 0; argNum < args.size(); argNum++) {
      Arg arg = args.get(argNum);
      Expression argExpr;
      if (Types.isArray(arg.type())) {
        // Special case: expand arrays to list
        // Use temporary variable to avoid double evaluation
        ArrayInfo ai = new ArrayInfo(arg.type());
        String unpackTmp = TCLTMP_UNPACKED + argNum;
        pointStack.peek().add(new SetVariable(unpackTmp, Turbine.unpackArray(
                                argToExpr(arg), ai.nesting,
                                Types.isFile(ai.baseType))));  
        argExpr = new Expand(new Value(unpackTmp));
      } else {
        argExpr = argToExpr(arg);
      }
      tclArgs.add(argExpr);
      logMsg.add(argExpr);
    }
    
    
    Expression stdinFilename = TclUtil.argToExpr(redirects.stdin, true);
    Expression stdoutFilename = TclUtil.argToExpr(redirects.stdout, true);
    Expression stderrFilename = TclUtil.argToExpr(redirects.stderr, true);
    logMsg.add(Turbine.execKeywordOpts(stdinFilename, stdoutFilename,
                                       stderrFilename));
    
    pointStack.peek().add(Turbine.turbineLog(logMsg));
    pointStack.peek().add(Turbine.exec(cmd, stdinFilename,
                stdoutFilename, stderrFilename, tclArgs));
        
    // Handle closing of outputs
    for (int i = 0; i < outFiles.size(); i++) {
      Var o = outFiles.get(i);
      if (Types.isFileVal(o)) {
        // Do nothing, filename was set when initialized earlier
      } else if (Types.isVoidVal(o)) {
        // Do nothing, void value is just a bookkeeping trick
      } else {
        throw new STCRuntimeError("Invalid app output type: " + o);
      }
    }
  }

  private void clearPriority(Arg priority) {
    if (priority != null) {
      pointStack.peek().add(Turbine.resetPriority());
    }
  }

  private void setPriority(Arg priority) {
    if (priority != null) {
      logger.trace("priority: " + priority);
      pointStack.peek().add(Turbine.setPriority(argToExpr(priority)));
    }
  }

  @Override
  public void structInitField(Var structVar, String fieldName,
      Var fieldContents) {
    pointStack.peek().add(
        Turbine.structInsert(prefixVar(structVar),
            fieldName, varToExpr(fieldContents)));
  }

  /**
   * load the turbine id of the field into alias
   * @param structVar
   * @param structField
   * @param alias
   */
  @Override
  public void structLookup(Var alias, Var structVar,
        String structField) {
    pointStack.peek().add(
        Turbine.structLookupFieldID(varToExpr(structVar),
            structField, prefixVar(alias)));
  }

  @Override
  public void structRefLookup(Var alias, Var structVar,
        String structField) {
    assert(Types.isRef(alias.type()));
    assert(Types.isStructRef(structVar));
    
    StructType structType = (StructType)structVar.type().memberType();
    int fieldID = structTypes.getFieldID(structType, structField);
    
    pointStack.peek().add(
        Turbine.structRefLookupFieldID(varToExpr(structVar),
            fieldID, varToExpr(alias),
            refRepresentationType(alias.type().memberType(), false)));
  }


  @Override
  public void arrayLookupFuture(Var oVar, Var arrayVar, Var indexVar,
        boolean isArrayRef) {
    arrayLoadCheckTypes(oVar, arrayVar, isArrayRef);
    assert(Types.isArrayKeyFuture(arrayVar, indexVar));
    assert(Types.isRef(oVar.type()));
    // Nested arrays - oVar should be a reference type
    Command getRef = Turbine.arrayLookupComputed(varToExpr(oVar), 
        refRepresentationType(oVar.type().memberType(), false),
        varToExpr(arrayVar), varToExpr(indexVar), isArrayRef);
    pointStack.peek().add(getRef);
  }

  @Override
  public void arrayLookupRefImm(Var oVar, Var arrayVar, Arg arrIx,
        boolean isArrayRef) {
    assert(Types.isArrayKeyVal(arrayVar, arrIx));
    
    arrayLoadCheckTypes(oVar, arrayVar, isArrayRef);
    Command getRef = Turbine.arrayLookupImmIx(
          varToExpr(oVar),
          arrayValueType(arrayVar.type(), false),
          varToExpr(arrayVar),
          argToExpr(arrIx), isArrayRef);

    pointStack.peek().add(getRef);
  }

  @Override
  public void arrayLookupImm(Var oVar, Var arrayVar, Arg arrIx) {
    assert(Types.isArrayKeyVal(arrayVar, arrIx));
    assert(oVar.type().equals(Types.arrayMemberType(arrayVar.type())));
     pointStack.peek().add(Turbine.arrayLookupImm(
         prefixVar(oVar),
         varToExpr(arrayVar),
         argToExpr(arrIx)));
  }

  /**
   * Make sure that types are valid for array load invocation
   * @param oVar The variable the result of the array should go into
   * @param arrayVar
   * @param isReference
   * @return the member type of the array
   */
  private Type arrayLoadCheckTypes(Var oVar, Var arrayVar,
      boolean isReference) {
    Type memberType;
    // Check that the types of the array variable are correct
    if (isReference) {
      assert(Types.isArrayRef(arrayVar.type()));
      Type arrayType = arrayVar.type().memberType();
      assert(Types.isArray(arrayType));
      memberType = arrayType.memberType();
    } else {
      assert(Types.isArray(arrayVar.type()));
      memberType = arrayVar.type().memberType();
    }


    Type oType = oVar.type();
    if (!Types.isRef(oType)) {
      throw new STCRuntimeError("Output variable for " +
          "array lookup should be a reference " +
          " but had type " + oType.toString());
    }
    if (!oType.memberType().equals(memberType)) {
      throw new STCRuntimeError("Output variable for "
          +" array lookup should be reference to "
          + memberType.toString() + ", but was reference to"
          + oType.memberType().toString());
    }

    return memberType;
  }

  @Override
  public void arrayInsertFuture(Var array, Var ix, Var member,
                                Arg writersDecr) {
    assert(Types.isArray(array.type()));
    assert(member.type().assignableTo(Types.arrayMemberType(array.type())));
    assert(writersDecr.isImmediateInt());
    assert(Types.isArrayKeyFuture(array, ix));

    Command r = Turbine.arrayStoreComputed(
        varToExpr(member), varToExpr(array),
        varToExpr(ix), argToExpr(writersDecr),
        arrayValueType(array, false));

    pointStack.peek().add(r);
  }
  
  @Override
  public void arrayDerefInsertFuture(Var array, Var ix, Var member,
                                Arg writersDecr) {
    assert(Types.isArray(array.type()));
    assert(Types.isArrayKeyFuture(array, ix));
    assert(writersDecr.isImmediateInt());
    assert(Types.isAssignableRefTo(member.type(),
                                   Types.arrayMemberType(array.type())));
    
    Command r = Turbine.arrayDerefStoreComputed(
        varToExpr(member), varToExpr(array),
        varToExpr(ix), argToExpr(writersDecr),
        arrayValueType(array, false));

    pointStack.peek().add(r);
  }

  @Override
  public void arrayRefInsertFuture(Var outerArray, Var array, Var ix, Var member) {
    assert(Types.isArrayRef(array.type()));
    assert(Types.isArray(outerArray.type()));
    assert(Types.isArrayKeyFuture(array, ix));
    assert(member.type().assignableTo(Types.arrayMemberType(array.type())));
    Command r = Turbine.arrayRefStoreComputed(
        varToExpr(member), varToExpr(array),
        varToExpr(ix), varToExpr(outerArray),
        arrayValueType(array, false));

    pointStack.peek().add(r);
  }
  
  @Override
  public void arrayRefDerefInsertFuture(Var outerArray, Var array, Var ix,
                                        Var member) {
    assert(Types.isArrayRef(array.type()));
    assert(Types.isArray(outerArray.type()));
    assert(Types.isArrayKeyFuture(array, ix));
    assert(Types.isAssignableRefTo(member.type(),
                                   Types.arrayMemberType(array.type())));
    
    Command r = Turbine.arrayRefDerefStoreComputed(
        varToExpr(member), varToExpr(array),
        varToExpr(ix), varToExpr(outerArray),
        arrayValueType(array, false));

    pointStack.peek().add(r);
  }

  @Override
  public void arrayBuild(Var array, List<Arg> keys, List<Var> vals) {
    assert(Types.isArray(array.type()));
    assert(keys.size() == vals.size());
    int elemCount = keys.size();
    
    List<Expression> keyExprs = new ArrayList<Expression>(elemCount * 2);
    List<Expression> valExprs = new ArrayList<Expression>(elemCount);
    for (int i = 0; i < elemCount; i++) {
      Arg key = keys.get(i);
      Var val = vals.get(i);
      assert(Types.isMemberType(array, val));
      assert(Types.isArrayKeyVal(array, key));
      keyExprs.add(argToExpr(key));
      valExprs.add(varToExpr(val));
    }
    pointStack.peek().add(
        Turbine.arrayBuild(varToExpr(array), keyExprs, valExprs, true,
                           arrayValueType(array, false)));
  }
  
  @Override
  public void arrayInsertImm(Var array, Arg arrIx, Var member, Arg writersDecr) {
    assert(Types.isArray(array.type()));
    assert(Types.isArrayKeyVal(array, arrIx));
    assert(writersDecr.isImmediateInt());
    assert(Types.isMemberType(array, member));
    
    Command r = Turbine.arrayStoreImmediate(
        varToExpr(member), varToExpr(array),
        argToExpr(arrIx), argToExpr(writersDecr),
        arrayValueType(array, false));
    pointStack.peek().add(r);
  }
  
  
  @Override
  public void arrayDerefInsertImm(Var array, Arg arrIx, Var member, Arg writersDecr) {
    assert(Types.isArray(array.type()));
    assert(Types.isArrayKeyVal(array, arrIx));
    assert(writersDecr.isImmediateInt());
    // Check that we get the right thing when we dereference it
    assert(Types.isAssignableRefTo(member.type(),
                                   Types.arrayMemberType(array.type())));
    Command r = Turbine.arrayDerefStore(
        varToExpr(member), varToExpr(array),
        argToExpr(arrIx), argToExpr(writersDecr),
        arrayValueType(array, false));
    pointStack.peek().add(r);
  }

  @Override
  public void arrayRefInsertImm(Var outerArray, Var array, Arg arrIx,
                                Var member) {
    assert(Types.isArrayRef(array.type()));
    assert(Types.isArray(outerArray.type()));
    assert(Types.isArrayKeyVal(array, arrIx));
    assert(member.type().assignableTo(Types.arrayMemberType(array.type())));
    Command r = Turbine.arrayRefStoreImmediate(
        varToExpr(member), varToExpr(array),
        argToExpr(arrIx), varToExpr(outerArray),
        arrayValueType(array, false));
    pointStack.peek().add(r);
  }
  
  @Override
  public void arrayRefDerefInsertImm(Var outerArray, Var array, Arg arrIx,
                                     Var member) {
    assert(Types.isArrayRef(array.type()));
    assert(Types.isArray(outerArray.type()));
    assert(Types.isArrayKeyVal(array, arrIx));
    Type memberType = array.type().memberType().memberType();
    // Check that we get the right thing when we dereference it
    assert(Types.isAssignableRefTo(member.type(),
                                   Types.arrayMemberType(array.type())));
    if (!member.type().memberType().equals(memberType)) {
      throw new STCRuntimeError("Type mismatch when trying to store " +
          "from variable " + member.toString() + " into array " + array.toString());
    }
    Command r = Turbine.arrayRefDerefStore(
        varToExpr(member), varToExpr(array),
        argToExpr(arrIx), varToExpr(outerArray),
        arrayValueType(array, false));
    pointStack.peek().add(r);
  }

  @Override
  public void bagInsert(Var bag, Var elem, Arg writersDecr) {
    assert(Types.isBagElem(bag, elem));
    pointStack.peek().add(Turbine.bagAppend(varToExpr(bag),
          refRepresentationType(elem.type(), false), varToExpr(elem),
          argToExpr(writersDecr)));
  }
  
  @Override
  public void initUpdateable(Var updateable, Arg val) {
    assert(Types.isScalarUpdateable(updateable.type()));
    if (!updateable.type().equals(Types.UP_FLOAT)) {
      throw new STCRuntimeError(updateable.type() +
          " not yet supported");
    }
    assert(val.isImmediateFloat());
    pointStack.peek().add(Turbine.updateableFloatInit(varToExpr(updateable),
                                                      argToExpr(val)));
  }
  
  @Override
  public void arrayCreateBag(Var bag, Var arr, Arg ix, Arg callerReadRefs,
      Arg callerWriteRefs) {
    assert(Types.isBag(bag));
    assert(bag.storage() == Alloc.ALIAS);
    assert(Types.isArrayKeyVal(arr, ix));
    assert(Types.isMemberType(arr, bag));
    assert(callerReadRefs.isImmediateInt());
    assert(callerWriteRefs.isImmediateInt());
    
    throw new STCRuntimeError("arrayCreateBag not implemented in " +
    		"turbine generator");
  }

  @Override
  public void latestValue(Var result, Var updateable) {
    assert(Types.isScalarUpdateable(updateable.type()));
    assert(Types.isScalarValue(result.type()));
    assert(updateable.type().primType() ==
                  result.type().primType());
    if (!updateable.type().equals(Types.UP_FLOAT)) {
      throw new STCRuntimeError(updateable.type().typeName()
              + " not yet supported");
    }
    // get with caching disabled
    pointStack.peek().add(Turbine.floatGet(prefixVar(result),
                          varToExpr(updateable), CacheMode.UNCACHED));
  }

  @Override
  public void update(Var updateable, UpdateMode updateMode, Var val) {
    assert(Types.isScalarUpdateable(updateable.type()));
    assert(Types.isScalarFuture(val.type()));
    assert(updateable.type().primType() == val.type().primType());
    assert(updateMode != null);
    String builtinName = getUpdateBuiltin(updateMode);
    pointStack.peek().add(new Command(builtinName, Arrays.asList(
                  (Expression)varToExpr(updateable), varToExpr(val))));
  }

  private String getUpdateBuiltin(UpdateMode updateMode) {
    String builtinName;
    switch(updateMode) {
    case INCR:
      builtinName = "turbine::update_incr";
      break;
    case MIN:
      builtinName = "turbine::update_min";
      break;
    case SCALE:
      builtinName = "turbine::update_scale";
      break;
    default:
      throw new STCRuntimeError("Unknown UpdateMode: " + updateMode);
    }
    return builtinName;
  }

  @Override
  public void updateImm(Var updateable, UpdateMode updateMode,
                                                Arg val) {
    assert(Types.isScalarUpdateable(updateable.type()));
    if (updateable.type().equals(Types.UP_FLOAT)) {
      assert(val.isImmediateFloat());
    } else {
      throw new STCRuntimeError("only updateable floats are"
          + " implemented so far");
    }
    assert(updateMode != null);
    String builtinName = getUpdateBuiltin(updateMode) + "_impl";
    pointStack.peek().add(new Command(builtinName, Arrays.asList(
        (Expression)varToExpr(updateable), argToExpr(val))));
  }

  /** This prevents duplicate "package require" statements */
  private final Set<String> requiredPackages = new HashSet<String>();

  @Override
  public void requirePackage(String pkg, String version) {
    String pv = pkg + version;
    if (!pkg.equals("turbine")) {
      if (!requiredPackages.contains(pv))
      {
        PackageRequire pr = new PackageRequire(pkg, version);
        pointStack.peek().add(pr);
        requiredPackages.add(pv);
        pointStack.peek().add(new Command(""));
      }
    }
  }
  
  @Override
  public void defineBuiltinFunction(String name, FunctionType type,
            TclFunRef impl) {
    builtinSymbols.put(name, impl);
    logger.debug("TurbineGenerator: Defined built-in " + name);
  }

  @Override
  public void startFunction(String functionName,
                                     List<Var> oList,
                                     List<Var> iList,
                                     TaskMode mode)
  throws UserException
  {
    List<String> outputs = prefixVars(Var.nameList(oList));
    List<String> inputs  = prefixVars(Var.nameList(iList));
    // System.out.println("function" + functionName);
    boolean isMain = functionName.equals(Constants.MAIN_FUNCTION);
    String prefixedFunctionName = null;
    if (isMain)
      prefixedFunctionName = MAIN_FUNCTION_NAME;
    else
      prefixedFunctionName = TclNamer.swiftFuncName(functionName);

    List<String> args =
      new ArrayList<String>(inputs.size()+outputs.size());
    if (!isMain) {
      args.add(Turbine.LOCAL_STACK_NAME);
    }
    args.addAll(outputs);
    args.addAll(inputs);

    // This better be the bottom
    Sequence point = pointStack.peek();

    Sequence s = new Sequence();
    Proc proc = new Proc(prefixedFunctionName,
                         usedTclFunctionNames, args, s);

    point.add(proc);
    s.add(Turbine.turbineLog("enter function: " +
                             functionName));

    if (noStack() && isMain) {
      s.add(Turbine.createDummyStackFrame());
    }

    if (!noStack()) {
      TclTree[] setupStack;
      if (isMain) {
        setupStack = Turbine.createStackFrame(StackFrameType.MAIN);
      } else {
        setupStack = Turbine.createStackFrame(StackFrameType.FUNCTION);
      }
      s.add(setupStack);
      if (!noStackVars()) {
        for (Var v : iList)
        {
          Command command = Turbine.storeInStack(v.name(),
                                      prefixVar(v));
          s.add(command);
        }
        for (Var v : oList)
        {
          Command command = Turbine.storeInStack(v.name(),
                                            prefixVar(v));
          s.add(command);
        }
      }
    }

    pointStack.push(s);
  }

    @Override
    public void endFunction()
  {
    pointStack.pop();
  }

    @Override
    public void startNestedBlock()
  {
    Sequence block = new Sequence();
    if (!noStack()) {
      TclTree[] t = Turbine.createStackFrame(StackFrameType.NESTED);
      block.add(t);
    }
    Sequence point = pointStack.peek();
    point.add(block);
    pointStack.push(block);
  }

    @Override
    public void endNestedBlock() {
    pointStack.pop();
  }

    @Override
    public void addComment(String comment) {
      pointStack.peek().add(new Comment(comment));
    }

  /** NOT UPDATED */

  /**
   * @param condition the variable name to branch based on
   * @param hasElse whether there will be an else clause ie. whether startElseBlock()
   *                will be called later for this if statement
   */
    @Override
    public void startIfStatement(Arg condition, boolean hasElse)
  {
    logger.trace("startIfStatement()...");
    assert(condition != null);
    assert(!condition.isVar()
        || condition.getVar().storage() == Alloc.LOCAL);
    assert(condition.isImmediateBool() || condition.isImmediateInt());


    Sequence thenBlock = new Sequence();
    Sequence elseBlock = hasElse ? new Sequence() : null;
    if (!noStack()) {
      thenBlock.add(Turbine.createStackFrame(StackFrameType.NESTED));
      if (hasElse) {
        elseBlock.add(Turbine.createStackFrame(StackFrameType.NESTED));
      }
    }

    If i = new If(argToExpr(condition),
        thenBlock, elseBlock);
    pointStack.peek().add(i);

    if (hasElse) {
       // Put it on the stack so it can be fetched when we start else block
      pointStack.push(elseBlock);
    }
    pointStack.push(thenBlock);
  }

  @Override
    public void startElseBlock() {
      logger.trace("startElseBlock()...");
    pointStack.pop(); // Remove then block
  }

    @Override
    public void endIfStatement()
  {
    logger.trace("endIfStatement()...");
    pointStack.pop();
  }

    @Override
    public void startWaitStatement(String procName, List<Var> waitVars,
        List<Var> passIn, boolean recursive, TaskMode target,
        TaskProps props) {
      logger.trace("startWaitStatement()...");
      startAsync(procName, waitVars, passIn, recursive, target, props);
    }

    @Override
    public void endWaitStatement() {
      logger.trace("endWaitStatement()...");
      endAsync();
    }

    /**
     * Internal helper to implement constructs that need to wait for
     * a number of variables, and then run some code
     * @param procName
     * @param waitVars
     * @param passIn
     * @param keepOpenVars
     * @param priority 
     * @param recursive
     */
    private void startAsync(String procName, List<Var> waitVars,
        List<Var> passIn, boolean recursive, TaskMode mode, TaskProps props) {
      props.assertInternalTypesValid();
      mode.checkSpawn(execContextStack.peek());
      for (Var v: passIn) {
        if (Types.isBlobVal(v)) {
          throw new STCRuntimeError("Can't directly pass blob value");
        }
      }
      
      List<String> args = new ArrayList<String>();
      args.add(Turbine.LOCAL_STACK_NAME);
      for (Var v: passIn) {
        args.add(prefixVar(v));
      }

      Sequence constructProc = new Sequence();

      String uniqueName = uniqueTCLFunctionName(procName);
      Proc proc = new Proc(uniqueName, usedTclFunctionNames, args,
                                                    constructProc);
      tree.add(proc);

      boolean useDeepWait = false; // True if we have to use special wait impl. 
      
      // Build up the rule string
      List<Expression> waitFor = new ArrayList<Expression>();
      for (Var w: waitVars) {
        if (recursive) {
          Type baseType = w.type();
          if (Types.isArray(w.type())) {
            baseType = new ArrayInfo(w.type()).baseType;
            useDeepWait = true;
          }
          if (Types.isPrimFuture(baseType)) {
            // ok
          } else if (Types.isRef(baseType)) {
            // TODO: might not be really recursive, but works for now
          } else {
            throw new STCRuntimeError("Recursive wait not yet supported"
                + " for type: " + w.type().typeName());
          }
        }
        
        Expression waitExpr = getTurbineWaitId(w);
        waitFor.add(waitExpr);
      }
      
      
      List<Expression> action = buildActionFromVars(uniqueName, passIn);

      RuleProps ruleProps = buildRuleProps(props);      
      if (useDeepWait) {
        // Nesting depth of arrays (0 == not array)
        int depths[] = new int[waitVars.size()];
        boolean isFile[] = new boolean[waitVars.size()];
        for (int i = 0; i < waitVars.size(); i++) {
          Type waitVarType = waitVars.get(i).type();
          Type baseType;
          if (Types.isArray(waitVarType)) {
            ArrayInfo ai = new ArrayInfo(waitVarType);
            depths[i] = ai.nesting;
            baseType = ai.baseType;
          } else {
            depths[i] = 0;
            baseType = waitVarType;
          }
          isFile[i] = Types.isFile(baseType);
        }

        Sequence rule = Turbine.deepRule(uniqueName, waitFor, depths,
              isFile, action, mode, execContextStack.peek(), ruleProps);
        pointStack.peek().append(rule);

      } else {
        // Whether we can enqueue rules locally
        pointStack.peek().append(Turbine.rule(uniqueName,
            waitFor, action, mode, execContextStack.peek(), ruleProps));
      }
      
      pointStack.push(constructProc);
      
      ExecContext newExecContext;
      if (mode == TaskMode.WORKER) {
        newExecContext = ExecContext.WORKER;
      } else if (mode == TaskMode.CONTROL) {
        newExecContext = ExecContext.CONTROL;
      } else {
        // Executes on same node
        newExecContext = execContextStack.peek();
      }
      execContextStack.push(newExecContext);
    }

    private void endAsync() {
      execContextStack.pop();
      pointStack.pop();
    }

    /**
     * Increment refcount of all vars by one
     * @param vars
     */
    private void incrementReaders(List<Var> vars, Expression incr) {
      pointStack.peek().append(buildIncReaders(vars, incr, false));
    }

    private void decrementReaders(List<Var> vars, Expression decr) {
      pointStack.peek().append(buildIncReaders(vars, decr, true));
    }

    /**
     * Increment readers by a
     * @param vars
     * @param incr expression for the amount of increment/decrement.  If null, assume 1
     * @param negate if true, then negate incr
     * @return
     */
    private static Sequence buildIncReaders(List<Var> vars, Expression incr, boolean negate) {
      Sequence seq = new Sequence();
      for (VarCount vc: Var.countVars(vars)) {
        Var var = vc.var;
        if (!RefCounting.hasReadRefCount(var)) {
          continue;
        }
        Expression amount;
        if (vc.count == 1 && incr == null) {
          amount = null;
        } else if (incr == null) {
          amount = new LiteralInt(vc.count);
        } else if (vc.count == 1 && incr != null) {
          amount = incr;
        } else {
          amount = TclExpr.mult(new LiteralInt(vc.count), incr);
        }
        if (Types.isFile(var.type())) {
          // Need to use different function to handle file reference
          if (negate) {
            seq.add(Turbine.decrFileRef(varToExpr(var), amount));
          } else {
            seq.add(Turbine.incrFileRef(varToExpr(var), amount));
          }
        } else {
          if (negate) {
            seq.add(Turbine.decrRef(varToExpr(var), amount));
          } else {
            seq.add(Turbine.incrRef(varToExpr(var), amount));
          }
        }
      }
      return seq;
    }

    private void incrementWriters(List<Var> keepOpenVars,
          Expression incr) {
      Sequence seq = new Sequence();
      for (VarCount vc: Var.countVars(keepOpenVars)) {
        if (!RefCounting.hasWriteRefCount(vc.var)) {
          continue;
        }
        
        if (incr == null) {
          seq.add(Turbine.incrWriters(varToExpr(vc.var), new LiteralInt(vc.count)));
        } else if (vc.count == 1) {
          seq.add(Turbine.incrWriters(varToExpr(vc.var), incr));
        } else {
          seq.add(Turbine.incrWriters(varToExpr(vc.var),
              TclExpr.mult(new LiteralInt(vc.count), incr)));
        }
      }
      pointStack.peek().append(seq);
    }

    private void decrementWriters(List<Var> vars,
                                             Expression decr) {
      Sequence seq = new Sequence();
      for (VarCount vc: Var.countVars(vars)) {
        if (!RefCounting.hasWriteRefCount(vc.var)) {
          continue;
        }
        if (decr == null) {
          seq.add(Turbine.decrWriters(varToExpr(vc.var), new LiteralInt(vc.count)));
        } else if (vc.count == 1) {
          seq.add(Turbine.decrWriters(varToExpr(vc.var), decr));
        } else {
          seq.add(Turbine.decrWriters(varToExpr(vc.var),
              TclExpr.mult(new LiteralInt(vc.count), decr)));
        }
      }
      pointStack.peek().append(seq);
    }

    private List<Expression> buildAction(String procName, List<Expression> args) {
      ArrayList<Expression> ruleTokens = new ArrayList<Expression>();
      ruleTokens.add(new Token(procName));
      ruleTokens.add(Turbine.LOCAL_STACK_VAL);
      ruleTokens.addAll(args);

      return ruleTokens; 
    }
    
    private List<Expression> buildActionFromVars(String procName, List<Var> usedVariables) {
      List<Expression> exprs = new ArrayList<Expression>();
      // Pass in variable ids directly in rule string
      for (Var v: usedVariables) {
        Type t = v.type();
        if (Types.isPrimFuture(t) || Types.isRef(t) ||
            Types.isArray(t) || Types.isStruct(t) ||
            Types.isPrimUpdateable(t) || Types.isBag(t)) {
          // Just passing turbine id
          exprs.add(varToExpr(v));
        } else if (Types.isPrimValue(t)) {
          PrimType pt = t.primType();
          if (pt == PrimType.INT || pt == PrimType.BOOL
              || pt == PrimType.FLOAT || pt == PrimType.STRING
              || pt == PrimType.FILE) {
            // Serialize
            exprs.add(varToExpr(v));
          } else if (pt == PrimType.VOID) {
            // Add a dummy token
            exprs.add(new Token("void"));
          } else {
            throw new STCRuntimeError("Don't know how to pass var " + v);
          }
        } else {
          throw new STCRuntimeError("Don't know how to pass var with type "
              + v);
        }
      }
      return buildAction(procName, exprs);
    }



    @Override
    public void startSwitch(Arg switchVar, List<Integer> caseLabels,
              boolean hasDefault) {
    logger.trace("startSwitch()...");
    assert(switchVar != null);
    assert(!switchVar.isVar() ||
        switchVar.getVar().storage() == Alloc.LOCAL);
    assert(switchVar.isImmediateInt());

    int casecount = caseLabels.size();
    if (hasDefault) casecount++;

    List<Sequence> caseBodies = new ArrayList<Sequence>(casecount);
    for (int c=0; c < casecount; c++) {
      Sequence casebody = new Sequence();
      // there might be new locals in the case
      if (!noStack()) {
        casebody.add(Turbine.createStackFrame(StackFrameType.NESTED));
      }
      caseBodies.add(casebody);
    }

    Switch sw = new Switch(argToExpr(switchVar),
        caseLabels, hasDefault, caseBodies);
    pointStack.peek().add(sw);

    for (int c = 1; c <= casecount; c++) {
      // Push case in reverse order so we can pop off as we add cases
      pointStack.push(caseBodies.get(casecount - c));
    }
  }

    @Override
    public void endCase() {
    logger.trace("endCase()...");
    // Pop the body of the last case statement off the stack
    pointStack.pop();

  }

  @Override
  public void endSwitch() {
    logger.trace("endSwitch()...");
    // don't pop anything off, last case should already be gone
  }

  @Override
  public void startForeachLoop(String loopName, Var container, Var memberVar,
        Var loopCountVar, int splitDegree, int leafDegree, boolean arrayClosed,
        List<PassedVar> passedVars, List<RefCount> perIterIncrs, 
        MultiMap<Var, RefCount> constIncrs) {

    boolean haveKeys = loopCountVar != null;
    
    if (Types.isArray(container)) {
      assert(!haveKeys || 
            Types.isArrayKeyVal(container, loopCountVar.asArg()));
      assert(Types.isMemberType(container, memberVar));
    } else {
      assert(Types.isBag(container));
      assert(!haveKeys);
      assert(Types.isBagElem(container, memberVar));
    }

    if (!arrayClosed) {
      throw new STCRuntimeError("Loops over open containers not yet supported");
    }

    String contentsVar = TCLTMP_ARRAY_CONTENTS;

    if (splitDegree <= 0) {
      // Load container contents and increment refcounts
      pointStack.peek().add(Turbine.containerContents(contentsVar,
                          varToExpr(container), haveKeys));
      Value tclDict = new Value(contentsVar);
      Expression containerSize = Turbine.dictSize(tclDict);
      handleForeachContainerRefcounts(perIterIncrs, constIncrs, containerSize);
    } else {
      startForeachSplit(loopName, container, contentsVar, splitDegree, 
          leafDegree, haveKeys, passedVars, perIterIncrs, constIncrs);
    }
    startForeachInner(new Value(contentsVar), memberVar, loopCountVar);
  }

  private void handleForeachContainerRefcounts(List<RefCount> perIterIncrs,
      MultiMap<Var, RefCount> constIncrs, Expression containerSize) {
    if (!perIterIncrs.isEmpty()) {
      pointStack.peek().add(new SetVariable(TCLTMP_ITERS, 
                                      containerSize));
 
      handleRefcounts(constIncrs, perIterIncrs, Value.numericValue(TCLTMP_ITERS), false);
    }
  }

  private void startForeachSplit(String procName, Var arrayVar,
      String contentsVar, int splitDegree, int leafDegree, boolean haveKeys,
      List<PassedVar> usedVars, List<RefCount> perIterIncrs,
      MultiMap<Var, RefCount> constIncrs) {
    // load array size
    pointStack.peek().add(Turbine.containerSize(TCLTMP_CONTAINER_SIZE,
                                      varToExpr(arrayVar)));
    Value containerSize = Value.numericValue(TCLTMP_CONTAINER_SIZE);
    
    Expression lastIndex = TclExpr.minus(containerSize, LiteralInt.ONE);

    handleForeachContainerRefcounts(perIterIncrs, constIncrs, containerSize);
    
    // recursively split the range
    ArrayList<PassedVar> splitUsedVars = new ArrayList<PassedVar>(usedVars);
    if (!PassedVar.contains(splitUsedVars, arrayVar)) {
      splitUsedVars.add(new PassedVar(arrayVar, false));
    }
    startRangeSplit(procName, splitUsedVars, perIterIncrs, splitDegree, 
                    leafDegree, LiteralInt.ZERO, lastIndex, LiteralInt.ONE);

    // need to find the length of this split since that is what the turbine
    //  call wants
    pointStack.peek().add(new SetVariable(TCLTMP_SPLITLEN,
            new TclExpr(Value.numericValue(TCLTMP_RANGE_HI), TclExpr.MINUS,
                        Value.numericValue(TCLTMP_RANGE_LO), TclExpr.PLUS,
                        LiteralInt.ONE)));
    
    // load the subcontainer
    pointStack.peek().add(Turbine.containerContents(contentsVar,
        varToExpr(arrayVar), haveKeys, Value.numericValue(TCLTMP_SPLITLEN),
        TCLTMP_RANGE_LO_V));
  }

  private void startForeachInner(
      Value arrayContents, Var memberVar, Var loopCountVar) {
    Sequence curr = pointStack.peek();
    boolean haveKeys = loopCountVar != null;
    Sequence loopBody = new Sequence();

    String tclMemberVar = prefixVar(memberVar);
    String tclCountVar = haveKeys ? prefixVar(loopCountVar) : null;

    /* Iterate over keys and values, or just values */
    Sequence tclLoop;
    if (haveKeys) {
      tclLoop = new DictFor(new Token(tclCountVar), new Token(tclMemberVar),
                      arrayContents, loopBody);
    } else {
      tclLoop = new ForEach(new Token(tclMemberVar), arrayContents, loopBody);
    }
    curr.add(tclLoop);
    pointStack.push(loopBody);
  }


  @Override
  public void endForeachLoop(int splitDegree, boolean arrayClosed, 
                  List<RefCount> perIterDecrements) {
    assert(pointStack.size() >= 2);
    pointStack.pop(); // tclloop body
    if (splitDegree > 0) {
      endRangeSplit(perIterDecrements);
    }
  }

  @Override
  public void startRangeLoop(String loopName, Var loopVar, Var countVar,
      Arg start, Arg end, Arg increment, int splitDegree, int leafDegree,
      List<PassedVar> passedVars, List<RefCount> perIterIncrs,
      MultiMap<Var, RefCount> constIncrs) {
    assert(start.isImmediateInt());
    assert(end.isImmediateInt());
    assert(increment.isImmediateInt());
    assert(Types.isIntVal(loopVar));
    if (countVar != null) { 
      throw new STCRuntimeError("Backend doesn't support counter var in range " +
      		                      "loop yet");
      
    }
    Expression startE = argToExpr(start);
    Expression endE = argToExpr(end);
    Expression incrE = argToExpr(increment);


    if (!perIterIncrs.isEmpty()) {
      // Increment references by # of iterations
      pointStack.peek().add(new SetVariable(TCLTMP_ITERSTOTAL,
                       rangeItersLeft(startE, endE, incrE)));
      
      Value itersTotal = Value.numericValue(TCLTMP_ITERSTOTAL);
      handleRefcounts(constIncrs, perIterIncrs, itersTotal, false);
    }
    
    if (splitDegree > 0) {
      startRangeSplit(loopName, passedVars, perIterIncrs,
              splitDegree, leafDegree, startE, endE, incrE);
      startRangeLoopInner(loopName, loopVar,
          TCLTMP_RANGE_LO_V, TCLTMP_RANGE_HI_V, TCLTMP_RANGE_INC_V);
    } else {
      startRangeLoopInner(loopName, loopVar, startE, endE, incrE);
    }
  }

  @Override
  public void endRangeLoop(int splitDegree, List<RefCount> perIterDecrements) {
    assert(pointStack.size() >= 2);
    pointStack.pop(); // for loop body

    if (splitDegree > 0) {
      endRangeSplit(perIterDecrements);
    }
  }

  private void startRangeLoopInner(String loopName, Var loopVar,
          Expression startE, Expression endE, Expression incrE) {
    Sequence loopBody = new Sequence();
    String loopVarName = prefixVar(loopVar);
    ForLoop tclLoop = new ForLoop(loopVarName, startE, endE, incrE, loopBody);
    pointStack.peek().add(tclLoop);
    pointStack.push(loopBody);
  }

  /**
   * After this function is called, in the TCL context at the top of the stack
   * will be available the bottom, top (inclusive) and increment of the split in
   * tcl values: TCLTMP_RANGE_LO TCLTMP_RANGE_HI and TCLTMP_RANGE_INC
   * @param loopName
   * @param splitDegree
   * @param leafDegree 
   * @param startE start of range (inclusive)
   * @param endE end of range (inclusive)
   * @param incrE
   * @param usedVariables
   * @param keepOpenVars
   */
  private void startRangeSplit(String loopName,
          List<PassedVar> passedVars, List<RefCount> perIterIncrs, int splitDegree,
          int leafDegree, Expression startE, Expression endE,
          Expression incrE) {
    // Create two procedures that will be called: an outer procedure
    //  that recursively breaks up the foreach loop into chunks,
    //  and an inner procedure that actually runs the loop
    List<String> commonFormalArgs = new ArrayList<String>();
    commonFormalArgs.add(Turbine.LOCAL_STACK_NAME);
    for (PassedVar pv: passedVars) {
      commonFormalArgs.add(prefixVar(pv.var.name()));
    }
    commonFormalArgs.add(TCLTMP_RANGE_LO);
    commonFormalArgs.add(TCLTMP_RANGE_HI);
    commonFormalArgs.add(TCLTMP_RANGE_INC);
    List<String> outerFormalArgs = new ArrayList<String>(commonFormalArgs);
    

    Value loVal = Value.numericValue(TCLTMP_RANGE_LO);
    Value hiVal = Value.numericValue(TCLTMP_RANGE_HI);
    Value incVal = Value.numericValue(TCLTMP_RANGE_INC);

    List<Expression> commonArgs = new ArrayList<Expression>();
    commonArgs.add(Turbine.LOCAL_STACK_VAL);
    for (PassedVar pv: passedVars) {
      commonArgs.add(varToExpr(pv.var));
    }

    List<Expression> outerCallArgs = new ArrayList<Expression>(commonArgs);
    outerCallArgs.add(startE);
    outerCallArgs.add(endE);
    outerCallArgs.add(incrE);

    List<Expression> innerCallArgs = new ArrayList<Expression>(commonArgs);
    innerCallArgs.add(loVal);
    innerCallArgs.add(hiVal);
    innerCallArgs.add(incVal);

    Sequence outer = new Sequence();
    String outerProcName = uniqueTCLFunctionName(loopName + ":outer");
    tree.add(new Proc(outerProcName,
            usedTclFunctionNames, outerFormalArgs, outer));

    Sequence inner = new Sequence();
    String innerProcName = uniqueTCLFunctionName(loopName + ":inner");
    tree.add(new Proc(innerProcName,
          usedTclFunctionNames, commonFormalArgs, inner));
    
    // Call outer directly
    pointStack.peek().add(new Command(outerProcName, outerCallArgs));


    // itersLeft = ceil( (hi - lo + 1) /(double) inc))
    // ==> itersLeft = ( (hi - lo) / inc ) + 1
    outer.add(new SetVariable(TCLTMP_ITERSLEFT,
              rangeItersLeft(Value.numericValue(TCLTMP_RANGE_LO),
                             Value.numericValue(TCLTMP_RANGE_HI),
                             Value.numericValue(TCLTMP_RANGE_INC))));

    Value itersLeft = Value.numericValue(TCLTMP_ITERSLEFT);
    Expression done = new TclExpr(itersLeft,
                                  TclExpr.LTE, LiteralInt.ZERO);
    Sequence thenDoneB = new Sequence();
    If finishedIf = new If(done, thenDoneB);
    thenDoneB.add(new Command("return"));
    outer.add(finishedIf);

    Expression doneSplitting = new TclExpr(itersLeft,
                        TclExpr.LTE, new LiteralInt(leafDegree));
    // if (iters < splitFactor) then <call inner> else <split more>
    Sequence thenNoSplitB = new Sequence();
    Sequence elseSplitB = new Sequence();
    If splitIf = new If(doneSplitting, thenNoSplitB, elseSplitB);
    outer.add(splitIf);

    thenNoSplitB.add(new Command(innerProcName, innerCallArgs));

    Sequence splitBody = new Sequence();
    String splitStart = "tcltmp:splitstart";
    String skip = "tcltmp:skip";
    // skip = max(splitFactor,  ceil(iters /(float) splitfactor))
    // skip = max(splitFactor,  ((iters - 1) /(int) splitfactor) + 1)
    elseSplitB.add(new SetVariable(skip, 
        TclExpr.mult(Value.numericValue(TCLTMP_RANGE_INC),
          TclExpr.max(new LiteralInt(leafDegree),
            TclExpr.group(
                TclExpr.paren(
                    TclExpr.paren(itersLeft, TclExpr.MINUS,
                        LiteralInt.ONE),
                     TclExpr.DIV,  new LiteralInt(splitDegree)),
                TclExpr.PLUS, LiteralInt.ONE)))));
        
        /*new Token(
          String.format("(%s - 1) / %d ) + 1",
                  ,
                  splitDegree, */

    ForLoop splitLoop = new ForLoop(splitStart, loVal,
            hiVal, Value.numericValue(skip), splitBody);
    elseSplitB.add(splitLoop);


    ArrayList<Expression> outerRecCall = new ArrayList<Expression>();
    outerRecCall.add(new Token(outerProcName));
    outerRecCall.addAll(commonArgs);
    outerRecCall.add(Value.numericValue(splitStart));
    // splitEnd = min(hi, start + skip - 1)
    
    TclExpr splitEndExpr = new TclExpr(
        TclExpr.min(Value.numericValue(TCLTMP_RANGE_HI),
          TclExpr.group(Value.numericValue(splitStart), TclExpr.PLUS,
                        Value.numericValue(skip), TclExpr.MINUS,
                        LiteralInt.ONE)));
    splitBody.add(new SetVariable(TCLTMP_SPLITEND, splitEndExpr));
    
    outerRecCall.add(Value.numericValue(TCLTMP_SPLITEND));
    outerRecCall.add(incVal);

    splitBody.add(Turbine.rule(outerProcName, new ArrayList<Value>(0),
                    outerRecCall, TaskMode.CONTROL, 
                    execContextStack.peek(), RuleProps.DEFAULT));

    pointStack.push(inner);
  }

  /**
   * Generate refcounting code from RefCount list
   * @param constIncrs constant increments.  Assume that every constant incr
   *            has a corresponding multipled one.  This can be null
   * @param multipliedIncrs
   * @param multiplier amount to multiply all refcounts by
   * @param decrement if true, generate decrements instead
   */
  private void handleRefcounts(MultiMap<Var, RefCount> constIncrs, List<RefCount> multipliedIncrs,
                               Value multiplier, boolean decrement) {
    // Track which were added for validation
    Set<Pair<Var, RefCountType>> processed = new HashSet<Pair<Var, RefCountType>>();
    
    for (RefCount refCount: multipliedIncrs) {
      List<Expression> refCountExpr;
      if (refCount.amount.equals(Arg.ONE)) {
        refCountExpr = new ArrayList<Expression>();
        refCountExpr.add(multiplier);
      } else {
        refCountExpr = new ArrayList<Expression>();
        refCountExpr.addAll(Arrays.asList(
            argToExpr(refCount.amount), TclExpr.TIMES, multiplier));
      }
      
      
      if (constIncrs != null) {
        for (RefCount constRC: constIncrs.get(refCount.var)) {
          if (constRC.type == refCount.type) {
            if (constRC.amount.isIntVal() && constRC.amount.getIntLit() < 0) {
              refCountExpr.add(TclExpr.MINUS);
              refCountExpr.add(new LiteralInt(constRC.amount.getIntLit() * -1));
            } else {
              refCountExpr.add(TclExpr.PLUS);
              refCountExpr.add(argToExpr(constRC.amount));
            }
          }
        }
      }
      
      Expression totalAmount = new TclExpr(refCountExpr);

      if (refCount.type == RefCountType.READERS) {
        if (decrement) {
          decrementReaders(Arrays.asList(refCount.var), totalAmount);
        } else {
          incrementReaders(Arrays.asList(refCount.var), totalAmount);
        }
      } else {
        assert(refCount.type == RefCountType.WRITERS);
        if (decrement) {
          decrementWriters(Arrays.asList(refCount.var), totalAmount);
        } else {
          incrementWriters(Arrays.asList(refCount.var), totalAmount);
        }
      }
      processed.add(Pair.create(refCount.var, refCount.type));
    }
    
    // Check that all constant increments has a corresponding multiplier 
    if (constIncrs != null) {
      for (List<RefCount> rcList: constIncrs.values()) {
        for (RefCount rc: rcList) {
          assert(processed.contains(Pair.create(rc.var, rc.type))) : 
            rc + "\n" + constIncrs + "\n" + multipliedIncrs;
        }
      }
    }
  }

  private TclExpr rangeItersLeft(Expression lo, Expression hi, Expression inc) {
    Expression calcLeft; // Expression to calculate how many left (may be neg.) 
    if (LiteralInt.ONE.equals(inc)) {
      // More readable
      calcLeft = TclExpr.group(hi, TclExpr.MINUS, lo, TclExpr.PLUS,
                            LiteralInt.ONE);
    } else {
      calcLeft = TclExpr.group(
            TclExpr.paren(hi, TclExpr.MINUS, lo), TclExpr.DIV,
            inc, TclExpr.PLUS, LiteralInt.ONE);
    }
    return new TclExpr(TclExpr.max(LiteralInt.ZERO, calcLeft));
  }

  private void endRangeSplit(List<RefCount> perIterDecrements) {
    if (!perIterDecrements.isEmpty()) {
      // Decrement # of iterations executed in inner block
      pointStack.peek().add(new SetVariable(TCLTMP_ITERS, 
                   rangeItersLeft(Value.numericValue(TCLTMP_RANGE_LO),
                                  Value.numericValue(TCLTMP_RANGE_HI),
                                  Value.numericValue(TCLTMP_RANGE_INC))));
      Value iters = Value.numericValue(TCLTMP_ITERS);
      handleRefcounts(null, perIterDecrements, iters, true);
    }
    pointStack.pop(); // inner proc body
  }

  @Override
  public void addGlobal(String name, Arg val) {
    String tclName = prefixVar(name);
    Value tclVal = new Value(tclName);
    globInit.add(Turbine.makeTCLGlobal(tclName));
    TypeName typePrefix;
    Expression expr;
    Command setCmd;
    switch (val.getKind()) {
    case INTVAL:
      typePrefix = Turbine.ADLB_INT_TYPE;
      expr = new LiteralInt(val.getIntLit());
      setCmd = Turbine.integerSet(tclVal, expr);
      break;
    case FLOATVAL:
      typePrefix = Turbine.ADLB_FLOAT_TYPE;
      expr = new LiteralFloat(val.getFloatLit());
      setCmd = Turbine.floatSet(tclVal, expr);
      break;
    case STRINGVAL:
      typePrefix = Turbine.ADLB_STRING_TYPE;
      expr = new TclString(val.getStringLit(), true);
      setCmd = Turbine.stringSet(tclVal, expr);
      break;
    case BOOLVAL:
      typePrefix = Turbine.ADLB_INT_TYPE;
      expr = new LiteralInt(val.getBoolLit() ? 1 : 0);
      setCmd = Turbine.integerSet(tclVal, expr);
      break;
    default:
      throw new STCRuntimeError("Non-constant oparg type "
          + val.getKind());
    }
    globInit.add(Turbine.allocatePermanent(tclName, typePrefix));
    globInit.add(setCmd);
  }

  /**
     Generate and return Tcl from  our internal TclTree
   */
    @Override
    public String code()
  {
    StringBuilder sb = new StringBuilder(10*1024);
    try
    {
      tree.appendTo(sb);
    }
    catch (Exception e)
    {
      System.out.println("CODE GENERATOR INTERNAL ERROR");
      System.out.println(e.getMessage());
      e.printStackTrace();
      System.out.println("code generated before error:");
      System.out.println(sb);
      System.out.println("exiting");
      throw new STCFatal(ExitCode.ERROR_INTERNAL.code());
    }
    return sb.toString();
  }


  private static Value varToExpr(Var v) {
    return TclUtil.varToExpr(v);
  }

  /**
   * Return an expression for the Turbine id to
   * wait on.
   * @param var
   * @return
   */
  private Expression getTurbineWaitId(Var var) {
    Value wv = varToExpr(var);
    Expression waitExpr;
    if (Types.isFile(var)) {
      // Block on file status
      waitExpr = Turbine.getFileStatus(wv);
    } else if (Types.isScalarFuture(var) || Types.isRef(var) ||
            Types.isArray(var) || Types.isScalarUpdateable(var)||
            Types.isBag(var)) {
      waitExpr = wv;
    } else {
      throw new STCRuntimeError("Don't know how to wait on var: "
              + var.toString());
    }
    return waitExpr;
  }

  private Expression argToExpr(Arg in) {
    return TclUtil.argToExpr(in);
  }


  private static String prefixVar(String varname) {
    return TclNamer.prefixVar(varname);
  }
  
  private static String prefixVar(Var var) {
    return TclNamer.prefixVar(var.name());
  }

  private static List<String> prefixVars(List<String> vlist) {
    return TclNamer.prefixVars(vlist);
  }


  private boolean noStackVars() {
    boolean no_stack_vars;
    try {
      no_stack_vars = Settings.getBoolean(Settings.TURBINE_NO_STACK_VARS);
    } catch (InvalidOptionException e) {
      e.printStackTrace();
      throw new STCRuntimeError(e.getMessage());
    }
    return no_stack_vars;
  }

  private boolean noStack() {
    boolean no_stack;
    try {
      no_stack = Settings.getBoolean(Settings.TURBINE_NO_STACK);
    } catch (InvalidOptionException e) {
      e.printStackTrace();
      throw new STCRuntimeError(e.getMessage());
    }
    return no_stack;
  }

  @Override
  public void startLoop(String loopName, List<Var> loopVars,
      List<Boolean> definedHere, List<Var> initVals, List<Var> usedVariables,
      List<Var> keepOpenVars, List<Boolean> blockingVars) {

    // call rule to start the loop, pass in initVals, usedVariables
    ArrayList<String> loopFnArgs = new ArrayList<String>();
    ArrayList<Value> firstIterArgs = new ArrayList<Value>();
    loopFnArgs.add(Turbine.LOCAL_STACK_NAME);
    firstIterArgs.add(Turbine.LOCAL_STACK_VAL);

    for (Var arg: loopVars) {
      loopFnArgs.add(prefixVar(arg));
    }
    for (Var init: initVals) {
      firstIterArgs.add(varToExpr(init));
    }

    for (Var uv: usedVariables) {
      loopFnArgs.add(prefixVar(uv));
      firstIterArgs.add(varToExpr(uv));
    }


    // See which values the loop should block on
    ArrayList<Value> blockingVals = new ArrayList<Value>();
    assert(blockingVars.size() == initVals.size());
    for (int i = 0; i < blockingVars.size(); i++) {
      Var iv = initVals.get(i);
      if (blockingVars.get(i)) {
        blockingVals.add(varToExpr(iv));
      }
    }

    String uniqueLoopName = uniqueTCLFunctionName(loopName);

    pointStack.peek().add(Turbine.loopRule(
        uniqueLoopName, firstIterArgs, blockingVals, execContextStack.peek()));

    Sequence loopBody = new Sequence();
    Proc loopProc = new Proc(uniqueLoopName, usedTclFunctionNames,
                                            loopFnArgs, loopBody);
    tree.add(loopProc);
    // add loop body to pointstack, loop to loop stack
    pointStack.push(loopBody);
    loopNameStack.push(uniqueLoopName);
  }

  private String uniqueTCLFunctionName(String tclFunctionName) {
    String unique = tclFunctionName;
    int next = 1;
    while (usedTclFunctionNames.contains(unique)) {
      unique = tclFunctionName + "-" + next;
      next++;
    }
    return unique;
  }

  @Override
  public void loopContinue(List<Var> newVals,
         List<Var> usedVariables,
         List<Boolean> blockingVars) {
    ArrayList<Value> nextIterArgs = new ArrayList<Value>();
    String loopName = loopNameStack.peek();
    nextIterArgs.add(Turbine.LOCAL_STACK_VAL);

    for (Var v: newVals) {
      nextIterArgs.add(varToExpr(v));
    }
    for (Var v: usedVariables) {
      nextIterArgs.add(varToExpr(v));
    }
    ArrayList<Value> blockingVals = new ArrayList<Value>();
    assert(newVals.size() == blockingVars.size());
    for (int i = 0; i < newVals.size(); i++) {
      if (blockingVars.get(i)) {
        blockingVals.add(varToExpr(newVals.get(i)));
      }
    }
    pointStack.peek().add(Turbine.loopRule(loopName,
        nextIterArgs, blockingVals, execContextStack.peek()));
  }

  @Override
  public void loopBreak(List<Var> loopUsedVars, List<Var> keepOpenVars) {
    // Note: this is no-op now
  }

  @Override
  public void endLoop() {
    assert(pointStack.size() >= 2);
    assert(loopNameStack.size() > 0);
    pointStack.pop();
    loopNameStack.pop();
  }
}
