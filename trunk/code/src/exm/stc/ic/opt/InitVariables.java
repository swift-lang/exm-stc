package exm.stc.ic.opt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;

import exm.stc.common.Logging;
import exm.stc.common.exceptions.STCRuntimeError;
import exm.stc.common.lang.Arg;
import exm.stc.common.lang.ForeignFunctions.SpecialFunction;
import exm.stc.common.lang.PassedVar;
import exm.stc.common.lang.Types;
import exm.stc.common.lang.Types.StructType;
import exm.stc.common.lang.Types.StructType.StructField;
import exm.stc.common.lang.Var;
import exm.stc.common.lang.Var.Alloc;
import exm.stc.common.util.HierarchicalMap;
import exm.stc.common.util.HierarchicalSet;
import exm.stc.common.util.Pair;
import exm.stc.common.util.Sets;
import exm.stc.ic.ICUtil;
import exm.stc.ic.tree.ICContinuations.Continuation;
import exm.stc.ic.tree.ICInstructions.CommonFunctionCall;
import exm.stc.ic.tree.ICInstructions.Instruction;
import exm.stc.ic.tree.ICInstructions.Instruction.InitType;
import exm.stc.ic.tree.ICTree.Block;
import exm.stc.ic.tree.ICTree.Function;
import exm.stc.ic.tree.ICTree.Statement;
import exm.stc.ic.tree.Opcode;

/**
 * Analysis of which variables are initialized.
 */
public class InitVariables {
  public static class InitState {
    public final HierarchicalSet<Var> initVars;
    public final HierarchicalSet<Var> assignedVals;
    /** Track which struct fields remain to be initialized
     *  (if there is no entry, none are) */
    public final HierarchicalMap<Var, List<String>> uninitStructFields;
    
    private InitState(HierarchicalSet<Var> initVars,
        HierarchicalSet<Var> assignedVals,
        HierarchicalMap<Var, List<String>> uninitStructFields) {
      this.initVars = initVars;
      this.assignedVals = assignedVals;
      this.uninitStructFields = uninitStructFields;
    }
    
    private InitState() {
      this(new HierarchicalSet<Var>(), new HierarchicalSet<Var>(),
           new HierarchicalMap<Var, List<String>>());
    }
    
    private InitState makeChild() {
      return new InitState(initVars.makeChild(), assignedVals.makeChild(),
                           uninitStructFields.makeChildMap());
    }

    /**
     * Create new state initialized for function
     * @param fn
     * @return
     */
    public static InitState enterFunction(Function fn) {
      InitState state = new InitState();
      for (Var v: fn.getInputList()) {
        if (varMustBeInitialized(v, false) || varMustBeInitialized(v, true)) {
          state.initVars.add(v);
        }
        if (assignBeforeRead(v)) {
          state.assignedVals.add(v);
        }
      }
      for (Var v: fn.getOutputList()) {
        if (varMustBeInitialized(v, true) || varMustBeInitialized(v, false)) {
          state.initVars.add(v);
        }
      }
      return state;
    }
    

    /**
     * Create child initialized for continuation
     * @param cont
     * @return
     */
    public InitState enterContinuation(Continuation cont) {
      InitState contState = makeChild();

      for (Var v: cont.constructDefinedVars()) {
        if (varMustBeInitialized(v, false)) {
          contState.initVars.add(v);
        }
        if (assignBeforeRead(v)) {
          contState.assignedVals.add(v);
        }
      }
      return contState;
    }
    
    public InitState enterBlock(Block block) {
      return makeChild();
    }
    
    public static boolean canUnifyBranches(Continuation cont) {
      return cont.isExhaustiveSyncConditional();
    }
    
    /**
     * Unify any information from branches of continuation
     * @param cont
     * @param branchStates
     */
    public void unifyBranches(Continuation cont,
                              List<InitState> branchStates) {
      assert(canUnifyBranches(cont));
      List<Set<Var>> branchInitVars = new ArrayList<Set<Var>>();
      List<Set<Var>> branchAssigned = new ArrayList<Set<Var>>();
      for (InitState branchState: branchStates) {
        branchInitVars.add(branchState.initVars);
        branchAssigned.add(branchState.assignedVals);
      }
      initVars.addAll(Sets.intersection(branchInitVars));
      assignedVals.addAll(Sets.intersection(branchAssigned));
    }

    /**
     * Update initialized variables for instruction
     * @param inst
     * @param validate
     */
    public void updateInitVars(Instruction inst, boolean validate) {
      
      if (validate) {
        for (Arg in: inst.getInputs()) {
          if (in.isVar()) {
            assertInitialized(inst, in.getVar(), false);
            
            assertAssigned(inst, in.getVar());
          }
        }
      }
      List<Var> regularOutputs = inst.getOutputs();
      List<Pair<Var, InitType>> initialized = inst.getInitialized();
      
      if (initialized.size() > 0) {
        regularOutputs = new ArrayList<Var>(regularOutputs);
        for (Pair<Var, InitType> init: initialized) {
          Var initVar = init.val1;
          InitType initType = init.val2;
          assert(Types.outputRequiresInitialization(initVar) ||
                 Types.inputRequiresInitialization(initVar)) : inst + " " + init;
          // some functions initialise and assign the future at once
          if (!initAndAssignLocalFile(inst))
            ICUtil.remove(regularOutputs, initVar);

          if (initType == InitType.FULL) {
            if (validate && initVars.contains(initVar)) {
              throw new STCRuntimeError("double initialized variable " + init);
            }
            initVars.add(initVar);
          } else {
            assert(initType == InitType.PARTIAL);
            updatePartialInit(inst, initVar);
          }
        }
      }
      for (Var out: regularOutputs) {
        if (validate) {
          assertInitialized(inst, out, true);
        }
        
        if (assignBeforeRead(out)) {
          boolean added = assignedVals.add(out);
    
          if (validate && !added) {
            throw new STCRuntimeError("double assigned val " + out);
          }
        }
      }
    }

    /**
     * Update state for partially initialized variable.
     * @param inst
     * @param initVar
     */
    private void updatePartialInit(Instruction inst, Var initVar) {
      if (Types.isStruct(initVar)) {
        updatePartialInitStruct(inst, initVar);
      } else {
        throw new STCRuntimeError("Can't handle partial init for type " +
                                   initVar.type());
      }
    }

    private void updatePartialInitStruct(Instruction inst, Var initVar) {
      assert(inst.op == Opcode.STRUCT_INIT_FIELD) : "Expected STRUCT_INIT_FIELD";
      String field = inst.getInput(0).getStringLit();

      List<String> prevUninitFields = uninitStructFields.get(initVar);
      List<String> newUninitFields; 
      StructType st = (StructType)(initVar.type().getImplType());
      if (prevUninitFields == null) {
        // Initialize with fields
        newUninitFields = new ArrayList<String>(st.getFieldCount() - 1);
        for (StructField f: st.getFields()) {
          // Add all except initialized
          if (!f.getName().equals(field)) {
            newUninitFields.add(f.getName());
          }
        }
      } else {
        // Create a new array to avoid modifying something higher up in 
        // hierarchical map
        newUninitFields = new ArrayList<String>(
              Math.max(0, prevUninitFields.size() - 1));
        // Add back all uninitialized fields except the new one
        boolean wasUninit = false;
        for (String prevUninit: prevUninitFields) {
          if (prevUninit.equals(field)) {
            wasUninit = true;
          } else {
            newUninitFields.add(prevUninit);
          }
        }
        if (!wasUninit) {
          throw new STCRuntimeError(initVar.name() + "." + field +
                                    " was doubly initialized");
        }
      }

      if (Logging.getSTCLogger().isTraceEnabled()) {
        Logging.getSTCLogger().debug("At " + inst + ": uninit for " +
                               initVar.name() + " " + newUninitFields);
      }

      uninitStructFields.put(initVar, newUninitFields);

      if (newUninitFields.isEmpty()) {
        // All initialized: struct is fully initialized
        initVars.add(initVar);
      }
    }

    /**
     * Check that variable is correctly initialized
     * @param var
     * @param output
     * @return
     */
    public boolean isInitialized(Var var, boolean output) {
      if (varMustBeInitialized(var, output) && !initVars.contains(var)) {
        return false;
      }
      if (!output && assignBeforeRead(var) && !assignedVals.contains(var)) {
        return false;
      }
      return true;
    }
    
    /**
     * Check that variable is initialized
     * @param context
     * @param var
     * @param output
     */
    private void assertInitialized(Object context, Var var, boolean output) {
      if (varMustBeInitialized(var, output) &&
          !initVars.contains(var)) {
        throw new STCRuntimeError("Uninitialized var " +
                      var + " in " + context.toString());
      }
    }
    
    private void assertAssigned(Object context, Var inVar) {
      if (assignBeforeRead(inVar)
          && !assignedVals.contains(inVar)) {
        throw new STCRuntimeError("Var " + inVar + " was an unassigned value " +
                                 " read in instruction " + context.toString());
      }
    }
    
    
  }
  
  

  /**
   * Analysis that performs validation of variable initialization
   * within a function. Throws a runtime error if a problem is
   * found.
   * 
   * @param logger
   * @param fn
   * @param block
   */
  public static void checkVarInit(Logger logger, Function fn) {
    recurseOnBlock(logger, fn.mainBlock(), InitState.enterFunction(fn), true);
  }
  
  /**
   * Perform analysis on a statement to determine what is initialized
   * by it. This doesn't perform any validation.
   * @param logger
   * @return
   */
  public static InitState analyze(Logger logger, Statement stmt) {
    InitState state = new InitState();
    updateInitVars(logger, stmt, state, false);
    return state;
  }
  
  /**
   * Check variable initialization recursively on continuation.
   * This is the workhorse of this module that traverses the intermediate
   * representation and finds out which variables are initialized 
   * @param logger
   * @param fn
   * @param state Initialized vars.  Updated if we discover that more vars
   *      are initialized after continuation
   * @param validate if true, validate correct usage of initialized variables.
   *              For validation to work, initVars and assignedVals must
   *              be updated correctly to reflect initializations that
   *              occurred in outer continuations
   * @param c
   */
  public static void checkInitCont(Logger logger, InitState state,
      Continuation c, boolean validate) {
    
    if (validate) {
      for (Var v: c.requiredVars(false)) {
        state.assertInitialized(c.getType(), v, false);
        state.assertAssigned(c.getType(), v);
      }
    }
    if (validate && c.isAsync()) {
      // If alias var passed to async continuation, must be initialized
      for (PassedVar pv: c.getPassedVars()) {
        state.assertInitialized(c.getType(), pv.var, false);
        state.assertAssigned(c.getType(), pv.var);
      }
    }

    
    if (validate || InitState.canUnifyBranches(c)) {
      recurseOnContinuation(logger, state, c, validate);
    }
  }

  private static void recurseOnContinuation(Logger logger, InitState state,
      Continuation cont, boolean validate) {
    // Only recurse if we're validating, or if we need info from inner blocks
    boolean unifyBranches = InitState.canUnifyBranches(cont);
    List<InitState> branchStates = null;
    if (unifyBranches) {
      branchStates = new ArrayList<InitState>();
    }
    
    InitState contState = state.enterContinuation(cont);
    for (Block inner: cont.getBlocks()) {
      InitState blockState = contState.makeChild();

      recurseOnBlock(logger, inner, blockState, validate);
      if (unifyBranches) {
        branchStates.add(blockState);
      }
    }
    
    // Unify information from branches into parent
    if (unifyBranches) {
      state.unifyBranches(cont, branchStates);
    }
  }

  private static void recurseOnBlock(Logger logger,
      Block block, InitState state, boolean validate) {
    if (validate) {
      for (Var v: block.getVariables()) {
        if (v.mapping() != null) {
          if (varMustBeInitialized(v.mapping(), false)) {
            assert (state.initVars.contains(v.mapping())):
              v + " mapped to uninitialized var " + v.mapping();
          }
          if (assignBeforeRead(v.mapping())) {
            assert(state.assignedVals.contains(v.mapping())) :
              v + " mapped to unassigned var " + v.mapping();
          }
        }
      }
    }
    
    for (Statement stmt: block.getStatements()) {
      updateInitVars(logger, stmt, state, validate);
    }
    
    for (Continuation c: block.getContinuations()) {
      if (validate || InitState.canUnifyBranches(c)) {
        // Only recurse if this might result in more initialized vars
        // being detected, or if we're recursively validating things
        checkInitCont(logger, state, c, validate);
      }
    }
  }

  /**
   * Update state with variables initialized by statement
   * @param logger
   * @param stmt
   * @param state
   * @param validate
   */
  public static void updateInitVars(Logger logger, 
            Statement stmt, InitState state, boolean validate) {
    switch (stmt.type()) {
      case INSTRUCTION:
        state.updateInitVars(stmt.instruction(), validate);
        break;
      case CONDITIONAL:
        // Recurse on the conditional.
        // This will also fill in information about which variables are closed on the branch
        checkInitCont(logger, state, stmt.conditional(), validate);
        break;
      default:
        throw new STCRuntimeError("Unknown statement type" + stmt.type());
    }
  }
  
  public static boolean assignBeforeRead(Var v) {
    return v.storage() == Alloc.LOCAL;
  }

  public static boolean varMustBeInitialized(Var v, boolean output) {
    if (output) {
      return Types.outputRequiresInitialization(v);
    } else {
      return Types.inputRequiresInitialization(v);
    } 
  }

  public static boolean initAndAssignLocalFile(Instruction inst) {
    if (inst.op == Opcode.LOAD_FILE) {
      return true;
    } else if (inst.op == Opcode.CALL_FOREIGN_LOCAL &&
          ((CommonFunctionCall)inst).isImpl(SpecialFunction.INITS_OUTPUT_MAPPING)) {
      return true;
    } else {
      return false;
    }
  }

}
