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

package exm.stc.common.lang;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import exm.stc.common.exceptions.STCRuntimeError;
import exm.stc.common.lang.Types.Type;
import exm.stc.common.lang.Types.Typed;
import exm.stc.common.util.TernaryLogic.Ternary;

/**
 * This class is used to contain the relevant information about
 * each variable in a Swift program.  All member variables
 * in the Variable are final, which means that each variable
 * instance can be treated as an immutable value and
 * safely shared between multiple data structures
 *
 */
public class Var implements Comparable<Var>, Typed {
  private final Type type;
  private final String name;
  private final Alloc storage;
  private final DefType defType;
  private final Var mapping;
  private final int hashCode; // Cache hashcode

  public static final String TMP_VAR_PREFIX = "__t";
  public static final String ALIAS_VAR_PREFIX = "__alias";
  public static final String STRUCT_FIELD_VAR_PREFIX = "__sf_";
  public static final String LOCAL_VALUE_VAR_PREFIX = "__v_";
  public static final String FILENAME_OF_PREFIX = "__filename_";
  public static final String WRAP_FILENAME_PREFIX = "__wfilename_";
  /* Separate prefixes to avoid name clashes for optimizer 
   *    inserted variables */
  public static final String OPT_VAR_PREFIX = "__o_";
  public static final String OPT_VALUE_VAR_PREFIX = "__ov_";
  public static final String OPT_FILENAME_PREFIX = "__of_";
  public static final String LOOP_INDEX_VAR_PREFIX = "__i";
  public static final String GLOBAL_CONST_VAR_PREFIX = "__c";
  public static final String DEREF_COMPILER_VAR_PREFIX = "__dr_";
  public static final String COMPILER_ARG_PREFIX = "__ca_";
  public static final String LOOP_COND_PREFIX = "__xcond";
  public static final String OUTER_VAR_PREFIX = "__outer";
  
  // Convenience constant
  public static final List<Var> NONE = Collections.emptyList();

  
  /**
   * How the variable is allocated and stored.
   */
  public enum Alloc {
    /** Shared store variable, allocated upon block entry.
     *  Stored in stack frame */
    STACK,
    /** Shared store variable, allocated upon block entry.
     *  Not stored in stack frame */
    TEMP,
    /** Handle to shared store variable allocated elsewhere.
     *  Must be initialized before use*/
    ALIAS,
    /** Non-shared value not in shared store. */
    LOCAL,
    /** Global constant store variable, allocated at program
     *  start. */
    GLOBAL_CONST;
    
    public boolean allocUponBlockEntry() {
      return this == STACK || this == TEMP;
    }
  }

  /**
   * How the variable was defined (e.g. local vs argument)
   */
  public enum DefType
  {
    /** Local variable */
    LOCAL_USER,
    /** Compiler-generated local */
    LOCAL_COMPILER,
    /** Input argument */
    INARG,
    /** Output argument */
    OUTARG,
    /** Global constant generated by compiler */
    GLOBAL_CONST;

    public boolean isLocal() {
      return this == LOCAL_COMPILER || this == LOCAL_USER;
    }
  }

  public Var(Type type, String name,
                  Alloc storage, DefType defType)
  {
    this.type = type;
    this.name = name;
    this.storage = storage;
    this.defType = defType;
    this.mapping = null;
    this.hashCode = calcHashCode();
  }
  
  public Var(Type type, String name,
      Alloc storage, DefType defType, Var mapping) {
    this.type = type;
    this.name = name;
    this.storage = storage;
    this.defType = defType;
    assert(mapping == null || Types.isString(mapping.type()));
    this.mapping = mapping;
    this.hashCode = calcHashCode();
  }

  /**
   * Create new variable with different type, but same everything else
   * @param var
   * @param newType
   * @return
   */
  public static Var substituteType(Var var, Type newType) {
    if (newType.equals(var.type)) {
      return var;
    } else {
      return new Var(newType, var.name, var.storage, var.defType, var.mapping);
    }
  }

  @Override
  public int hashCode() {
    return hashCode;
  }
  
  public int calcHashCode() {
    // Assume name unique
    return name.hashCode();
  }
  
  /**
   * Compare variables by name (assume name unique)
   * @param o
   * @return
   */
  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (!(o instanceof Var))
      throw new STCRuntimeError("Compare var with type " +
                    o.getClass().getCanonicalName() + " " + o.toString());
    Var ov = (Var)o;
    return name.equals(ov.name);
  }
  
  @Override
  public int compareTo(Var o) {
    return this.name.compareTo(o.name);
  }

  /**
   * True if all attributes match
   */
  public boolean identical(Var o) {
    return name.equals(o.name) &&
           type.equals(o.type) &&
           (mapping == o.mapping || mapping.identical(o.mapping)) &&
           storage == o.storage &&
           defType == o.defType;
  }
  
  public String name() {
    return name;
  }

  @Override // For Typed interface
  public Type type() {
    return type;
  }
  
  public Alloc storage() {
    return storage;
  }

  public DefType defType() {
    return defType;
  }

  public Var mapping() {
    return mapping;
  }

  /**
   * Determine if variable is mapped.  This is not trivial, as
   * we sometimes don't have visibility of whether a variable is
   * mapped or not if, e.g., it is passed in as a function argument.
   * @return
   */
  public Ternary isMapped() {
    if (mapping != null) {
      return Ternary.TRUE;
    } else if (!Types.isMappable(this)) {
      return Ternary.FALSE;
    } else if (storage.allocUponBlockEntry() &&
               defType.isLocal()) {
      // If the variable is allocated in this scope and we didn't see a
      // mapping, then we can assume it's unmapped 
      return Ternary.FALSE;
    }
    // Can't be sure
    return Ternary.MAYBE;
  }



  public static String names(List<Var> list)
  {
    StringBuilder sb = new StringBuilder(list.size()*16);
    Iterator<Var> it = list.iterator();
    while (it.hasNext())
    {
      Var v = it.next();
      sb.append(v.name());
      if (it.hasNext())
        sb.append(' ');
    }
    return sb.toString();
  }

  public static List<String> nameList(Collection<Var> variables)
  {
    List<String> result = new ArrayList<String>(variables.size());
    nameFill(result, variables);
    return result;
  }
  
  public static Set<String> nameSet(Collection<Var> variables)
  {
    Set<String> result = new HashSet<String>(variables.size());
    nameFill(result, variables);
    return result;
  }
  
  private static void nameFill(Collection<String> names,
                                  Collection<Var> variables)
  {
    for (Var v : variables)
      names.add(v.name());
  }

  public static List<Type> extractTypes(List<Var> variables)
  {
    ArrayList<Type> result = new ArrayList<Type>(variables.size());
    for (Var v: variables) {
      result.add(v.type());
    }

    return result;
  }
  
  /**
   * Different by variable name
   * @param list
   * @param subtract
   * @return
   */
  public static List<Var> varListDiff(List<Var> list, List<Var> subtract) {
    Set<String> subSet = nameSet(subtract);
    ArrayList<Var> diff = new ArrayList<Var>();
    for (Var v: list) {
      if (!subSet.contains(v.name())) {
        diff.add(v);
      }
    }
    return diff;
  }
  
  public static class VarCount {
    public Var var;
    public int count;
    public VarCount(Var var, int count) {
      super();
      this.var = var;
      this.count = count;
    }
  }
  public static List<VarCount> countVars(List<Var> list) {
    ArrayList<Var> sorted = new ArrayList<Var>(list);
    Collections.sort(sorted, new Comparator<Var>() {
      @Override
      public int compare(Var v1, Var v2) {
        return v1.name().compareTo(v2.name());
      }
    });
    ArrayList<VarCount> res = new ArrayList<VarCount>();
    VarCount curr = null;
    for (Var v: sorted) {
      if (curr == null || !v.name().equals(curr.var.name())) {
        curr = new VarCount(v, 1);
        res.add(curr);
      } else {
        curr.count++;
      }
    }
    return res;
  }

  /**
   * Union of lists with one instance of each variable by name
   * included in result
   * @param list1
   * @param list2
   * @return
   */
  public static List<Var> varListUnion(List<Var> list1, List<Var> list2) {
    ArrayList<Var> res = new ArrayList<Var>();
    Set<String> present = new HashSet<String>();
    for (Var v: list1) {
      if (!present.contains(v.name())) {
        res.add(v);
      }
    }
    for (Var v: list2) {
      if (!present.contains(v.name())) {
        res.add(v);
      }
    }
    return res;
  }
  
  /**
   * Do intersection by name
   * @param vs1
   * @param vs2
   * @return
   */
  public static List<Var> varIntersection(List<Var> vs1, List<Var> vs2) {
    List<Var> res = new ArrayList<Var>();
    for (Var v1: vs1) {
      for (Var v2: vs2) {
        if (v1.name().equals(v2.name())) {
          res.add(v1);
          break;
        }
      }
    }
    return res;
  }
  
  
  /**
   * Create dereferenced variable given a reference
   */
  public static Var createDerefTmp(Var ref, Alloc storage) {
    assert(Types.isRef(ref.type()));
    Var res = new Var(ref.type().memberType(),
        DEREF_COMPILER_VAR_PREFIX + ref.name(),
        storage, DefType.LOCAL_COMPILER, 
        null);
    assert(Types.isAssignableRefTo(ref.type(), res.type()));
    return res;
  }
  
  public Arg asArg() {
    return Arg.createVar(this);
  }

  public static List<Arg> asArgList(List<Var> inputs) {
    ArrayList<Arg> res = new ArrayList<Arg>(inputs.size());
    for (Var v: inputs) {
      res.add(v.asArg());
    }
    return res;
    
  }

  public List<Var> asList() {
    return Collections.singletonList(this);
  }
  
  @Override
  public String toString()
  {
    return type.typeName() + ':' + name;
  }

  public static Var findByName(List<Var> vars, String name) {
    for (Var v: vars) {
      if (v.name().equals(name)) {
        return v;
      }
    }
    return null;
  }
}
