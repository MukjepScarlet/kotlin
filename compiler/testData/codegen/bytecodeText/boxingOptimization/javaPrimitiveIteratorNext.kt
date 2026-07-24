// TARGET_BACKEND: JVM_IR
// FULL_JDK

import java.util.PrimitiveIterator
import java.util.stream.DoubleStream
import java.util.stream.IntStream
import java.util.stream.LongStream

fun testIntStream(): Int {
    val iterator = IntStream.range(0, 3).iterator()
    var sum = 0
    for (x in iterator) {
        sum += x
    }
    return sum
}

fun testLongStream(): Long {
    val iterator = LongStream.range(0, 3).iterator()
    var sum = 0L
    for (x in iterator) {
        sum += x
    }
    return sum
}

fun testDoubleStream(): Double {
    val iterator = DoubleStream.of(1.0, 2.0, 3.0).iterator()
    var sum = 0.0
    for (x in iterator) {
        sum += x
    }
    return sum
}

fun testExplicitNext(iterator: PrimitiveIterator.OfInt): Int {
    return if (iterator.hasNext()) iterator.next() else 0
}

// 0 java/util/Iterator.next \(\)Ljava/lang/Object;
// 0 java/util/PrimitiveIterator\$OfInt.next \(\)Ljava/lang/Integer;
// 0 java/util/PrimitiveIterator\$OfLong.next \(\)Ljava/lang/Long;
// 0 java/util/PrimitiveIterator\$OfDouble.next \(\)Ljava/lang/Double;
// 2 java/util/PrimitiveIterator\$OfInt.nextInt \(\)I
// 1 java/util/PrimitiveIterator\$OfLong.nextLong \(\)J
// 1 java/util/PrimitiveIterator\$OfDouble.nextDouble \(\)D
