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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * Simple multi-map implementation that is useful for
 * some algorithms.  Lists are used to store values in order.
 * 
 * The list is implemented as an ArrayList by default, but a factory
 * can be provided that produces other list types.
 *
 * @param <K>
 * @param <V>
 */
public class MultiMap<K, V> {

  public MultiMap() {
    this(new ArrayListFactory<V>());
  }

  public MultiMap(ListFactory<V> f) {
    this.factory = f;
  }
  
  private final ListFactory<V> factory;

  private HashMap<K, List<V>> map = 
        new HashMap<K, List<V>>(); 
  
  /**
   * Returns the value associated with a key.
   * The list is allowed to be mutated and changes will
   * be reflected in the map
   * @param key
   * @return a list (never null - empty list if no keys!)
   */
  public List<V> get(K key) {
    List<V> val = map.get(key);
    if (val == null) {
      return Collections.emptyList();
    } else {
      return val;
    }
  }
  
  public Set<Entry<K, List<V>>> entrySet() {
    return map.entrySet();
  }

  public Set<K> keySet() {
    return map.keySet();
  }

  public Collection<List<V>> values() {
    return map.values();
  }

  public void put(K key, V val) {
    List<V> list = map.get(key);
    if (list == null) {
      list = factory.make();
      map.put(key, list);
    }
    list.add(val);
  }

  public void putAll(K key, List<V> vals) {
    List<V> list = map.get(key);
    if (list == null) {
      list = factory.make();
      map.put(key, list);
    }
    list.addAll(vals);
  }
  
  public void putAll(Map<K, V> map) {
    for (Entry<K, V> e: map.entrySet()) {
      put(e.getKey(), e.getValue());
    }
  }

  public boolean containsKey(K key) {
    return map.containsKey(key) 
            && map.get(key).size() > 0;
  }

  public List<V> remove(K key) {
    List<V> res = map.remove(key);
    if (res == null) {
      return Collections.emptyList();
    } else {
      return res;
    }
  }

  public boolean isEmpty() {
    if (map.isEmpty()) {
      return true;
    }
    Iterator<Entry<K, List<V>>> it = map.entrySet().iterator();
    while (it.hasNext()) {
      Entry<K, List<V>> e = it.next();
      if (e.getValue().size() == 0) {
        // Clear out empty entries
        it.remove();
      } else {
        return false;
      }
    }
    return true;
  }

  /**
   * Can't tell always if definitely empty, since it is
   * possible that a caller mutated one of the lists and
   * made it empty.  This will be correct if remove() is called
   * whenever a stored list has all its elements removed
   * @return
   */
  public boolean isDefinitelyEmpty() {
    return map.isEmpty();
  }

  @Override
  /**
   * Return a shallow copy that clones map structure but doesn't clone
   * keys and values
   */
  public MultiMap<K, V> clone() {
    MultiMap<K, V> clone = new MultiMap<K, V>();
    for (Entry<K, List<V>> e: map.entrySet()) {
      List<V> clonedList = factory.make();
      clonedList.addAll(e.getValue());
      clone.map.put(e.getKey(), clonedList);
    }
    return clone;
  }
  
  @Override
  public String toString() {
    return map.toString();
  }  
  
  public static interface ListFactory<V1> {
    public List<V1> make();
  }
  
  public static class LinkedListFactory<V1> 
            implements ListFactory<V1> {
    public List<V1> make() {
      return new LinkedList<V1>();
    }
  }
  
  public static class ArrayListFactory<V1> 
  implements ListFactory<V1> {
    public List<V1> make() {
      return new ArrayList<V1>();
    }
  }
}
