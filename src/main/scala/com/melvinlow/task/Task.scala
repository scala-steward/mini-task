package com.melvinlow.task

import cats.effect.kernel.{Concurrent, Deferred}
import cats.effect.std.MapRef
import cats.syntax.all.*
import cats.{Applicative, Parallel}

final case class Task[
  F[_],
  O
] private (
  dependencies: List[Task[F, ?]],
  pf: PartialFunction[Tuple, F[O]]
) derives CanEqual {

  /** Run the task and all dependencies in parallel, caching intermediary
    * results to prevent duplicate computation.
    *
    * The result will be `F[None]` if the task or any of its upstream
    * dependencies are not executed due to conditional logic in the partial
    * functions.
    *
    * @param shards
    *   The number of shards to use for the cache. Increase this value to reduce
    *   contention caused by multiple tasks trying to write to the cache at the
    *   same time.
    */
  def parRun(shards: Int)(using Concurrent[F], Parallel[F]): F[Option[O]] =
    for {
      cache <- MapRef.ofShardedImmutableMap[F, Task[F, ?], Deferred[
        F,
        Option[?]
      ]](shards)

      result <- parRun(cache)
    } yield result

  def parRun(cache: MapRef[
    F,
    Task[F, ?],
    Option[Deferred[F, Option[?]]]
  ])(using Concurrent[F], Parallel[F]): F[Option[O]] = {
    val deferred = Deferred[F, Option[?]].flatMap { d =>
      cache(this).flatModify {
        case None =>
          d.some -> parExecute(cache).flatMap(v => d.complete(v).as(d))
        case Some(e) => e.some -> e.pure[F]
      }
    }

    val result: F[Option[?]] = deferred.flatMap(_.get)
    result.asInstanceOf[F[Option[O]]]
  }

  /** Run the task and all dependencies sequentially, caching intermediary
    * results to prevent duplicate computation.
    *
    * The result will be `F[None]` if the task or any of its upstream
    * dependencies are not executed due to conditional logic in the partial
    * functions.
    *
    * @param shards
    *   The number of shards to use for the cache. Increase this value to reduce
    *   contention caused by multiple tasks trying to write to the cache at the
    *   same time.
    */
  def run(using Concurrent[F]): F[Option[O]] =
    for {
      cache <- MapRef.ofSingleImmutableMap[F, Task[F, ?], Deferred[
        F,
        Option[?]
      ]]()

      result <- run(cache)
    } yield result

  def run(cache: MapRef[
    F,
    Task[F, ?],
    Option[Deferred[F, Option[?]]]
  ])(using Concurrent[F]): F[Option[O]] = {
    val deferred = Deferred[F, Option[?]].flatMap { d =>
      cache(this).flatModify {
        case None =>
          d.some -> execute(cache).flatMap(v => d.complete(v).as(d))
        case Some(e) => e.some -> e.pure[F]
      }
    }

    val result: F[Option[?]] = deferred.flatMap(_.get)
    result.asInstanceOf[F[Option[O]]]
  }

  private def parExecute(
    cache: MapRef[
      F,
      Task[F, ?],
      Option[Deferred[F, Option[?]]]
    ]
  )(using Concurrent[F], Parallel[F]): F[Option[O]] = {
    val deps: F[List[Option[?]]] = dependencies.parTraverse { d =>
      d.parRun(cache).asInstanceOf[F[Option[?]]]
    }

    val args: F[Option[List[?]]] = deps.map(_.sequence)

    args.flatMap {
      case None => None.pure[F]
      case Some(f) =>
        val t = Tuple.fromArray(f.toArray)
        pf.lift(t).sequence
    }
  }

  private def execute(
    cache: MapRef[
      F,
      Task[F, ?],
      Option[Deferred[F, Option[Any]]]
    ]
  )(using Concurrent[F]): F[Option[O]] = {
    val deps: F[List[Option[?]]] = dependencies.traverse { d =>
      d.run(cache).asInstanceOf[F[Option[?]]]
    }

    val args: F[Option[List[?]]] = deps.map(_.sequence)

    args.flatMap {
      case None => None.pure[F]
      case Some(f) =>
        val t = Tuple.fromArray(f.toArray)
        pf.lift(t).sequence
    }
  }
}

object Task {
  given applicativeParInstance[F[_]: Applicative]
    : Applicative[[O] =>> Task[F, O]]
  with {
    override def ap[A, B](ff: Task[F, A => B])(fa: Task[F, A]): Task[F, B] =
      Task(
        ff.dependencies ++ fa.dependencies,
        Function.unlift {
          inputs =>
            val ffd =
              Tuple.fromIArray(inputs.toIArray.take(ff.dependencies.length))
            val fbd =
              Tuple.fromIArray(inputs.toIArray.drop(ff.dependencies.length))

            val rf: Option[F[A => B]] = ff.pf.lift(ffd)
            val rb: Option[F[A]]      = fa.pf.lift(fbd)
            (rf, rb).mapN(_ `ap` _)
        }
      )

    override def product[A, B](
      fa: Task[F, A],
      fb: Task[F, B]
    ): Task[F, (A, B)] =
      Task(
        fa.dependencies ++ fa.dependencies,
        Function.unlift {
          inputs =>
            val fad =
              Tuple.fromIArray(inputs.toIArray.take(fa.dependencies.length))
            val fbd =
              Tuple.fromIArray(inputs.toIArray.drop(fa.dependencies.length))

            val ra: Option[F[A]] = fa.pf.lift(fad)
            val rb: Option[F[B]] = fb.pf.lift(fbd)
            (ra, rb).mapN(_ `product` _)
        }
      )

    override def map[A, B](fa: Task[F, A])(f: A => B): Task[F, B] =
      Task(fa.dependencies, fa.pf.andThen(_.map(f)))

    override def pure[A](x: A): Task[F, A] = Task(Nil, _ => x.pure[F])
  }

  opaque type TaskPureOps[F[_]] = Boolean
  object TaskPureOps {
    inline def apply[F[_]]: TaskPureOps[F] = true

    extension [F[_]](dummy: TaskPureOps[F]) {
      def apply[O](value: O)(using Applicative[F]): Task[F, O] =
        Task(Nil, _ => value.pure[F])

      def apply[D <: Tuple, O](dependencies: D)(
        pf: PartialFunction[Inputs[F, D], O]
      )(using Applicative[F]): Task[F, O] = {
        val dependencies_ = dependencies.toList.asInstanceOf[List[Task[F, ?]]]
        val pf_           = pf.asInstanceOf[PartialFunction[Tuple, O]]
        Task(dependencies_, pf_.andThen(_.pure[F]))
      }

      def apply[I, O](dependency: Task[F, I])(
        pf: PartialFunction[I, O]
      )(using Applicative[F]): Task[F, O] = {
        val dependencies_ = List(dependency).asInstanceOf[List[Task[F, ?]]]
        val head: PartialFunction[Any, I] = _.asInstanceOf[Tuple1[I]].head
        val pf_ = pf
          .compose(head)
          .andThen(_.pure[F])
          .asInstanceOf[PartialFunction[Tuple, F[O]]]

        Task(dependencies_, pf_)
      }
    }
  }

  /** Construct a task from pure values.
    */
  inline def pure[F[_]] = TaskPureOps.apply[F]

  def apply[F[_], O](value: F[O]): Task[F, O] =
    Task(Nil, _ => value)

  def apply[F[_], D <: Tuple, I, O](dependencies: Task[F, I] *: D)(
    pf: PartialFunction[Inputs[F, Task[F, I] *: D], F[O]]
  ): Task[F, O] = {
    val dependencies_ = dependencies.toList.asInstanceOf[List[Task[F, ?]]]
    val pf_           = pf.asInstanceOf[PartialFunction[Tuple, F[O]]]
    Task(dependencies_, pf_)
  }

  def apply[F[_], I, O](dependency: Task[F, I])(
    pf: PartialFunction[I, F[O]]
  ): Task[F, O] = {
    val dependencies_ = List(dependency).asInstanceOf[List[Task[F, ?]]]
    val head: PartialFunction[Any, I] = _.asInstanceOf[Tuple1[I]].head
    val pf_ = pf.compose(head).asInstanceOf[PartialFunction[Tuple, F[O]]]

    Task(dependencies_, pf_)
  }

  type Inputs[F[_], D <: Tuple] <: Tuple = D match {
    case EmptyTuple      => EmptyTuple
    case Task[F, h] *: t => h *: Inputs[F, t]
  }
}
