package exm.stc.ic.refcount;

import java.util.Map.Entry;

import exm.stc.common.Settings;
import exm.stc.common.exceptions.InvalidOptionException;
import exm.stc.common.exceptions.STCRuntimeError;
import exm.stc.common.lang.RefCounting.RefCountType;
import exm.stc.common.lang.Var;
import exm.stc.ic.opt.AliasTracker.AliasKey;
import exm.stc.ic.tree.ICContinuations.Continuation;
import exm.stc.ic.tree.ICContinuations.ContinuationType;
import exm.stc.ic.tree.ICTree.Block;

public class RCUtil {

  static boolean isAsyncForeachLoop(Continuation cont) {
    return cont.isAsync() && isForeachLoop(cont);
  }

  static boolean isForeachLoop(Continuation cont) {
    return (cont.getType() == ContinuationType.FOREACH_LOOP ||
            cont.getType() == ContinuationType.RANGE_LOOP);
  }

  /**
   * 
   * @param cont
   * @param block
   * @param var
   *          a var that is in scope within block
   * @return true if var is accessible outside continuation
   */
  static boolean definedOutsideCont(Continuation cont, Block block, Var var) {
    assert (block.getParentCont() == cont);
    return !block.getVariables().contains(var)
        && !cont.constructDefinedVars().contains(var);
  }
  
  static boolean cancelEnabled() {
    try {
      return Settings.getBoolean(Settings.OPT_CANCEL_REFCOUNTS);
    } catch (InvalidOptionException e) {
      throw new STCRuntimeError(e.getMessage());
    }
  }

  static boolean piggybackEnabled() {
    try {
      return Settings.getBoolean(Settings.OPT_PIGGYBACK_REFCOUNTS);
    } catch (InvalidOptionException e) {
      throw new STCRuntimeError(e.getMessage());
    }
  }
  

  /**
   * Check reference counts are all set to zero
   * @param block
   * @param increments
   * @param rcType
   * @param noIncrements if true, check that they are <= 0
   * @param noDecrements if true, check that they are >= 0
   */
  static void checkRCZero(Block block, RCTracker increments,
                         RefCountType rcType,
                         boolean noIncrements, boolean noDecrements) {
    // Check that all refcounts are zero
    for (Entry<AliasKey, Long> e : increments.rcIter(rcType)) {
      if ((noIncrements && e.getValue() > 0) ||
          (noDecrements && e.getValue() < 0)) {
        throw new STCRuntimeError("Refcount " + rcType  + " not 0 after pass "
                   + e.toString() + " in block " + block);
      }
    }
  }
}
