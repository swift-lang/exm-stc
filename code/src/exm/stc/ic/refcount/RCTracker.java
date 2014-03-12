package exm.stc.ic.refcount;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import org.apache.log4j.Logger;

import exm.stc.common.Logging;
import exm.stc.common.lang.RefCounting;
import exm.stc.common.lang.RefCounting.RefCountType;
import exm.stc.common.lang.Types.Type;
import exm.stc.common.lang.Var;
import exm.stc.common.util.Counters;
import exm.stc.common.util.Pair;
import exm.stc.ic.opt.AliasTracker;
import exm.stc.ic.opt.AliasTracker.Alias;
import exm.stc.ic.opt.AliasTracker.AliasKey;
import exm.stc.ic.tree.ICInstructions.Instruction;
import exm.stc.ic.tree.ICTree.Block;
import exm.stc.ic.tree.TurbineOp.RefCountOp.RCDir;

/**
 * Class to keep track of information relevant to refcount pass
 */
public class RCTracker {
  
  /**
   * Current read increments per var
   */
  private final Counters<AliasKey> readIncrements;
  
  /**
   * Current read decrements per var (negative numbers)
   */
  private final Counters<AliasKey> readDecrements;
  
  /**
   * Current write increments per var
   */
  private final Counters<AliasKey> writeIncrements;
  
  /**
   * Current write decrements per var (negative numbers)
   */
  private final Counters<AliasKey> writeDecrements;
  
  private final AliasTracker aliases;
  
  public RCTracker() {
    this(null);
  }
  
  public RCTracker(AliasTracker parentAliases) {
    this.readIncrements =  new Counters<AliasKey>();
    this.readDecrements =  new Counters<AliasKey>();
    this.writeIncrements =  new Counters<AliasKey>();
    this.writeDecrements =  new Counters<AliasKey>();
    if (parentAliases != null) {
      this.aliases = parentAliases.makeChild();
    } else {
      this.aliases = new AliasTracker();
    }
  }

  public AliasTracker getAliases() {
    return aliases;
  }
  
  public void updateForInstruction(Instruction inst) {
    for (Alias alias: aliases.update(inst)) {
      updateForAlias(alias);
    }
  }

  /**
   * Perform any updates required for alias info
   * 
   * @param parent
   * @param field
   * @param child
   */
  private void updateForAlias(Alias alias) {
    // This may bind a struct path to a concrete variable, which may mean
    // that, e.g. if the variable is a constant, that it no longer has
    // a refcount.
    for (RefCountType rcType: RefcountPass.RC_TYPES) {
      if (!RefCounting.trackRefCount(alias.child, rcType)) {
        reset(rcType, alias.child);
      }
    }
  }

  /**
   * Update counts with a set of changes
   * @param changes
   * @param rcType
   */
  public void merge(Counters<Var> changes, RefCountType rcType,
                    RCDir dir) {
    Counters<AliasKey> changes2 = new Counters<AliasKey>();
    for (Entry<Var, Long> e: changes.entries()) {
      changes2.add(getCountKey(e.getKey()), e.getValue());
    } 
    getCounters(rcType, dir).merge(changes2);
  }

  /**
   * In case where a variable is part of multiple structs, canonicalize the
   * paths so we can merge refcount operations
   */
  public void canonicalize() {
    for (RefCountType rcType: RefcountPass.RC_TYPES) {
      for (RCDir dir: RCDir.values()) {
        Iterator<Entry<AliasKey, Long>> it =
                          rcIter(rcType, dir).iterator();
        // Add these back in at end
        List<Pair<AliasKey, Long>> toAdd = 
                                  new ArrayList<Pair<AliasKey,Long>>();
        while (it.hasNext()) {
          Entry<AliasKey, Long> e = it.next();
          
          // Canonicalize key by finding var, and then finding first path
          // that a var is a part of
          AliasKey currKey = e.getKey();
          Var v = getRefCountVar(null, currKey);
          if (v != null) {
            AliasKey canonKey = getCountKey(v);
            if (!canonKey.equals(currKey)) {
              // Replace with canonical
              it.remove();
              toAdd.add(Pair.create(canonKey, e.getValue()));
            }
          }
        }
        
        
        for (Pair<AliasKey, Long> e: toAdd) {
          incrKey(e.val1, rcType, e.val2, e.val1.type());
        }
      }
    }
  }
  
  private Counters<AliasKey> getCounters(RefCountType rcType, RCDir dir) {
    if (rcType == RefCountType.READERS) {
      if (dir == RCDir.INCR) {
        return readIncrements;
      } else {
        return readDecrements;
      }
    } else {
      assert(rcType == RefCountType.WRITERS);
      if (dir == RCDir.INCR) {
        return writeIncrements;
      } else {
        return writeDecrements;
      }
    }
  }
  
  /**
   * Return list of variables that need to be incremented/decremented.
   * Modifying this doesn't affect underlying map
   * @param rcType
   * @return
   */
  public Counters<Var> getVarCandidates(Block block, RefCountType rcType,
                                        RCDir dir) {
    Counters<Var> result = new Counters<Var>();
    for (Entry<AliasKey, Long> e: rcIter(rcType, dir)) {
      result.add(getRefCountVar(block, e.getKey()), e.getValue());
    }
    return result;
  }

  /**
   * Get iterator over reference counts.  Modifying this will affect
   * underlying map.
   * @param rcType
   * @param dir
   * @return
   */
  public Iterable<Entry<AliasKey, Long>> rcIter(RefCountType rcType,
                                                RCDir dir) {
    return getCounters(rcType, dir).entries();
  }

  /**
   * Get the variable that we need to increment/decrement refcount of
   * @param block
   * @param key
   * @return
   */
  public Var getRefCountVar(Block block, AliasKey key) {
    // If inside struct, check to see if there is a reference
    for (int i = key.pathLength() - 1; i >= 0; i--) {
      if (key.structPath[i].equals(AliasTracker.DEREF_MARKER)) {
        // Refcount var key includes prefix including deref marker:
        // I.e. it should be the ID of the datum being refcounting
        String pathPrefix[] = new String[i + 1];
        for (int j = 0; j <= i; j++) {
          pathPrefix[j] = key.structPath[j];
        }
        AliasKey prefixKey = new AliasKey(key.var, pathPrefix);
        Var refcountVar = aliases.findVar(prefixKey);
        assert(refcountVar != null) : "Expected var for alias key " + key +
                                       " to exist";
        return refcountVar;
      }
    }
    
    // If no references, struct root
    return key.var;
  }


  public void reset(RefCountType rcType, Var v, RCDir dir) {
    getCounters(rcType, dir).reset(getCountKey(v));
  }
  
  private void reset(RefCountType rcType, Var v) {
    for (RCDir dir: RCDir.values()) {
      getCounters(rcType, dir).reset(getCountKey(v));
    }
  }

  public void resetAll(RefCountType rcType, Collection<? extends Var> vars,
                       RCDir dir) {
    for (Var var: vars) {
      reset(rcType, var, dir);
    }
  }
  
  public void resetAll() {
    for (RCDir dir: RCDir.values()) {
      resetAll(dir);
    }
  }
  
  public void resetAll(RCDir dir) {
    for (RefCountType rcType: RefcountPass.RC_TYPES) {
      getCounters(rcType, dir).resetAll();
    }
  }

  public long getCount(RefCountType rcType, AliasKey k, RCDir dir) {
    return getCounters(rcType, dir).getCount(k);
  }
  
  public long getCount(RefCountType rcType, Var v, RCDir dir) {
    return getCount(rcType, getCountKey(v), dir);
  }

  void readIncr(Var var) {
    readIncr(var, 1);
  }
  
  void readDecr(Var var) {
    readIncr(var, -1);
  }
  
  void readIncr(Var var, long amount) {
    incr(var, RefCountType.READERS, amount);
  }
  
  void readDecr(Var var, long amount) {
    readIncr(var, -1 * amount);
  }
  
  void writeIncr(Var var) {
    writeIncr(var, 1);
  }
  
  void writeDecr(Var var) {
    writeIncr(var, -1);
  }
  
  void writeDecr(Var var, long amount) {
    writeIncr(var, -1 * amount);
  }
  
  void writeIncr(Var var, long amount) {
    incr(var, RefCountType.WRITERS, amount);
  }

  public void cancel(Var var, RefCountType rcType, long amount) {
    cancel(getCountKey(var), rcType, amount);
  }
  
  public void cancel(AliasKey key, RefCountType rcType,
                    long amount) {
    RCDir dir = RCDir.fromAmount(-amount);
    Counters<AliasKey> counters = getCounters(rcType, dir);
    
    long newCount = counters.add(key, amount);
    long oldCount = newCount - amount;
    Logger logger = Logging.getSTCLogger();
    if (logger.isTraceEnabled()) {
      logger.trace("Cancelled " + key + " " + rcType + " "
                   + dir + " old: " + oldCount + " new: " + newCount);
    }
    // Check we don't overshoot
    if (oldCount < 0) {
      assert(newCount <= 0) : oldCount + " + " + amount;
    } else {
      assert(newCount >= 0) : oldCount + " + " + amount;
    }
  }

  void incr(Var var, RefCountType rcType, long amount) {
    if (RefCounting.trackRefCount(var, rcType)) {
      AliasKey key = getCountKey(var);
      incrDirect(key, rcType, amount);
    }
  }

  /**
   * Increment a key directly, without trying to parse out structs
   * This should only be used if the key has already been validated.
   * @param key
   * @param rcType
   * @param amount
   */
  void incrDirect(AliasKey key, RefCountType rcType, long amount) {
    getCounters(rcType, RCDir.fromAmount(amount)).add(key, amount);
  }

  void decr(Var var, RefCountType type, long amount) {
    incr(var, type, -1 * amount);
  }
  
  /**
   * Increment key by given amount
   * @param key
   * @param rcType
   * @param amount
   * @param varType
   */
  public void incrKey(AliasKey key, RefCountType rcType, long amount,
                      Type varType) {
    // Check to see if var/type may be able to carry refcount
    Var var = getRefCountVar(null, key);
    if ((var != null && RefCounting.trackRefCount(var, rcType)) ||
        (var == null &&
         RefCounting.mayHaveRefcount(varType, rcType))) {
      incrDirect(key, rcType, amount);
    }
  }

  /**
   * Find the key we're using to track refcounts for this var
   * @param var
   * @return
   */
  public AliasKey getCountKey(Var var) {
    // See if this is within an enclosing struct, and if so find
    // topmost struct

    return aliases.getCanonical(var);
  }
  
  @Override
  public String toString() {
    return "ReadIncr: " + readIncrements +
            "\n    ReadDecr: " + readDecrements +
           "\n    WriteIncr: " + writeIncrements +
           "\n    WriteDecr: " + writeDecrements +
           "\n    " + aliases;
  }
}