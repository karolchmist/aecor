package aecor.runtime.queue

import cats.effect.implicits._
import cats.effect.Concurrent
import cats.implicits._
import fs2.concurrent.{Queue, SignallingRef}
import fs2._

private[queue] trait Actor[F[_], A] { outer =>
  def send(message: A): F[Unit]
  def terminate: F[Unit]
  def watchTermination: F[Unit]
  final def contramap[B](f: B => A): Actor[F, B] = new Actor[F, B] {
    override def send(message: B): F[Unit] = outer.send(f(message))
    override def terminate: F[Unit] = outer.terminate
    override def watchTermination: F[Unit] = outer.watchTermination
  }
}

private[queue] object Actor {
  object Receive {
    final class Builder[A] {
      def apply[F[_]](f: A => F[Unit]): A => F[Unit] = f
    }
    private val instance: Builder[Any] = new Builder[Any]
    def apply[A]: Builder[A] = instance.asInstanceOf[Builder[A]]
  }
  trait Context[F[_], A] {
    def send(a: A): F[Unit]
  }
  //TODO: Make handler a Resource
  def create[F[_], A](
    init: Context[F, A] => F[A => F[Unit]]
  )(implicit F: Concurrent[F]): F[Actor[F, A]] =
    for {
      mailbox <- Queue.unbounded[F, A]
      killed <- SignallingRef[F, Boolean](false)
      actorContext = new Context[F, A] {
        override def send(a: A): F[Unit] =
          killed.get.ifM(
            F.raiseError(new IllegalStateException("Actor terminated")),
            mailbox.enqueue1(a)
          )
      }

      runloop <- {

        def run: Stream[F, Unit] = Stream.force {
          init(actorContext).map { handle =>
            mailbox.dequeue.interruptWhen(killed).evalMap(handle)
          }
        }.handleErrorWith(_ => run)

        run
          .compile
          .drain
          .start
      }
    } yield
      new Actor[F, A] {
        override def send(message: A): F[Unit] =
          actorContext.send(message)

        override def terminate: F[Unit] =
          killed.set(true) >> watchTermination.attempt.void

        override def watchTermination: F[Unit] =
          runloop.join
      }

  def ignore[F[_], A](implicit F: Concurrent[F]): F[Actor[F, A]] =
    create[F, A](_ => { _: A =>
      ().pure[F]
    }.pure[F])

}