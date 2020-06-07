package exercise1

import cats.data.NonEmptyList
import cats.effect.{ExitCode, IO, IOApp, Timer}
import cats.effect.concurrent.{MVar, Ref}
import cats.implicits._

import scala.concurrent.duration._
import scala.util.Random

object ExerciseOne extends IOApp {
  // To start, our requests can be modelled as simple functions.
  // You might want to replace this type with a class if you go for bonuses. Or not.
  type Worker[A, B] = A => IO[B]

  override def run(args: List[String]): IO[ExitCode] = {

    // Sample test pool to play with in IOApp
    val testPool: IO[WorkerPool[Int, Int]] =
      List
        .range(0, 10)
        .traverse(mkWorker)
        .flatMap(WorkerPool.of)

    val normalProgram = testPool
      .flatMap { pool =>
        List.range(0, 100).traverse(pool.exec(_).start).flatMap(_.traverse(_.join))
      }
      .as(ExitCode.Success)

    val bonusProgram = for {
      pool <- WorkerPool.of[Unit, Unit](List(_ => IO(println("foo")) >> IO.sleep(2.seconds)))
      _    <- pool.exec(()).start
      _    <- pool.removeAllWorkers
      _    <- pool.exec(())
    } yield ExitCode.Error

    bonusProgram
//    normalProgram
  }

  // Sample stateful worker that keeps count of requests it has accepted
  def mkWorker(id: Int)(implicit timer: Timer[IO]): IO[Worker[Int, Int]] =
    Ref[IO].of(0).map { counter =>
      def simulateWork: IO[Unit] =
        IO(50 + Random.nextInt(450)).map(_.millis).flatMap(IO.sleep)

      def report: IO[Unit] =
        counter.get.flatMap(i => IO(println(s"Total processed by $id: $i")))

      x =>
        simulateWork >>
          counter.update(_ + 1) >>
          report >>
          IO.pure(x + 1)
    }

  trait WorkerPool[A, B] {
    def exec(a: A): IO[B]

    def add(w: Worker[A, B]): IO[Unit]

    def removeAllWorkers: IO[Unit]
  }

  object WorkerPool {
    private def validateWorkers[A, B](fs: List[Worker[A, B]]): IO[NonEmptyList[Worker[A, B]]] = fs match {
      case Nil    => IO.raiseError(new Exception("Need at least one worker"))
      case h :: t => IO.pure(NonEmptyList.of(h, t: _*))
    }

    // Implement this constructor, and, correspondingly, the interface above.
    // You are free to use named or anonymous classes
    def of[A, B](fs: List[Worker[A, B]]): IO[WorkerPool[A, B]] =
      validateWorkers(fs).flatMap { workers =>
        (Ref.of[IO, Boolean](false), MVar.of[IO, NonEmptyList[Worker[A, B]]](workers)).tupled.map {
          case (emptied, freeWorkers) =>
            def free(w: Worker[A, B]): IO[Unit] =
              emptied.get.flatMap {
                case true => IO.unit
                case false =>
                  for {
                    ws <- freeWorkers.tryTake
                    _  <- ws.fold(freeWorkers.put(NonEmptyList.of(w)))(ws => freeWorkers.put(w :: ws))
                  } yield ()
              }

            new WorkerPool[A, B] {
              override def exec(a: A): IO[B] =
                freeWorkers.take
                  .flatMap { ws =>
                    val w = ws.head
                    val putBack = ws.tail match {
                      case Nil    => IO.unit
                      case h :: t => freeWorkers.put(NonEmptyList.of(h, t: _*))
                    }

                    for {
                      pbR <- putBack.as(w).start
                      wR  <- w(a).start
                      _   <- pbR.join
                      b   <- wR.join.guarantee(free(w).start.void)
                    } yield b
                  }

              override def add(w: Worker[A, B]): IO[Unit] =
                for {
                  ws <- freeWorkers.tryTake
                  _  <- freeWorkers.put(ws.fold(NonEmptyList.of(w))(ws => w :: ws))
                } yield ()

              override def removeAllWorkers: IO[Unit] =
                for {
                  _ <- emptied.set(true)
                  _ <- freeWorkers.tryTake
                } yield ()
            }
        }
      }
  }
}
