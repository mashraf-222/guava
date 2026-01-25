/*
 * Copyright (C) 2024 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.thirdparty.publicsuffix;

import com.google.common.annotations.GwtCompatible;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A memory-efficient binary Trie structure for public suffix lookups. The Trie is stored as a
 * series of 16-bit characters in a String.
 *
 * <p>The trie data is stored in a {@link CharSequence} where each node takes 3 characters:
 *
 * <ol>
 *   <li>The offset of the label in the {@code stringPool}.
 *   <li>The index of the first child node in the {@code trieData}.
 *   <li>A bitfield containing:
 *       <ul>
 *         <li>Bits 0-10: Number of children.
 *         <li>Bit 11: Whether this node represents an exclusion (!rule).
 *         <li>Bits 12-13: The type of wildcard match (*.rule) at this node (0=none, 1=REGISTRY,
 *             2=PRIVATE).
 *         <li>Bits 14-15: The type of exact match (rule) at this node (0=none, 1=REGISTRY,
 *             2=PRIVATE).
 *       </ul>
 * </ol>
 *
 * <p>The {@code stringPool} stores the labels of the nodes as length-prefixed strings.
 *
 * <p><b>Capacity Limits and Scalability:</b>
 *
 * <ul>
 *   <li><b>Total nodes:</b> 65535 (16-bit index in the Trie)
 *   <li><b>String pool size:</b> 65535 characters (16-bit offset in the String Pool)
 *   <li><b>Max children per node:</b> 2047 (11-bit count in the bitfield)
 * </ul>
 *
 * <p>As of Jan 2026, the capacity usage is approximately:
 *
 * <ul>
 *   <li>Total nodes: ~16% (10k / 65k)
 *   <li>String pool size: ~79% (52k / 65k)
 *   <li>Max children per node: ~71% (1.5k / 2k)
 * </ul>
 *
 * <p>If any of these limits are exceeded, the definitive solution is to increase the node size from
 * 3 to 4 characters. This would allow:
 *
 * <ul>
 *   <li>A full 32-bit offset for the String Pool (using 2 characters).
 *   <li>A full 16-bit child count (using the freed bits from the 4th character).
 * </ul>
 */
@GwtCompatible
public final class PublicSuffixTrie {

  /** Each node in the trie takes up 3 characters. */
  private static final int NODE_SIZE = 3;

  private static final int CHILDREN_MASK = 0x7FF;
  private static final int EXCLUSION_MASK = 0x1;
  private static final int TYPE_MASK = 0x3;
  private static final int EXCLUSION_SHIFT = 11;
  private static final int WILDCARD_SHIFT = 12;
  private static final int EXACT_SHIFT = 14;

  final CharSequence trieData;
  final CharSequence stringPool;

  public PublicSuffixTrie(
      ImmutableList<CharSequence> trieDataChunks, ImmutableList<CharSequence> stringPoolChunks) {
    this.trieData = new ChunksCharSequence(trieDataChunks);
    this.stringPool = new ChunksCharSequence(stringPoolChunks);
  }

  /**
   * Performs a lookup for the public suffix of the given labels and returns the index of the
   * leftmost label in the public suffix. Returns -1 if no match found.
   *
   * @param labels the domain labels, from left to right.
   * @param desiredType the desired type of public suffix (e.g. REGISTRY or PRIVATE).
   * @return the index of the leftmost label in the public suffix, or -1 if not found.
   */
  public int findSuffixIndex(List<String> labels, Optional<PublicSuffixType> desiredType) {
    int partsSize = labels.size();
    int nodeIdx = 0; // Root node
    int bestResult = -1;

    // Iterate over the labels in reverse order (from TLD to the left).
    for (int i = partsSize - 1; i >= 0; i--) {
      // Decode the current node.
      int firstChild = trieData.charAt(nodeIdx * NODE_SIZE + 1);
      int numChildren = trieData.charAt(nodeIdx * NODE_SIZE + 2) & CHILDREN_MASK;

      nodeIdx = findChild(firstChild, numChildren, labels.get(i));
      if (nodeIdx == -1) {
        break;
      }

      // Check for matches at this node. Higher priority rules are checked last to overwrite.
      int metadata = trieData.charAt(nodeIdx * NODE_SIZE + 2);

      // Excluded match (!rule). If this bit is set, it means the current path is NOT a public
      // suffix, even if a previous rule matched. For example, "!www.ck" would exclude "www.ck"
      // from being a public suffix, leaving "ck" as the suffix.
      if (((metadata >> EXCLUSION_SHIFT) & EXCLUSION_MASK) != 0) {
        bestResult = i + 1;
      }

      // Exact match. If the exact type bits are set, this node itself is a public suffix.
      PublicSuffixType exactType = getType((metadata >> EXACT_SHIFT) & TYPE_MASK);
      if (matchesType(desiredType, exactType)) {
        bestResult = i;
      }

      // Wildcard match (*.rule). If the wildcard type bits are set, any child of this node is a
      // public suffix. For example, "*.ck" means "any.ck" is a public suffix.
      PublicSuffixType underType = getType((metadata >> WILDCARD_SHIFT) & TYPE_MASK);
      if (i > 0 && matchesType(desiredType, underType)) {
        bestResult = i - 1;
      }
    }
    return bestResult;
  }

  private static boolean matchesType(
      Optional<PublicSuffixType> desiredType, PublicSuffixType actualType) {
    return actualType != null && (!desiredType.isPresent() || desiredType.get().equals(actualType));
  }

  private @Nullable PublicSuffixType getType(int typeBits) {
    if (typeBits == 1) {
      return PublicSuffixType.REGISTRY;
    } else if (typeBits == 2) {
      return PublicSuffixType.PRIVATE;
    }
    return null;
  }

  /**
   * Performs a binary search over the children of a node to find the child with the given label.
   */
  private int findChild(int firstChild, int numChildren, String label) {
    int low = firstChild;
    int high = firstChild + numChildren - 1;

    while (low <= high) {
      int mid = (low + high) >>> 1;
      int labelOffset = trieData.charAt(mid * NODE_SIZE);
      int cmp = compareLabel(labelOffset, label);

      if (cmp < 0) {
        high = mid - 1;
      } else if (cmp > 0) {
        low = mid + 1;
      } else {
        return mid;
      }
    }
    return -1;
  }

  /**
   * Compares the label stored in the string pool at the given offset with the provided label
   * string.
   */
  private int compareLabel(int offset, String label) {
    int nodeLabelLen = stringPool.charAt(offset);
    int labelLen = label.length();
    int minLen = Math.min(nodeLabelLen, labelLen);
    for (int i = 0; i < minLen; i++) {
      char c1 = label.charAt(i);
      char c2 = stringPool.charAt(offset + 1 + i);
      if (c1 != c2) {
        return c1 - c2;
      }
    }
    return labelLen - nodeLabelLen;
  }

  /**
   * A CharSequence that is composed of multiple chunks. This is used to work around the 64k size
   * limit for string literals in Java.
   */
  private static class ChunksCharSequence implements CharSequence {
    private final ImmutableList<CharSequence> chunks;
    private final int[] offsets;
    private final int length;

    ChunksCharSequence(ImmutableList<CharSequence> chunks) {
      this.chunks = chunks;
      this.offsets = new int[chunks.size() + 1];
      int total = 0;
      for (int i = 0; i < chunks.size(); i++) {
        offsets[i] = total;
        total += chunks.get(i).length();
      }
      offsets[chunks.size()] = total;
      this.length = total;
    }

    @Override
    public int length() {
      return length;
    }

    @Override
    public char charAt(int index) {
      if (index < offsets[1]) {
        return chunks.get(0).charAt(index);
      }
      // Simple binary search for the right chunk
      int low = 1;
      int high = chunks.size() - 1;
      while (low <= high) {
        int mid = (low + high) >>> 1;
        if (index < offsets[mid]) {
          high = mid - 1;
        } else if (index >= offsets[mid + 1]) {
          low = mid + 1;
        } else {
          return chunks.get(mid).charAt(index - offsets[mid]);
        }
      }
      throw new IndexOutOfBoundsException();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder(length);
      for (CharSequence chunk : chunks) {
        sb.append(chunk);
      }
      return sb.toString();
    }
  }
}
