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

package exm.stc.tclbackend.tree;

import exm.stc.common.exceptions.STCRuntimeError;

/**
 * If-then construct
 * To include else-if-then, else-then blocks, we need to extend this
 *
 * @author wozniak
 * */
public class If extends Sequence
{
  TclTree condition;

  public If(TclTree condition, TclTree thenBlock, TclTree elseBlock)
  {
    this.condition = condition;
    members.add(thenBlock);
    if (elseBlock != null) {
      members.add(elseBlock);
    }
  }

  public If(String condition, TclTree thenBlock)
  {
    this(new Token(condition), thenBlock, null);
  }

  public If(Expression condition, TclTree thenBlock)
  {
    this((TclTree) condition, thenBlock, null);
  }

  /**
     The user must do an add() later to set the then-block
   */
  public If(Expression condition)
  {
    this((TclTree) condition, null, null);
  }

  @Override
  public void appendTo(StringBuilder sb)
  {
    if (members.size() < 1) {
      throw new STCRuntimeError("if: no then block found");
    } else if (members.size() > 2) {
      throw new STCRuntimeError("if: additional block found after " +
                                       "else block");
    }
    indent(sb);
    sb.append("if { ");
    condition.appendTo(sb);
    sb.append(" } ");
    // then block
    TclTree thenBlock = members.get(0);
    thenBlock.setIndentation(indentation);
    thenBlock.appendToAsBlock(sb);
    if (members.size() == 2) {
      sb.append(" else ");
      TclTree elseBlock = members.get(1);
      // else block
      elseBlock.setIndentation(indentation);
      elseBlock.appendToAsBlock(sb);
    }
    sb.append("\n");
  }
  

}
