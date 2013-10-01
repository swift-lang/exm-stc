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
package exm.stc.ic.tree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import exm.stc.common.CompilerBackend;
import exm.stc.common.CompilerBackend.WaitMode;
import exm.stc.common.Settings;
import exm.stc.common.exceptions.InvalidOptionException;
import exm.stc.common.exceptions.STCRuntimeError;
import exm.stc.common.lang.Arg;
import exm.stc.common.lang.Arg.ArgKind;
import exm.stc.common.lang.ExecContext;
import exm.stc.common.lang.PassedVar;
import exm.stc.common.lang.TaskMode;
import exm.stc.common.lang.TaskProp.TaskPropKey;
import exm.stc.common.lang.TaskProp.TaskProps;
import exm.stc.common.lang.Types;
import exm.stc.common.lang.Var;
import exm.stc.common.util.Pair;
import exm.stc.ic.ICUtil;
import exm.stc.ic.tree.ICInstructions.Instruction;
import exm.stc.ic.tree.ICInstructions.LoopBreak;
import exm.stc.ic.tree.ICInstructions.LoopContinue;
import exm.stc.ic.tree.ICTree.Block;
import exm.stc.ic.tree.ICTree.BlockType;
import exm.stc.ic.tree.ICTree.Function;
import exm.stc.ic.tree.ICTree.GenInfo;
import exm.stc.ic.tree.ICTree.RenameMode;
import exm.stc.ic.tree.ICTree.Statement;
import exm.stc.ic.tree.ICTree.StatementType;

/**
 * This module contains definitions of all of the continuation varieties used
 * in the intermediate representation.  Each continuation is some sort of
 * control flow structure.
 * 
 * Each continuation object is responsible for being able to perform particular
 * transformations and report information about itself.  See the Continuation
 * base class to see what methods must be implemented. 
 *
 */
public class ICContinuations {
  public static final String indent = ICUtil.indent;

  public static abstract class Continuation {
    private Block parent;
    
    protected Continuation() {
      this.parent = null;
    }
    
    public abstract ContinuationType getType();
    
    public Block parent() {
      return this.parent;
    }


    public void setParent(Block parent) {
      assert(parent != null);
      Function oldParentFunction = this.parent != null ?
                                    this.parent.getParentFunction() : null;
      this.parent = parent;
      /*
       * Update parent function links if needed.  This appraoch used here
       * should only update info when needed (when a tree is
       * attached to a different function).
       */
      Function parentFunction = this.parent.getParentFunction();
      if (parentFunction != null && parentFunction != oldParentFunction) {
        parentFunction.addUsedVarNames(this.constructDefinedVars());
        for (Block block: getBlocks()) {
          // Fixup parent links in child blocks
          block.fixParentLinksRec(parentFunction);
        }
      }
    }
    
    public abstract void generate(Logger logger, CompilerBackend gen, GenInfo info);

    public abstract void prettyPrint(StringBuilder sb, String currentIndent);

    public String toString() {
      StringBuilder sb = new StringBuilder();
      prettyPrint(sb, "");
      return sb.toString();
    }
    
    /** Returns all nested blocks in this continuation */
    public abstract List<Block> getBlocks();
    
    /**
     * @param renames
     * @param mode what sort of renaming
     * @param recursive recursively do replacement in inner blocks
     */
    public void renameVars(Map<Var, Arg> renames, RenameMode mode,
                                     boolean recursive) {
      if (renames.isEmpty())
        return;
      if (recursive) {
        this.replaceVarsInBlocks(renames, mode);
      }
      this.replaceConstructVars(renames, mode);
    }
    
    /**
     * Rename recursively
     * @param renames
     * @param mode
     */
    public void renameVars(Map<Var, Arg> renames, RenameMode mode) {
      renameVars(renames, mode, true);
    }
    
    protected abstract void replaceConstructVars(Map<Var, Arg> renames,
                                                 RenameMode mode);

    /**
     * For the case where a consturct redefines a variable
     * name from outside, replace this variable.
     * @param oldV
     * @param newV
     */
    public void removeRedef(Var oldV, Var newV) {
       // Do nothing by default 
    }
    
    protected void replaceVarsInBlocks(Map<Var, Arg> renames,
                                       RenameMode mode) {
      for (Block b: this.getBlocks()) {
        b.renameVars(renames, mode, true);
      }
    }

    public abstract void removeVars(Set<Var> removeVars);

    protected void removeVarsInBlocks(Set<Var> removeVars) {
      for (Block b: this.getBlocks()) {
        b.removeVars(removeVars);
      }
    }

    /**
     * @param forDeadCodeElim if true, only return those variables
     * that this construct would prevent from eliminating
     * @return all variables whose values are needed to evaluate this construct
     * (e.g. branch condition).  empty list if none
     */
    public abstract Collection<Var> requiredVars(boolean forDeadCodeElim);

    /**
     * replace variables with constants in loop construct
     * @param knownConstants
     * @return true if anything changed
     */
    public boolean constantReplace(Map<Var, Arg> knownConstants) {
      // default: do nothing
      return false;
    }

    /** @return true if the continuation does nothing */
    public abstract boolean isNoop();

    public abstract boolean isAsync();
    
    /** @return true if continuation is async and a single task is spawned from
     *    current context */
    public boolean spawnsSingleTask() {
      assert(!isAsync());
      return false;
    }

    /** Return list of variables that the continuations waits for
     * before executing
     * @param includeConstructDefined if false, only include vars defined outside
     * @return
     */
    public List<BlockingVar> blockingVars(boolean includeConstructDefined) {
      // default implementation for sync continuations
      assert(!isAsync());
      return Collections.emptyList();
    }

    /**
     * Return list of variables that are defined by construct and
     * accessible inside
     * @param type type of constructs to return
     * @return non-null list
     */
    public List<Var> constructDefinedVars(ContVarDefType type) {
      return Var.NONE;
    }
    
    public List<Var> constructDefinedVars() {
      // In most cases don't care about redefs
      return constructDefinedVars(ContVarDefType.NEW_DEF);
    }

    /**
     * @return true if all variables in block containing continuation are
     *        automatically visible in inner blocks
     */
    public boolean inheritsParentVars() {
      // Generally non-async continuations inherit scope
      return !isAsync();
    }

    /**
     * Only applies to async continuations
     * @return List of variables passed into scope.
     *        empty list means none
     */
    public Collection<PassedVar> getPassedVars() {
      throw new STCRuntimeError("not implemented");
    }
    
    /**
     * Only applies to async continuations
     * @return List of all variables that are used within continuation,
     *    including those that aren't directly referred to
     */
    public Collection<PassedVar> getAllPassedVars() {
      throw new STCRuntimeError("not implemented");
    }
    
    /**
     * Only applies to async continuations 
     * @return List of variables kept open in this scope.
     *        empty list means none 
     */
    public Collection<Var> getKeepOpenVars() {
      throw new STCRuntimeError("not implemented");
    }

    public void setPassedVars(Collection<PassedVar> passedIn) {
      throw new STCRuntimeError("not implemented");
    }
    
    /** 
     * Set all keep open vars for continuation
     */
    public void setKeepOpenVars(Collection<Var> keepOpen) {
      throw new STCRuntimeError("not implemented");
    }

    /**
     * Remove this continuation from block, inlining one of
     * the nested blocks inside the continuation (e.g. the predicted branch
     *  of an if statement)
     * @param block the parent block of continuation
     * @param predictedBranch the branch in continuation that will be executed
     */
    public void inlineInto(Block block, Block predictedBranch, ListIterator<Statement> it) {
      // Default implementation
      block.insertInline(predictedBranch, it);
      if (parent != null)
        parent.removeContinuation(this);
    }
    
    public void inlineInto(Block block, Block predictedBranch) {
      inlineInto(block, predictedBranch, null);
    }

    /**
     *
     * It is ok if the unrolling introduced duplicate variable names in
     * nested blocks (so long as they don't shadow each other) - a
     * subsequent pass will make those names unique
     * @param logger
     * @param outerBlock
     * @return true if change made, also any additional continuations to be
     *        added by caller to outerBlock
     */
    public Pair<Boolean, List<Continuation>> tryUnroll(Logger logger, Block outerBlock) {
      // default: do nothing
      return Pair.create(false, Collections.<Continuation>emptyList());
    }

    /**
     * Try to inline a block, depending on which variables are closed
     * This is also a mechanism to let the continuation know what variables
     * are closed so it can make internal optimizations
     * @param closedVars variables which are closed
     * @param recClosedVars variables which are recursively closed (may
     *    overlap with closed)
     * @param keepExplicitDependencies if true, don't remove variables
     *        that are explicitly waited on
     * @return null if it cannot be inlined, a block that is equivalent to
     *          the continuation otherwise
     */
    public Block tryInline(Set<Var> closedVars, Set<Var> recClosedVars,
        boolean keepExplicitDependencies) {
      // Default: do nothing
      return null;
    }

    /**
     * Return the execution context inside the continuation
     * @param outerContext the context outside
     * @return
     */
    public ExecContext childContext(ExecContext outerContext) {
      // Default implementation for sync continuations
      assert(!isAsync());
      return outerContext;
    }
    
    /**
     * Create a copy of the continuation.  Do not set any
     * parent links.
     */
    @Override
    public abstract Continuation clone();
    
    /**
     * If the continuation should be put after all other continuations
     */
    private boolean runLast = false;
    public void setRunLast(boolean val) {
      runLast = val;
    }
    public boolean runLast() {
      return runLast;
    }

    public abstract boolean isLoop();

    /**
     * @return true if this is a conditional in which at most one of the
     *              subblocks is executed
     */
    public abstract boolean isConditional();

    /**
     * @return true if this is a conditional that executes synchronously, with
     *          the blocks representing the exhaustive set of possible paths
     */
    public boolean isExhaustiveSyncConditional() {
      // default
      return false;
    }

    /**
     * @return any variables that must be passed in even if they don't appear
     *        in any blocks within in the continuation
     */
    public List<PassedVar> getMustPassVars() {
      return PassedVar.NONE;
    }
  }

  public enum ContinuationType {
    NESTED_BLOCK,
    IF_STATEMENT,
    SWITCH_STATEMENT,
    FOREACH_LOOP,
    RANGE_LOOP,
    LOOP,
    WAIT_STATEMENT
  }

  /**
   * Categorization of vars defined inside continuations
   */
  public static enum ContVarDefType {
    ANY, // All variables defined or redefined inside construct
    NEW_DEF, // Doesn't shadow outer variables
    REDEF,;  // Redefines value of variable from outer scope

    public boolean includesRedefs() {
      return this == ANY || this == REDEF;
    }
    
    public boolean includesNewDefs() {
      return this == ANY || this == NEW_DEF;
    }
  }
  
  /**
   * A variable that must be closed for a computation to proceed
   */
  public static class BlockingVar {
    public final Var var;
    /** Whether variable must be recursively closed */
    public final boolean recursive;
    public final boolean explicit;
    
    public BlockingVar(Var var, boolean recursive, boolean explicit) {
      super();
      this.var = var;
      this.recursive = recursive;
      this.explicit = explicit;
    }
  }
  
  public static abstract class AsyncContinuation extends Continuation {
    protected final List<PassedVar> passedVars;
    protected final List<Var> keepOpenVars;
    
    
    public AsyncContinuation(List<PassedVar> passedVars,
                            List<Var> keepOpenVars) {
      this.passedVars = new ArrayList<PassedVar>(passedVars);
      this.keepOpenVars = new ArrayList<Var>(keepOpenVars);
    }
    @Override
    public Collection<PassedVar> getPassedVars() {
      return Collections.unmodifiableList(this.passedVars);
    }
    
    public Collection<PassedVar> getAllPassedVars() {
      // By default, no extras
      return getPassedVars();
    }
    
    @Override
    public void setPassedVars(Collection<PassedVar> passedVars) {
      this.passedVars.clear();
      this.passedVars.addAll(passedVars);
    }
    
    @Override
    public void setKeepOpenVars(Collection<Var> keepOpenVars) {
      this.keepOpenVars.clear();
      this.keepOpenVars.addAll(keepOpenVars);
    }

    @Override
    public Collection<Var> getKeepOpenVars() {
      return Collections.unmodifiableList(this.keepOpenVars);
    }
    
    /**
     * For overriding by child class
     * @param renames
     * @param mode
     */
    public abstract void replaceConstructVars_(Map<Var, Arg> renames, 
                  RenameMode mode);
    
    @Override
    public final void replaceConstructVars(Map<Var, Arg> renames, 
                                            RenameMode mode) {
      this.replaceConstructVars_(renames, mode);
    }
    
    /**
     * For overriding by child class
     */
    public abstract void removeVars_(Set<Var> removeVars);
    
    @Override
    public final void removeVars(Set<Var> removeVars) {
      removeVars_(removeVars);
      removeVarsInBlocks(removeVars);
    }
    
    @Override
    public abstract ExecContext childContext(ExecContext outerContext);
    
  }

  public static abstract class AbstractLoop extends AsyncContinuation {
    protected Block loopBody;

    public AbstractLoop(Block loopBody, List<PassedVar> passedVars,
        List<Var> keepOpenVars, boolean emptyLoop) {
      super(passedVars, keepOpenVars);
      this.loopBody = loopBody;
      this.loopBody.setParent(this, emptyLoop);
    }

    public Block getLoopBody() {
      return loopBody;
    }
    
    @Override
    public boolean isLoop() {
      return true;
    }
    
    @Override
    public boolean isConditional() {
      return false;
    }

    @Override
    public List<Block> getBlocks() {
      return Arrays.asList(loopBody);
    }

    @Override
    public Collection<Var> requiredVars(boolean de) {
      return Var.NONE;
    }

    protected void checkNotRemoved(Var v, Set<Var> removeVars) {
      if (removeVars.contains(v)) {
        throw new STCRuntimeError("bad optimization: tried to remove" +
        " required variable " + v.toString());
      }
    }
    protected void checkNotRemoved(Arg o, Set<Var> removeVars) {
      if (o.kind == ArgKind.VAR) {
        checkNotRemoved(o.getVar(), removeVars);
      }
    }

    @Override
    public void inlineInto(Block block, Block predictedBranch) {
      throw new STCRuntimeError("Can't inline loops yet");
    }

    protected void fuseIntoAbstract(AbstractLoop o, boolean insertAtTop) {
      this.loopBody.insertInline(o.loopBody, insertAtTop);
    }
  }
  
  public static class Loop extends AbstractLoop {
    private final String loopName;
    private final Var condVar;
    private final List<Var> loopVars;
    // Whether loop var is defined here (instead of defined outside loop)
    private final List<Boolean> definedHere;
    private final List<Var> initVals;

    /*
     * Have handles to the termination instructions
     */
    private LoopBreak loopBreak;

    private LoopContinue loopContinue;
    private final ArrayList<Boolean> blockingVars;


    public Loop(String loopName, List<Var> loopVars,
            List<Boolean> definedHere, List<Var> initVals, List<PassedVar> passedVars,
            List<Var> keepOpenVars, List<Boolean> blockingVars) {
      this(loopName, new Block(BlockType.LOOP_BODY, null), loopVars,
          definedHere, initVals, passedVars, keepOpenVars, blockingVars,
          true);
    }

    private Loop(String loopName, Block loopBody,
        List<Var> loopVars,  List<Boolean> definedHere,
        List<Var> initVals,
        List<PassedVar> passedVars, List<Var> keepOpenVars,
        List<Boolean> blockingVars, boolean emptyLoop) {
      super(loopBody, passedVars, keepOpenVars, emptyLoop);
      this.loopName = loopName;
      this.condVar = loopVars.get(0);
      this.loopVars = new ArrayList<Var>(loopVars);
      this.definedHere = new ArrayList<Boolean>(definedHere);
      this.initVals = new ArrayList<Var>(initVals);
      this.blockingVars = new ArrayList<Boolean>(blockingVars);
      assert(loopVars.size() == definedHere.size());
      assert(loopVars.size() == initVals.size());
      assert(loopVars.size() == blockingVars.size());
      for (int i = 0; i < loopVars.size(); i++) {
        Var loopV = loopVars.get(i);
        Var initV = initVals.get(i);
        if (!loopV.type().equals(initV.type())) {
          throw new STCRuntimeError("loop variable " + loopV.toString()
              + " is given init value of wrong type: " + initV.toString());
        }
      }
    }

    @Override
    public Loop clone() {
      // Constructor creates copies of variable lists
      Loop cloned = new Loop(loopName, this.loopBody.clone(),
          loopVars, definedHere, initVals,
          passedVars, keepOpenVars, blockingVars, false);

      // fix up the references to the loopContinue/loopBreak instructions
      Pair<LoopBreak, LoopContinue> insts = cloned.findInstructions();
      cloned.setLoopBreak(insts.val1);
      cloned.setLoopContinue(insts.val2);
      return cloned;
    }

    private Pair<LoopBreak, LoopContinue> findInstructions() {
      LoopBreak breakInst = null;
      LoopContinue continueInst = null;
      Deque<Block> blocks = new ArrayDeque<Block>();
      blocks.add(loopBody);
      while (!blocks.isEmpty()) {
        // Find instructions
        Block curr = blocks.pop();
        for (Statement stmt: curr.getStatements()) {
          if (stmt.type() == StatementType.INSTRUCTION) {
            Instruction inst = stmt.instruction();
            if (inst.op == Opcode.LOOP_BREAK) {
              assert(breakInst == null): "duplicate instructions: " + breakInst
                      + " and \n" + inst;
              breakInst = (LoopBreak)inst;
            } else if (inst.op == Opcode.LOOP_CONTINUE) {
              assert(continueInst == null): "duplicate instructions: " + continueInst
                      + " and \n" + inst;
              continueInst = (LoopContinue)inst;
            }
          }
        }
        
        for (Continuation cont: curr.allComplexStatements()) {
          // Don't go into inner loops, as they will have their own
          // break/continue instructions
          if (cont.getType() != ContinuationType.LOOP) {
            for (Block inner: cont.getBlocks()) {
              blocks.push(inner);
            }
          }
        }
      }
      
      assert(breakInst != null) : "No loop break for loop\n" + this;
      assert(continueInst != null) : "No loop continue for loop\n" + this;
      
      Pair<LoopBreak, LoopContinue> insts = Pair.create(breakInst, continueInst);
      return insts;
    }

    @Override
    public ContinuationType getType() {
      return ContinuationType.LOOP;
    }

    @Override
    public boolean isAsync() {
      return true;
    }
    
    @Override
    public boolean spawnsSingleTask() {
      // Only one task is spawned right away.  That tasks spawns further
      // for subsequent iterations
      return true;
    }

    public void setLoopBreak(LoopBreak loopBreak) {
      this.loopBreak = loopBreak;
    }

    public void setLoopContinue(LoopContinue loopContinue) {
      this.loopContinue = loopContinue;
    }

    @Override
    public void generate(Logger logger, CompilerBackend gen, GenInfo info) {
      gen.startLoop(loopName, loopVars, definedHere, initVals,
                    PassedVar.extractVars(passedVars), keepOpenVars, blockingVars);
      this.loopBody.generate(logger, gen, info);
      gen.endLoop();
    }

    @Override
    public void prettyPrint(StringBuilder sb, String currentIndent) {
      sb.append(currentIndent + "loop /*" + loopName + "*/\n");
      sb.append(currentIndent + indent + indent + "while (");
      sb.append(condVar.type().typeName() + " " + condVar.name());
      sb.append(")\n" + currentIndent + indent + indent + "loopvars (");
      boolean first = true;
      for (int i = 0; i < loopVars.size(); i++) {
        Var loopV = loopVars.get(i);
        Var initV = initVals.get(i);
        if (first) {
          first = false;
        } else {
          sb.append(", ");
        }
        sb.append(loopV.type().typeName() + " " + loopV.name() + "="
            + initV.name());
      }

      sb.append(")\n" + currentIndent + indent + indent);
      ICUtil.prettyPrintVarInfo(sb, passedVars, keepOpenVars);
      if (blockingVars.contains(true)) {
        List<Var> blockon = new ArrayList<Var>();
        for (int i = 0; i < loopVars.size(); i++) {
          if (blockingVars.get(i)) {
            blockon.add(loopVars.get(i));
          }
        }
        sb.append(" #blockon[");
        ICUtil.prettyPrintVarList(sb, blockon);
        sb.append("]");
      }
      sb.append(" {\n");
      loopBody.prettyPrint(sb, currentIndent + indent);
      sb.append(currentIndent + "}\n");
    }
    
    @Override
    public void replaceConstructVars_(Map<Var, Arg> renames,
                                      RenameMode mode) {
      ICUtil.replaceVarsInList(renames, initVals, false);
      if (mode == RenameMode.REPLACE_VAR) {
        ICUtil.replaceVarsInList(renames, loopVars, false);
      }
    }
    
    @Override
    public void removeRedef(Var oldV, Var newV) {
      for (int i = 0; i < loopVars.size(); i++) {
        Var loopVar = loopVars.get(i);
        if (loopVar.equals(oldV)) {
          assert(!this.definedHere.get(i)) : loopVar;
          this.loopVars.set(i, newV);
          this.definedHere.set(i, true);
        }
      }
    }

    @Override
    public Collection<Var> requiredVars(boolean forDeadCodeElim) {
      Collection<Var> res = new ArrayList<Var>(
          super.requiredVars(forDeadCodeElim));
      res.addAll(initVals);
      return res;
    }

    @Override
    public void removeVars_(Set<Var> removeVars) {
      // check it isn't removing initial values
      for (Var v: this.initVals) {
        checkNotRemoved(v, removeVars);
      }
      for (Var v: this.loopVars) {
        checkNotRemoved(v, removeVars);
      }
    }

    @Override
    public boolean isNoop() {
      // TODO: think about particular conditions that would render it a noop.
      //
      return false;
    }

    @Override
    public List<Var> constructDefinedVars(ContVarDefType type) {
      ArrayList<Var> defVars = new ArrayList<Var>();
      for (int i = 0; i < this.loopVars.size(); i++) {
        Boolean defHere = this.definedHere.get(i);
        Var loopVar = this.loopVars.get(i);
        if (type.includesNewDefs() && defHere) {
          defVars.add(loopVar);
        } else if (type.includesRedefs() && !defHere) {
          defVars.add(loopVar);
        }
      }
      return defVars;
    }

    /**
     * Return all per-iteration vars defined within continuation
     * @return
     */
    public List<Var> getLoopVars() {
      return Collections.unmodifiableList(this.loopVars);
    }

    @Override
    public List<BlockingVar> blockingVars(boolean includeConstructDefined) {
      ArrayList<BlockingVar> res = new ArrayList<BlockingVar>();
      for (int i = 0; i < loopVars.size(); i++) {
        if (blockingVars.get(i)) {
          res.add(new BlockingVar(initVals.get(i), false, false));
          if (includeConstructDefined) {
            res.add(new BlockingVar(loopVars.get(i), false, true));
          }
        }
      }
      return res;
    }
    
    @Override
    public void setPassedVars(Collection<PassedVar> passedVars) {
      super.setPassedVars(passedVars);
      this.loopContinue.setLoopUsedVars(PassedVar.extractVars(passedVars));
      this.loopBreak.setLoopUsedVars(passedVars);
    }
    
    @Override
    public void setKeepOpenVars(Collection<Var> keepOpen) {
      super.setKeepOpenVars(keepOpen);
      this.loopBreak.setKeepOpenVars(keepOpen);
    }
    
    public Collection<PassedVar> getAllPassedVars() {
      // Initial vals of loop vars are also passed in
      List<PassedVar> result = new ArrayList<PassedVar>();
      result.addAll(passedVars);
      for (Var initVal: initVals) {
        result.add(new PassedVar(initVal, false));
      }
      return result;
    }
    
    
    public Var getInitCond() {
      return this.initVals.get(0);
    }
    
    @Override
    public ExecContext childContext(ExecContext outerContext) {
      return outerContext;
    }

  }
  
  public static class NestedBlock extends Continuation {
    private final Block block;

    public NestedBlock() {
      this(new Block(BlockType.NESTED_BLOCK, null));
    }


    public NestedBlock(Block block) {
      super();
      this.block = block;
      this.block.setParent(this, false);
    }

    @Override
    public NestedBlock clone() {
      return new NestedBlock(this.block.clone());
    }

    @Override
    public void generate(Logger logger, CompilerBackend gen, GenInfo info) {
      gen.startNestedBlock();
      block.generate(logger, gen, info);
      gen.endNestedBlock();
    }

    public Block getBlock() {
      return this.block;
    }

    @Override
    public void prettyPrint(StringBuilder sb, String currentIndent) {
      sb.append(currentIndent + "{\n");
      block.prettyPrint(sb, currentIndent + indent);
      sb.append(currentIndent + "}\n");
    }

    @Override
    public List<Block> getBlocks() {
      return Arrays.asList(block);
    }

    @Override
    public ContinuationType getType() {
      return ContinuationType.NESTED_BLOCK;
    }

    @Override
    public boolean isAsync() {
      return false;
    }
    
    @Override
    public boolean isLoop() {
      return false;
    }
    
    @Override
    public boolean isConditional() {
      return false;
    }

    @Override
    protected void replaceConstructVars(Map<Var, Arg> renames, 
                                        RenameMode mode) {
      // Do nothing
    }

    @Override
    public Collection<Var> requiredVars(boolean forDeadCodeElim) {
      return Var.NONE;
    }

    @Override
    public void removeVars(Set<Var> removeVars) {
      removeVarsInBlocks(removeVars);
    }

    @Override
    public boolean isNoop() {
      return block.isEmpty();
    }
  }
  
  /**
   * TODO: add recursive?
   *
   */
  public static class WaitVar implements Comparable<WaitVar> {
    public final Var var;
    public final boolean explicit;
    public WaitVar(Var var, boolean explicit) {
      this.var = var;
      this.explicit = explicit;
    }
    
    /**
     * Remove duplicate entries.  If one is explicit, retained one
     * must be explicit
     * @param list
     */
    public static final void removeDuplicates(List<WaitVar> list) {
      Collections.sort(list);
      ListIterator<WaitVar> it = list.listIterator();
      if (!it.hasNext())
        return;
      
      WaitVar last = it.next();
      while (it.hasNext()) {
        WaitVar curr = it.next();
        if (curr.var.equals(last.var)) {
          // Remove any duplicates.  Due to ordering, explicit version will be retained
          it.remove();
        } else {
          last = curr;
        }
      }
    }

    /**
     * Make comparable so can sort to find duplicates
     */
    @Override
    public int compareTo(WaitVar o) {
      int res = var.name().compareTo(o.var.name());
      if (res != 0) {
        return res;
      }
      if (explicit == o.explicit)
        return 0;
      // explicit should be before not explicit
      return explicit ? -1 : 1;
    }

    public static List<Var> asVarList(List<WaitVar> waitVars) {
      ArrayList<Var> res = new ArrayList<Var>();
      for (WaitVar wv: waitVars) {
        res.add(wv.var);
      }
      return res;
    }

    public static List<WaitVar> asWaitVarList(List<Var> vars, boolean explicit) {
      ArrayList<WaitVar> res = new ArrayList<WaitVar>(vars.size());
      for (Var v: vars) {
        res.add(new WaitVar(v, explicit));
      }
      return res;
    }

    public static List<WaitVar> makeList(Collection<Var> waitVars, boolean explicit) {
      List<WaitVar> waitVars2 = new ArrayList<WaitVar>(waitVars.size());
      for (Var v: waitVars) {
        waitVars2.add(new WaitVar(v, explicit));
      }
      return waitVars2;
    }
    
    public static WaitVar find(List<WaitVar> waitVars,
        Var var) {
      for (WaitVar wv: waitVars) {
        if (var.equals(wv.var))
          return wv;
      }
      return null;
    }

    /**
     * For pretty printing
     */
    public String toString() {
      return var.name() + (explicit ? ":EXPLICIT" : "");
    }
    
    @Override
    public boolean equals(Object other) {
      if (!(other instanceof WaitVar))
          throw new STCRuntimeError("Comparing WaitVar with: " + other + 
                                    " of class " + other.getClass());
      WaitVar owv = (WaitVar)other;
      return owv.explicit == explicit &&
             owv.var.equals(this.var);
    }

    @Override
    public int hashCode() {
      return Integer.rotateLeft(var.hashCode(), 1) & (explicit ? 0 : 1);
    }
    
    public static final List<WaitVar> NONE = Collections.emptyList();
  }

  /**
   * A new construct that blocks on a list of variables (blockVars),
   * and only runs the contents once all of those variables are closed
   *
   */
  public static class WaitStatement extends AsyncContinuation {
    /** Label name to use in final generated code */
    private final String procName;
    private final Block block;
    private final List<WaitVar> waitVars;
    
    /* True if this wait was compiler-generated so can be removed if needed
     * We can only remove an explicit wait if we know that the variables are
     * already closed*/
    private WaitMode mode;
    private final boolean recursive;
    private TaskMode target;
    
    private final TaskProps props;

    public WaitStatement(String procName, List<WaitVar> waitVars,
                    List<PassedVar> passedVars,
                    List<Var> keepOpenVars,
                    WaitMode mode, boolean recursive, TaskMode target,
                    TaskProps props) {
      this(procName, new Block(BlockType.WAIT_BLOCK, null), true, waitVars,
           passedVars, keepOpenVars, mode, recursive, target, props);
      assert(this.block.getParentCont() != null);
    }

    private WaitStatement(String procName, Block block,
        boolean newBlock, List<WaitVar> waitVars, List<PassedVar> passedVars,
        List<Var> keepOpenVars,
        WaitMode mode, boolean recursive, TaskMode target,
        TaskProps props) {
      super(passedVars, keepOpenVars);
      assert(waitVars != null);
      assert(passedVars != null);
      assert(keepOpenVars != null);
      assert(target != null);
      assert(mode != null);
      this.procName = procName;
      this.block = block;
      this.block.setParent(this, newBlock);
      this.waitVars = new ArrayList<WaitVar>(waitVars);
      WaitVar.removeDuplicates(this.waitVars);
      this.mode = mode;
      this.recursive = recursive;
      this.target = target;
      this.props = props.clone();
    }

    @Override
    public WaitStatement clone() {
      return new WaitStatement(procName, this.block.clone(),
          false, waitVars, passedVars, keepOpenVars,
          mode, recursive, target, props.clone());
    }

    public Block getBlock() {
      return block;
    }

    @Override
    public void generate(Logger logger, CompilerBackend gen, GenInfo info) {
      gen.startWaitStatement(procName, WaitVar.asVarList(waitVars), 
          PassedVar.extractVars(passedVars), recursive, target, props);
      this.block.generate(logger, gen, info);
      gen.endWaitStatement();
    }

    @Override
    public void prettyPrint(StringBuilder sb, String currentIndent) {
      String newIndent = currentIndent + indent;
      sb.append(currentIndent + "wait (");
      ICUtil.prettyPrintList(sb, waitVars);
      sb.append(") ");
      sb.append("/*" + procName + "*/ " );
      ICUtil.prettyPrintVarInfo(sb, passedVars, keepOpenVars);
      sb.append(" <" + mode + ", " + target + ", " +
                (recursive ? "RECURSIVE" : "NONRECURSIVE") + ">");
      ICUtil.prettyPrintProps(sb, props);
      sb.append(" {\n");
      block.prettyPrint(sb, newIndent);
      sb.append(currentIndent + "}\n");
    }

    @Override
    public List<Block> getBlocks() {
      return Arrays.asList(block);
    }

    @Override
    public void replaceConstructVars_(Map<Var, Arg> renames, 
                                      RenameMode mode) {
      boolean replaced = false;
      ListIterator<WaitVar> it = waitVars.listIterator();
      while (it.hasNext()) {
        WaitVar wv = it.next();
        Arg replacement = renames.get(wv.var);
        if (replacement != null && replacement.isVar()) {
          it.set(new WaitVar(replacement.getVar(), wv.explicit));
          replaced = true;
        }
      }
      if (replaced) {
        WaitVar.removeDuplicates(waitVars);
      }
      
      ICUtil.replaceArgValsInMap(renames, props);
    }
    
    public WaitMode getMode() {
      return mode;
    }
    
    public boolean isRecursive() {
      return recursive;
    }
    
    public TaskMode getTarget() {
      return target;
    }
    @Override
    public ContinuationType getType() {
      return ContinuationType.WAIT_STATEMENT;
    }

    public void setTarget(TaskMode target) {
      this.target = target;
    }

    public void setMode(WaitMode mode) {
      this.mode = mode;
    }

    @Override
    public boolean isAsync() {
      return true;
    }
    
    @Override
    public boolean spawnsSingleTask() {
      return !isParallel();
    }
    
    public boolean isParallel() {
      Arg parallelism = parallelism();
      return parallelism != null && !(parallelism.isIntVal() &&
                                      parallelism.getIntLit() > 1);
    }
    
    public Arg parallelism() {
      return props.get(TaskPropKey.PARALLELISM);
    }
    
    public Arg targetLocation() {
      return props.get(TaskPropKey.LOCATION);
    }
    
    @Override
    public boolean isLoop() {
      // The parallel annotation means that the contents of block can execute
      // multiple times => basically a loop
      return isParallel();
    }
    
    @Override
    public boolean isConditional() {
      return false;
    }

    @Override
    public Collection<Var> requiredVars(boolean forDeadCodeElim) {
      ArrayList<Var> res = new ArrayList<Var>();
      for (WaitVar wv: waitVars) {
        // Explicit variables cannot be eliminated
        if (!forDeadCodeElim || wv.explicit) { 
          res.add(wv.var);
        }
      }
      for (Arg arg: props.values()) {
        assert(arg != null);
        if (arg.isVar()) {
          res.add(arg.getVar());
        }
      }
      return res; // can later eliminate waitVars, etc
    }
    
    public List<PassedVar> getMustPassVars() {
      try {
        if (Settings.getBoolean(Settings.MUST_PASS_WAIT_VARS)) {
          List<PassedVar> res = new ArrayList<PassedVar>();
          for (WaitVar wv: waitVars) {
            res.add(new PassedVar(wv.var, false));
          }
          return res;
        } else {
          return PassedVar.NONE;
        }
      } catch (InvalidOptionException e) {
        throw new STCRuntimeError(e.getMessage());
      }
    }

    public List<WaitVar> getWaitVars() {
      return Collections.unmodifiableList(this.waitVars);
    }

    public void addWaitVars(Collection<WaitVar> vars) {
      this.waitVars.addAll(vars);
      WaitVar.removeDuplicates(this.waitVars);
    }

    @Override
    public void removeVars_(Set<Var> removeVars) {
      ListIterator<WaitVar> it = waitVars.listIterator();
      while (it.hasNext()) {
        WaitVar wv = it.next();
        if (removeVars.contains(wv.var)) {
          it.remove();
        }
      }
    }

    @Override
    public boolean isNoop() {
      return this.block.isEmpty();
    }

    @Override
    public Block tryInline(Set<Var> closedVars, Set<Var> recClosedVars,
        boolean keepExplicitDependencies) {
      boolean varsLeft = false;
      // iterate over wait vars, remove those in list
      ListIterator<WaitVar> it = waitVars.listIterator();
      while(it.hasNext()) {
        WaitVar wv = it.next();
        // See if we can skip waiting on var
        if (keepExplicitDependencies && wv.explicit) {
          varsLeft = true;
        } else if ((closedVars.contains(wv.var) && !recursionRequired(wv.var))
            || recClosedVars.contains(wv.var)) {
          it.remove();
        } else {
          varsLeft = true;
        }
      }
      // Can't eliminate if purpose of wait is to dispatch task
      if (varsLeft || mode == WaitMode.TASK_DISPATCH || 
          isParallel() || targetLocation() != null) {
        return null;
      } else {
        // if at end we have nothing left, return the inner block for inlining
        return block;
      }
    }
    
    public void removeWaitVars(List<WaitVar> toRemove,
        boolean allRecursive, boolean retainExplicit) {
      ListIterator<WaitVar> it = waitVars.listIterator();
      while (it.hasNext()) {
        WaitVar wv = it.next();
        for (WaitVar r: toRemove) {
          if (r.var.equals(wv.var)) {
            if (this.recursive && !allRecursive  &&recursionRequired(wv.var)) {
              // Do nothing
            } else if (!retainExplicit || r.explicit || !wv.explicit) {
              // Sufficiently explicit
              it.remove();
            }
          }
        }
      }
    }

    public void inlineInto(Block dstBlock) {
      inlineInto(dstBlock, this.block);
    }
    
    /**
     * @param wv
     * @return true if we need to recursively check closing for variable, i.e.
     *      if superficial closing isn't enough to consider it closed for this
     *      wait statement
     */
    private boolean recursionRequired(Var wv) {
      if (!recursive) {
        return false;
      }
      if (Types.isPrimFuture(wv.type())) {
        return false;
      }
      return true;
    }

    @Override
    public List<BlockingVar> blockingVars(boolean includeConstructDefined) {
      ArrayList<BlockingVar> res = new ArrayList<BlockingVar>(waitVars.size());
      for (WaitVar wv: waitVars) {
        res.add(new BlockingVar(wv.var, this.recursive, wv.explicit));
      }
      return res;
    }

    /**
     * True if any wait vars are explicit
     * @return
     */
    public boolean hasExplicit() {
      for (WaitVar wv: waitVars) {
        if (wv.explicit) {
          return true;
        }
      }
      
      return false;
    }

    @Override
    public ExecContext childContext(ExecContext outerContext) {
      switch (target) {
      case SYNC:
      case LOCAL:
        return outerContext;
      case LOCAL_CONTROL:
        // Check used validly
        assert(outerContext == ExecContext.CONTROL);
        return outerContext;
      case CONTROL:
        return ExecContext.CONTROL;
      case WORKER:
        return ExecContext.WORKER;
      default:
        throw new STCRuntimeError("Unknown wait target: " + target);
      }
    }
  }

}
