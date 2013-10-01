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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;

import exm.stc.common.Settings;
import exm.stc.common.lang.Arg;
import exm.stc.common.lang.ExecContext;
import exm.stc.common.lang.Types;
import exm.stc.common.lang.Types.StructType;
import exm.stc.common.lang.Types.StructType.StructField;
import exm.stc.common.lang.Types.Type;
import exm.stc.common.lang.Var;
import exm.stc.ic.opt.OptimizerPass.FunctionOptimizerPass;
import exm.stc.ic.opt.TreeWalk.TreeWalker;
import exm.stc.ic.tree.ICContinuations.Continuation;
import exm.stc.ic.tree.ICContinuations.ContinuationType;
import exm.stc.ic.tree.ICContinuations.NestedBlock;
import exm.stc.ic.tree.ICContinuations.WaitStatement;
import exm.stc.ic.tree.ICInstructions.Instruction;
import exm.stc.ic.tree.ICTree.Block;
import exm.stc.ic.tree.ICTree.Function;

/**
 * Compile-time pipelining optimization where we merge sequentially dependent
 * tasks.  This reduces scheduling/task dispatch/load balancing overhead, and
 * also eliminates the need to move intermediate data.
 * 
 * This is a pass that should be run once near end of optimization.
 * 
 * Running it multiple times can result in reduction in parallelism 
 */
public class Pipeline extends FunctionOptimizerPass {
  @Override
  public String getPassName() {
    return "Compile time pipelining";
  }

  @Override
  public String getConfigEnabledKey() {
    return Settings.OPT_PIPELINE;
  }

  @Override
  public void optimize(Logger logger, Function f) {
    pipelineTasks(logger, f, f.mainBlock(), ExecContext.CONTROL);
  }

  private static void pipelineTasks(Logger logger, Function f, Block curr,
      ExecContext cx) {
    // Do a bottom-up tree walk
    for (Continuation cont: curr.allComplexStatements()) {
      ExecContext childCx = cont.childContext(cx);
      for (Block childBlock: cont.getBlocks()) {
        pipelineTasks(logger, f, childBlock, childCx);
      }
    }

    // Find candidates for merging: wait statements which are not
    // blocked on anything and which execute in same context as this
    // block.
    List<WaitStatement> candidates = new ArrayList<WaitStatement>();
    for (Continuation cont: curr.getContinuations()) {
      if (cont.getType() == ContinuationType.WAIT_STATEMENT) {
        WaitStatement w = (WaitStatement)cont;
        boolean compatible = true;
        if (!w.getWaitVars().isEmpty()) {
          compatible = false;
        } else if (w.childContext(cx) != cx) {
          compatible = false;
        } else if (w.isParallel()) {
          compatible = false;
        }
        
        if (compatible) {
          candidates.add(w);
        }
      }
    }

    if (candidates.isEmpty()) {
      // Nothing to merge up
      return;
    }
    
    logger.trace("Found " + candidates.size() + " candidates for " +
    		" wait pipelining");
    WaitStatement bestCand = candidates.get(0);
    
    if (candidates.size() > 1) {
      int bestCost = heuristicCost(logger, f, curr, bestCand);
      for (int i = 1; i < candidates.size(); i++) {
        WaitStatement cand = candidates.get(i);
        int cost = heuristicCost(logger, f, curr, cand);
        if (cost < bestCost) {
          bestCost = cost;
          bestCand = cand;
        }
      }
    }
    
    if (candidates.size() == 1) {
      bestCand.inlineInto(curr);
    } else {
      // Need to make sure local code runs after tasks are spawned
      NestedBlock nested = new NestedBlock();
      bestCand.inlineInto(nested.getBlock());
      nested.setRunLast(true);
      curr.addContinuation(nested);
    }
  }
  
  
  private static int heuristicCost(Logger logger, Function f,
                      Block curr, WaitStatement cand) {

    final Set<Var> varsReadByChildTask = new HashSet<Var>();
    final Set<Var> varsDeclaredWithinChildTask = new HashSet<Var>();
    TreeWalker walker = new TreeWalker() {
            @Override
            protected void visit(Instruction inst) {
              for (Arg in: inst.getInputs()) {
                if (in.isVar()) {
                  varsReadByChildTask.add(in.getVar());
                }
              }
            }

            @Override
            protected void visit(Block block) {
              varsDeclaredWithinChildTask.addAll(block.getVariables());
            }};
            
    // Find variables used in child task
    TreeWalk.walkSyncChildren(logger, f, cand.getBlock(), true, walker);
    
    // Only count variables that were passed in
    varsReadByChildTask.removeAll(varsDeclaredWithinChildTask);
    
    int cost = 0;
    for (Var passed: varsReadByChildTask) {
      cost += costOfPassing(logger, passed.type());
    }
    return cost;
  }

  /**
   * Heuristic score
   * 
   * TODO: this is simplistic, since this doesn't incorporate whether the
   * variable is written in the child, or if 
   * @param logger
   * @param t
   * @return
   */
  private static int costOfPassing(Logger logger, Type t) {
    if (Types.isFile(t)) {
      // Files tend to be large
      return 20;
    } else if (Types.isBlob(t)) {
      // Blobs also tend to be fairly large
      return 5;
    } else if (Types.isPrimFuture(t) || Types.isRef(t)) {
      // Baseline cost is plain future: 1
      return 1;
    } else if (Types.isPrimValue(t)) {
      return 0;
    } else if (Types.isArray(t)) {
      return 1;
    } else if (Types.isStruct(t)) {
      StructType st = (StructType)t.getImplType();
      int totalCost = 0;
      for (StructField sf: st.getFields()) {
        totalCost += costOfPassing(logger, sf.getType());
      }
      return totalCost;
    } else {
      logger.warn("Don't know how to calculate passing cost for type: " + t);
      return 1;
    }
  }
}
