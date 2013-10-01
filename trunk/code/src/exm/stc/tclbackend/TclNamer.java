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
package exm.stc.tclbackend;

import java.util.ArrayList;
import java.util.List;

import exm.stc.common.lang.Var;

public class TclNamer {
  /** to avoid clashes with other tcl variables, prefix variables/functions
   * names from swift with these prefixes:
   */
  private static final String FN_PREFIX = "f:";
  private static final String TCL_TMP_VAR_PREFIX = "t:";
  private static final String TCL_ALIAS_VAR_PREFIX = "a:";
  private static final String TCL_USER_VAR_PREFIX = "u:";
  private static final String TCL_VALUE_VAR_PREFIX = "v:";
  private static final String TCL_OPT_VAR_PREFIX = "opt:";
  private static final String TCL_OPT_VALUE_VAR_PREFIX = "optv:";
  private static final String TCL_OPT_FILENAME_PREFIX = "optf:";
  private static final String TCL_STRUCT_FIELD_VAR_PREFIX = "sf:";
  private static final String TCL_LOOPINDEX_VAR_PREFIX = "i:";
  private static final String TCL_GLOBAL_CONST_VAR_PREFIX = "c:";
  private static final String TCL_DEREF_COMPILER_VAR_PREFIX = "dr:";
  private static final String TCL_LOOP_INDEX_VAR_PREFIX = "lv:";
  private static final String TCL_OUTER_VAR_PREFIX = "outer:";
  private static final String TCL_FILENAME_OF_PREFIX = "filename:";
  private static final String TCL_WRAP_FILENAME_OF_PREFIX = "wfilename:";
  private static final String TCL_COMPILER_ARG_PREFIX = "ca:";
  
  public static String prefixVar(String varname) {
    // Replace the internal names of temporary variables with
    // shorter ones for generated tcl code
    if (varname.startsWith(Var.TMP_VAR_PREFIX)) {
      return TCL_TMP_VAR_PREFIX + varname.substring(
              Var.TMP_VAR_PREFIX.length());
    } else if (varname.startsWith(Var.ALIAS_VAR_PREFIX)) {
      return TCL_ALIAS_VAR_PREFIX + varname.substring(Var.ALIAS_VAR_PREFIX.length());
    } else if (varname.startsWith(Var.STRUCT_FIELD_VAR_PREFIX)) {
      return TCL_STRUCT_FIELD_VAR_PREFIX + varname.substring(
          Var.STRUCT_FIELD_VAR_PREFIX.length());
    } else if (varname.startsWith(Var.LOCAL_VALUE_VAR_PREFIX))  {
      return TCL_VALUE_VAR_PREFIX +
          varname.substring(Var.LOCAL_VALUE_VAR_PREFIX.length());
    } else if (varname.startsWith(Var.OPT_VALUE_VAR_PREFIX))  {
      return TCL_OPT_VALUE_VAR_PREFIX +
          varname.substring(Var.OPT_VALUE_VAR_PREFIX.length());
    } else if (varname.startsWith(Var.OPT_VAR_PREFIX))  {
      return TCL_OPT_VAR_PREFIX +
          varname.substring(Var.OPT_VAR_PREFIX.length());
    } else if (varname.startsWith(Var.OPT_FILENAME_PREFIX))  {
      return TCL_OPT_FILENAME_PREFIX +
          varname.substring(Var.OPT_FILENAME_PREFIX.length());
    } else if (varname.startsWith(Var.LOOP_INDEX_VAR_PREFIX)) {
      return TCL_LOOPINDEX_VAR_PREFIX +
          varname.substring(Var.LOOP_INDEX_VAR_PREFIX.length());
    } else if (varname.startsWith(Var.GLOBAL_CONST_VAR_PREFIX)) {
      return TCL_GLOBAL_CONST_VAR_PREFIX +
          varname.substring(Var.GLOBAL_CONST_VAR_PREFIX.length());
    } else if (varname.startsWith(Var.DEREF_COMPILER_VAR_PREFIX)) {
      return TCL_DEREF_COMPILER_VAR_PREFIX +
          varname.substring(Var.DEREF_COMPILER_VAR_PREFIX.length());
    } else if (varname.startsWith(Var.LOOP_INDEX_VAR_PREFIX)) {
      return TCL_LOOP_INDEX_VAR_PREFIX +
          varname.substring(Var.LOOP_INDEX_VAR_PREFIX.length());
    } else if (varname.startsWith(Var.OUTER_VAR_PREFIX)) {
      return TCL_OUTER_VAR_PREFIX +
          varname.substring(Var.OUTER_VAR_PREFIX.length());
    } else if (varname.startsWith(Var.FILENAME_OF_PREFIX)) {
      return TCL_FILENAME_OF_PREFIX + 
          varname.substring(Var.FILENAME_OF_PREFIX.length());
    } else if (varname.startsWith(Var.WRAP_FILENAME_PREFIX)) {
      return TCL_WRAP_FILENAME_OF_PREFIX + 
          varname.substring(Var.WRAP_FILENAME_PREFIX.length());
    } else if (varname.startsWith(Var.COMPILER_ARG_PREFIX)){
      return TCL_COMPILER_ARG_PREFIX + 
          varname.substring(Var.COMPILER_ARG_PREFIX.length());
    } else {
      return TCL_USER_VAR_PREFIX + varname;
    }
  }

  public static List<String> prefixVars(List<String> vlist) {
    ArrayList<String> result = new ArrayList<String>(vlist.size());
    for (String v: vlist) {
      result.add(prefixVar(v));
    }
    return result;
  }

  public static String swiftFuncName(String function) {
    return FN_PREFIX + function;
  }
}
