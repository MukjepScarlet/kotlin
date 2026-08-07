/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen.optimization

import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.tree.MethodNode
import org.jetbrains.org.objectweb.asm.tree.TypeInsnNode
import org.jetbrains.org.objectweb.asm.tree.analysis.Analyzer
import org.jetbrains.org.objectweb.asm.tree.analysis.BasicVerifier
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class CapturedVarsOptimizationMethodTransformerTest {
    @Test
    fun testSwapWithWideCapturedVars() {
        transformAndVerify(wideRefMethod("kotlin/jvm/internal/Ref\$LongRef", "J", Opcodes.LCONST_0))
        transformAndVerify(wideRefMethod("kotlin/jvm/internal/Ref\$DoubleRef", "D", Opcodes.DCONST_0))
    }

    @Test
    fun testSwapBetweenTwoCapturedRefs() {
        val refType = "kotlin/jvm/internal/Ref\$IntRef"
        val method = emptyMethod(maxStack = 2, maxLocals = 4).apply {
            newRef(refType, 0)
            newRef(refType, 1)

            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInsn(Opcodes.SWAP)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitVarInsn(Opcodes.ASTORE, 3)

            visitVarInsn(Opcodes.ALOAD, 2)
            visitInsn(Opcodes.ICONST_1)
            visitFieldInsn(Opcodes.PUTFIELD, refType, "element", "I")
            visitVarInsn(Opcodes.ALOAD, 3)
            visitInsn(Opcodes.ICONST_2)
            visitFieldInsn(Opcodes.PUTFIELD, refType, "element", "I")
            visitInsn(Opcodes.RETURN)
            visitMaxs(maxStack, maxLocals)
            visitEnd()
        }

        transformAndVerify(method)
    }

    private fun wideRefMethod(refType: String, elementType: String, valueOpcode: Int): MethodNode =
        emptyMethod(maxStack = 3, maxLocals = 2).apply {
            newRef(refType, 0)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.SWAP)
            visitVarInsn(Opcodes.ASTORE, 1)
            visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInsn(valueOpcode)
            visitFieldInsn(Opcodes.PUTFIELD, refType, "element", elementType)
            visitInsn(Opcodes.RETURN)
            visitMaxs(maxStack, maxLocals)
            visitEnd()
        }

    private fun emptyMethod(maxStack: Int, maxLocals: Int) =
        MethodNode(Opcodes.API_VERSION, Opcodes.ACC_STATIC, "test", "()V", null, null).apply {
            this.maxStack = maxStack
            this.maxLocals = maxLocals
            visitCode()
        }

    private fun MethodNode.newRef(refType: String, local: Int) {
        visitTypeInsn(Opcodes.NEW, refType)
        visitInsn(Opcodes.DUP)
        visitMethodInsn(Opcodes.INVOKESPECIAL, refType, "<init>", "()V", false)
        visitVarInsn(Opcodes.ASTORE, local)
    }

    private fun transformAndVerify(method: MethodNode) {
        CapturedVarsOptimizationMethodTransformer().transform("Test", method)
        Analyzer(BasicVerifier()).analyze("Test", method)

        assertFalse(method.instructions.toArray().any { it.opcode == Opcodes.SWAP })
        assertFalse(method.instructions.toArray().filterIsInstance<TypeInsnNode>().any { it.opcode == Opcodes.NEW })
    }
}
