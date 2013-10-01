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

package exm.stc.frontend;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import exm.stc.ast.FilePosition;
import exm.stc.ast.FilePosition.LineMapping;
import exm.stc.ast.SwiftAST;
import exm.stc.common.exceptions.DoubleDefineException;
import exm.stc.common.exceptions.STCRuntimeError;
import exm.stc.common.exceptions.UndefinedTypeException;
import exm.stc.common.exceptions.UndefinedVarError;
import exm.stc.common.exceptions.UserException;
import exm.stc.common.lang.Intrinsics.IntrinsicFunction;
import exm.stc.common.lang.Types;
import exm.stc.common.lang.Types.FunctionType;
import exm.stc.common.lang.Types.Type;
import exm.stc.common.lang.Var;
import exm.stc.common.lang.Var.Alloc;
import exm.stc.common.lang.Var.DefType;

/**
 * Abstract interface used to track and access contextual information about the
 * program at different points in the AST. 
 */
public abstract class Context {
  
  /**
   * How many levels from root: 0 if this is the root
   */
  protected final int level;

  /**
   * A logger for use by child classes 
   */
  protected final Logger logger;

  /**
     Map from variable name to Variable object
   */
  protected final Map<String,Var> variables = new HashMap<String,Var>();

  /**
   * Map from type name to the type object.  Most types are defined
   * in global context only, but we also have type variables with
   * restricted scope.
   */
  protected final Map<String, Type> types = new HashMap<String, Type>();
  
  /**
   * Track all definitions (variables and types)
   */
  protected final Map<String, DefInfo> allDefs =
                                         new HashMap<String, DefInfo>();

  /**
   * Current input file
   */
  protected String inputFile;
  
  /**
     Current line in input file
   */
  protected int line = 0;
  
  /**
   * Current column in input file.  0 if unknown
   */
  protected int col = 0;
  
  public Context(Logger logger, int level) {
    super();
    this.level = level;
    this.logger = logger;
  }

  /**
     Return global context.
     If this is a GlobalContext, return this,
     else return the GlobalContext this is using.
   */
  public abstract GlobalContext getGlobals();
  
  /**
   * Lookup definition corresponding to name
   * @param name
   * @return the definition info, or null if not defined
   */
  public abstract DefInfo lookupDef(String name);
  
  /**
   * Add definition for current location in file
   * @param name 
   * @param type
   */
  protected void addDef(String name, DefKind kind) {
    allDefs.put(name, new DefInfo(kind, inputFile, line, col));
  }
  
  public void checkNotDefined(String name) throws DoubleDefineException {
    DefInfo def = lookupDef(name);
    if (def != null) {
      String loc = buildLocationString(def.file, def.line, def.col, false);
      throw new DoubleDefineException(this, def.kind.humanReadable() + 
          " called " + name + " already defined at " + loc);
    }
  }
  
  /**
   * Declare a new variable that will be visible in the
   * current scope and all descendant scopes
   * @param type
   * @param name
   * @param scope
   * @param defType
   * @param mapping
   * @return
   * @throws UserException
   */
  public Var declareVariable(Type type, String name, Alloc scope,
      DefType defType, Var mapping)
              throws DoubleDefineException {
    if (logger.isTraceEnabled()) {
      logger.trace("context: declareVariable: " +
                 type.toString() + " " + name + "<" + scope.toString() + ">"
                 + "<" + defType.toString() + ">");
    }

    Var variable = new Var(type, name, scope, defType, mapping);
    declareVariable(variable);
    return variable;
  }

  protected Var declareVariable(Var variable)
          throws DoubleDefineException {
    String name = variable.name();
    checkNotDefined(name);
  
    variables.put(name, variable);
    DefKind kind;
    if (Types.isFunction(variable.type())) {
      kind = DefKind.FUNCTION; 
    } else { 
      kind = DefKind.VARIABLE;
    }
    addDef(name, kind);
    return variable;
  }
  
  
  /**
   * Define a temporary variable with a unique name in the
   * current context
   * @param type
   * @param storeInStack
   * @return
   * @throws UserException
   */
  public abstract Var createTmpVar(Type type, boolean storeInStack)
  throws UserException;

  /**
   * Create a temporary variable name which will be an alias for a previously-
   * created variable (e.g. an array member).
   * @param type
   * @return
   * @throws UserException
   */
  public abstract Var createAliasVariable(Type type)
  throws UserException;

  /**
   * Lookup variable based on name.  This version will
   * return null if variable undeclared, leaving handling
   * to caller.
   * @param name
   * @return variable if declared, null if not declared
   */
  public abstract Var lookupVarUnsafe(String name);

  /**
   * Lookup variable based on name that is referred to
   * in user code
   * @param name
   * @return the variable
   * @throws UndefinedVarError if not found
   */
  public Var lookupVarUser(String name)
    throws UndefinedVarError {
    Var result = lookupVarUnsafe(name);
    if (result == null) {
      throw UndefinedVarError.fromName(this, name);
    }
    return result;
  }
  
  /**
   * Lookup variable based on name.  This version should be used in contexts
   * where the compiler has already checked the variable is declared, so if
   * the variable can't be found the problem is a bug
   * @param name
   * @return the variable
   * @throws STCRuntimeError if not found
   */
  public Var lookupVarInternal(String name)
    throws STCRuntimeError {
    Var result = lookupVarUnsafe(name);
    if (result == null) {
      throw new STCRuntimeError("Expected var " + name +
            " to already be declared at " + getLocation());
    }
    return result;
  }
  
  /**
   * Returns a list of all variables that are stored in the current stack
   * or an ancestor stack frame.
   * @return
   */
  public abstract List<Var> getVisibleVariables();

  public boolean isFunction(String name) {
    return lookupFunction(name) != null;
  }

  public abstract void defineFunction(String name, FunctionType type)
                                          throws UserException;
  
  public abstract void setFunctionProperty(String name, FnProp prop);
  
  public abstract List<FnProp> getFunctionProps(String function);
  
  public abstract boolean hasFunctionProp(String name, FnProp prop);
  
  public abstract void addIntrinsic(String function, IntrinsicFunction intrinsic);
  
  public abstract IntrinsicFunction lookupIntrinsic(String function);
  
  public boolean isIntrinsic(String function) {
    return lookupIntrinsic(function) != null;
  }

  /**
   * Lookup the type of a function
   * @param name
   * @return
   */
  public FunctionType lookupFunction(String name) {
    Var var = lookupVarUnsafe(name);
    if (var == null || !Types.isFunction(var.type())) {
      return null;
    }
    return (FunctionType)var.type();
  }

  public String getInputFile() {
    return inputFile;
  }

  
  /**
   * Synchronize preprocessed line numbers with input file
   * line numbers
   * @param tree antlr tree for current position
   * @param lineMapping map from input lines to source lines
   */
  public void syncFilePos(SwiftAST tree, LineMapping lineMapping) {
    // Sometime antlr nodes give bad line info - negative numbers
    if (tree.getLine() > 0) {
      FilePosition pos = lineMapping.getFilePosition(tree.getLine());
      this.inputFile = pos.file;
      this.line = pos.line;
      this.col = tree.getCharPositionInLine();
    }
  }
  
  public int getLine() {
    return line;
  }
  
  public int getColumn() {
    return col;
  }

  /**
     @return E.g.; "path/file.txt:42"
   */
  public String getFileLine() {
    return getInputFile() + ":" + getLine();
  }

  /**
     @return E.g.; "file.txt:42: "
   */
  public String getLocation() {
    return buildLocationString(getInputFile(), getLine(), getColumn(), true);
  }

  /**
   * Build a human-readable location string
   * @param inputFile
   * @param line
   * @param col
   * @return
   */
  private String buildLocationString(String inputFile, int line, int col,
                              boolean trailingColon) {
    String res = new File(inputFile).getName() + ":" + line;
    if (col > 0) {
      res += ":" + (col + 1);
    }
    if (trailingColon) {
      res += ": ";
    }
    return res;
  }

  public final int getLevel() {
    return level;
  }

  public final Logger getLogger() {
    return logger;
  }

  /**
   * @return the variables which were declared in this scope
   */
  public Collection<Var> getScopeVariables() {
    return Collections.unmodifiableCollection(variables.values());
  }
  
  /**
   * @param typeName
   * @return type corresponding to name, or otherwise null
   */
  abstract public Type lookupTypeUnsafe(String typeName);
  
  /**
   * @param typeName
   * @return type corresponding to name
   * @throws UndefinedTypeException 
   * @throw UndefinedTypeException if type is not defined
   */
  public Type lookupTypeUser(String typeName) throws UndefinedTypeException {
    Type t = lookupTypeUnsafe(typeName);
    if (t == null) {
      throw new UndefinedTypeException(this, typeName);
    } else {
      return t;
    }
  }

  public void defineType(String typeName, Type newType)
      throws DoubleDefineException {
    checkNotDefined(typeName);
    types.put(typeName, newType);
    addDef(typeName, DefKind.TYPE);
  }

  protected String buildPathStr(List<String> fieldPath) {
    StringBuilder build = new StringBuilder();
    for (String field: fieldPath) {
      if (build.length() > 0) {
        build.append('.');
      }
      build.append(field);
    }
    return build.toString();
  }

  abstract protected Var createStructFieldTmp(Var struct,
      Type fieldType, String fieldPath, Alloc storage);

  public Var createStructFieldTmp(Var struct,
      Type fieldType, List<String> fieldPath, Alloc storage) {
    String pathStr = buildPathStr(fieldPath);
    return createStructFieldTmp(struct, fieldType, pathStr, storage);
  }
  
  /** Get info about the enclosing function */
  abstract public FunctionContext getFunctionContext();

  /**
   * 
   * @param type
   * @param varName name of future this is the value of
   * @return
   * @throws UserException
   */
  abstract public Var createLocalValueVariable(Type type,
      String varName) throws UserException;

  public Var createLocalValueVariable(Type type) 
        throws UserException {
    return createLocalValueVariable(type, null);
  }
  
  /**
   * Create filename alias variable (a string future) for a file
   * variable with provided name
   * @param name name of file variable
   * @return
   */
  abstract public Var createFilenameAliasVariable(String name);
  
  public static enum FnProp {
    APP, COMPOSITE,
    BUILTIN, SYNC,
    WRAPPED_BUILTIN,
    PARALLEL, /** if this is a parallel task */
    TARGETABLE, /** if this is targetable */
    DEPRECATED, /** Warn if user uses function */
  }
  
  /**
   * Different types of definition name can be associated with.
   */
  public static enum DefKind {
    FUNCTION, VARIABLE, TYPE;
    
    public String humanReadable() {
      return this.toString().toLowerCase();
    }
  }
  
  /**
   * Information about a definition
   */
  public static class DefInfo {
    public DefInfo(DefKind kind, String file, int line, int col) {
      super();
      this.kind = kind;
      this.file = file;
      this.line = line;
      this.col = col;
    }
    public final DefKind kind;
    public final String file;
    public final int line;
    public final int col;
  }
}
