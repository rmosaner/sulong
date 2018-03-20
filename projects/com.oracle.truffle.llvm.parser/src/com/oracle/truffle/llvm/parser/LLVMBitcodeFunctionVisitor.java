/*
 * Copyright (c) 2016, Oracle and/or its affiliates.
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
package com.oracle.truffle.llvm.parser;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.llvm.parser.LLVMLivenessAnalysis.LLVMLivenessAnalysisResult;
import com.oracle.truffle.llvm.parser.LLVMPhiManager.Phi;
import com.oracle.truffle.llvm.parser.metadata.debuginfo.SourceVariable;
import com.oracle.truffle.llvm.parser.model.blocks.InstructionBlock;
import com.oracle.truffle.llvm.parser.model.functions.FunctionDefinition;
import com.oracle.truffle.llvm.parser.model.symbols.instructions.Instruction;
import com.oracle.truffle.llvm.parser.model.visitors.FunctionVisitor;
import com.oracle.truffle.llvm.parser.nodes.LLVMSymbolReadResolver;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMExpressionNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class LLVMBitcodeFunctionVisitor implements FunctionVisitor {

    private final LLVMParserRuntime runtime;
    private final FrameDescriptor frame;
    private final List<LLVMExpressionNode> blocks;
    private final Map<InstructionBlock, List<Phi>> phis;
    private final LLVMSymbolReadResolver symbols;
    private final NodeFactory nodeFactory;
    private final int argCount;
    private final FunctionDefinition function;
    private final LLVMLivenessAnalysisResult liveness;
    private final List<FrameSlot> notNullable;
    private final LLVMRuntimeDebugInformation dbgInfoHandler;
    private boolean initDebugValues;

    LLVMBitcodeFunctionVisitor(LLVMParserRuntime runtime, FrameDescriptor frame, Map<InstructionBlock, List<Phi>> phis, NodeFactory nodeFactory, int argCount, LLVMSymbolReadResolver symbols,
                    FunctionDefinition functionDefinition, LLVMLivenessAnalysisResult liveness, List<FrameSlot> notNullable, LLVMRuntimeDebugInformation dbgInfoHandler) {
        this.runtime = runtime;
        this.frame = frame;
        this.phis = phis;
        this.symbols = symbols;
        this.nodeFactory = nodeFactory;
        this.argCount = argCount;
        this.function = functionDefinition;
        this.liveness = liveness;
        this.notNullable = notNullable;
        this.dbgInfoHandler = dbgInfoHandler;
        this.blocks = new ArrayList<>();
        this.initDebugValues = dbgInfoHandler.isEnabled();
    }

    public List<LLVMExpressionNode> getBlocks() {
        return Collections.unmodifiableList(blocks);
    }

    public FunctionDefinition getFunction() {
        return function;
    }

    @Override
    public void visit(InstructionBlock block) {
        List<Phi> blockPhis = phis.get(block);
        ArrayList<LLVMLivenessAnalysis.NullerInformation> blockNullerInfos = liveness.getNullableWithinBlock()[block.getBlockIndex()];
        LLVMBitcodeInstructionVisitor visitor = new LLVMBitcodeInstructionVisitor(frame, blockPhis, nodeFactory, argCount, symbols, runtime, blockNullerInfos, function.getSourceFunction(),
                        notNullable, dbgInfoHandler);

        if (initDebugValues) {
            for (SourceVariable variable : function.getSourceFunction().getVariables()) {
                final LLVMExpressionNode initNode = dbgInfoHandler.createInitializer(variable);
                if (initNode != null) {
                    visitor.addInstructionUnchecked(initNode);
                }
            }
            initDebugValues = false;
        }

        for (int i = 0; i < block.getInstructionCount(); i++) {
            Instruction instruction = block.getInstruction(i);
            visitor.setInstructionIndex(i);
            instruction.accept(visitor);
        }

        blocks.add(nodeFactory.createBasicBlockNode(runtime, visitor.getInstructions(), visitor.getControlFlowNode(), block.getBlockIndex(), block.getName()));
    }

    public void patchLoops(FrameSlot[][] nullableBeforeBlock, FrameSlot[][] nullableAfterBlock) {
        if (!this.function.getName().contains("main"))
            return;

        function.updateLoops();
        for (List<Integer> loop : function.getLoops()) {
            LLVMExpressionNode[] basicBlocks = new LLVMExpressionNode[loop.size()];
            int j = 0;
            for (int i : loop) {
                basicBlocks[j++] = blocks.get(i);
            }

            blocks.set(loop.get(0), nodeFactory.createLoopNode(basicBlocks, nullableBeforeBlock, nullableAfterBlock));
        }
    }
}
