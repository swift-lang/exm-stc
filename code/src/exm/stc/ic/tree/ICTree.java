package exm.stc.ic.tree;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.log4j.Logger;

import exm.stc.common.CompilerBackend;
import exm.stc.common.TclFunRef;
import exm.stc.common.exceptions.STCRuntimeError;
import exm.stc.common.exceptions.UndefinedTypeException;
import exm.stc.common.exceptions.UserException;
import exm.stc.common.lang.Arg;
import exm.stc.common.lang.TaskMode;
import exm.stc.common.lang.Types;
import exm.stc.common.lang.Types.FunctionType;
import exm.stc.common.lang.Types.Type;
import exm.stc.common.lang.Var;
import exm.stc.common.lang.Var.DefType;
import exm.stc.common.lang.Var.VarStorage;
import exm.stc.common.util.Pair;
import exm.stc.ic.ICUtil;
import exm.stc.ic.tree.ICContinuations.Continuation;
import exm.stc.ic.tree.ICInstructions.Instruction;
import exm.stc.ic.tree.ICInstructions.Opcode;

/**
 * This has the definitions for the top-level constructs in the intermediate
 * representation, including functions and blocks 
 * 
 * The IC tree looks like:
 * 
 * Program -> Comp Function
 *         -> App Function
 *         -> Comp Function
 *
 * Function -> Block
 *
 * Block -> Variable
 *       -> Variable
 *       -> Instruction
 *       -> Instruction
 *       -> Instruction
 *       -> Continuation       -> Block
 *                             -> Block
 */
public class ICTree {

  public static final String indent = ICUtil.indent;
  
  public static class Program {
    /**
     * Use treemap to keep them in alpha order
     */
    private final TreeMap<String, Arg> globalConsts = 
                                            new TreeMap<String, Arg>();
    private final HashMap<Arg, String> globalConstsInv = 
                                            new HashMap<Arg, String>();

    private final ArrayList<Function> functions = new ArrayList<Function>();
    private final ArrayList<BuiltinFunction> builtinFuns = new ArrayList<BuiltinFunction>();
    private final Set<Pair<String, String>> required = new HashSet<Pair<String, String>>();
  
    public void generate(Logger logger, CompilerBackend gen)
        throws UserException {
      
      Map<String, List<Boolean>> blockVectors = new 
              HashMap<String, List<Boolean>> (functions.size());
      for (Function f: functions) {
        blockVectors.put(f.getName(), f.getBlockingInputVector());
      }
      GenInfo info = new GenInfo(blockVectors);
      
      logger.debug("Starting to generate program from Swift IC");
      gen.header();
      
      logger.debug("Generating required packages");
      for (Pair<String, String> req: required) {
        gen.requirePackage(req.val1, req.val2);
      }
      logger.debug("Done generating required packages");
  
      logger.debug("Generating builtins");
      for (BuiltinFunction f: builtinFuns) {
        f.generate(logger, gen, info);
      }
      logger.debug("Done generating builtin functions");
  
      logger.debug("Generating functions");
      // output functions in original order
      for (Function f: functions) {
        f.generate(logger, gen, info);
      }
      logger.debug("Done generating functions");
  
      gen.turbineStartup();
      
      // Global Constants
      logger.debug("Generating global constants");
      for (Entry<String, Arg> c: globalConsts.entrySet()) {
        String name = c.getKey();
        Arg val = c.getValue();
        gen.addGlobal(name, val);
      }
      logger.debug("Done generating global constants");
      
    }
    
    public void addRequiredPackage(String pkg, String version) {
      required.add(Pair.create(pkg, version));
    }
  
    public void addBuiltin(BuiltinFunction fn) {
      this.builtinFuns.add(fn);
    }
  
    public void addFunction(Function fn) {
      this.functions.add(fn);
    }
  
    public void addFunctions(Collection<Function> c) {
      functions.addAll(c);
    }
  
    public List<Function> getFunctions() {
      return Collections.unmodifiableList(this.functions);
    }
    
    public ListIterator<Function> functionIterator() {
      return functions.listIterator();
    }

    public void addGlobalConst(String name, Arg val) {
      if (globalConsts.put(name, val) != null) {
        throw new STCRuntimeError("Overwriting global constant "
            + name);
      }
      globalConstsInv.put(val, name);
    }
    
    /** 
     * Add global const with generated name
     * @param val
     * @return
     */
    public String addGlobalConst(Arg val) {
      
      String suffix;
      switch(val.kind) {
      case BOOLVAL:
        suffix = "b_" + Boolean.toString(val.getBoolLit());
        break;
      case INTVAL:
        suffix = "i_" + Long.toString(val.getIntLit());
        break;
      case FLOATVAL:
        String stringRep = Double.toString(val.getFloatLit());
        // Truncate float val
        suffix = "f_" +
              stringRep.substring(0, Math.min(5, stringRep.length()));
        break;
      case STRINGVAL:
        // Try to have var name something to do with string contents
        String onlyalphanum = val.getStringLit()
              .replaceAll("[ \n\r\t.,]", "_")
              .replaceAll("[^a-zA-Z0-9_]", "");
        
        suffix = "s_" + onlyalphanum.substring(0, 
            Math.min(10, onlyalphanum.length()));   
        break;
      case VAR:
        throw new STCRuntimeError("Variable can't be a constant");
      default:
        throw new STCRuntimeError("Unknown enum value " + 
                val.kind.toString()); 
      }
      
      String origname = Var.GLOBAL_CONST_VAR_PREFIX + suffix;
      String name = origname;
      int seq = 0;
      while (globalConsts.containsKey(name)) {
        seq++;
        name = origname + "-" + seq;
      }
      addGlobalConst(name, val);
      return name;
    }
    
    public String invLookupGlobalConst(Arg val) {
      return this.globalConstsInv.get(val);
 
    }
  
    public Arg lookupGlobalConst(String name) {
      return this.globalConsts.get(name);
 
    }

    public SortedMap<String, Arg> getGlobalConsts() {
      return Collections.unmodifiableSortedMap(globalConsts);
    }
    
    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      prettyPrint(sb);
      return sb.toString();
    }
  
    public void prettyPrint(StringBuilder out) {
      for (Pair<String, String> req: required) {
        out.append("require " + req.val1 + "::" + req.val2 + "\n");
      }
      
      for (Entry<String, Arg> constE: globalConsts.entrySet()) {
        Arg val = constE.getValue();
        out.append("const " +   constE.getKey() + " = ");
        out.append(val.toString());
        out.append(" as " + val.getType().typeName());
        out.append("\n");
      }
      
      out.append("\n");
      
      for (BuiltinFunction f: builtinFuns) {
        f.prettyPrint(out);
        out.append("\n");
      }
  
      for (Function f: functions) {
        f.prettyPrint(out);
        out.append("\n");
      }
    }
  
  
    public void log(PrintStream icOutput, String codeTitle) {
      StringBuilder ic = new StringBuilder();
      try {
        icOutput.append("\n\n" + codeTitle + ": \n" + 
            "============================================\n");
        prettyPrint(ic) ;
        icOutput.append(ic.toString());
        icOutput.flush();
      } catch (Exception e) {
        icOutput.append("ERROR while generating code. Got: "
            + ic.toString());
        icOutput.flush();
        e.printStackTrace();
        throw new STCRuntimeError("Error while generating IC: " +
        e.toString());
      }
    }
  }

  public static class BuiltinFunction {
    private final String name;
    private final TclFunRef impl;
    private final FunctionType fType;
    

    public BuiltinFunction(String name, FunctionType fType,
                           TclFunRef impl) {
      this.name = name;
      this.impl = impl;
      this.fType = fType;
    }

    public void prettyPrint(StringBuilder out) {
      out.append("tcl ");
      out.append("(");
      //ICUtil.prettyPrintFormalArgs(out, this.oList);
      boolean first = true;
      for(Type t: fType.getOutputs()) {
        if (first) {
          first = false;
        } else {
          out.append(", ");
        }
        out.append(t.typeName());
      }
      out.append(") @" + this.name + "(");
      first = true;
      for(Type t: fType.getInputs()) {
        if (first) {
          first = false;
        } else {
          out.append(", ");
        }
        out.append(t.typeName());
      }
      out.append(") { ");
      out.append(impl.pkg + "::" + impl.symbol + " ");
      out.append("pkgversion: " + impl.version + " ");
      
      
      out.append(" }\n");
      
    }

    public void generate(Logger logger, CompilerBackend gen, GenInfo info)
    throws UserException {
      logger.debug("generating: " + name);
      gen.defineBuiltinFunction(name, fType, impl);
    }
  }

  public static class Function {
    private final Block mainBlock;
    private final String name;
    public String getName() {
      return name;
    }


    public List<Boolean> getBlockingInputVector() {
      ArrayList<Boolean> res = new ArrayList<Boolean>(iList.size());
      Set<String> blocks = Var.nameSet(this.blockingInputs);
      for (Var input: this.iList) {
        boolean isBlocking = blocks.contains(input.name());
        res.add(isBlocking);
      }
      return res;
    }


    private final List<Var> iList;
    private final List<Var> oList;
    
    /** Wait until the below inputs are available before running function */
    private final List<Var> blockingInputs;

    private TaskMode mode;

    public Function(String name, List<Var> iList,
        List<Var> oList, TaskMode mode) {
      this(name, iList, oList, mode, new Block(BlockType.MAIN_BLOCK));
    }

    public Function(String name, List<Var> iList,
        List<Var> oList, TaskMode mode, Block mainBlock) {
      if (mainBlock.getType() != BlockType.MAIN_BLOCK) {
        throw new STCRuntimeError("Expected main block " +
        "for function to be tagged as such");
      }
      this.name = name;
      this.iList = iList;
      this.oList = oList;
      this.mode = mode;
      this.mainBlock = mainBlock;
      this.blockingInputs = new ArrayList<Var>();
    }


    public List<Var> getInputList() {
      return Collections.unmodifiableList(this.iList);
    }

    public List<Var> getOutputList() {
      return Collections.unmodifiableList(this.oList);
    }

    public Block getMainblock() {
      return mainBlock;
    }

    public void generate(Logger logger, CompilerBackend gen, GenInfo info)
        throws UserException {
      logger.debug("Generating function " + name);
      gen.startFunction(name, oList, iList, mode);
      this.mainBlock.generate(logger, gen, info);
      gen.endFunction();
      logger.debug("Done generating function " + name);
    }

    public void prettyPrint(StringBuilder sb) {
      ICUtil.prettyPrintFormalArgs(sb, this.oList);
      sb.append(" @" + name + " ");
      ICUtil.prettyPrintFormalArgs(sb, this.iList);
      sb.append("#waiton[");
      ICUtil.prettyPrintVarList(sb, this.blockingInputs);
      sb.append("] {\n");
      mainBlock.prettyPrint(sb, indent);
      sb.append("}\n");
    }
    
    public List<Var> getBlockingInputs() {
      return blockingInputs;
    }
    
    public void addBlockingInput(String vName) {
      Var v = Var.findByName(iList, vName);
      if (v == null) {
        throw new STCRuntimeError(vName + " is not the name of " +
        " an input argument to function " + name + ":\n" + this);
      }
      addBlockingInput(v);
    }
    
    public void addBlockingInput(Var v) {
      assert(v.defType() == DefType.INARG);
      boolean oneOfArgs = false;
      for (Var ia: iList) {
        if (ia.name().equals(v.name())
            && ia.type().equals(v.type())) {
          oneOfArgs = true;
          break;
        }
      }
      if (oneOfArgs) {
        for (Var i: blockingInputs) {
          if (i.name().equals(v.name())) {
            // already there
            return;
          }
        }
        blockingInputs.add(v);
      } else {
        StringBuilder fn = new StringBuilder();
        prettyPrint(fn);
        throw new STCRuntimeError("Tried to add blocking input" + v +
        " which wasn't one of the input arguments of function: "
            + this.iList + "\n" + fn.toString());
      }
    }

    public boolean isAsync() {
      return this.mode != TaskMode.SYNC;
    }
  }


  // Keep track of block type, mainly for purpose of asserts
  public enum BlockType {
    MAIN_BLOCK,
    NESTED_BLOCK,
    CASE_BLOCK,
    ELSE_BLOCK,
    THEN_BLOCK,
    FOREACH_BODY,
    WAIT_BLOCK,
    LOOP_BODY,
    RANGELOOP_BODY
  }

  public static class CleanupAction {
    private CleanupAction(Var var, Instruction action) {
      super();
      this.var = var;
      this.action = action;
    }
    private final Var var;
    private final Instruction action;
    
    public Var var() {
      return var;
    }
    public Instruction action() {
      return action;
    }
  }
  
  public static class Block {

    private final BlockType type;
    public Block(BlockType type) {
      this(type, new LinkedList<Instruction>(), new ArrayList<Var>(),
          new ArrayList<Continuation>(), new ArrayList<CleanupAction>());
    }
    
    /**
     * Used to create duplicate.  This will take ownership of all
     * data structures passed in
     * @param type
     * @param instructions
     */
    private Block(BlockType type, LinkedList<Instruction> instructions, 
        ArrayList<Var> variables, ArrayList<Continuation> conds,
        ArrayList<CleanupAction> cleanupActions) {
      this.type = type;
      this.instructions = instructions;
      this.variables = variables;
      this.continuations = conds;
      this.cleanupActions = cleanupActions;
    }

    /**
     * Make a copy without any shared mutable state
     */
    @Override
    public Block clone() {
      return this.clone(this.type);
    }
    
    public Block clone(BlockType newType) {
      return new Block(newType, ICUtil.cloneInstructions(this.instructions),
          new ArrayList<Var>(this.variables),
          ICUtil.cloneContinuations(this.continuations), 
          new ArrayList<CleanupAction>(this.cleanupActions));
    }

    public BlockType getType() {
      return type;
    }

    private final LinkedList<Instruction> instructions;
    
    private final ArrayList<CleanupAction> cleanupActions;

    private final ArrayList<Var> variables;

    /** conditional statements for block */
    private final ArrayList<Continuation> continuations;

    public void addInstruction(Instruction e) {
      instructions.add(e);
    }

    public void addInstructionFront(Instruction e) {
      instructions.addFirst(e);
    }
    
    public void addInstructions(List<Instruction> instructions) {
      this.instructions.addAll(instructions);
    }

    public void addContinuation(Continuation c) {
      this.continuations.add(c);
    }

    public List<Continuation> getContinuations() {
      return Collections.unmodifiableList(continuations);
    }
    
    public ListIterator<Continuation> getContinuationIterator() {
      return continuations.listIterator();
    }
    
    public Continuation getContinuation(int i) {
      return continuations.get(i);
    }

    public List<Var> getVariables() {
      return Collections.unmodifiableList(variables);
    }

    public Var declareVariable(Type t, String name,
        VarStorage storage, DefType defType, Var mapping) {
      assert(mapping == null || Types.isString(mapping.type()));
      Var v = new Var(t, name, storage, defType, mapping);
      this.variables.add(v);
      return v;
    }
    
    public Var declareVariable(Var v) {
      this.variables.add(v);
      return v;
    }

    public boolean isEmpty() {
      return instructions.isEmpty() && continuations.isEmpty();
    }

    public void generate(Logger logger, CompilerBackend gen, GenInfo info)
        throws UndefinedTypeException {

      logger.trace("Generate code for block of type " + this.type.toString());
      // Can push forward variable declaration to top of block
      for (Var v: variables) {
        logger.trace("generating variable decl for " + v.toString());
        gen.declare(v.type(), v.name(), v.storage(), v.defType(),
            v.mapping());
      }
      for (Instruction i: instructions) {
        i.generate(logger, gen, info);
      }

      // Can put conditional statements at end of block, making sure
      // Ones which are marked as runLast occur after those not
      for (boolean runLast: new boolean[] {false, true}) {
        for (Continuation c: continuations) {
          if (c.runLast() == runLast) {
            logger.trace("generating code for continuation");
            c.generate(logger, gen, info);
          }
        }
      }

      for (CleanupAction cleanup: cleanupActions) {
        cleanup.action().generate(logger, gen, info);
      }
      logger.trace("Done with code for block of type " + this.type.toString());

    }

    public void prettyPrint(StringBuilder sb, String indent) {
      for (Var v: variables) {
        sb.append(indent);
        sb.append("alloc " + v.type().typeName() + " " + v.name() + 
                " <" + v.storage().toString().toLowerCase() + ">");
        
        if (v.isMapped()) {
          sb.append(" @mapping=" + v.mapping().name());
        }
        sb.append("\n");
      }

      for (Instruction i: instructions) {
        sb.append(indent);
        sb.append(i.toString());
        sb.append("\n");
      }

      for (boolean runLast: new boolean[] {false, true}) {
        for (Continuation c: continuations) {
          if (c.runLast() == runLast) {
            c.prettyPrint(sb, indent);
          }
        }
      }

      for (CleanupAction a: cleanupActions) {
        sb.append(indent);
        sb.append(a.action().toString());
        sb.append(" # cleanup " + a.var.name());
        sb.append("\n");
      }
    }

    public ListIterator<Continuation> continuationIterator() {
      return continuations.listIterator();
    }

    public ListIterator<Var> variableIterator() {
      return variables.listIterator();
    }
    public List<Instruction> getInstructions() {
      return Collections.unmodifiableList(instructions);
    }

    public ListIterator<Instruction> instructionIterator() {
      return instructions.listIterator();
    }
    
    
    public ListIterator<CleanupAction> cleanupIterator() {
      return cleanupActions.listIterator();
    }

    public void addCleanup(Var var, Instruction action) {
      this.cleanupActions.add(new CleanupAction(var, action));
    }
    
    /**
     * Rename variables in block (and nested blocks) according to map.
     * If the map doesn't have an entry, we don't rename anything
     *
     * @param inputsOnly  if true, only change references which are reading
     *      the var.  if false, completely remove the old variable and replace 
     *      with new
     * @param renames OldName -> NewName
     */
    public void renameVars(Map<String, Arg> renames, boolean inputsOnly) {
      if (!inputsOnly) {
        renameVarsInBlockVarsList(renames);
      }
      
      for (Instruction i: instructions) {
        if (inputsOnly) {
          i.renameInputs(renames);
        } else {
          i.renameVars(renames);
        }
      }

      // Rename in nested blocks
      for (Continuation c: continuations) {
        c.replaceVars(renames, inputsOnly, true);
      }
      renameCleanupActions(renames, inputsOnly);
    }

    private void renameVarsInBlockVarsList(Map<String, Arg> renames) {
      // Replace definition of var
      ListIterator<Var> it = variables.listIterator();
      List<Var> changedMappingVars = new ArrayList<Var>();
      while (it.hasNext()) {
        Var v = it.next();

        if (v.isMapped()) {
          if (renames.containsKey(v.name())) {
            throw new STCRuntimeError("Tried to replace mapped variable in " +
            "IC, this isn't supported so this probably indicates a " +
            "compiler bug");
          }
          
          // Check to see if string variable for mapping is replaced
          if (renames.containsKey(v.mapping().name())) {
            Arg replacement = renames.get(v.mapping().name());
            if (replacement.isVar()) {
              // Need to maintain variable ordering so that mapped vars appear
              // after the variables containing the mapping string. Remove
              // var declaration here and put it at end of list
              it.remove();
              changedMappingVars.add(new Var(v.type(), v.name(),
                  v.storage(), v.defType(), replacement.getVar()));
            }
          }
        } else {
          // V isn't mapped
          String varName = v.name();
          if (renames.containsKey(varName)) {
            Arg replacement = renames.get(varName);
            if (replacement.isVar()) {
              it.set(replacement.getVar());
            } else {
              // value replaced with constant
              it.remove();
            }
          }
        }
      }

      this.variables.addAll(changedMappingVars);
    }


    public void renameCleanupActions(Map<String, Arg> renames,
                                                boolean inputsOnly) {
      ListIterator<CleanupAction> it = cleanupActions.listIterator();
      while (it.hasNext()) {
        CleanupAction a = it.next();
        if (inputsOnly) {
          a.action.renameInputs(renames);
        } else {
          a.action.renameVars(renames);
        }
        if (!inputsOnly && renames.containsKey(a.var.name())) {
          Arg replacement = renames.get(a.var.name());
          if (replacement.isVar()) {
            CleanupAction newCleanup = new CleanupAction(replacement.getVar(),
                  a.action);
            it.set(newCleanup);
          } else {
            // Was replaced with constant
            it.remove();
          }
        }
      }
    }

    /**
     * Remove the variable from this block and all internal constructs,
     *    removing all instructions with this as output
     * preconditions: variable is not used as input for any instruction,
     *            variable is not used as output for any instruction with a sideeffect,
     *            variable is not required for any constructs
     */
    public void removeVars(Set<String> removeVars) {
      if (removeVars.isEmpty()) {
        return;
      }
      
      removeVarDeclarations(removeVars);

      ListIterator<Instruction> it = instructionIterator();
      while (it.hasNext()) {
        Instruction inst = it.next();
        inst.removeVars(removeVars);
        // See if we can remove instruction
        if (!inst.hasSideEffects() && inst.op != Opcode.COMMENT) {
          boolean allRemoveable = true;
          for (Var out: inst.getOutputs()) {
            // Doesn't make sense to assign to anything other than
            //  variable
            if (! removeVars.contains(out.name())) {
              allRemoveable = false; break;
            }
          }
          if (allRemoveable) {
            it.remove();
          }
        }
      }
      for (Continuation c: continuations) {
        c.removeVars(removeVars);
      }
    }


    public void addVariables(List<Var> variables) {
      this.variables.addAll(variables);
    }

    public void addVariable(Var variable) {
      this.variables.add(variable);
    }

    public void addContinuations(List<? extends Continuation>
                                                continuations) {
      this.continuations.addAll(continuations);
    }

    public Set<String> unneededVars() {
      HashSet<String> toRemove = new HashSet<String>();
      Pair<Set<String>, List<List<Var>>> res = findEssentialVars();
      Set<String> stillNeeded = res.val1; 
      
      // Check to see if we have to retain additional
      // variables based on interdependencies
      for (List<Var> dependentSet: res.val2) { {
        boolean needed = false;
        for (Var v: dependentSet) {
          if (stillNeeded.contains(v.name())) {
            needed = true;
            break;
          }
        }
        if (needed) {
          for (Var v: dependentSet) {
            stillNeeded.add(v.name());
          }
        }
      }
        
      }
      for (Var v: getVariables()) {
        if (!stillNeeded.contains(v.name())) {
          toRemove.add(v.name());
        }
      }
      return toRemove;
    }

    /**
     * 
     * @return First element: list of essential vars
     *         Second element: list of vars which are interdependent: if one var in
     *                         list is needed, all are needed
     */
    public Pair<Set<String>, List<List<Var>>> findEssentialVars() {
      Set<String> stillNeeded = new HashSet<String>();
      List<List<Var>> interdependencies = new ArrayList<List<Var>>();
      findEssentialVars(stillNeeded, interdependencies);
      return Pair.create(stillNeeded,  interdependencies);
    }

    private void findEssentialVars(Set<String> stillNeeded, List<List<Var>> interdependencies) {
      // Need to hold on to mapped variables
      for (Var v: variables) {
        if (v.isMapped()) {
          stillNeeded.add(v.name());
          stillNeeded.add(v.mapping().name());
        }
      }
      for (Instruction i : instructions) {
        // check which variables are still needed
        for (Arg oa: i.getInputs()) {
          if (oa.isVar()) {
            stillNeeded.add(oa.getVar().name());
          }
        }
        // Can't eliminate instructions with side-effects
        if (i.hasSideEffects()) {
          for (Var out: i.getOutputs()) {
            stillNeeded.add(out.name());
          }
        } else {
          // Can only eliminate one var if can eliminate all
          interdependencies.add(i.getOutputs());
        }
      }

      for (Continuation c: continuations) {
        for (Var v: c.requiredVars()) {
          stillNeeded.add(v.name());
        }
        for (Block b: c.getBlocks()) {
          b.findEssentialVars(stillNeeded, interdependencies);
        }
      }
      
      for (CleanupAction a: cleanupActions) {
        // See if the variable might an an alias for out of scope
        if (a.action.writesAliasVar()) {
          stillNeeded.add(a.var.name());
        }
        for (Arg out: a.action.getInputs()) {
          if (out.isVar()) {
            stillNeeded.add(out.getVar().name());
          }
        }
      }
    }

    public void removeContinuation(Continuation c) {
      this.continuations.remove(c);
    }
    
    public void removeContinuations(
                    Collection<? extends Continuation> c) {
      this.continuations.removeAll(c);
    }

    /**
     * Insert the instructions, variables, etc from b inline
     * in the current block
     * @param b 
     * @param insertAtTop whether to insert at top of block or not
     */
    public void insertInline(Block b, boolean insertAtTop) {
      insertInline(b, insertAtTop ? b.instructionIterator() : null);
    }
    

    public void insertInline(Block b,
          ListIterator<Instruction> pos) {
      insertInline(b, null, pos);
    }
  
    /**
     * Insert the instructions, variables, etc from b inline
     * in the current block
     * @param b
     * @param pos
     */
    public void insertInline(Block b,
          ListIterator<Continuation> contPos,
          ListIterator<Instruction> pos) {
      Set<String> varNames = Var.nameSet(this.variables);
      for (Var newVar: b.getVariables()) {
        // Check for duplicates (may be duplicate globals)
        if (!varNames.contains(newVar.name())) {
          variables.add(newVar);
        }
      }
      if (pos != null) {
        for (Instruction i: b.getInstructions()) {
          pos.add(i);
        }
      } else {
        this.instructions.addAll(b.getInstructions());
      }
      if (contPos != null) {
        for (Continuation c: b.getContinuations()) {
          contPos.add(c);
        }
      } else {
        this.continuations.addAll(b.getContinuations());
      }
      this.cleanupActions.addAll(b.cleanupActions);
    }
    
    public void insertInline(Block b) {
      insertInline(b, false);
    }

    public void removeVarDeclarations(Set<String> varNames) {
      ICUtil.removeVarsInList(variables, varNames);
      ListIterator<CleanupAction> it = cleanupActions.listIterator();
      while (it.hasNext()) {
        CleanupAction a = it.next();
        if (varNames.contains(a.var.name())) {
          it.remove();
        }
      }
    }
    
    public void replaceVarDeclaration(Var oldV, Var newV) {
      ListIterator<Var> it = variables.listIterator();
      while (it.hasNext()) {
        Var curr = it.next();
        if (curr.name().equals(oldV.name())) {
          it.set(newV);
          return;
        }
      }
      throw new STCRuntimeError("Variable: " + oldV.toString() + " not found" +
      " in block var declarations");
    }

    /**
     * Remove instructions by object identity
     * @param insts
     */
    public void removeInstructions(Set<Instruction> insts) {
      ListIterator<Instruction> it = this.instructionIterator();
      while (it.hasNext()) {
        Instruction i = it.next();
        if (insts.contains(i)) {
          it.remove();
        }
      }
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      prettyPrint(sb, "");
      return sb.toString();
    }
  }
  
  /** State to pass around when doing code generation from SwiftIC */
  public static class GenInfo {
    /** Function name -> vector of which inputs are blocking */
    private final Map<String, List<Boolean>> compBlockingInputs;

    public GenInfo(Map<String, List<Boolean>> compBlockingInputs) {
      super();
      this.compBlockingInputs = compBlockingInputs;
    }

    public List<Boolean> getBlockingInputVector(String fnName) {
      return compBlockingInputs.get(fnName);
    }
  }
}
