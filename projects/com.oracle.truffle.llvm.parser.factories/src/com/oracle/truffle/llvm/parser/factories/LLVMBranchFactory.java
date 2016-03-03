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
package com.oracle.truffle.llvm.parser.factories;

import com.oracle.truffle.llvm.nodes.base.LLVMExpressionNode;
import com.oracle.truffle.llvm.nodes.base.LLVMNode;
import com.oracle.truffle.llvm.nodes.impl.base.LLVMAddressNode;
import com.oracle.truffle.llvm.nodes.impl.base.LLVMStatementNode;
import com.oracle.truffle.llvm.nodes.impl.base.integers.LLVMI1Node;
import com.oracle.truffle.llvm.nodes.impl.control.LLVMBrUnconditionalNodeGen;
import com.oracle.truffle.llvm.nodes.impl.control.LLVMConditionalBranchNodeFactory;
import com.oracle.truffle.llvm.nodes.impl.control.LLVMConditionalBranchNodeFactory.LLVMBrConditionalInjectionNodeGen;
import com.oracle.truffle.llvm.nodes.impl.control.LLVMIndirectBranchNode;
import com.oracle.truffle.llvm.parser.LLVMParserRuntime;

public class LLVMBranchFactory {

    public static LLVMNode createIndirectBranch(LLVMExpressionNode value, int[] labelTargets) {
        return new LLVMIndirectBranchNode((LLVMAddressNode) value, labelTargets);
    }

    public static LLVMStatementNode createConditionalBranch(LLVMParserRuntime runtime, int trueIndex, int falseIndex, LLVMExpressionNode conditionNode) {
        if (runtime.getOptimizationConfiguration().injectBranchProbabilitiesForConditionalBranch()) {
            return LLVMBrConditionalInjectionNodeGen.create(trueIndex, falseIndex, (LLVMI1Node) conditionNode);
        } else {
            return LLVMConditionalBranchNodeFactory.LLVMBrConditionalNodeGen.create(trueIndex, falseIndex, (LLVMI1Node) conditionNode);
        }
    }

    public static LLVMStatementNode createUnconditionalBranch(int unconditionalIndex) {
        return LLVMBrUnconditionalNodeGen.create(unconditionalIndex);
    }

}
