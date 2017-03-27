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
package com.oracle.truffle.llvm.nodes.func;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.llvm.nodes.api.LLVMExpressionNode;
import com.oracle.truffle.llvm.runtime.LLVMAddress;
import com.oracle.truffle.llvm.runtime.LLVMContext;
import com.oracle.truffle.llvm.runtime.LLVMException;
import com.oracle.truffle.llvm.runtime.memory.LLVMMemory;
import com.oracle.truffle.llvm.runtime.memory.LLVMNativeFunctions;

public final class LLVMLandingpadNode extends LLVMExpressionNode {

    @Child private LLVMExpressionNode allocateLandingPadValue;
    @Child private LLVMNativeFunctions.SulongGetUnwindHeaderNode getUnwindHeader;
    @Child private LLVMNativeFunctions.SulongGetExceptionTypeNode getExceptionType;
    @Children private final LandingpadEntryNode[] entries;
    private final FrameSlot exceptionSlot;
    private final boolean cleanup;

    public LLVMLandingpadNode(LLVMContext context, LLVMExpressionNode allocateLandingPadValue, FrameSlot exceptionSlot, boolean cleanup,
                    LandingpadEntryNode[] entries) {
        this.allocateLandingPadValue = allocateLandingPadValue;
        this.getUnwindHeader = context.getNativeFunctions().createGetUnwindHeader();
        this.getExceptionType = context.getNativeFunctions().createGetExceptionType();
        this.exceptionSlot = exceptionSlot;
        this.cleanup = cleanup;
        this.entries = entries;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        try {
            LLVMException exception = (LLVMException) frame.getObject(exceptionSlot);
            LLVMAddress exceptionInfo = exception.getPointer();
            LLVMAddress unwindHeader = getUnwindHeader.getUnwind(exceptionInfo);
            LLVMAddress thrownTypeID = getExceptionType.get(unwindHeader);

            int clauseId = getEntryIdentifier(frame, exceptionInfo, thrownTypeID);
            if (clauseId == 0 && !cleanup) {
                throw exception;
            } else {
                LLVMAddress executeLLVMAddress = allocateLandingPadValue.executeLLVMAddress(frame);
                LLVMAddress pair0 = executeLLVMAddress;
                LLVMMemory.putAddress(pair0, unwindHeader);
                LLVMAddress pair1 = executeLLVMAddress.increment(LLVMExpressionNode.ADDRESS_SIZE_IN_BYTES);
                LLVMMemory.putI32(pair1, clauseId);
                return executeLLVMAddress;
            }
        } catch (FrameSlotTypeException | UnexpectedResultException e) {
            CompilerDirectives.transferToInterpreter();
            throw new IllegalStateException(e);
        }
    }

    @ExplodeLoop
    private int getEntryIdentifier(VirtualFrame frame, LLVMAddress exceptionInfo, LLVMAddress thrownTypeID) {
        for (int i = 0; i < entries.length; i++) {
            int clauseId = entries[i].getIdentifier(frame, exceptionInfo, thrownTypeID);
            if (clauseId != 0) {
                return clauseId;
            }
        }
        return 0;
    }

    public abstract static class LandingpadEntryNode extends LLVMExpressionNode {

        public abstract int getIdentifier(VirtualFrame frame, LLVMAddress exceptionInfo, LLVMAddress thrownTypeID);

        @Override
        public final Object executeGeneric(VirtualFrame frame) {
            CompilerDirectives.transferToInterpreter();
            throw new IllegalStateException();
        }

    }

    public static final class LandingpadCatchEntryNode extends LandingpadEntryNode {

        @Child private LLVMExpressionNode catchType;
        @Child private LLVMNativeFunctions.SulongCanCatchNode canCatch;

        public LandingpadCatchEntryNode(LLVMContext context, LLVMExpressionNode catchType) {
            this.canCatch = context.getNativeFunctions().createSulongCanCatch();
            this.catchType = catchType;
        }

        @Override
        public int getIdentifier(VirtualFrame frame, LLVMAddress exceptionInfo, LLVMAddress thrownTypeID) {
            LLVMAddress catchAddress = catchType.enforceLLVMAddress(frame);
            if (catchAddress.getVal() == 0) {
                /*
                 * If ExcType is null, any exception matches, so the landing pad should always be
                 * entered. catch (...)
                 */
                return 1;
            }
            if (canCatch.canCatch(exceptionInfo, thrownTypeID, catchAddress) != 0) {
                return (int) catchAddress.getVal();
            }
            return 0;
        }
    }

    public static final class LandingpadFilterEntryNode extends LandingpadEntryNode {

        @Children private final LLVMExpressionNode[] filterTypes;
        @Child private LLVMNativeFunctions.SulongCanCatchNode canCatch;

        public LandingpadFilterEntryNode(LLVMContext context, LLVMExpressionNode[] filterTypes) {
            this.canCatch = context.getNativeFunctions().createSulongCanCatch();
            this.filterTypes = filterTypes;
        }

        @Override
        public int getIdentifier(VirtualFrame frame, LLVMAddress exceptionInfo, LLVMAddress thrownTypeID) {
            if (!filterMatches(frame, exceptionInfo, thrownTypeID)) {
                // when this clause is matched, the selector value has to be negative
                return -1;
            }
            return 0;
        }

        @ExplodeLoop
        private boolean filterMatches(VirtualFrame frame, LLVMAddress exceptionInfo, LLVMAddress thrownTypeID) {
            /*
             * Landingpad should be entered if the exception being thrown does not match any of the
             * types in the list
             */
            for (int i = 0; i < filterTypes.length; i++) {
                LLVMAddress filterAddress = filterTypes[i].enforceLLVMAddress(frame);
                if (filterAddress.getVal() == 0) {
                    /*
                     * If ExcType is null, any exception matches, so the landing pad should always
                     * be entered. catch (...)
                     */
                    return true;
                }
                if (canCatch.canCatch(exceptionInfo, thrownTypeID, filterAddress) != 0) {
                    return true;
                }
            }
            return false;
        }

    }
}