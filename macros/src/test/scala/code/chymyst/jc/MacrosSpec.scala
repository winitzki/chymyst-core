package code.chymyst.jc

import Core._
import Macros.{getName, rawTree, m, b, go}
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class MacrosSpec extends FlatSpec with Matchers with BeforeAndAfterEach {

  val warmupTimeMs = 200L

  var tp0: Pool = _

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  override def beforeEach(): Unit = {
    tp0 = new FixedPool(4)
  }

  override def afterEach(): Unit = {
    tp0.shutdownNow()
  }

  behavior of "macros for defining new molecule emitters"

  it should "fail to compute correct names when molecule emitters are defined together" in {
    val (counter, fetch) = (m[Int], b[Unit, String])

    (counter.name, fetch.name) shouldEqual (("x$1", "x$1"))
  }

  it should "compute correct names and classes for molecule emitters" in {
    val a = m[Option[(Int,Int,Map[String,Boolean])]] // complicated type

    a.isInstanceOf[M[_]] shouldEqual true
    a.isInstanceOf[E] shouldEqual false
    a.toString shouldEqual "a"

    val s = b[Map[(Boolean, Unit), Seq[Int]], Option[List[(Int, Option[Map[Int, String]])]]] // complicated type

    s.isInstanceOf[B[_,_]] shouldEqual true
    s.isInstanceOf[EB[_]] shouldEqual false
    s.isInstanceOf[BE[_]] shouldEqual false
    s.isInstanceOf[EE] shouldEqual false
    s.toString shouldEqual "s/B"
  }

  it should "create an emitter of class E for m[Unit]" in {
    val a = m[Unit]
    a.isInstanceOf[E] shouldEqual true
  }

  it should "create an emitter of class BE[Int] for b[Int, Unit]" in {
    val a = b[Int, Unit]
    a.isInstanceOf[BE[Int]] shouldEqual true
  }

  it should "create an emitter of class EB[Int] for b[Unit, Int]" in {
    val a = b[Unit, Int]
    a.isInstanceOf[EB[Int]] shouldEqual true
  }

  it should "create an emitter of class EE for b[Unit, Unit]" in {
    val a = b[Unit, Unit]
    a.isInstanceOf[EE] shouldEqual true
  }

  behavior of "macros for inspecting a reaction body"

  it should "inspect reaction body with default clause that declares a singleton" in {
    val a = m[Int]

    val reaction = go { case _ => a(123) }

    reaction.info.inputs shouldEqual Nil
    reaction.info.hasGuard.knownFalse shouldEqual true
    reaction.info.outputs shouldEqual Some(List(OutputMoleculeInfo(a, ConstOutputValue(123))))
  }

  it should "inspect reaction body containing local molecule emitters" in {
    val a = m[Int]

    val reaction =
      go { case a(x) =>
        val q = new M[Int]("q")
        val s = new E("s")
        go { case q(_) + s(()) => }
        q(0)
      }
    reaction.info.inputs shouldEqual List(InputMoleculeInfo(a, SimpleVar('x), simpleVarXSha1))
    reaction.info.outputs shouldEqual Some(List())
  }

  it should "inspect reaction body with embedded join" in {
    val a = m[Int]
    val bb = m[Int]
    val f = b[Unit, Int]
    site(tp0)(
      go { case f(_, r) + bb(x) => r(x) },
      go { case a(x) =>
        val p = m[Int]
        site(tp0)(go { case p(y) => bb(y) })
        p(x + 1)
      }
    )
    a(1)
    f.timeout(500 millis)() shouldEqual Some(2)
  }

  it should "inspect reaction body with embedded join and _go" in {
    val a = m[Int]
    val bb = m[Int]
    val f = b[Unit, Int]
    site(tp0)(
      _go { case f(_, r) + bb(x) => r(x) },
      _go { case a(x) =>
        val p = m[Int]
        site(tp0)(go { case p(y) => bb(y) })
        p(x + 1)
      }
    )
    a(1)
    f.timeout(500 millis)() shouldEqual Some(2)
  }

  val simpleVarXSha1 = "8227489534FBEA1F404CAAEC9F4CCAEEB9EF2DC1"
  val wildcardSha1 = "53A0ACFAD59379B3E050338BF9F23CFC172EE787"
  val constantZeroSha1 = "8227489534FBEA1F404CAAEC9F4CCAEEB9EF2DC1"
  val constantOneSha1 = "356A192B7913B04C54574D18C28D46E6395428AB"

  it should "inspect a two-molecule reaction body with None" in {
    val a = m[Int]
    val bb = m[Option[Int]]

    val result = go { case a(x) + bb(None) => bb(None) }

    (result.info.inputs match {
      case List(
      InputMoleculeInfo(`a`, SimpleVar('x), `simpleVarXSha1`),
      InputMoleculeInfo(`bb`, OtherInputPattern(_, List()), "4B93FCEF4617B49161D3D2F83E34012391D5A883")
      ) => true
      case _ => false
    }) shouldEqual true

    result.info.outputs shouldEqual Some(List(OutputMoleculeInfo(bb, OtherOutputPattern)))
    result.info.hasGuard == GuardAbsent
    result.info.sha1 shouldEqual "B1957B893BF4FE420EC790947A0BB62B856BBF33"
  }

  val axqq_qqSha1 = "F98837122C6B2A2945F61CAFC17D1E212B82F2C8"

  it should "inspect a two-molecule reaction body" in {
    val a = m[Int]
    val qq = m[Unit]

    val result = go { case a(x) + qq(_) => qq() }

    result.info.inputs shouldEqual List(
      InputMoleculeInfo(a, SimpleVar('x), simpleVarXSha1),
      InputMoleculeInfo(qq, Wildcard, wildcardSha1)
    )
    result.info.outputs shouldEqual Some(List(OutputMoleculeInfo(qq, ConstOutputValue(()))))
    result.info.hasGuard == GuardAbsent
    result.info.sha1 shouldEqual axqq_qqSha1
  }

  it should "inspect a _go reaction body" in {
    val a = m[Int]
    val qq = m[Unit]

    val result = _go { case a(x) + qq(_) => qq() }

    (result.info.inputs match {
      case List(
      InputMoleculeInfo(`a`, UnknownInputPattern, _),
      InputMoleculeInfo(`qq`, UnknownInputPattern, _)
      ) => true
      case _ => false
    }) shouldEqual true
    result.info.outputs shouldEqual None
    result.info.hasGuard == GuardPresenceUnknown
    result.info.sha1 should not equal axqq_qqSha1
  }

  it should "inspect a reaction body with another molecule and extra code" in {
    val a = m[Int]
    val qqq = m[String]

    object testWithApply {
      def apply(x: Int): Int = x + 1
    }

    val result = go {
      case a(_) + a(x) + a(1) =>
        a(x + 1)
        if (x > 0) a(testWithApply(123))
        println(x)
        qqq("")
    }

    result.info.inputs shouldEqual List(
      InputMoleculeInfo(a, Wildcard, wildcardSha1),
      InputMoleculeInfo(a, SimpleVar('x), simpleVarXSha1),
      InputMoleculeInfo(a, SimpleConst(1), constantOneSha1)
    )
    result.info.outputs shouldEqual Some(List(OutputMoleculeInfo(a, OtherOutputPattern), OutputMoleculeInfo(a, OtherOutputPattern), OutputMoleculeInfo(qqq, ConstOutputValue(""))))
    result.info.hasGuard == GuardAbsent
  }

  it should "inspect reaction body with embedded reaction" in {
    val a = m[Int]
    val qq = m[Unit]

    val result = go { case a(x) => go { case qq(_) => a(0) }; qq() }

    result.info.inputs shouldEqual List(InputMoleculeInfo(a, SimpleVar('x), simpleVarXSha1))
    result.info.outputs shouldEqual Some(List(OutputMoleculeInfo(qq, ConstOutputValue(()))))
    result.info.hasGuard == GuardAbsent
  }

  it should "inspect a very complicated reaction input pattern" in {
    val a = m[Int]
    val c = m[Unit]
    val qq = m[Unit]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    // reaction contains all kinds of pattern-matching constructions
    val result = go {
    // This generates a compiler warning "class M expects 2 patterns to hold (Int, Option[Int]) but crushing into 2-tuple to fit single pattern (SI-6675)".
    // However, this crushing is among the issues covered by this test, and we cannot tell scalac to ignore this warning.
      case a(p) + a(y) + a(1) + c(()) + c(_) + bb(_) + bb((1, z)) + bb((_, None)) + bb((t, Some(q))) + s(_, r) => s(); a(p + 1) + qq() + r(p)
    }

    (result.info.inputs match {
      case List(
      InputMoleculeInfo(`a`, SimpleVar('p), _),
      InputMoleculeInfo(`a`, SimpleVar('y), _),
      InputMoleculeInfo(`a`, SimpleConst(1), _),
      InputMoleculeInfo(`c`, SimpleConst(()), _),
      InputMoleculeInfo(`c`, Wildcard, _),
      InputMoleculeInfo(`bb`, Wildcard, _),
      InputMoleculeInfo(`bb`, OtherInputPattern(_, List('z)), _),
      InputMoleculeInfo(`bb`, OtherInputPattern(_, List()), _),
      InputMoleculeInfo(`bb`, OtherInputPattern(_, List('t, 'q)), _),
      InputMoleculeInfo(`s`, Wildcard, _)
      ) => true
      case _ => false
    }) shouldEqual true

    result.info.outputs shouldEqual Some(List(OutputMoleculeInfo(s, ConstOutputValue(())), OutputMoleculeInfo(a, OtherOutputPattern), OutputMoleculeInfo(qq, ConstOutputValue(()))))

  }

  it should "correctly split a guard condition" in {
    val a = m[Int]
    val bb = m[(Int, Option[Int])]

    val result = go {
      case a(p) + a(y) + a(1) + bb((1, z)) + bb((t, Some(q))) if y > 0 && q > 0 && t == p =>
    }
    result.info.hasGuard shouldEqual GuardPresent(List(List('y), List('q), List('t, 'p)))
  }

  it should "define a reaction with correct inputs with non-default pattern-matching in the middle of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new E("b")
    val c = new E("c")

    site(tp0)(_go { case b(_) + a(Some(x)) + c(_) => })

    a.logSoup shouldEqual "Site{a + b => ...}\nNo molecules" // this is the wrong result
    // when the problem is fixed, this test will have to be rewritten
  }

  it should "define a reaction with correct inputs with default pattern-matching in the middle of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new E("b")
    val c = new E("c")

    site(tp0)(go { case b(_) + a(None) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with non-simple default pattern-matching in the middle of reaction" in {
    val a = new M[Seq[Int]]("a")
    val b = new E("b")
    val c = new E("c")

    site(go { case b(_) + a(List()) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "fail to define a simple reaction with correct inputs with empty option pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new E("b")
    val c = new E("c")

    site(tp0)(_go { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Site{a => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with empty option pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new E("b")
    val c = new E("c")

    site(tp0)(go { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with non-default pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new E("b")
    val c = new E("c")

    site(tp0)(go { case a(Some(x)) + b(_) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "run reactions correctly with non-default pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new E("b")

    site(tp0)(go { case a(Some(x)) + b(_) => })

    a(Some(1))
    waitSome()
    a.logSoup shouldEqual "Site{a + b => ...}\nMolecules: a(Some(1))"
    b()
    waitSome()
    a.logSoup shouldEqual "Site{a + b => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant non-default pattern-matching at start of reaction" in {
    val a = new M[Int]("a")
    val b = new E("b")
    val c = new E("c")

    site(tp0)(go { case a(1) + b(_) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant default option pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new E("b")
    val c = new E("c")

    site(tp0)(go { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "determine input patterns correctly" in {
    val a = new M[Option[Int]]("a")
    val b = new M[String]("b")
    val c = new M[(Int, Int)]("c")
    val d = new E("d")

    val r = go { case a(Some(1)) + b("xyz") + d(()) + c((2, 3)) => a(Some(2)) }

    (r.info.inputs match {
      case List(
      InputMoleculeInfo(`a`, OtherInputPattern(_, List()), _),
      InputMoleculeInfo(`b`, SimpleConst("xyz"), _),
      InputMoleculeInfo(`d`, SimpleConst(()), _),
      InputMoleculeInfo(`c`, OtherInputPattern(_, List()), _)
      ) => true
      case _ => false
    }) shouldEqual true
    r.info.outputs shouldEqual Some(List(OutputMoleculeInfo(a, OtherOutputPattern)))
    r.info.hasGuard shouldEqual GuardAbsent

    // Note: Scala 2.11 and Scala 2.12 have different desugared syntax trees for this reaction.
    r.info.sha1 shouldEqual "9C93A6DE5D096D3CDC3C318E0A07B30B732EA37A"
  }

  it should "detect output molecules with constant values" in {
    val bb = m[Int]
    val bbb = m[Int]
    val cc = m[Option[Int]]

    val r1 = go { case bbb(x) => bb(x) }
    val r2 = go { case bbb(_) + bb(_) => bbb(0) }
    val r3 = go { case bbb(x) + bb(_) + bb(_) => bbb(1) + bb(x) + bbb(2) + cc(None) + cc(Some(1)) }

    r1.info.outputs shouldEqual Some(List(OutputMoleculeInfo(bb, OtherOutputPattern)))
    r2.info.outputs shouldEqual Some(List(OutputMoleculeInfo(bbb, ConstOutputValue(0))))
    r3.info.outputs shouldEqual Some(List(
      OutputMoleculeInfo(bbb, ConstOutputValue(1)),
      OutputMoleculeInfo(bb, OtherOutputPattern),
      OutputMoleculeInfo(bbb, ConstOutputValue(2)),
      OutputMoleculeInfo(cc, OtherOutputPattern),
      OutputMoleculeInfo(cc, OtherOutputPattern)
    ))
  }

  it should "compute input pattern variables correctly" in {

    val bb = m[(Int, Int, Option[Int], (Int, Option[Int]))]

    val result = go { case bb( p@(ytt, 1, None, (s, Some(t))) ) =>  }
    val vars = result.info.inputs.head.flag match {
      case OtherInputPattern(_, vs) => vs
      case _ => Nil
    }
    vars shouldEqual List('p, 'ytt, 's, 't)
  }

  it should "create partial functions for matching from reaction body" in {
    val aa = m[Option[Int]]
    val bb = m[(Int, Option[Int])]

    val result = go { case aa(Some(x)) + bb((0, None)) => aa(Some(x + 1)) }

    result.info.outputs shouldEqual Some(List(OutputMoleculeInfo(aa, OtherOutputPattern)))

    val pat_aa = result.info.inputs(0)
    pat_aa.molecule shouldEqual aa
    val pat_bb = result.info.inputs(1)
    pat_bb.molecule shouldEqual bb

    // different desugared syntax trees with Scala 2.11 vs. Scala 2.12
    (Set("9247828A8E7754B2D961E955541CF1D4D77E2D1E", "A67750BF5B6338391B0034D3A99694889CBB26A3") contains pat_aa.sha1) shouldEqual true
    (Set("2FB215E623E8AF28E9EA279CBEA827A1065CA226", "A67750BF5B6338391B0034D3A99694889CBB26A3") contains pat_bb.sha1) shouldEqual true

    (pat_aa.flag match {
      case OtherInputPattern(matcher, vars) =>
        matcher.isDefinedAt(Some(1)) shouldEqual true
        matcher.isDefinedAt(None) shouldEqual false
        vars shouldEqual List('x)
        true
      case _ => false
    }) shouldEqual true

    (pat_bb.flag match {
      case OtherInputPattern(matcher, vars) =>
        matcher.isDefinedAt((0, None)) shouldEqual true
        matcher.isDefinedAt((1, None)) shouldEqual false
        matcher.isDefinedAt((0, Some(1))) shouldEqual false
        matcher.isDefinedAt((1, Some(1))) shouldEqual false
        vars shouldEqual List()
        true
      case _ => false
    }) shouldEqual true
  }

  behavior of "output value computation"

  it should "not fail to compute outputs for an inline reaction" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      site(
        go { case a(1) => a(1) }
      )
      a.consumingReactions.get.map(_.info.outputs) shouldEqual Set(Some(List(OutputMoleculeInfo(a, ConstOutputValue(1)))))
    }
    thrown.getMessage shouldEqual "In Site{a => ...}: Unavoidable livelock: reaction {a(1) => a(1)}"
  }

  it should "compute inputs and outputs correctly for an inline nested reaction" in {
    val a = m[Int]
    site(
      go {
        case a(1) =>
          val c = m[Int]
          site(go { case c(_) => })
          c(2)
          a(2)
      }
    )
    a.consumingReactions.get.map(_.info.outputs) shouldEqual Set(Some(List(OutputMoleculeInfo(a, ConstOutputValue(2)))))
    a.consumingReactions.get.map(_.info.inputs) shouldEqual Set(List(InputMoleculeInfo(a, SimpleConst(1), constantOneSha1)))
    a.emittingReactions.map(_.info.outputs) shouldEqual Set(Some(List(OutputMoleculeInfo(a, ConstOutputValue(2)))))
    a.emittingReactions.map(_.info.inputs) shouldEqual Set(List(InputMoleculeInfo(a, SimpleConst(1), constantOneSha1)))
  }

  it should "not fail to compute outputs correctly for an inline nested reaction" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      site(
        go {
          case a(1) =>
            val c = m[Int]
            site(go { case c(_) => })
            c(2)
            a(1)
        }
      )
    }
    thrown.getMessage shouldEqual "In Site{a => ...}: Unavoidable livelock: reaction {a(1) => a(1)}"
  }

  it should "compute outputs in the correct order for a reaction with no livelock" in {
    val a = m[Int]
    val b = m[Int]
    site(
      go { case a(2) => b(2) + a(1) + b(1) }
    )
    a.consumingReactions.get.map(_.info.outputs) shouldEqual Set(Some(List(
      OutputMoleculeInfo(b, ConstOutputValue(2)),
      OutputMoleculeInfo(a, ConstOutputValue(1)),
      OutputMoleculeInfo(b, ConstOutputValue(1))
    )))
  }

  it should "correctly recognize nested emissions of non-blocking molecules" in {
    val a = m[Int]
    val c = m[Int]
    val d = m[Boolean]

    site(
      go { case a(x) + d(_) => c({ a(1); 2} ) }
    )

    a.isBound shouldEqual true
    c.isBound shouldEqual false


    val reaction = a.consumingReactions.get.head
    c.emittingReactions.head shouldEqual reaction
    a.emittingReactions.head shouldEqual reaction

    reaction.info.inputs shouldEqual List(InputMoleculeInfo(a, SimpleVar('x), simpleVarXSha1), InputMoleculeInfo(d, Wildcard, wildcardSha1))
    reaction.info.outputs shouldEqual Some(List(OutputMoleculeInfo(a, ConstOutputValue(1)), OutputMoleculeInfo(c, OtherOutputPattern)))
  }

  it should "correctly recognize nested emissions of blocking molecules and reply values" in {
    val a = b[Int, Int]
    val c = m[Int]
    val d = m[Unit]

    site(
      go { case d(_) => c(a(1)) },
      go { case a(x, r) => d(r(x)) }
    )

    a.isBound shouldEqual true
    c.isBound shouldEqual false
    d.isBound shouldEqual true

    val reaction1 = d.consumingReactions.get.head
    a.emittingReactions.head shouldEqual reaction1
    c.emittingReactions.head shouldEqual reaction1

    val reaction2 = a.consumingReactions.get.head
    d.emittingReactions.head shouldEqual reaction2

    reaction1.info.inputs shouldEqual List(InputMoleculeInfo(d, Wildcard, wildcardSha1))
    reaction1.info.outputs shouldEqual Some(List(OutputMoleculeInfo(a, ConstOutputValue(1)), OutputMoleculeInfo(c, OtherOutputPattern)))

    reaction2.info.inputs shouldEqual List(InputMoleculeInfo(a, SimpleVar('x), simpleVarXSha1))
    reaction2.info.outputs shouldEqual Some(List(OutputMoleculeInfo(d, OtherOutputPattern)))
  }

  behavior of "auxiliary functions"

  it should "find expression trees for constant values" in {
    rawTree(1) shouldEqual "Literal(Constant(1))"
    rawTree(None) shouldEqual "Select(Ident(scala), scala.None)"

    (Set(
      "Apply(TypeApply(Select(Select(Ident(scala), scala.Some), TermName(\"apply\")), List(TypeTree())), List(Literal(Constant(1))))"
    ) contains rawTree(Some(1))) shouldEqual true
  }

  it should "find expression trees for matchers" in {

    rawTree(Some(1) match { case Some(1) => }) shouldEqual "Match(Apply(TypeApply(Select(Select(Ident(scala), scala.Some), TermName(\"apply\")), List(TypeTree())), List(Literal(Constant(1)))), List(CaseDef(Apply(TypeTree().setOriginal(Select(Ident(scala), scala.Some)), List(Literal(Constant(1)))), EmptyTree, Literal(Constant(())))))"
  }

  it should "find enclosing symbol names with correct scopes" in {
    val x = getName

    x shouldEqual "x"

    val y = {
      val z = getName
      (z, getName)
    }
    y shouldEqual(("z", "y"))

    val (y1,y2) = {
      val z = getName
      (z, getName)
    }
    (y1, y2) shouldEqual(("z", "x$8"))
  }

}

