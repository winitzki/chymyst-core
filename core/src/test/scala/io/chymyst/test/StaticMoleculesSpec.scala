package io.chymyst.test

import io.chymyst.jc._
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

import scala.language.postfixOps
import scala.concurrent.duration._

class StaticMoleculesSpec extends FlatSpec with Matchers with TimeLimitedTests with BeforeAndAfterEach {

  val timeLimit = Span(3000, Millis)

  var tp: Pool = _

  override def beforeEach(): Unit = tp = new FixedPool(3)

  override def afterEach(): Unit = tp.shutdownNow()

  behavior of "static molecule emission"

  it should "refuse to emit a static molecule from user code" in {

    val f = b[Unit, String]
    val d = m[String]

    val tp1 = new FixedPool(1) // This test works only with single threads.

    site(tp1, tp1)(
      go { case f(_, r) + d(text) => r(text); d(text) },
      go { case _ => d("ok") } // static reaction
    )

    (1 to 200).foreach { i =>
      val thrown = intercept[Exception] {
        d(s"bad $i") // this "d" should not be emitted, even though "d" is sometimes not in the soup due to reactions!
      }
      thrown.getMessage shouldEqual s"In Site{d + f/B => ...}: Refusing to emit static molecule d(bad $i) because this thread does not run a chemical reaction"
      f.timeout()(500 millis) shouldEqual Some("ok")
    }

    tp1.shutdownNow()
  }

  it should "refuse to emit a static molecule immediately after reaction site" in {

    val tp1 = new FixedPool(1) // This test works only with single threads.

    (1 to 20).foreach { i =>
      val f = b[Unit, String]
      val d = m[String]

      site(tp1, tp1)(
        go { case f(_, r) + d(text) => r(text); d(text) },
        go { case _ => d("ok") } // static reaction
      )

      // Warning: the timeouts might fail the test due to timed tests.
      (1 to 20).foreach { j =>
        val thrown = intercept[Exception] {
          d(s"bad $i $j") // this "d" should not be emitted, even though we are immediately after a reaction site,
          // and even if the initial d() emission was done late
        }
        thrown.getMessage shouldEqual s"In Site{d + f/B => ...}: Refusing to emit static molecule d(bad $i $j) because this thread does not run a chemical reaction"
        f.timeout()(500 millis) shouldEqual Some("ok")
      }

    }

    tp1.shutdownNow()
  }

  it should "signal error when a static molecule is consumed by reaction but not emitted" in {
    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]

      site(tp)(
        go { case c(_, r) + d(_) => r("ok") },
        go { case _ => d() } // static reaction
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B + d => ...}: Incorrect static molecule declaration: static molecule (d) consumed but not emitted by reaction c/B(_) + d(_) => "
  }

  it should "signal error when a static molecule is consumed by reaction and emitted twice" in {
    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]

      site(tp)(
        go { case c(_, r) + d(_) => r("ok"); d() + d() },
        go { case _ => d() } // static reaction
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B + d => ...}: Incorrect static molecule declaration: static molecule (d) emitted more than once by reaction c/B(_) + d(_) => d() + d()"
  }

  it should "signal error when a static molecule is emitted but not consumed by reaction" in {
    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]
      val e = m[Unit]

      site(tp)(
        go { case c(_, r) => r("ok"); d() },
        go { case e(_) => d() },
        go { case _ => d() } // static reaction
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B => ...; e => ...}: Incorrect static molecule declaration: static molecule (d) emitted but not consumed by reaction c/B(_) => d(); static molecule (d) emitted but not consumed by reaction e(_) => d(); Incorrect static molecule declaration: static molecule (d) not consumed by any reactions"
  }

  it should "signal error when a static molecule is emitted by reaction inside a loop to trick static analysis" in {
    val thrown = intercept[Exception] {
      val c = b[Unit, Unit]
      val d = m[Unit]

      site(tp)(
        go { case c(_, r) + d(_) => (1 to 2).foreach(_ => d()); r() },
        go { case _ => d() } // static reaction
      )
      c()
    }
    thrown.getMessage shouldEqual "Error: In Site{c/B + d => ...}: Reaction {c/B(_) + d(_) => d()} with inputs [c/B(), d()] finished without replying to c/B. Reported error: In Site{c/B + d => ...}: Reaction {c/B(_) + d(_) => d()} produced an exception that is internal to Chymyst Core. Input molecules [c/B(), d()] were not emitted again. Message: In Site{c/B + d => ...}: Refusing to emit static molecule d() because this reaction {c/B(_) + d(_) => d()} already emitted it"
  }

  it should "signal error when a static molecule is consumed multiple times by reaction" in {
    val thrown = intercept[Exception] {
      val d = m[Unit]
      val e = m[Unit]

      site(tp)(
        go { case e(_) + d(_) + d(_) => d() },
        go { case _ => d() } // static reaction
      )
    }
    thrown.getMessage shouldEqual "In Site{d + d + e => ...}: Incorrect static molecule declaration: static molecule (d) consumed 2 times by reaction d(_) + d(_) + e(_) => d()"
  }

  it should "signal error when a static molecule is emitted but not bound to any reaction site" in {
    val thrown = intercept[Exception] {
      val d = m[Unit]

      site(tp)(
        go { case _ => d() } // static reaction
      )
    }
    thrown.getMessage shouldEqual "Molecule d is not bound to any reaction site"
  }

  it should "signal error when a static molecule is emitted but not bound to this reaction site" in {
    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]

      site(tp)(
        go { case d(_) => }
      )

      site(tp)(
        go { case c(_, r) => r("ok"); d() },
        go { case _ => d() } // static reaction
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B => ...}: Incorrect static molecule declaration: static molecule (d) emitted but not consumed by reaction c/B(_) => d(); Incorrect static molecule declaration: static molecule (d) not consumed by any reactions"
  }

  it should "signal error when a static molecule is defined by a static reaction with guard" in {
    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]

      val n = 1

      site(tp)(
        go { case c(_, r) + d(_) => r("ok") + d() },
        go { case _ if n > 0 => d() } // static reaction
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B + d => ...}: Static reaction { if(?) => d()} should not have a guard condition"
  }

  it should "refuse to define a blocking molecule as a static molecule" in {
    val c = m[Int]
    val d = m[Int]
    val f = b[Unit, Unit]

    val thrown = intercept[Exception] {
      site(tp)(
        go { case f(_, r) => r() },
        go { case c(x) + d(_) => d(x) },
        go { case _ => f(); d(0) }
      )
    }

    thrown.getMessage shouldEqual "In Site{c + d => ...; f/B => ...}: Refusing to emit molecule f/B() as static (must be a non-blocking molecule)"
  }

  behavior of "volatile reader"

  it should "refuse to read the value of a molecule not bound to a reaction site" in {
    val c = m[Int]

    val thrown = intercept[Exception] {
      c.volatileValue shouldEqual null.asInstanceOf[Int] // If this passes, we are not detecting the fact that c is not bound.
    }

    thrown.getMessage shouldEqual "Molecule c is not bound to any reaction site"
  }

  it should "refuse to read the value of a non-static molecule" in {
    val c = m[Int]
    site(tp)(go { case c(_) => })

    val thrown = intercept[Exception] {
      c.volatileValue
    }

    thrown.getMessage shouldEqual "In Site{c => ...}: volatile reader requested for non-static molecule (c)"
  }

  it should "always be able to read the value of a static molecule early" in {
    def makeNewVolatile(i: Int) = {
      val c = m[Int]
      val d = m[Int]

      site(tp)(
        go { case c(x) + d(_) => d(x) },
        go { case _ => d(i) }
      )

      d.volatileValue
    }

    (1 to 100).foreach { i =>
      makeNewVolatile(i) // This should sometimes throw an exception, so let's make sure it does.
    }
  }

  it should "report that the value of a static molecule is ready even if called early" in {
    def makeNewVolatile(i: Int): Int = {
      val c = m[Int]
      val d = m[Int]

      site(tp)(
        go { case c(x) + d(_) => d(x) },
        go { case _ => d(i) }
      )

      if (d.volatileValue > 0) 0 else 1
    }

    val result = (1 to 100).map { i =>
      makeNewVolatile(i)
    }.sum // how many times we failed

    println(s"Volatile value was not ready $result times")
    result shouldEqual 0
  }

  it should "read the initial value of the static molecule after stabilization" in {
    val d = m[Int]
    val stabilize_d = b[Unit, Unit]
    site(tp)(
      go { case d(x) + stabilize_d(_, r) => r(); d(x) }, // Await stabilizing the presence of d
      go { case _ => d(123) } // static reaction
    )
    stabilize_d()
    d.volatileValue shouldEqual 123
  }

  it should "read the value of the static molecule sometimes inaccurately after many changes" in {
    val d = m[Int]
    val incr = b[Unit, Unit]
    val stabilize_d = b[Unit, Unit]
    val n = 1
    val delta_n = 1000

    site(tp)(
      go { case d(x) + incr(_, r) => r(); d(x + 1) },
      go { case d(x) + stabilize_d(_, r) => d(x); r() }, // Await stabilizing the presence of d
      go { case _ => d(n) } // static reaction
    )
    stabilize_d.timeout()(500 millis)
    d.volatileValue shouldEqual n

    (n + 1 to n + delta_n).map { i =>
      incr.timeout()(500 millis) shouldEqual Some(())

      i - d.volatileValue // this is mostly 0 but sometimes 1
    }.sum should be > 0 // there should be some cases when d.value reads the previous value
  }

  it should "keep the previous value of the static molecule while update reaction is running" in {
    val d = m[Int]
    val e = m[Unit]
    val wait = b[Unit, Unit]
    val incr = b[Unit, Unit]
    val stabilize_d = b[Unit, Unit]

    val tp1 = new FixedPool(1)
    val tp3 = new SmartPool(5)

    site(tp3)(
      go { case wait(_, r) + e(_) => r() } onThreads tp3,
      go { case d(x) + incr(_, r) => r(); wait(); d(x + 1) } onThreads tp1,
      go { case d(x) + stabilize_d(_, r) => d(x); r() } onThreads tp1, // Await stabilizing the presence of d
      go { case _ => d(100) } // static reaction
    )
    stabilize_d.timeout()(500 millis) shouldEqual Some(())
    d.volatileValue shouldEqual 100
    incr.timeout()(500 millis) shouldEqual Some(()) // update started and is waiting for e()
    d.volatileValue shouldEqual 100 // We don't have d() present in the soup, but we can read its previous value.
    e()
    stabilize_d.timeout()(500 millis) shouldEqual Some(())
    d.volatileValue shouldEqual 101

    tp1.shutdownNow()
    tp3.shutdownNow()
  }

  it should "signal error when a static molecule is emitted fewer times than declared" in {
    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]
      val e = m[Unit]
      val f = m[Unit]

      site(tp)(
        go { case d(_) + e(_) + f(_) + c(_, r) => r("ok"); d(); e(); f() },
        go { case _ => if (false) {
          d()
          e()
        }
          f()
        } // static molecules d() and e() will actually not be emitted because of a condition
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B + d + e + f => ...}: Too few static molecules emitted: d emitted 0 times instead of 1, e emitted 0 times instead of 1"
  }

  it should "signal no error (but a warning) when a static molecule is emitted more times than declared" in {
    val c = b[Unit, String]
    val d = m[Unit]
    val e = m[Unit]
    val f = m[Unit]

    val warnings = site(tp)(
      go { case d(_) + e(_) + f(_) + c(_, r) => r("ok"); d(); e(); f() },
      go { case _ => (1 to 2).foreach { _ => d(); e() }; f(); } // static molecules d() and e() will actually be emitted more times
    )
    warnings.errors shouldEqual Seq()
    warnings.warnings shouldEqual Seq("Possibly too many static molecules emitted: d emitted 2 times instead of 1, e emitted 2 times instead of 1")
  }

  it should "detect livelock with static molecules" in {
    val a = m[Unit]
    val c = m[Int]
    val thrown = intercept[Exception] {
      site(tp)(
        go { case a(_) + c(x) if x > 0 => c(1) + a() },
        go { case _ => c(0) }
      )
    }
    thrown.getMessage shouldEqual "In Site{a + c => ...}: Unavoidable livelock: reaction {a(_) + c(x if ?) => c(1) + a()}"
  }

}
