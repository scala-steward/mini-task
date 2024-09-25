package com.melvinlow.task

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.kernel.{Clock, Ref}
import cats.syntax.all.*

import weaver.SimpleIOSuite

object TaskSuite extends SimpleIOSuite {
  test("Run a pure task") {
    val a = Task.pure[IO](1)

    for {
      obtained <- a.run
    } yield expect(obtained.contains(1))
  }

  test("Run an effectful task") {
    val a = Task(IO(1))

    for {
      obtained <- a.run
    } yield expect(obtained.contains(1))
  }

  test("Run a pure chain") {
    val a = Task.pure[IO](1)
    val b = Task.pure[IO](a)(_ + 1)
    val c = Task.pure[IO](b)(_ + 1)

    for {
      obtained <- c.run
    } yield expect(obtained.contains(3))
  }

  test("Run an effectful chain") {
    val a = Task(IO(1))
    val b = Task(a)(k => IO(k + 1))
    val c = Task(b)(k => IO(k + 1))

    for {
      obtained <- c.run
    } yield expect(obtained.contains(3))
  }

  test("Run a pure graph with multiple deps") {
    val a = Task.pure[IO](1)
    val b = Task.pure[IO]((a, a))(_ + _)

    for {
      obtained <- b.run
    } yield expect(obtained.contains(2))
  }

  test("Run an effectful graph with multiple deps") {
    val a = Task(IO(1))
    val b = Task((a, a))((x, y) => IO(x + y))

    for {
      obtained <- b.run
    } yield expect(obtained.contains(2))
  }

  test("Effects should be cached") {
    val counter = Ref.of[IO, Int](0)

    val task = counter.map { c =>
      val x = Task(c.updateAndGet(_ + 1))

      Task.pure[IO]((x, x))((a, b) => a + b)
    }

    for {
      obtained1 <- task.flatMap(_.run)
      obtained2 <- task.flatMap(_.run)
    } yield expect(obtained1.contains(2) && obtained2.contains(2))
  }

  test("Effects should be run in parallel with parRun") {
    val i = Task.pure[IO](1)
    val a = Task(i)(_ => IO.sleep(1.second) *> IO(1))
    val b = Task(i)(_ => IO.sleep(1.second) *> IO(2))
    val c = Task(i)(_ => IO.sleep(1.second) *> IO(3))
    val d = Task((a, b, c))((x, y, z) => IO(x + y + z))

    for {
      (time, result) <- Clock[IO].timed(d.parRun(1))
    } yield expect(time < 2.seconds && result.contains(6))
  }

  test("Effects should be run in sequence with run") {
    val i = Task.pure[IO](1)
    val a = Task(i)(_ => IO.sleep(1.second) *> IO(1))
    val b = Task(i)(_ => IO.sleep(1.second) *> IO(2))
    val c = Task(i)(_ => IO.sleep(1.second) *> IO(3))
    val d = Task((a, b, c))((x, y, z) => IO(x + y + z))

    for {
      (time, result) <- Clock[IO].timed(d.run)
    } yield expect(time > 2.seconds && result.contains(6))
  }

  test("Effect should not execute if condition is not met") {
    val counter = Ref.of[IO, Int](0)

    val result = counter.flatMap { c =>
      val x = Task(c.updateAndGet(_ + 1))
      val y = Task(x) {
        case v if v > 10 => c.updateAndGet(_ + 10)
      }
      val z = Task(y)(_ => c.get)

      for {
        taskResult    <- z.run
        counterResult <- c.get
      } yield (taskResult, counterResult)
    }

    for {
      (taskResult, counterResult) <- result
    } yield expect(taskResult.isEmpty && counterResult == 1)
  }

  test("Should map a task") {
    val i = Task.pure[IO](1)
    val a = Task.pure[IO](i)(_ + 1)
    val b = a.map(_ + 1)

    for {
      result <- b.run
    } yield expect(result.contains(3))
  }

  test("Should tuple a task") {
    val a = Task(IO.sleep(1.second) *> IO(1))
    val b = Task(IO.sleep(1.second) *> IO(2))
    val c = (a, b).mapN(_ + _)

    for {
      (time, result) <- Clock[IO].timed(c.parRun(1))
    } yield expect(time > 1.seconds && result.contains(3))
  }

  test("Should run multiple parallel layers") {
    val l1a = Task(IO.sleep(1.second) *> IO(1))
    val l1b = Task(IO.sleep(1.second) *> IO(1))
    val l1c = Task(IO.sleep(1.second) *> IO(1))

    val l2a =
      Task((l1a, l1b, l1c))((a, b, c) => IO.sleep(1.second) *> IO(a + b + c))
    val l2b =
      Task((l1a, l1b, l1c))((a, b, c) => IO.sleep(1.second) *> IO(a + b + c))
    val l2c =
      Task((l1a, l1b, l1c))((a, b, c) => IO.sleep(1.second) *> IO(a + b + c))

    val l3a =
      Task((l2a, l2b, l2c))((a, b, c) => IO.sleep(1.second) *> IO(a + b + c))
    val l3b =
      Task((l2a, l2b, l2c))((a, b, c) => IO.sleep(1.second) *> IO(a + b + c))
    val l3c =
      Task((l2a, l2b, l2c))((a, b, c) => IO.sleep(1.second) *> IO(a + b + c))

    val result = Task((l3a, l3b, l3c))((a, b, c) => IO(a + b + c))

    for {
      (time, result) <- Clock[IO].timed(result.parRun(1))
    } yield expect(time < 4.seconds && result.contains(27))
  }

  test("Should handle inputs and outputs of different types") {
    val a = Task.pure[IO](1)
    val b = Task.pure[IO]("hello")
    val c = Task.pure[IO](b)(_ => 0)
    val d = Task.pure[IO]((a, c))((x, y) => true)
    val e = Task(IO(1))
    val f = Task(IO("hello"))
    val g = Task(f)(_ => IO(0))
    val h = Task((e, g))((x, y) => IO(true))

    for {
      result1 <- d.run
      result2 <- h.run
    } yield expect(result1.contains(true) && result2.contains(true))
  }
}
