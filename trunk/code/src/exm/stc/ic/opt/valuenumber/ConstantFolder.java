package exm.stc.ic.opt.valuenumber;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import exm.stc.common.lang.Arg;
import exm.stc.common.lang.OpEvaluator;
import exm.stc.common.lang.Operators.BuiltinOpcode;
import exm.stc.common.lang.Var;
import exm.stc.common.util.TernaryLogic.Ternary;
import exm.stc.ic.opt.valuenumber.ComputedValue.ArgOrCV;
import exm.stc.ic.tree.ICInstructions.CommonFunctionCall;
import exm.stc.ic.tree.Opcode;

/**
 * Implement constant folding using information from congruent sets.
 */
public class ConstantFolder {

  /**
   * Do constant folding
   * @param val
   * @return the folded value if successful.  Note that this may be
   *         a constant, a variable, or a computed value representing
   *         one of these stored in a future.  Returns null if not
   *         successful. 
   */
  public static ArgOrCV constantFold(Logger logger, CongruentSets sets,
                                   ComputedValue<Arg> val) {
    switch (val.op) {
      case ASYNC_OP:
      case LOCAL_OP:
        return foldBuiltinOp(logger, sets, val);
      case IS_MAPPED:
        return foldIsMapped(val);
        // TODO: merge over other constantFold() implementations once we can
        //       replace constant folding pass with this analysis
      case CALL_CONTROL:
      case CALL_FOREIGN:
      case CALL_FOREIGN_LOCAL:
      case CALL_LOCAL:
      case CALL_LOCAL_CONTROL:
      case CALL_SYNC:
        return foldFunctionCall(logger, sets, val);
      default:
        // Can't fold others
        return null;
    }
  }


  private static ArgOrCV foldBuiltinOp(Logger logger, CongruentSets sets,
                                     ComputedValue<Arg> val) {
    List<Arg> inputs;
    if (val.op == Opcode.LOCAL_OP) {
      inputs = val.inputs;
    } else {
      assert(val.op == Opcode.ASYNC_OP);
      inputs = findFutureValues(sets, val);
    }

    if (logger.isTraceEnabled()) {
      logger.trace("Try constant fold: " + val + " " + inputs);
    }
    if (inputs != null) {
      // constant fold
      Arg res = OpEvaluator.eval((BuiltinOpcode)val.subop, inputs);
      if (res != null) {
        if (logger.isDebugEnabled()) {
          logger.debug("Constant fold: " + val + " => " + res);
        }
        boolean futureResult = val.op != Opcode.LOCAL_OP;
        return valFromArg(futureResult, res);
      }
    }
    return null;
  }
  

  private static ArgOrCV foldIsMapped(ComputedValue<Arg> val) {
    Arg fileCV = val.getInput(0);
    assert(fileCV.isVar());
    Var file = fileCV.getVar();
    if (file.isMapped() != Ternary.MAYBE) {
      Arg isMapped = Arg.createBoolLit(file.isMapped() == Ternary.TRUE);
      return new ArgOrCV(isMapped);
    }
    return null;
  }


  private static ArgOrCV foldFunctionCall(Logger logger, CongruentSets sets,
      ComputedValue<Arg> val) {
    List<Arg> inputs;
    if (!CommonFunctionCall.canConstantFold(val)) {
      return null;
    }
    boolean usesValues = CommonFunctionCall.acceptsLocalValArgs(val.op);
    if (usesValues) {
      inputs = val.inputs;
    } else {
      inputs = findFutureValues(sets, val);
    }
    if (inputs != null) {
      Arg result = CommonFunctionCall.tryConstantFold(val, inputs);
      if (result != null) {
        return valFromArg(!usesValues, result);
      }
    }
    return null;
  }


  /**
   * Convert arg representing result of computation (maybe constant)
   * into a computed value
   * @param futureResult
   * @param constant
   * @return
   */
  private static ArgOrCV valFromArg(boolean futureResult, Arg constant) {
    if (!futureResult) {
      // Can use directly
      return new ArgOrCV(constant);
    } else {
      // Record stored future
      return new ArgOrCV(Opcode.assignOpcode(constant.futureType()),
                                             constant.asList());
    }
  }

  /**
   * Try to find constant values of futures  
   * @param val
   * @param congruent
   * @return a list with constants in places with constant values,
   *      or future values in places with future args.  Returns null
   *      if we couldn't resolve to args.
   */
  private static List<Arg> findFutureValues(CongruentSets sets,
                                            ComputedValue<Arg> val) {
    List<Arg> inputs = new ArrayList<Arg>(val.inputs.size());
    for (Arg arg: val.inputs) {
      if (arg.isConstant()) {
        // For some calling conventions, constants are used
        inputs.add(arg);
      } else {
        Arg storedConst = findValueOf(sets, arg);
        if (storedConst != null && storedConst.isConstant()) {
          inputs.add(storedConst);
        } else {
          inputs.add(arg);
        }
      }
    }
    return inputs;
  }

  /**
   * Find if a future has a constant value stored in it
   * @param congruent
   * @param arg
   * @return a value stored to the var, or null
   */
  private static Arg findValueOf(CongruentSets sets, Arg arg) {
    assert(arg.isVar()) : arg;
    // Try to find constant load
    Opcode retrieveOp = Opcode.retrieveOpcode(arg.getVar());
    assert(retrieveOp != null);
    ArgOrCV retrieveVal = new ArgOrCV(retrieveOp, arg.asList());
    return sets.findCanonical(retrieveVal);
  }



}
