package io.chymyst.test

import java.util.concurrent.ConcurrentLinkedQueue

import io.chymyst.jc._
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

import scala.collection.JavaConverters.asScalaIteratorConverter

class Patterns03Spec extends FlatSpec with Matchers with BeforeAndAfterEach {

  var tp: Pool = _

  override def beforeEach(): Unit = {
    tp = new SmartPool(4)
  }

  override def afterEach(): Unit = {
    tp.shutdownNow()
  }

  behavior of "readersWriter"

  it should "implement n shared readers 1 exclusive writer" in {
    val supplyLineSize = 25 // make it high enough to try to provoke race conditions, but not so high that sleeps make the test run too slow.

    sealed trait LockEvent {
      val name: String
      def toString: String
    }
    case class LockAcquisition(override val name: String) extends LockEvent {
      override def toString: String = s"$name enters critical section"
    }
    case class LockRelease(override val name: String) extends LockEvent {
      override def toString: String = s"$name leaves critical section"
    }
    val logFile = new ConcurrentLinkedQueue[LockEvent]

    def useResource(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble * 4.0 + 1.0).toLong)
    def waitForUserRequest(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble * 4.0 + 1.0).toLong)
    def visitCriticalSection(name: String): Unit = {
      logFile.add(LockAcquisition(name))
      useResource()
    }
    def leaveCriticalSection(name: String): Unit = {
      logFile.add(LockRelease(name))
      ()
    }

    val count = m[Int]
    val readerCount = m[Int]

    val check = b[Unit, Unit] // blocking Unit, only blocking molecule of the example.

    val readers = "ABCDEFGH".toCharArray.map(_.toString).toVector // vector of letters as Strings.
    // Making readers a large collection introduces lots of sleeps since we count number of writer locks for simulation and the more readers we have
    // the more total locks and total sleeps simulation will have.

    val readerExit = m[String]
    val reader = m[String]
    val writer = m[String]

    site(tp)(
      go { case writer(name) + readerCount(0) + count(n) if n > 0 =>
        visitCriticalSection(name)
        writer(name)
        count(n - 1)
        leaveCriticalSection(name)
        readerCount(0)
      },
      go { case count(0) + readerCount(0) + check(_, r) => r() }, // readerCount(0) condition ensures we end when all locks are released.

      go { case readerCount(n) + readerExit(name)  =>
        readerCount(n - 1)
        leaveCriticalSection(name)
        waitForUserRequest() // gives a chance to writer to do some work
        reader(name)
      },
      go { case readerCount(n) + reader(name)  =>
        readerCount(n+1)
        visitCriticalSection(name)
        readerExit(name)
      }
    )
    readerCount(0)
    readers.foreach(n => reader(n))
    val writerName = "exclusive-writer"
    writer(writerName)
    count(supplyLineSize)

    check()

    val events: IndexedSeq[LockEvent] = logFile.iterator().asScala.toIndexedSeq
    // events.foreach(println) // comment out to see what's going on.
    val eventsWithIndices: IndexedSeq[(LockEvent, Int)] = events.zipWithIndex

    case class LockEventPair(lock: LockAcquisition, unlock: LockRelease) {
      // this validates that there is never double locking by same lock with assumption that all events
      // pertain to the same lock and consequently that the position of the event increases by 1 all the time
      // (no holes). So events should contain [(..., 0), (..., 1), ..., (..., k), (..., k+1), ...]
      def validateLockUsage(events: IndexedSeq[(LockEvent, Int)]): Unit = {
        events.foreach {
          case (event: LockAcquisition, i: Int) =>
            i + 1 should be < events.size // for additional safety when accessing i+1 below and avoid unplanned exceptions on errors
            events(i + 1)._1 shouldBe LockRelease(event.name)
          case _ => // don't care about LockRelease as it's handled above
        }
      }
    }

    // 1) Number of locks being acquired is same as number being released (the other ones as there are only two types of events)
    val acquiredLocks = events.collect{case (event: LockAcquisition) => 1}.sum
    acquiredLocks * 2 shouldBe events.size

    val (writersByName, readersPart) = eventsWithIndices.partition(_._1.name == writerName) // binary split by predicate

    // 2) Each lock writer acquisition is followed by a writer release before acquiring a new writer (ignoring for now interference of readers)
    // we'll reindex the collection by discarding original indices and introducing new ones with zipWithIndex, intentionally discarding spots
    // occupied by readers. This satisfies the limited functionality and strong assumption of validateLockUsage.
    LockEventPair(LockAcquisition(writerName), LockRelease(writerName)).validateLockUsage(writersByName.map(_._1).zipWithIndex)

    // 3) Similarly, a reader lock is never acquired twice before being released.
    val readersByName = readersPart.groupBy(_._1.name).mapValues(x => x.map(_._1)) // general split into map, dropping the original zipIndex (._2)
    readersByName.foreach {
      case ((name, eventsByName)) =>
        LockEventPair(LockAcquisition(name), LockRelease(name)).validateLockUsage(eventsByName.zipWithIndex)
      // add a new index specific to each new reader collection to facilitate comparison of consecutive lock events.
    }
    // 4) no read lock acquisition while a writer has a lock (we cannot use validateLockUsage here as it's too limiting for this purpose,
    // so we don't remap the indices and keep them as is using eventsWithIndices)
    writersByName.foreach {
      case (event: LockAcquisition, i: Int) =>
        i + 1 should be < events.size // for additional safety when accessing i+1 below and avoid unplanned exceptions on errors
        eventsWithIndices(i + 1)._1 shouldBe LockRelease(event.name)
      case _ => // don't care about LockRelease as it's handled above
    }

  }
}