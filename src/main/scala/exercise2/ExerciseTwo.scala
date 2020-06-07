package exercise2

import cats.effect.{ExitCode, IO, IOApp}

import scala.util.Random
import scala.concurrent.duration._
import cats.data._
import cats.implicits._
import cats.effect.{ExitCase, IO, Timer}
import cats.effect.concurrent.Deferred

object ExerciseTwo extends IOApp {
  case class Data(source: String, body: String)

  def provider(name: String)(implicit timer: Timer[IO]): IO[Data] = {
    val proc = for {
      dur <- IO { Random.nextInt(500) }
      _   <- IO.sleep { (100 + dur).millis }
      _   <- IO { if (Random.nextBoolean()) throw new Exception(s"Error in $name") }
      txt <- IO { Random.alphanumeric.take(16).mkString }
    } yield Data(name, txt)

    proc.guaranteeCase {
      case ExitCase.Completed => IO { println(s"$name request finished") }
      case ExitCase.Canceled  => IO { println(s"$name request canceled") }
      case ExitCase.Error(ex) => IO { println(s"$name errored") }
    }
  }

  // Use this class for reporting all failures.
  case class CompositeException(ex: NonEmptyList[Throwable]) extends Exception("All race candidates have failed")

  // Implement this function:
  def raceToSuccess[A](ios: NonEmptyList[IO[A]]): IO[A] = Deferred[IO, A].flatMap { deferred =>
    val startAll = ios.parTraverse { io =>
      io.flatMap(deferred.complete).attempt.start
    }
    startAll.flatMap { fibers =>
      val getResult = deferred.get.map(_.some)
      val allFailed = for {
        (errors, successes) <- fibers.traverse(_.join).map(_.toList.partitionEither(identity))
        maybeA              <- if (successes.isEmpty) IO(println(errors.map(_.getMessage).mkString("\n"))).as(none[A]) else getResult
      } yield maybeA

      IO.race(deferred.get, allFailed).flatMap {
        case Left(a)        => fibers.parTraverse(_.cancel).as(a)
        case Right(Some(a)) => IO.pure(a)
        case Right(None)    => IO.raiseError(new Exception("Finished with error"))
      }
    }
  }

  override def run(args: List[String]): IO[ExitCode] = {
    // In your IOApp, you can use the following sample method list

    val methods: NonEmptyList[IO[Data]] = NonEmptyList
      .of(
        "memcached",
        "redis",
        "postgres",
        "mongodb",
        "hdd",
        "aws"
      )
      .map(provider)

    raceToSuccess(methods).as(ExitCode.Success)
  }
}
