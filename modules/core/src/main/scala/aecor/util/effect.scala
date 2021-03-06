package aecor.util

import cats.effect.{ Effect, IO, LiftIO }

import scala.concurrent.Future

object effect {
  implicit final class AecorIOOps(val self: IO.type) extends AnyVal {
    final def fromEffect[F[_]: Effect, A](fa: F[A]): IO[A] =
      IO.async { cb =>
        Effect[F].runAsync(fa)(x => IO(cb(x))).unsafeRunAsync(_ => ())
      }
  }

  implicit final class AecorEffectOps[F[_], A](val self: F[A]) extends AnyVal {
    @inline final def unsafeToFuture()(implicit F: Effect[F]): Future[A] =
      toIO.unsafeToFuture()
    @inline final def toIO(implicit F: Effect[F]): IO[A] =
      IO.fromEffect(self)
  }

  implicit final class AecorAsyncTCOps[F[_]](val self: LiftIO[F]) extends AnyVal {
    def fromFuture[A](future: => Future[A]): F[A] =
      IO.fromFuture(IO(future)).to(self)
  }
}
