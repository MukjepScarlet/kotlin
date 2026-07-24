/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.intrinsics

import org.jetbrains.kotlin.backend.jvm.codegen.BlockInfo
import org.jetbrains.kotlin.backend.jvm.codegen.ExpressionCodegen
import org.jetbrains.kotlin.backend.jvm.codegen.MaterialValue
import org.jetbrains.kotlin.backend.jvm.codegen.PromisedValue
import org.jetbrains.kotlin.builtins.StandardNames.COLLECTIONS_PACKAGE_FQ_NAME
import org.jetbrains.kotlin.fileClasses.internalNameWithoutInnerClasses
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.jvm.JvmPrimitiveType
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type

/**
 * Lowers `next()` on specialized primitive iterators to the unboxed `nextXxx()` call.
 *
 * Covers:
 * - Kotlin `kotlin.collections.*Iterator` (e.g. `IntIterator.next` → `nextInt()`)
 * - Java 8 `java.util.PrimitiveIterator.OfInt` / `OfLong` / `OfDouble` when the call is resolved
 *   to those types, or when the dispatch receiver's static type is one of them
 *   (e.g. `val it = intStream.iterator(); it.next()` / `for (x in it)`).
 *
 * When registered for [kotlin.collections.Iterator.next] / [MutableIterator.next], this intrinsic
 * only applies if the receiver is a Java primitive iterator; otherwise it returns `null` so
 * normal codegen runs.
 */
object IteratorNext : IntrinsicMethod() {
    private val JAVA_OF_INT = FqName("java.util.PrimitiveIterator.OfInt")
    private val JAVA_OF_LONG = FqName("java.util.PrimitiveIterator.OfLong")
    private val JAVA_OF_DOUBLE = FqName("java.util.PrimitiveIterator.OfDouble")

    private val KOTLIN_PRIMITIVE_ITERATOR_OWNERS: Set<FqName> = setOf(
        COLLECTIONS_PACKAGE_FQ_NAME.child(Name.identifier("BooleanIterator")),
        COLLECTIONS_PACKAGE_FQ_NAME.child(Name.identifier("CharIterator")),
        COLLECTIONS_PACKAGE_FQ_NAME.child(Name.identifier("ByteIterator")),
        COLLECTIONS_PACKAGE_FQ_NAME.child(Name.identifier("ShortIterator")),
        COLLECTIONS_PACKAGE_FQ_NAME.child(Name.identifier("IntIterator")),
        COLLECTIONS_PACKAGE_FQ_NAME.child(Name.identifier("LongIterator")),
        COLLECTIONS_PACKAGE_FQ_NAME.child(Name.identifier("FloatIterator")),
        COLLECTIONS_PACKAGE_FQ_NAME.child(Name.identifier("DoubleIterator")),
    )

    override fun invoke(expression: IrFunctionAccessExpression, codegen: ExpressionCodegen, data: BlockInfo): PromisedValue? {
        val specialization = resolveSpecialization(expression) ?: return null

        val signature = codegen.methodSignatureMapper.mapSignatureSkipGeneric(expression.symbol.owner)
        // Prefer the specialized primitive return type. `Iterator.next` is typed as Object/boxed on JVM,
        // so we cannot derive the unboxed type from that signature when rewriting to nextInt/nextLong/nextDouble.
        val type = specialization.primitiveReturnType
        val newSignature = signature.newReturnType(type)

        val callable = IntrinsicFunction.create(
            expression,
            newSignature,
            codegen.classCodegen,
            listOf(specialization.ownerType),
        ) { iv ->
            if (specialization.isInterface) {
                iv.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    specialization.ownerType.internalName,
                    specialization.methodName,
                    "()" + type.descriptor,
                    true,
                )
            } else {
                iv.invokevirtual(
                    specialization.ownerType.internalName,
                    specialization.methodName,
                    "()" + type.descriptor,
                    false,
                )
            }
        }
        callable.invoke(codegen.mv, codegen, data, expression)
        // expression.type may still be a boxed/platform type from Iterator.next; stack value is unboxed.
        return MaterialValue(codegen, type, expression.type)
    }

    private data class Specialization(
        val ownerType: Type,
        val methodName: String,
        val isInterface: Boolean,
        val primitiveReturnType: Type,
    )

    private fun resolveSpecialization(expression: IrFunctionAccessExpression): Specialization? {
        // Prefer dispatch receiver static type (scheme B), e.g. OfInt-typed variables.
        javaPrimitiveIteratorSpecialization(expression.dispatchReceiver?.type)?.let { return it }

        val ownerFqName = expression.symbol.owner.parentAsClass.fqNameWhenAvailable ?: return null

        javaPrimitiveIteratorSpecialization(ownerFqName)?.let { return it }

        if (ownerFqName in KOTLIN_PRIMITIVE_ITERATOR_OWNERS) {
            // Derive primitive type from the Kotlin iterator class name (BooleanIterator → boolean, ...).
            val primitiveName = ownerFqName.shortName().asString().removeSuffix("Iterator")
            val primitiveReturnType = jvmPrimitiveTypeByKotlinName(primitiveName)
            val primitiveClassName = getKotlinPrimitiveClassName(primitiveReturnType)
            return Specialization(
                ownerType = getKotlinPrimitiveIteratorType(primitiveClassName),
                methodName = "next${primitiveClassName.asString()}",
                isInterface = false,
                primitiveReturnType = primitiveReturnType,
            )
        }

        return null
    }

    private fun javaPrimitiveIteratorSpecialization(type: IrType?): Specialization? {
        if (type == null) return null
        return javaPrimitiveIteratorSpecialization(type.classOrNull?.owner)
    }

    private fun javaPrimitiveIteratorSpecialization(irClass: IrClass?): Specialization? {
        if (irClass == null) return null
        javaPrimitiveIteratorSpecialization(irClass.fqNameWhenAvailable)?.let { return it }
        // Subtypes of OfInt / OfLong / OfDouble.
        for (superType in irClass.superTypes) {
            javaPrimitiveIteratorSpecialization(superType.classOrNull?.owner)?.let { return it }
        }
        return null
    }

    private fun javaPrimitiveIteratorSpecialization(fqName: FqName?): Specialization? =
        when (fqName) {
            JAVA_OF_INT -> Specialization(
                ownerType = Type.getObjectType("java/util/PrimitiveIterator\$OfInt"),
                methodName = "nextInt",
                isInterface = true,
                primitiveReturnType = Type.INT_TYPE,
            )
            JAVA_OF_LONG -> Specialization(
                ownerType = Type.getObjectType("java/util/PrimitiveIterator\$OfLong"),
                methodName = "nextLong",
                isInterface = true,
                primitiveReturnType = Type.LONG_TYPE,
            )
            JAVA_OF_DOUBLE -> Specialization(
                ownerType = Type.getObjectType("java/util/PrimitiveIterator\$OfDouble"),
                methodName = "nextDouble",
                isInterface = true,
                primitiveReturnType = Type.DOUBLE_TYPE,
            )
            else -> null
        }

    // Type.CHAR_TYPE -> "Char"
    private fun getKotlinPrimitiveClassName(type: Type): Name {
        return JvmPrimitiveType.get(type.className).primitiveType.typeName
    }

    // "Char" -> type for kotlin.collections.CharIterator
    private fun getKotlinPrimitiveIteratorType(primitiveClassName: Name): Type {
        val iteratorName = Name.identifier(primitiveClassName.asString() + "Iterator")
        return Type.getObjectType(COLLECTIONS_PACKAGE_FQ_NAME.child(iteratorName).internalNameWithoutInnerClasses)
    }

    private fun jvmPrimitiveTypeByKotlinName(kotlinName: String): Type =
        when (kotlinName) {
            "Boolean" -> Type.BOOLEAN_TYPE
            "Char" -> Type.CHAR_TYPE
            "Byte" -> Type.BYTE_TYPE
            "Short" -> Type.SHORT_TYPE
            "Int" -> Type.INT_TYPE
            "Long" -> Type.LONG_TYPE
            "Float" -> Type.FLOAT_TYPE
            "Double" -> Type.DOUBLE_TYPE
            else -> error("Unexpected primitive iterator type name: $kotlinName")
        }
}
