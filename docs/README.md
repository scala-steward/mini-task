# Mini Task

_Mini Task_ is a lightweight task graph library for Scala 3,
designed for declaring and executing tasks with dependencies in a
type-safe and concurrent manner.

Main features:

- _Type-safe task dependencies_: The compiler ensures that the number and types of dependencies match the inputs of the provided task function.
- _Concurrent execution_: Tasks are run concurrently whenever possible.
- _Conditional execution_: Tasks can be conditionally executed based on the input values.
- _Caching_: Intermediate results are automatically cached so each task is executed only once per run.

## Installation

This library is built for Scala 3.

```scala
libraryDependencies += "com.melvinlow" %% "mini-task" % "<VERSION>"
```

## Example

```scala mdoc:invisible
import cats.effect.*
import cats.effect.std.*
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
```

Calculate `(1 + 2) * (3 + 4)`:

```scala mdoc:silent
import com.melvinlow.task.Task

val a = Task.pure[IO](1)
val b = Task.pure[IO](2)
val c = Task.pure[IO](3)
val d = Task.pure[IO](4)

// Define tasks for addition
val sum1 = Task.pure[IO]((a, b))(_ + _)  // 1 + 2
val sum2 = Task.pure[IO]((c, d))(_ + _)  // 3 + 4

// Define a task for multiplication
val product = Task.pure[IO]((sum1, sum2))(_ * _)  // (1 + 2) * (3 + 4)
```

To execute the task graph:

```scala mdoc
product.parRun(shards = 1).unsafeRunSync()
```

## Conditional Execution

Each task takes a `PartialFunction` that determines whether the task should be executed based on the input values.
If a task is not executed, all downstream tasks that depend on it will also not be executed.

In the following example, `k` will not be executed because it depends on `j`, which will only run if `i > 10`:

```scala mdoc:silent
val i = Task.pure[IO](1)

val j = Task.pure[IO](i) {
  case i if i > 10 => i + 1 // only execute if i > 10
}

val k = Task.pure[IO](j)(_ + 1)
```

```scala mdoc
k.parRun(shards = 1).unsafeRunSync() // result of k is empty
```

## Effects

The compiler can infer the effect type when using tasks with effectful functions. Some syntax examples:

```scala mdoc:silent
val x = Task(IO(1)) // 1
val y = Task(x)(v => IO(v + 1)) // 1 + 1
val z = Task((y, y))((v1, v2) => IO(v1 + v2)) // (1 + 1) + (1 + 1)
```

```scala mdoc
z.parRun(shards = 1).unsafeRunSync()
```

## Type Safety

All tasks are type-checked at compile time to ensure that
the number and types of dependencies match the inputs of the provided task function.

The following code does not compile because the order of the dependencies does not match the order of the inputs:

```scala mdoc:fail
val strTask = Task.pure[IO]("hello")
val intTask = Task.pure[IO](1)

// swap the order of the tasks
Task.pure[IO]((intTask, strTask)) { (s, i) =>
  s.length + " " + i
}
```

The following code does not compile because the number of inputs is wrong:

```scala mdoc:fail
val intTask = Task.pure[IO](1)

// Pass in 1 task instead of 2
Task.pure[IO](intTask)(_ + _)
```

## Caching

All tasks will only be executed once per run, no matter how many times they are referenced.

```scala mdoc:silent
var counter = 0 // Don't try this at home

val inc = Task(IO {
  counter += 1
  counter
})
```

```scala mdoc
// Create a task that calls inc 3 times
Task((inc, inc, inc))(_.pure[IO]).run.unsafeRunSync()

// counter is only incremented once
counter
```
