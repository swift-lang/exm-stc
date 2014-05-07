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

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import exm.stc.common.Logging;
import exm.stc.common.exceptions.DoubleDefineException;
import exm.stc.common.exceptions.STCRuntimeError;
import exm.stc.common.exceptions.UndefinedTypeException;
import exm.stc.common.exceptions.UserException;
import exm.stc.common.lang.Arg;
import exm.stc.common.lang.Types;
import exm.stc.common.lang.Types.StructType;
import exm.stc.common.lang.Types.StructType.StructField;
import exm.stc.common.lang.Types.Type;
import exm.stc.common.lang.Var;
import exm.stc.common.lang.Var.Alloc;
import exm.stc.common.lang.Var.DefType;
import exm.stc.common.lang.Var.VarProvenance;
import exm.stc.common.util.StackLite;
import exm.stc.ic.STCMiddleEnd;

/**
 * This module contains logic to create and initialise variables, in order
 * to ensure that variable are consistently created and initialised in front end.
 * This is potentially a problem because we store variable state in both the
 * context and in the backend.
 *
 */
public class VarCreator {
  private final STCMiddleEnd backend;
  private final Logger logger;

  public VarCreator(STCMiddleEnd backend) {
    super();
    this.logger = Logging.getSTCLogger();
    this.backend = backend;
  }

  /**
   * @param context
   * @param var
   * @return
   * @throws UserException
   */
  public Var createVariable(Context context, Var var) throws UserException {

    if (var.mappedDecl() && (!Types.isMappable(var))) {
      throw new UserException(context, "Variable " + var.name() + " of type "
          + var.type().toString() + " cannot be mapped to");
    }

    try {
      context.declareVariable(var);
    } catch (DoubleDefineException e) {
      throw new DoubleDefineException(context, e.getMessage());
    }
    initialiseVariable(context, var);
    return var;
  }
  
  
  public Var createMappedVariable(Context context, Type type, String name,
      Alloc storage, DefType defType, VarProvenance prov, Var mapping)
                                                throws UserException {
    Var v = createVariable(context,
        new Var(type, name, storage, defType, prov, mapping != null));
    
    if (mapping != null) {
      if (Types.isFile(v)) {
        backend.copyInFilename(VarRepr.backendVar(v),
                          VarRepr.backendVar(mapping));
      } else {
        throw new STCRuntimeError("Mapping type " + v.type() +
                                  " not implemented");
      }
    }
    
    return v;
  }
  
  public Var createVariable(Context context, Type type, String name,
      Alloc storage, DefType defType, VarProvenance prov, boolean mapped)
                                                throws UserException {
    return createVariable(context,
        new Var(type, name, storage, defType, prov, mapped));
  }

  public void initialiseVariable(Context context, Var v)
      throws UndefinedTypeException, DoubleDefineException {
    backendInit(v);
    if (Types.isStruct(v)) {
      initialiseStruct(context, v);
    }
  }

  /**
   * Convenience function to declare var in backend with appropriate
   * converted type
   * @param var
   * @throws UndefinedTypeException
   */
  public void backendInit(Var var) throws UndefinedTypeException {
    backend.declare(VarRepr.backendVar(var));
  }

  private void initialiseStruct(Context context, Var struct)
      throws UndefinedTypeException, DoubleDefineException {
    StructType structType = (StructType)struct.type().getImplType();
    
    List<List<String>> fieldPaths = new ArrayList<List<String>>();
    List<Arg> fieldVals = new ArrayList<Arg>();

    StackLite<String> currFieldPath = new StackLite<String>();
    int initFieldCount = initialiseStructRec(context, struct, currFieldPath,
        structType, fieldPaths, fieldVals);
    if (initFieldCount > 0) {
      logger.trace("Init struct: " + struct.name() +
               " type: " + struct.type() + "\n" +
               "Paths: " + fieldPaths + "\n" + "Vals: " + fieldVals);
      backend.structInitFields(VarRepr.backendVar(struct), fieldPaths,
          VarRepr.backendArgs(fieldVals), Arg.createIntLit(initFieldCount));
    }
  }

  /**
   * 
   * @param rootStruct
   * @param currFieldPath path from root to here
   * @param structType type of current path
   * @param fieldPaths Field paths - added to
   * @param fieldVals Field values - added to
   * @return number of fields initialised
   * @throws DoubleDefineException 
   * @throws UndefinedTypeException 
   */
  private int initialiseStructRec(Context context,
      Var rootStruct, StackLite<String> currFieldPath, StructType structType,
      List<List<String>> fieldPaths, List<Arg> fieldVals)
          throws UndefinedTypeException, DoubleDefineException {
    int initFieldCount = 0;
    
    for (StructField field: structType.getFields()) {
      currFieldPath.push(field.getName());
      
      Type fieldT = field.getType();
      if (VarRepr.storeRefInStruct(fieldT)) {
        ArrayList<String> fieldPath = new ArrayList<String>(currFieldPath);
        // initialize data being referenced and put into struct
        Var fieldVar = createStructFieldTmp(context, rootStruct, fieldT,
                                              fieldPath, Alloc.TEMP);
        
        fieldPaths.add(fieldPath);
        fieldVals.add(VarRepr.backendVar(fieldVar).asArg());
        
        initFieldCount++;
      } else if (Types.isStruct(fieldT)) {
        initFieldCount += initialiseStructRec(context, rootStruct,
            currFieldPath, (StructType)fieldT.getImplType(),
            fieldPaths, fieldVals);
      }
      
      currFieldPath.pop();
    }
    return initFieldCount;
  }

  /**
   * Convenience shortcut createTmp: creates a local temporary + storage
   * which isn't stored in stack
   * @param context
   * @param type
   * @return
   * @throws UserException
   * @throws UndefinedTypeException
   */
  public Var createTmp(Context context, Type type) 
      throws UserException, UndefinedTypeException {
    assert(context != null);
    return createTmp(context, type, false, false);
  }
  
  /**
   * Shortcut to create tmp alias vars
   * @param context
   * @param type
   * @return
   * @throws UserException
   * @throws UndefinedTypeException
   */
  public Var createTmpAlias(Context context, Type type) 
      throws UserException, UndefinedTypeException {
    return createTmp(context, type, false, true);
  }
  
  /**
   * Creates a new tmp value, entering it in the provided context
   * and calling the backend to initialise it 
   * @param context
   * @param type
   * @param storeInStack if the variable should be stored in the stack
   * @param isAlias if the variable is just going to be an alias for another
   *      variable (i.e. no storage should be declared)
   * @return
   * @throws UserException
   * @throws UndefinedTypeException
   */
  public Var createTmp(Context context, Type type,
      boolean storeInStack, boolean isAlias) throws UserException,
      UndefinedTypeException {
    assert(context != null);
    if (storeInStack && isAlias) {
      throw new STCRuntimeError("Cannot create variable which is both alias" +
              " and on stack");
    }
    Var tmp;
    if ((!storeInStack) && isAlias) {
      tmp = context.createTmpAliasVar(type);
    } else {
      tmp = context.createTmpVar(type, storeInStack);
    }

    initialiseVariable(context, tmp);
    return tmp;
  }
  
  public Var createTmpLocalVal(Context context, Type type) 
        throws UserException {
    assert(Types.isPrimValue(type));
    Var val = context.createLocalValueVariable(type);
    backendInit(val);
    return val;
  }
  
  
  public Var createStructFieldAlias(Context context, Var rootStruct, 
      Type memType, List<String> fieldPath)
          throws UndefinedTypeException, DoubleDefineException {
    return createStructFieldTmp(context, rootStruct, memType, fieldPath,
            Alloc.ALIAS);
  }
  
  public Var createStructFieldTmp(Context context, Var rootStruct, 
                  Type memType, List<String> fieldPath,
                  Alloc storage) throws UndefinedTypeException, DoubleDefineException {
    Var tmp = context.createStructFieldTmp(rootStruct, memType,
          fieldPath, storage);
    initialiseVariable(context, tmp);
    return tmp;
  }

  public Var createValueOfVar(Context context, Var future) 
      throws UserException {
    return createValueOfVar(context, future, true);
  }
  /**
   * Create a value variable which ahs the type which is the value equivalent
   * to a scalar future
   * @param context
   * @param future
   * @param initialise false if the variable doesn't need to be initialised (e.g.
   *    if it is initialised by a surrounding construct)
   * @return
   * @throws UserException
   */
  public Var createValueOfVar(Context context, Var future,
        boolean initialise) throws UserException {
    Type valType = Types.retrievedType(future.type());
    assert(valType != null) : future.type() + " could not be derefed";
    Var val = createValueVar(context, valType, future, initialise);
    return val;
  }

  public Var createValueVar(Context context, Type valType, Var future,
          boolean initialise) throws UserException, UndefinedTypeException,
          DoubleDefineException {
    Var val = context.createLocalValueVariable(valType, future);
    if (initialise) {
      initialiseVariable(context, val);
    }
    return val;
  }
  
  /**
   * Shortcut to create filename of
   * @param context
   * @return
   */
  public Var createFilenameAlias(Context context, Var fileVar)
      throws UserException, UndefinedTypeException {
    assert(Types.isFile(fileVar.type()));
    Var filename = context.createFilenameAliasVariable(fileVar);
    initialiseVariable(context, filename);
    return filename;
  }
  
}
