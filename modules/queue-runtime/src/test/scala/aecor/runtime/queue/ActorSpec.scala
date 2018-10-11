package aecor.runtime.queue

import aecor.runtime.queue.Actor.Receive
import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.implicits._
import fs2.concurrent.Queue

class ActorSpec extends TestSuite {

  test("Actor should receive sent messages") {
    for {
      queue <- Queue.unbounded[IO, Int]
      actor <- Actor.create[IO, Int](_ => IO.pure(queue.enqueue1))
      _ <- actor.send(1)
      _ <- actor.send(2)
      result <- queue.dequeue.take(2).compile.toVector
    } yield assert(result == Vector(1, 2))
  }

  test("Actor should receive messages sent to itself") {
    for {
      queue <- Queue.unbounded[IO, Int]
      actor <- Actor.create[IO, Int](ctx => IO.pure(x => queue.enqueue1(x) >> ctx.send(x + 1)))
      _ <- actor.send(1)
      result <- queue.dequeue.take(2).compile.toVector
    } yield assert(result == Vector(1, 2))
  }

  test("Actor should terminate and complete subsequent tell with error") {
    for {
      actor <- Actor.create[IO, Int](_ => IO.pure(_ => ().pure[IO]))
      _ <- actor.send(1)
      _ <- actor.terminate
      result <- actor.send(2).attempt
    } yield assert(result.left.get.isInstanceOf[IllegalStateException])
  }

  test("Actor should restart after receive error ") {
    for {
      queue <- Queue.unbounded[IO, Int]
      actor <- Actor.create[IO, Unit] { _ =>
                Ref[IO].of(0).map { ref =>
                  Receive[Unit] { _ =>
                    ref.get.flatMap {
                      case 1 => IO.raiseError(new RuntimeException("Terminate"))
                      case c => queue.enqueue1(c) >> ref.set(c + 1)
                    }
                  }
                }
              }
      _ <- actor.send(())
      _ <- actor.send(())
      _ <- actor.send(())
      _ <- actor.send(())
      result <- queue.dequeue.take(2).compile.toVector
    } yield assert(result == Vector(0, 0))
  }

}