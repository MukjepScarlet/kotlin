# kotlinx-io #520 编译器问题交接文档

## 目的与范围

本文记录对 [kotlinx-io #520](https://github.com/Kotlin/kotlinx-io/issues/520) 的纯静态研究结果。问题表现为 `Sink.writeString`/`commonWriteUtf8` 在 JVM 上产生多余的 `Character` 装箱、拆箱，以及 `kotlin.jvm.internal.Ref$IntRef` 堆对象。

本目录未运行构建、测试或编译命令。后续验证应在 fork 的 GitHub Actions 分支中完成。

关联 YouTrack： [KT-88352](https://youtrack.jetbrains.com/issue/KT-88352/JVM-redundant-Character-box-unbox-and-Ref.IntRef-when-a-bound-callable-reference-is-passed-to-an-inline-function-from-within)。

## 最小复现

来自 issue 和 [dronda-t/kotlin-boxing-repro](https://github.com/dronda-t/kotlin-boxing-repro)：

```kotlin
private inline fun scan(n: Int, charAt: (Int) -> Char): Int {
    var acc = 0
    var i = 0
    while (i < n) {
        var c = charAt(i).code
        acc += run { c = charAt(i).code; c }
        i++
    }
    return acc
}

fun good(s: String): Int = scan(s.length, s::get)
fun bad(s: String): Int = run { scan(s.length, s::get) }
```

`good` 和 `bad` 只差外层 `run`。复现仓库给出的版本矩阵为：

```text
1.5.32  clean
1.6.0   clean
1.6.10  clean
1.6.20  bad() box=2 intref=1
1.6.21  bad() box=2 intref=1
1.9.24  bad() box=2 intref=1
2.0.21  bad() box=2 intref=1
2.2.20  bad() box=2 intref=1
2.4.10  bad() box=2 intref=1
```

在 1.6.21 上切换 `-language-version` 无效，而 `-Xuse-old-backend` 为 clean。因此已知问题属于 JVM IR backend，而不是语言前端规则。`bad` 的关键字节码形状是：

```text
new           kotlin/jvm/internal/Ref$IntRef
invokevirtual java/lang/String.charAt:(I)C
invokestatic  java/lang/Character.valueOf:(C)Ljava/lang/Character;
invokevirtual java/lang/Character.charValue:()C
putfield      kotlin/jvm/internal/Ref$IntRef.element:I
```

`String.charAt` 本身已经被直接内联；保留下来的是 `Char` 返回值的装箱/拆箱和捕获变量的 heap cell。

## `skipRichCallables` 的确切作用

当前实现见 [`SharedVariablesLowering.kt`](../compiler/ir/backend.common/src/org/jetbrains/kotlin/backend/common/lower/SharedVariablesLowering.kt)：

* `skipRichCallables` 只影响 `collectSharedVariables()` 遍历 inline call 参数时，是否把 `IrRichFunctionReference.invokeFunction` 或 `IrRichPropertyReference.getterFunction` 作为待跳过的函数 body（约第 89-95 行）。
* JVM 包装类 [`JvmSharedVariablesLowering.kt`](../compiler/ir/backend.jvm/lower/src/org/jetbrains/kotlin/backend/jvm/lower/JvmSharedVariablesLowering.kt) 显式传入 `skipRichCallables = false`，所以当前 JVM lowering 会遍历 rich callable 的 body。
* 该参数是在 2025-10-16 的 [3d3d9cdf](https://github.com/JetBrains/kotlin/commit/3d3d9cdf7f786fe7dbcf315df22f0757bc61805f)（KT-74383）中引入的；问题在 Kotlin 1.6.20 已经出现，时间上不可能是该回归的根因。
* 复现中的 `c` 是普通 inline lambda `run { ... }` 捕获的 `var`，不是 `IrRichFunctionReference` 或 `IrRichPropertyReference` 的 body。把开关设为 true/false 不会把这个普通 lambda 变成 rich callable，也不能解释 1.6.20 的版本分界。

因此，`skipRichCallables` 最多会改变 shared-variable 收集的遍历边界；它不是本 issue 的历史回归点，也不是当前字节码中 `Character.valueOf`/`Ref$IntRef` 的直接开关。

## 更可能的责任链

### 1. callable reference adapter

Kotlin 1.6.20 包含 [9375f419](https://github.com/JetBrains/kotlin/commit/9375f4193651c4967581a95b9e895fd391c00a72)，提交标题为 `JVM_IR KT-50073 inline callable reference adapter into 'invoke'`。该提交在 JVM IR 的 `FunctionReferenceLowering.kt` 中加入 `inlineAdapterCallIfPossible`，把 `ADAPTER_FOR_CALLABLE_REFERENCE` 的单语句 adapter body 内联到生成的 `invoke` 方法。

对 `s::get` 而言，目标返回类型是原生 `Char`，而函数引用接口的 `invoke` 返回 `Object`。adapter/invoke 边界因此需要 `Character.valueOf` 与 `charValue` 适配。正常情况下，后续优化应消除立即成对出现的装箱和拆箱；本复现中这一步没有完成。

### 2. shared variable 与 JVM 字节码优化

`c` 在嵌套 inline lambda 中被重新赋值，IR/JVM codegen 先把它表示成共享变量 `Ref.IntRef.element`。随后 [`CapturedVarsOptimizationMethodTransformer.kt`](../compiler/backend/src/org/jetbrains/kotlin/codegen/optimization/CapturedVarsOptimizationMethodTransformer.kt) 尝试将该对象标量替换为局部 primitive：

* `CapturedVarDescriptor.hazard` 在第 47-52 行决定一个 `Ref` 是否可重写。
* `ReferenceTrackingInterpreter.processRefValueUsage()`（第 96-115 行）只允许 `ALOAD`/`ASTORE`/`DUP`/`POP`、`element` 字段读写和唯一构造调用；其他使用会置 `hazard = true`。
* `assignLocalVars()`（第 139-165 行）若同一个 descriptor 对应多个 LocalVariableTable 区间，也会置 `hazard = true`。
* 只有 `canRewrite()` 成立时，第 185-219 行才会移除 `Ref` 分配并把字段访问改成 `ILOAD`/`ISTORE`。

从 issue 提供的 `javap -c` 可以确认最终重写没有发生，但不能单独确定 `hazard` 是由“非白名单字节码使用”触发，还是由“多个 LocalVariableTable 区间”触发。两者都会产生相同的最终 `Ref$IntRef` 形状。

## 已确定与未确定

### 已确定

1. 回归窗口在 1.6.10 与 1.6.20 之间，且至少持续到 2.4.10。
2. 影响面是 JVM IR backend；1.6.21 的 old backend 对照为 clean。
3. callable reference 已内联到直接 `String.charAt`，问题不是调用目标没有内联。
4. 多余开销由 `Character.valueOf`/`charValue` 和未标量替换的 `Ref$IntRef` 构成。
5. `skipRichCallables` 是 2025 年才引入的遍历选项，不能是 1.6.20 回归根因。

### 仅靠现有静态材料无法确定

无法从 issue 的 `javap -c` 唯一定位 `CapturedVarsOptimizationMethodTransformer` 中触发 `hazard` 的具体条件，也无法仅凭最终字节码断言应修复 adapter lowering、shared-variable lowering，还是后续 redundant-boxing 优化的契约。需要带局部变量表和更早阶段信息的产物。

## Fork GitHub Actions 验证计划

所有以下步骤在用户 fork 的分支中执行，不在本地运行：

1. 建立 Kotlin 编译器版本矩阵：`1.6.10`、`1.6.20`、`1.6.21`、`2.4.10`，另加 `1.6.21 -Xuse-old-backend` 对照。
2. 对每个版本编译最小复现，并保存 `javap -c -v -l` 输出；`-v -l` 用于保留 StackMapTable、LocalVariableTable 和行号范围。
3. 自动统计 `Character.valueOf`、`Character.charValue`、`Ref$IntRef`，同时分别截取 `good`/`bad` 方法，避免把无调用的 standalone inline 函数体误计入。
4. 若 workflow 能取得 compiler debug/IR dump，再比较 1.6.10 与 1.6.20 在 callable-reference adapter、`SharedVariablesLowering` 和 JVM inline 后的形状。
5. 对 `bad` 的 `Ref$IntRef`，检查：
   * 是否存在多个覆盖同一 descriptor 的 LocalVariableTable 区间；
   * 是否存在 `processRefValueUsage()` 白名单之外的引用使用。
6. 将结果、编译器发行版下载地址和完整 disassembly 作为 Actions artifact，供后续修复 PR 复核。

建议 workflow 的最小命令是 `kotlinc`、`javap -c -v -l` 和文本统计；不要在本地复制执行。

## 当前判断

首个相关回归候选是 9375f419 引入的 callable-reference adapter 内联；最接近可观察故障的优化失败点是 `CapturedVarsOptimizationMethodTransformer` 未能消除 `Ref.IntRef`，从而连带保留 `Character` 装箱/拆箱。静态研究尚不能给出应修改的唯一源码行；下一步必须用 fork 上 Actions 生成的带 `-l` 字节码或 JVM IR/inline dump 来区分上述两个 `hazard` 分支。
