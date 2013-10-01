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
package exm.stc.common.util;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import exm.stc.common.exceptions.STCRuntimeError;

/**
 * An implementation of the Map interface that allows cheap creation of
 * nested maps.  If a lookup fails in the current map, then it sees if 
 * the key is in the parent map.  Attempts to remove items that appear in 
 * the parent result in a copy of the parent map.
 * 
 */
public class HierarchicalMap<K, V> implements Map<K, V> {
  private final HashMap<K, V> map;
  private HierarchicalMap<K, V> parent;
  
  /** True if a copy on write was performed on parent */
  private boolean parentCopy;
  
  public HierarchicalMap() {
    this(null);
  }
  
  private void copyOnWrite() {
    if (parentCopy || parent == null) {
      // Already have exclusively owned copy
      return;
    }
    HierarchicalMap<K, V> newParent = new HierarchicalMap<K,V>();
    newParent.putAll(parent);
    parent = newParent;
    parentCopy = true;
  }
  
  private HierarchicalMap(HierarchicalMap<K, V> parent) {
    this.map = new HashMap<K, V>();
    this.parent = parent;
    this.parentCopy = false;
  }

  public HierarchicalMap<K, V> makeChildMap() {
    return new HierarchicalMap<K,V>(this);
  }
  public boolean containsKey(Object key) {
    HierarchicalMap<K, V> curr = this;
    do {
      if (curr.map.containsKey(key)) {
        return true;
      }
      curr = curr.parent;
    } while (curr != null);
    return false;
  }

  public V get(Object key) {
    HierarchicalMap<K, V> curr = this;
    do {
      if (curr.map.containsKey(key)) {
        return curr.map.get(key);
      }
      curr = curr.parent;
    } while (curr != null);
    return null;
  }

  /**
   * @param key
   * @return the depth at which the key is defined, -1 if not present
   */
  public int getDepth(K key) {
    int depth = 0;
    HierarchicalMap<K, V> curr = this;
    while (curr != null) {
      if (curr.map.containsKey(key)) {
        return depth;
      }
      depth++;
      curr = curr.parent;
    }
    return -1;
  }

  public V put(K key, V value) {
    return map.put(key, value);
  }

  /**
   * Put at a higher level in map
   * @param key
   * @param value
   * @param depth
   */
  public void put(K key, V value, int depth) throws IllegalArgumentException {
    HierarchicalMap<K, V> curr = this;
    for (int i = 0; i < depth; i++) {
      curr = curr.parent;
      if (curr == null) {
        throw new IllegalArgumentException("Hierarchical map didn't have "
                    + depth + " ancestors"); 
      }
    }
    curr.put(key, value);
  }

  public void putAll(Map<? extends K, ? extends V> m) {
    map.putAll(m);
  }

  @Override
  public void clear() {
    map.clear();
    parent = null; // Don't modify parent
  }

  @Override
  public boolean containsValue(Object val) {
    throw new STCRuntimeError("not implemented");
  }

  @Override
  public HierarchicalSet<Entry<K, V>> entrySet() {
    if (parent != null) {
      return parent.entrySet().makeChild(this.map.entrySet());
    } else {
      return new HierarchicalSet<Entry<K,V>>(this.map.entrySet());
    }
  }

  @Override
  public boolean isEmpty() { 
    HierarchicalMap<K, V> curr = this;
    do {
      if (!curr.map.isEmpty()) {
        return false;
      }
      curr = curr.parent;
    } while (curr != null);
    return true;
  }

  @Override
  public HierarchicalSet<K> keySet() {
    if (parent != null) {
      return parent.keySet().makeChild(this.map.keySet());
    } else {
      return new HierarchicalSet<K>(this.map.keySet());
    }
  }
  
  @Override
  public V remove(Object key) {
    return remove(key, true);
  }
  
  public V remove(Object key, boolean copyOnWrite) {
    V removed = map.remove(key);
    if (parent != null) {
      if (parent.containsKey(key)) {
        if (copyOnWrite) {
          copyOnWrite();
        }
        V parentRemoved = parent.remove(key, copyOnWrite);
        if (removed == null) {
          removed = parentRemoved;
        }
      }
    }
    return removed;
  }

  @Override
  public int size() {
    int parentSize = parent == null ? 0 : parent.size();
    return parentSize + map.size();
  }

  @Override
  public Collection<V> values() {
    throw new STCRuntimeError("not implemented");
  }
  
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    int written = writeContents(sb, true);
    HierarchicalMap<K, V> ancestor = parent;
    while (ancestor != null) {
      written += ancestor.writeContents(sb, written == 0);
      ancestor = ancestor.parent;
    }
    sb.append("}");
    return sb.toString();
  }
  
  private int writeContents(StringBuilder sb, boolean first) {
    for (Entry<K, V> e: this.map.entrySet()) {
      if (first) {
        first = false;
      } else {
        sb.append(",");
      }
      sb.append(e.getKey());
      sb.append(":");
      sb.append(e.getValue());
    }
    return map.size();
  }
  
}