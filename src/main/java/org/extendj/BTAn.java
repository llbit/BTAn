/* Copyright (c) 2017, Jesper Öqvist
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.extendj;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.InflaterInputStream;

public class BTAn {
  private static final int UNUSED_OFFSET = Integer.MIN_VALUE;

  public static void main(String[] args) throws IOException {
    boolean verbose = false;
    String input = null;
    for (String arg : args) {
      if (arg.equals("-v")) {
        verbose = true;
      } else {
        input = arg;
      }
    }
    if (input == null) {
      System.err.println("Missing input!");
      System.out.println("Usage: btan [OPTIONS] <INPUT>");
      System.out.println("   INPUT    Beaver parser filename, or base64 encoded parsing tables");
      System.out.println("   OPTIONS  -v for verbose mode");
      System.exit(1);
      return;
    }
    Path path = Paths.get(input);
    if (path.toFile().isFile()) {
      if (verbose) {
        System.out.format("Extracting parser tables from %s...%n", path);
      }
      String file = new String(Files.readAllBytes(path));
      int index = file.indexOf("new ParsingTables(");
      if (index == -1) {
        System.err.format("ERROR: input file %s does not look like a generated Beaver parser.%n",
            path);
      }
      file = file.substring(index);
      StringBuilder spec = new StringBuilder();
      while (true) {
        int start = file.indexOf('"');
        int stop = file.indexOf(");");
        if (start == -1 || stop <= start) {
          break;
        }
        int end = file.indexOf('"', start + 1);
        spec.append(file.substring(start + 1, end));
        file = file.substring(end + 1);
      }
      if (verbose) {
        System.out.println("Compressed parser tables: " + spec);
      }
      stats(new ByteArrayInputStream(base64Decode(spec.toString())), verbose);
    } else {
      stats(new ByteArrayInputStream(base64Decode(input)), verbose);
    }
  }

  private static void stats(InputStream in, boolean verbose) throws IOException {
    try (DataInputStream data = new DataInputStream(new InflaterInputStream(in))) {
      int numActions = data.readInt();
      short[] actions = readActionTable(data, numActions);
      short[] lookaheads = readActionTable(data, numActions);
      if (verbose) {
        for (int i = 0; i < numActions; i++) {
          System.out.format("%d,", lookaheads[i]);
          if ((i + 1) % 10 == 0) {
            System.out.format("%n");
          }
        }
        System.out.println();
      }

      int numOffsets = data.readInt();
      if (verbose) {
        System.out.format("ACTION OFFSETS:%n");
      }
      int[] actionOffsets = readOffsetTable(data, numOffsets);
      int[] gotoOffsets = readOffsetTable(data, numOffsets);
      if (verbose) {
        for (int i = 0; i < numOffsets; i++) {
          System.out.format("%d,", actionOffsets[i]);
          if ((i + 1) % 10 == 0) {
            System.out.format("%n");
          }
        }
        System.out.println();
      }
      for (int i = 0; i < numOffsets; i++) {
        if (actionOffsets[i] != UNUSED_OFFSET) {
          for (int j = 0; j < i; ++j) {
            if (actionOffsets[j] == actionOffsets[i]) {
              System.out.format("NOTE: two actions use the same offset: %d (states %d and %d)%n",
                  actionOffsets[i], j, i);
            }
          }
        }
      }
      if (verbose) {
        System.out.format("GOTO OFFSETS:%n");
      }

      int numDefaultActions = data.readInt();
      boolean compressed = numDefaultActions != 0;
      short[] defaultActions;
      if (compressed) {
        defaultActions = new short[numDefaultActions];
        for (int i = 0; i < numDefaultActions; i++) {
          defaultActions[i] = data.readShort();
        }
      }

      int minNonterminalId = Integer.MAX_VALUE;
      int maxNonterminalId = 0;
      int numRules = data.readInt();
      if (verbose) {
        System.out.format("Ruleinfo len: %d%n", numRules);
      }
      int[] ruleInfo = new int[numRules];
      for (int i = 0; i < numRules; i++) {
        ruleInfo[i] = data.readInt();
        int rhs_size = ruleInfo[i] & 0xFFFF;
        int nt = ruleInfo[i] >>> 16;
        minNonterminalId = Math.min(minNonterminalId, nt);
        maxNonterminalId = Math.max(maxNonterminalId, nt);
        if (verbose) {
          System.out.format("  %d -> %d (%d)%n", i, nt, rhs_size);
        }
      }
      int numTerminals = minNonterminalId;
      int num_nt = 1 + maxNonterminalId - minNonterminalId;

      System.out.format("n_term: %d%n", numTerminals);
      System.out.format("max_nt: %d%n", maxNonterminalId);
      System.out.format("num nonterminal: %d%n", num_nt);

      int errorSymbolId = data.readShort();
      if (verbose) {
        System.out.format("error symbol: %d%n", errorSymbolId);
      }

      System.out.format("OFFSETS:%n");
      System.out.format("     ");
      for (int i = 0; i < numTerminals; ++i) {
        System.out.format("%3d,", i);
      }
      System.out.format("%n");
      for (int state = 0; state < actionOffsets.length; ++state) {
        int start = actionOffsets[state];
        System.out.format("%2d:  ", state);
        for (int i = 0; i < numTerminals; ++i) {
          int index = start + i;
          if (index >= 0 && index < actions.length && lookaheads[index] == i) {
            System.out.format("%3d,", index);
          } else {
            System.out.format("   ,");
          }
        }
        System.out.format("%n");
      }
      System.out.format("ACTIONS:%n");
      System.out.format("     ");
      for (int i = 0; i < numTerminals; ++i) {
        System.out.format("%3d,", i);
      }
      System.out.format("%n");
      for (int state = 0; state < actionOffsets.length; ++state) {
        int start = actionOffsets[state];
        System.out.format("%2d:  ", state);
        for (int i = 0; i < numTerminals; ++i) {
          int index = start + i;
          if (index >= 0 && index < actions.length && lookaheads[index] == i) {
            System.out.format("%3d,", actions[index]);
          } else {
            System.out.format("   ,");
          }
        }
        System.out.format("%n");
      }
      if (verbose) {
        int numAction = 0;
        for (int state = 0; state < actionOffsets.length; ++state) {
          int start = actionOffsets[state];
          if (start != UNUSED_OFFSET) {
            System.out.format("%d: (start = %d)%n", state, start);
            for (int i = Math.max(-start, 0); i < numTerminals && i + start < actions.length; ++i) {
              int index = i + start;
              if (lookaheads[index] == i) {
                if (actions[index] < 0) {
                  System.out.format("  %d: REDUCE %d%n", i, ~actions[index]);
                  numAction += 1;
                } else {
                  System.out.format("  %d: SHIFT %d%n", i, actions[index]);
                  numAction += 1;
                }
              }
            }
          }
          start = gotoOffsets[state];
          if (start != UNUSED_OFFSET) {
            System.out.format("%d: (start = %d)%n", state, start);
            start += numTerminals;
            for (int i = Math.max(-start, 0); i < num_nt && i + start < actions.length; ++i) {
              int index = i + start;
              if (lookaheads[index] == numTerminals + i) {
                if (actions[index] != ~ruleInfo.length) {
                  System.out.format("  %d -> %d%n", i + numTerminals, actions[index]);
                } else {
                  System.out.format("  %d -> ACCEPT%n", i + numTerminals);
                }
                numAction += 1;
              }
            }
          }
        }
        System.out.format("num action: %d, action table size: %d%n", numAction, actions.length);
      }
    }
  }

  private static short[] readActionTable(DataInputStream data, int numActions) throws IOException {
    short[] actions = new short[numActions];
    for (int i = 0; i < numActions; i++) {
      actions[i] = data.readShort();
    }
    return actions;
  }

  private static int[] readOffsetTable(DataInputStream data, int numOffsets) throws IOException {
    int[] offsets = new int[numOffsets];
    for (int i = 0; i < numOffsets; i++) {
      offsets[i] = data.readInt();
    }
    return offsets;
  }


  private static byte[] base64Decode(String input) {
    int outputSize = (3 * input.length() + 3) / 4;
    byte[] output = new byte[outputSize];
    int outpos = 0;
    int step = 0;
    for (int i = 0; i < input.length(); ++i) {
      char ch = input.charAt(i);
      int value;
      if (ch >= '0' && ch <= '9') {
        value = ch - '0';
      } else if (ch >= 'A' && ch <= 'Z') {
        value = 10 + (ch - 'A');
      } else if (ch >= 'a' && ch <= 'z') {
        value = 36 + (ch - 'a');
      } else if (ch == '#') {
        value = 62;
      } else if (ch == '$') {
        value = 63;
      } else if (ch == '=') {
        value = 0;
      } else {
        throw new Error(String.format("Unexpected Base64 character: '%c' in input.", ch));
      }
      switch (step) {
        case 0:
          output[outpos] = (byte) (value << 2);
          step = 1;
          break;
        case 1:
          output[outpos++] |= (byte) (value >> 4);
          output[outpos] = (byte) (0xFF & (value << 4));
          step = 2;
          break;
        case 2:
          output[outpos++] |= (byte) (value >> 2);
          output[outpos] = (byte) (0xFF & (value << 6));
          step = 3;
          break;
        case 3:
          output[outpos++] |= (byte) value;
          step = 0;
          break;
      }
    }
    return output;
  }
}
