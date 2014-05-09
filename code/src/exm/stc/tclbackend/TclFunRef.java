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

import exm.stc.common.lang.WrappedForeignFunction;

public class TclFunRef extends WrappedForeignFunction {
  
  public TclFunRef(String pkg, String symbol) {
    this(pkg, symbol, "0.0");
  }
  
  public TclFunRef(String pkg, String symbol, String version) {
    this.pkg = pkg;
    this.version = version;
    this.symbol = symbol;
  }
  public final String pkg;
  public final String version;
  public final String symbol;
  
  @Override
  public String toString() {
    return pkg + "::" + version + " (" + symbol + ")";
  }
}