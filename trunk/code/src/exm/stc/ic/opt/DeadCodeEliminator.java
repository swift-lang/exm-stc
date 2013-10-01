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
package exm.stc.ic.opt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.apache.log4j.Logger;

import exm.stc.common.Settings;
import exm.stc.common.exceptions.UserException;
import exm.stc.common.lang.Arg;
import exm.stc.common.lang.Types;
import exm.stc.common.lang.Var;
import exm.stc.common.lang.Var.Alloc;
import exm.stc.common.util.MultiMap;
import exm.stc.common.util.Pair;
import exm.stc.common.util.TernaryLogic.Ternary;
import exm.stc.ic.ICUtil;
import exm.stc.ic.opt.OptimizerPass.FunctionOptimizerPass;
import exm.stc.ic.tree.ICContinuations.Continuation;
import exm.stc.ic.tree.ICInstructions.Instruction;
import exm.stc.ic.tree.ICTree.Block;
import exm.stc.ic.tree.ICTree.Function;
import exm.stc.ic.tree.ICTree.Statement;
import exm.stc.ic.tree.ICTree.StatementType;
import exm.stc.ic.tree.Opcode;

public class DeadCodeEliminator extends FunctionOptimizerPass {

  @Override
  public String getPassName() {
    return "Dead code elimination";
  }

  @Override
  public String getConfigEnabledKey() {
    return Settings.OPT_DEAD_CODE_ELIM;
  }
  
  
  
  @Override
  public void optimize(Logger logger, Function f) throws UserException {
    eliminate(logger, f);
  }

  /**
   * Eliminates dead code in the current block and child blocks. Since this is a
   * data flow language, the easiest way to do this is to find variables which
   * aren't needed, eliminate those and the instructions which write to them,
   * and then do that repeatedly until we don't have anything more to eliminate.
   * 
   * We avoid eliminating any instructions with side-effects, and anything that
   * contributes to the return value of a function. We currently assume that all
   * non-builtin functions have side effects, as well as any builtins operations
   * that are not specifically marked as side-effect free.
   * 
   * @param logger
   * @param f
   */
  public static void eliminate(Logger logger, Function f) {
    boolean converged = false;
    while (!converged) {
      // Dead variable elimination can allow dead code blocks to be removed,
      // which can allow more variables to be removed.  So we should just
      // iterate until no more changes were made
      converged = !eliminateIter(logger, f);
    }
  }

  /**
   * 
   * @param logger
   * @param f
   * @return true if changes made
   */
  private static boolean eliminateIter(Logger logger, Function f) {
    /* All vars defined in function blocks that could possibly be eliminated */
    HashSet<Var> removeCandidates = new HashSet<Var>();

    /* Set of vars that are definitely required */
    HashSet<Var> needed = new HashSet<Var>();

    /* List of vars that were written that might have resulted in
     *  modification of part of larger array or structure */
    List<Var> modifiedComponents = new ArrayList<Var>(); 
    /*
     * Graph of dependencies from vars to other vars. If edge exists v1 -> v2
     * this means that if v1 is required, then v2 is required
     */
    MultiMap<Var, Var> dependencyGraph = new MultiMap<Var, Var>();
    
    /* a -> b means that a is part of b */
    Map <Var, Var> componentOf = new HashMap<Var, Var>();

    walkFunction(logger, f, removeCandidates, needed, dependencyGraph,
                            modifiedComponents, componentOf);
    
    if (logger.isTraceEnabled()) {
      logger.trace("Dead code elimination in function " + f.getName() + "\n" +
                   "removal candidates: " + removeCandidates + "\n" +
                   "definitely needed: "+ needed + "\n" +
                   "dependencies: \n" + printDepGraph(dependencyGraph, 4) +
                   "modified: " + modifiedComponents + "\n" +
                   "componentOf: \n" + ICUtil.prettyPrintMap(componentOf, 4));
    }
    
    /*
     * Add in component info.
     * Take into account that we might modify value of containing
     * structure, e.g. array
     */
    for (Var written: modifiedComponents) {
      Var whole = componentOf.get(written);
      if (logger.isTraceEnabled())
        logger.trace("Modified var " + written);
      while (whole != null) {
        // Need to keep written var if we keep whole
        if (logger.isTraceEnabled())
          logger.trace("Add transitive dep " + whole + " => " + written);
        dependencyGraph.put(whole, written);
        whole = componentOf.get(whole);
      }
    }
    
    if (logger.isTraceEnabled())
      logger.trace("dependencies after component updates: \n" +
                   printDepGraph(dependencyGraph, 4));
    /*
     * Expand set of needed based on dependency graph 
     */
    ArrayDeque<Var> workStack = new ArrayDeque<Var>();
    workStack.addAll(needed);
    
    while (!workStack.isEmpty()) {
      Var neededVar = workStack.pop();
      // This loop converges as dependencyGraph is taken apart
      List<Var> deps = dependencyGraph.remove(neededVar);
      if (deps != null) {
        needed.addAll(deps);
        workStack.addAll(deps);
      }
    }
    
    removeCandidates.removeAll(needed);
    
    if (logger.isDebugEnabled()) {
      logger.debug("Final variables to be eliminated: " + removeCandidates);
    }
    if (removeCandidates.isEmpty()) {
      return false;
    } else {
      f.mainBlock().removeVars(removeCandidates);
      return true;
    }
  } 

  /**
   * Collect information for dead code elimination
   * 
   * @param logger
   * @param f
   * @param removeCandidates list of vars declared in function that
   *                           could be removed
   * @param needed
   * @param dependencyGraph
   * @param modifiedComponents 
   * @param componentOf
   */
  private static void walkFunction(Logger logger, Function f,
      HashSet<Var> removeCandidates, HashSet<Var> needed,
      MultiMap<Var, Var> dependencyGraph, List<Var> modifiedComponents,
      Map <Var, Var> componentOf) {
    ArrayDeque<Block> workStack = new ArrayDeque<Block>();
    workStack.push(f.mainBlock());
    
    needed.addAll(f.getOutputList());
    
    while (!workStack.isEmpty()) {
      Block block = workStack.pop();

      walkBlockVars(block, removeCandidates, dependencyGraph);
      
      walkInstructions(logger, block, needed, dependencyGraph, modifiedComponents,
                       componentOf);
      
      Iterator<Continuation> it = block.allComplexStatements().iterator();
      while (it.hasNext()) {
        Continuation c = it.next();
        if (c.isNoop()) {
          it.remove();
        } else {
          // Add vars for continuation
          needed.addAll(c.requiredVars(true));
          
          for (Block inner: c.getBlocks()) {
            workStack.push(inner);
          }
        }
      }
    }
  }

  private static void walkInstructions(Logger logger,
      Block block, HashSet<Var> needed, MultiMap<Var, Var> dependencyGraph,
      List<Var> modifiedComponents, Map<Var, Var> componentOf) {
    ListIterator<Statement> it = block.statementIterator();
    while (it.hasNext()) {
      Statement stmt = it.next();
      if (stmt.type() == StatementType.INSTRUCTION) {
        walkInstruction(logger, stmt.instruction(), needed, dependencyGraph,
                        modifiedComponents, componentOf);
      } else if (stmt.type() == StatementType.CONDITIONAL) {
        if (stmt.conditional().isNoop()) {
          it.remove();
        }
      }
    }
  }

  private static void walkInstruction(Logger logger, Instruction inst,
      HashSet<Var> needed, MultiMap<Var, Var> dependencyGraph,
      List<Var> modifiedComponents, Map<Var, Var> componentOf) {
    // If it has side-effects, need all inputs and outputs
    if (inst.hasSideEffects()) {
      needed.addAll(inst.getOutputs());
      for (Arg input: inst.getInputs()) {
        if (input.isVar()) {
          needed.add(input.getVar());
        }
      }
    } else {
      // Add edges to dependency graph
      List<Var> outputs = inst.getOutputs();
      List<Var> modOutputs = inst.getModifiedOutputs();
      List<Var> readOutputs = inst.getReadOutputs();
      List<Arg> inputs = inst.getInputs();
      
      // First, if multiple modified outputs, need to remove all at once
      if (modOutputs.size() > 1) {
        // Connect mod outputs in ring so that they are
        // strongly connected component
        for (int i = 0; i < modOutputs.size(); i++) { 
          int j = (i + 1) % modOutputs.size();
          dependencyGraph.put(modOutputs.get(i), modOutputs.get(j));
        } 
      }
      if (modOutputs.size() > 0) {
        // Second, modified output depends on all inputs and read outputs. 
        // Just use one output if multiple
        Var out = modOutputs.get(0);
        for (Arg in: inputs) {
          if (in.isVar()) {
            addOutputDep(logger, inst, dependencyGraph, componentOf, out,
                         in.getVar());
          }
        }
        
        for (Var readOut: readOutputs) {
          addOutputDep(logger, inst, dependencyGraph, componentOf, out,
                       readOut);
        }
      }
      // Writing mapped var can have side-effect, unless we're storing a
      // mapped value var that was written
      for (Var output: outputs) {
        if (output.isMapped() != Ternary.FALSE && 
            inst.op != Opcode.STORE_FILE) {
          needed.add(output);
        }
      }
      
      // Update written vars list
      for (Var mod: modOutputs) {
        if (Types.isArray(mod.type()) || Types.isRef(mod.type())) {
          boolean modInit = inst.isInitialized(mod);
          if (!modInit && inst.op != Opcode.STORE_REF) {
            modifiedComponents.add(mod);
          }
        }
      }
      
      // Update structural information
      Pair<Var, Var> componentAlias = inst.getComponentAlias();
      if (componentAlias != null) {
        Var component = componentAlias.val1;
        assert(component.storage() == Alloc.ALIAS ||
            Types.isRef(component.type())) : component + " " + inst;
        Var whole = componentAlias.val2;
        Var prev = componentOf.put(component, whole);
        // shouldn't be component of multiple things
        if (prev != null && logger.isTraceEnabled()) {
          logger.trace(component + " part of (" + prev + ", " + whole + ")");
        }
      }
    }
  }
  
  private static void addOutputDep(Logger logger,
      Instruction inst, MultiMap<Var, Var> dependencyGraph,
      Map<Var, Var> componentOf, Var out, Var in) {
    if (logger.isTraceEnabled())
      logger.trace("Add dep " + out + " => " + in + " for inst " + inst);
    dependencyGraph.put(out, in);
  }

  private static void walkBlockVars(Block block,
      HashSet<Var> removeCandidates, MultiMap<Var, Var> dependencyGraph) {
    for (Var v: block.getVariables()) {
      if (v.storage() != Alloc.GLOBAL_CONST) {
        removeCandidates.add(v);
      }
      if (v.mapping() != null) {
        // Need mapping if v is retained
        dependencyGraph.put(v, v.mapping());
      }
    }
  }

  // TODO: do this somewhere else?
  private static void pushdownDeclarations(Block block,
      Map<Var, Block> candidates) {
    if (candidates.size() > 0) {
      ListIterator<Var> varIt = block.variableIterator();
      while (varIt.hasNext()) {
        Var var = varIt.next();
        Block newHome = candidates.get(var);
        // Don't push declaration down into loop
        if (newHome != null && !newHome.getParentCont().isLoop()) {
          varIt.remove();
          newHome.addVariable(var);
          block.moveCleanups(var, newHome);
        }
      }
    }
  }

  private static String printDepGraph(MultiMap<Var, Var> dependencyGraph,
                                      int indent) {
    List<Var> keys = new ArrayList<Var>(dependencyGraph.keySet());
    Collections.sort(keys);
    StringBuilder sb = new StringBuilder();
    for (Var key: keys) {
      for (int i = 0; i < indent; i++) {
        sb.append(' ');
      }
      sb.append(key.name() + " => [");
      ICUtil.prettyPrintVarList(sb, dependencyGraph.get(key));
      sb.append("]\n");
    }
    
    return sb.toString();
  }

}