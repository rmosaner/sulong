/*
 * Copyright (c) 2017, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.parser.util;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.oracle.truffle.llvm.parser.model.blocks.InstructionBlock;
import com.oracle.truffle.llvm.parser.model.functions.FunctionDefinition;
import com.oracle.truffle.llvm.parser.model.symbols.instructions.TerminatingInstruction;

public final class LLVMLoopFinder {

    private FunctionDefinition fd;

    public LLVMLoopFinder(FunctionDefinition fd) {
        this.fd = fd;
    }

    public Set<List<Integer>> findLoops() {
        Map<Integer, List<Integer>> successors = new HashMap<>();
        List<Integer> blockIds = new LinkedList<>();

        for (InstructionBlock block : fd.getBlocks()) {
            int blockId = block.getBlockIndex();
            blockIds.add(blockId);
            TerminatingInstruction term = block.getTerminatingInstruction();
            successors.put(blockId, new LinkedList<>());
            for (int suc = 0; suc < term.getSuccessorCount(); suc++) {
                successors.get(blockId).add(term.getSuccessor(suc).getBlockIndex());
            }
        }

        Set<List<Integer>> sccs = Tarjan.execute(blockIds, successors); // calculate strongly connected components using Tarjan algorithm

        Map<Integer, Loop> loopSet = new HashMap<>();
        int blockId = 0;
        while (blockId < fd.getBlocks().size()) {
            List<Integer> sucs = successors.get(blockId);
            for (int suc : sucs) {
                // inSameSCC checks if block_blockId could be reached by block_suc --> otherwise: second loop entry
                if (suc < blockId && inSameSCC(sccs, suc, blockId)) {
                    // backedge found --> suc is loop header (not necessarily containing condition)
                    if (loopSet.get(suc) == null) {
                        Loop loop = new Loop(fd.getBlock(suc));
                        loop.addBackedge(fd.getBlock(blockId));
                        loopSet.put(suc, loop);
                    } else {
                        loopSet.get(suc).addBackedge(fd.getBlock(blockId));
                    }
                }
            }
            blockId++;
        }
        // TODO remove backedges to separate loops before using Tarjan again

        for (Loop l : loopSet.values()) {
            // remove all backedges for one loop
            for (InstructionBlock backedge : l.backedges) {
                successors.get(backedge.getBlockIndex()).remove((Integer) l.loopStart.getBlockIndex());
            }
            // execute Tarjan with only one loop intact

        }

        Set<List<Integer>> loops = new HashSet<>();
        for (Loop l : loopSet.values()) {
            for (InstructionBlock backedge : l.backedges) {
                successors.get(backedge.getBlockIndex()).add(l.loopStart.getBlockIndex());
            }
            for (List<Integer> scc : Tarjan.execute(blockIds, successors)) {
                if (scc.size() > 1) {
                    Collections.reverse(scc);
                    loops.add(scc);
                }
            }
            for (InstructionBlock backedge : l.backedges) {
                successors.get(backedge.getBlockIndex()).remove((Integer) l.loopStart.getBlockIndex());
            }
        }

        // TODO fixup for nested loops, to pull loop bodies into surrounding loop

        return loops;
    }

    private static boolean inSameSCC(Set<List<Integer>> sccs, int block1, int block2) {
        boolean block1Found = false;
        boolean block2Found = false;
        for (List<Integer> scc : sccs) {
            for (int i : scc) {
                if (i == block1) {
                    if (block2Found)
                        return true;
                    block1Found = true;
                } else if (i == block2) {
                    if (block1Found)
                        return true;
                    block2Found = true;
                }
            }
            block1Found = false;
            block2Found = false;
        }
        return false;
    }

    private static final class Loop {
        private InstructionBlock loopStart;
        private Set<InstructionBlock> backedges;

        public Loop(InstructionBlock startBlock) {
            this.loopStart = startBlock;
            backedges = new HashSet<>();
        }

        public void addBackedge(InstructionBlock backedge) {
            backedges.add(backedge);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Loop))
                return false;
            return this.loopStart.equals(((Loop) obj).loopStart);
        }

        @Override
        public int hashCode() {
            return loopStart.getBlockIndex();
        }
    }

    private static final class Tarjan {

        private List<Integer> vertices = new LinkedList<>();
        private Map<Integer, List<Integer>> successors = new HashMap<>();
        private Map<Integer, TarjanVertex> vertexInfos = new HashMap<>();
        private Deque<Integer> stack = new ArrayDeque<>();
        private int index = 0;
        private Set<List<Integer>> loops = new HashSet<>();

        public Tarjan(List<Integer> vertices, Map<Integer, List<Integer>> successors) {
            this.vertices = vertices;
            this.successors = successors;
        }

        private Set<List<Integer>> tarjan() {
            for (int blockId : vertices) {
                vertexInfos.put(blockId, new TarjanVertex());
            }

            for (int v : vertices) {
                if (vertexInfos.get(v).index == -1) {
                    strongConnect(v);
                }
            }

            return loops;

        }

        private void strongConnect(int v) {
            TarjanVertex tv = vertexInfos.get(v);
            tv.index = index;
            tv.lowlink = index;
            index++;
            stack.push(v);
            tv.onStack = true;

            for (int w : successors.get(v)) {
                TarjanVertex tw = vertexInfos.get(w);
                if (tw.index == -1) {
                    strongConnect(w);
                    tv.lowlink = Math.min(tv.lowlink, tw.lowlink);
                } else if (tw.onStack) {
                    tv.lowlink = Math.min(tv.lowlink, tw.index);
                }
            }

            if (tv.lowlink == tv.index) {
                List<Integer> loop = new LinkedList<>();
                int w;
                do {
                    w = stack.pop();
                    vertexInfos.get(w).onStack = false;
                    loop.add(w);
                } while (w != v);
                loops.add(loop);
            }
        }

        private static final class TarjanVertex {
            int index;
            int lowlink;
            boolean onStack;

            public TarjanVertex() {
                this.index = -1;
                this.lowlink = -1;
                this.onStack = false;
            }
        }

        public static Set<List<Integer>> execute(List<Integer> vertices, Map<Integer, List<Integer>> successors) {
            Tarjan tarjan = new Tarjan(vertices, successors);
            return tarjan.tarjan();
        }
    }

}