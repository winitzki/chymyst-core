package io.chymyst.jc

import Core._
import scala.{Symbol => ScalaSymbol}
import scala.collection.mutable

/** Represents compile-time information about the pattern matching for values carried by input molecules.
  * Possibilities:
  * `a(_)` is represented by [[WildcardInput]]
  * `a(x)` is represented by [[SimpleVarInput]] with value `SimpleVar(v = 'x, cond = None)`
  * `a(x) if x > 0` is represented by [[SimpleVarInput]] with value `SimpleVar(v = 'x, cond = Some({ case x : Int if x > 0 => }))`
  * `a(Some(1))` is represented by [[ConstInputPattern]] with value `SimpleConst(v = Some(1))`
  * `a( (x, Some((y,z)))) ) if x > y` is represented by [[OtherInputPattern]] with value
  * {{{OtherInputPattern(matcher = { case (x, Some((y,z)))) if x > y => }, vars = List('x, 'y, 'z), isIrrefutable = false)}}}
  */
sealed trait InputPatternType {
  /** Detect whether the input pattern will always match any given value.
    * In other words, a pattern is considered "trivial" if it does not constrain the value but merely puts variables on its parts.
    *
    * Examples of trivial patterns are `a(_)`, `a(x)`, and `a( z@(x,y,_) )`, where `a(...)` is a molecule with a suitable value type.
    * Examples of nontrivial patterns are `a(1)`, `a(Some(x))`, `a( (_, None) )`.
    *
    * @return `true` if the pattern always matches, `false` otherwise.
    */
  def isTrivial: Boolean = false
}

case object WildcardInput extends InputPatternType {
  override def isTrivial: Boolean = true
}

final case class SimpleVarInput(v: ScalaSymbol, cond: Option[PartialFunction[Any, Unit]]) extends InputPatternType {
  override def isTrivial: Boolean = cond.isEmpty
}

/** Represents molecules that have constant pattern matchers, such as `a(1)`.
  * Constant pattern matchers are either literal values (`Int`, `String`, `Symbol`, etc.) or special values such as `None`, `Nil`, `()`,
  * as well as `Some`, `Left`, `Right`, `List`, and tuples of constant matchers.
  *
  * @param v Value of the constant. This is nominally of type `Any` but actually is of the molecule's value type `T`.
  */
final case class ConstInputPattern(v: Any) extends InputPatternType

/** Represents a general pattern that is neither a wildcard nor a single variable nor a constant.
  * Examples of such patterns are `a(Some(x))` and `a( (x, _, 2, List(a, b)) )`.
  *
  * A pattern is recognized to be _irrefutable_ when it is a tuple where all places are either simple variables or wildcards.
  * For example, `a( z@(x, y, _) )` is an irrefutable pattern for a 3-tuple type.
  * On the other hand, `a( (x, _, Some(_) ) )` is not irrefutable because it fails to match `a( (_, _, None) )`.
  * Another recognized case of irrefutable patterns is a single case class that extends a sealed trait.
  *
  * @param matcher       Partial function that applies to the argument when the pattern matches.
  * @param vars          List of symbols representing the variables used in the pattern, in the left-to-right order.
  * @param isIrrefutable `true` if the pattern will always match the argument of the correct type, `false` otherwise.
  */
final case class OtherInputPattern(matcher: PartialFunction[Any, Unit], vars: List[ScalaSymbol], isIrrefutable: Boolean) extends InputPatternType {
  override def isTrivial: Boolean = isIrrefutable
}

/** Represents the value pattern of an emitted output molecule.
  * We distinguish only constant value patterns and all other patterns.
  */
sealed trait OutputPatternType {
  val specificity: Int

  def merge(other: OutputPatternType)(equals: (Any, Any) => Boolean = (a, b) => a === b): OutputPatternType = OtherOutputPattern
}

final case class ConstOutputPattern(v: Any) extends OutputPatternType {
  override def merge(other: OutputPatternType)(equals: (Any, Any) => Boolean = (a, b) => a === b): OutputPatternType =
    other match {
      case ConstOutputPattern(c) if equals(c, v) =>
        this
      case _ =>
        OtherOutputPattern
    }

  override val specificity = 0
}

case object OtherOutputPattern extends OutputPatternType {
  override val specificity = 1
}

/** Describe the code environment within which an output molecule is being emitted.
  * Possible environments are [[ChooserBlock]] describing an `if` or `match` expression with clauses,
  * and a function call [[FuncBlock]].
  *
  * For example, `if (x>0) a(x) else b(x)` is a chooser block environment with 2 clauses,
  * while `(1 to 10).foreach(a)` is a function block environment
  * and `(x) => a(x)` is a [[FuncLambda]] environment.
  */
sealed trait OutputEnvironment {
  /** This is to `true` if the output environment is guaranteed to emit the molecule at least once.
    * This is `false` for most environments.
    */
  val atLeastOne: Boolean = false

  /** Each output environment is identified by an integer Id number.
    *
    * @return The Id number of the output environment.
    */
  def id: Int

  val linear: Boolean = false
  val shrinkable: Boolean = false
}

/** Describes a molecule emitted in a chooser clause, that is, in an `if-then-else` or `match-case` construct.
  *
  * @param id     Id of the chooser construct.
  * @param clause Zero-based index of the clause.
  * @param total  Total number of clauses in the chooser constructs (2 for `if-then-else`, 2 or greater for `match-case`).
  */
final case class ChooserBlock(id: Int, clause: Int, total: Int) extends OutputEnvironment {
  override val atLeastOne: Boolean = total === 1
  override val linear: Boolean = true
  override val shrinkable: Boolean = true
}

/** Describes a molecule emitted under a function call.
  *
  * @param id   Id of the function call construct.
  * @param name Fully qualified name of the function call, for example `"scala.collection.TraversableLike.map"`.
  */
final case class FuncBlock(id: Int, name: String) extends OutputEnvironment

/** Describes a molecule emitted under an anonymous function.
  *
  * @param id Id of the anonymous function construct.
  */
final case class FuncLambda(id: Int) extends OutputEnvironment

/** Describes an output environment that is guaranteed to emit the molecule at least once. This is currently used only in a do-while construct.
  *
  * @param id   Id of the output environment construct.
  * @param name Name of the construct: one of `"do while"`, `"condition of while"`, or `"condition of do while"`.
  */
final case class AtLeastOneEmitted(id: Int, name: String) extends OutputEnvironment {
  override val atLeastOne: Boolean = true
  override val shrinkable: Boolean = true
}

private[jc] object OutputEnvironment {
  private[jc] type OutputItem[T] = (T, OutputPatternType, List[OutputEnvironment])
  private[jc] type OutputList[T] = List[OutputItem[T]]

  private[jc] def shrink[T](outputs: OutputList[T], equals: (Any, Any) => Boolean = (a, b) => a === b): OutputList[T] = {
    outputs.foldLeft[(OutputList[T], OutputList[T])]((Nil, outputs)) { (accOutputs, outputInfo) =>
      val (outputList, remaining) = accOutputs
      if (remaining contains outputInfo) {
        // TODO: make this algorithm general. Support all possible environments.
        // In the `remaining`, find all other molecules of the same sort that could help us shrink `outputInfo`.
        // Remove those molecules from `remaining`. Merge our flag with their flags.
        // When we are done merging (can't shrink any more), add the new molecule info to `outputList`.

        val newRemaining = remaining difff List(outputInfo)
        // Is this molecule already assured? If so, skip it and continue.
        val isAssured = outputInfo._3.forall(_.atLeastOne)
        // Is this molecule shrinkable? If not, we have to skip it and continue.
        val isShrinkable = outputInfo._3.forall(_.shrinkable)
        if (isAssured || !isShrinkable) {
          (outputList :+ outputInfo, newRemaining)
        } else {
          // First approximation to the full shrinkage algorithm: Only process one level of `ChooserBlock`.
          outputInfo._3.headOption match {
            case Some(ChooserBlock(id, clause, total)) if outputInfo._3.size === 1 =>
              // Find all other output molecules with the same id of ChooserBlock. Include this molecule too (so we use `remaining` here, instead of `newRemaining`).
              val others = remaining.filter { case (t, _, envs) =>
                t === outputInfo._1 &&
                  envs.headOption.exists(_.id === id) &&
                  envs.size === 1
              }.sortBy { case (_, outPattern, _) => outPattern.merge(outputInfo._2)(equals).specificity }
              // This will sort first by clause and then all other molecules that match us if they contain a constant, and then all others.
              // The molecules in this list are now sorted. We expect to find `total` molecules.
              // Go through the list in sorted order, removing molecules that have clause number 0, ..., total-1.
              val res = (0 until total).flatFoldLeft[(OutputList[T], OutputPatternType)]((others, outputInfo._2)) { (acc, newClause) =>
                val (othersRemaining, newFlag) = acc
                val found = othersRemaining.find {
                  case (t, _, List(ChooserBlock(_, `newClause`, `total`))) =>
                    true;
                  case _ =>
                    false
                }
                // If `found` == None, we stop. Otherwise, we remove it from `othersRemaining` and merge the obtained flag into `newFlag`.
                found map {
                  case item@(t, outputPattern, _) => (othersRemaining difff List(item), newFlag.merge(outputPattern)(equals))
                }
              }
              res match {
                case None =>
                  (outputList :+ outputInfo, newRemaining)
                case Some((newRemainingOut, newFlag)) => // New output info contains an empty env since we shrank everything.
                  (outputList :+ ((outputInfo._1, newFlag, List())), newRemainingOut)
              }
            case _ =>
              (outputList :+ outputInfo, newRemaining)
          }
        }
      } else // This molecule has been used already while shrinking others, so we just skip it and go on.
        accOutputs
    }._1
  }
}

/** Indicates whether a reaction has a guard condition.
  *
  */
sealed trait GuardPresenceFlag {
  /** Checks whether the reaction should not start because its static guard is present and returns `false`.
    *
    * @return `true` if the reaction's static guard returns `false`.
    *         `false` if the reaction has no static guard, or if the static guard returns `true`.
    */
  def staticGuardFails: Boolean = false

  /** Checks whether the reaction has no cross-molecule guard conditions.
    *
    * For example, `go { case a(x) + b(y) if x > y => }` has a cross-molecule guard condition,
    * whereas `go { case a(x) + b(y) if x == 1 && y == 2 => }` has no cross-molecule guard conditions because its guard condition
    * can be split into a conjunction of guard conditions that each constrain the value of a single molecule.
    *
    * @return `true` if the reaction has no guard condition, or if it has guard conditions that can be split between molecules;
    *         `false` if the reaction has a cross-molecule guard condition.
    */
  def effectivelyAbsent: Boolean = true
}

/** Indicates whether guard conditions are required for this reaction to start.
  *
  * The guard is parsed into a flat conjunction of guard clauses, which are then analyzed for cross-dependencies between molecules.
  *
  * For example, consider the reaction
  * {{{ go { case a(x) + b(y) + c(z) if x > n && y > 0 && y > z && n > 1 => ...} }}}
  * Here `n` is an integer constant defined outside the reaction.
  * The conditions for starting this reaction is that `a(x)` has value `x` such that `x > n`; that `b(y)` has value `y` such that `y > 0`;
  * that `c(z)` has value `z` such that `y > z`; and finally that `n > 1`, independently of any molecule values.
  * The condition `n > 1` is a static guard. The condition `x > n` restricts only the molecule `a(x)` and therefore can be moved out of the guard
  * into the per-molecule conditional inside [[InputMoleculeInfo]] for that molecule. Similarly, the condition `y > 0` can be moved out of the guard.
  * However, the condition `y > z` relates two different molecule values; it is a cross-molecule guard.
  *
  * Any guard condition given by the reaction code will be converted to the Conjunctive Normal Form, and split into a static guard,
  * a set of per-molecule conditionals, and a set of cross-molecule guards.
  *
  * @param vars        The list of all pattern variables used by the guard condition. Each element of this list is a list of variables used by one guard clause. In the example shown above, this will be `List(List('y, 'z))` because all other conditions are moved out of the guard.
  * @param staticGuard The conjunction of all the clauses of the guard that are independent of pattern variables. This closure can be called in order to determine whether the reaction should even be considered to start, regardless of the presence of molecules. In this example, the value of `staticGuard` will be `Some(() => n > 1)`.
  * @param crossGuards A list of values of type [[CrossMoleculeGuard]], each representing one cross-molecule clauses of the guard. The partial function `Any => Unit` should be called with the arguments representing the tuples of pattern variables from each molecule used by the cross guard.
  *                    In the present example, the value of `crossGuards` will be
  *                    {{{indices = Array(1, 2), List((List('y, 'z), { case List(y: Int, z: Int) if y > z => () })) }}}.
  */
final case class GuardPresent(vars: Array[Array[ScalaSymbol]], staticGuard: Option[() => Boolean], crossGuards: Array[CrossMoleculeGuard]) extends GuardPresenceFlag {
  override def staticGuardFails: Boolean = staticGuard.exists(guardFunction => !guardFunction())

  override def effectivelyAbsent: Boolean = staticGuard.isEmpty && crossGuards.isEmpty

  override val toString: String =
    s"GuardPresent([${vars.map(vs => s"[${vs.mkString(",")}]").mkString(", ")}], ${staticGuard.map(_ => "")}, [${crossGuards.map(_.toString).mkString("; ")}])"
}

/** Indicates that a guard was initially present but has been simplified, or it was absent but some molecules have nontrivial pattern matchers (not a wildcard and not a simple variable).
  * Nevertheless, no cross-molecule guard conditions need to be checked for this reaction to start.
  */
case object GuardAbsent extends GuardPresenceFlag

/** Indicates that a guard was initially absent and, in addition, all molecules have trivial matchers - this reaction can start with any molecule values.
  */
case object AllMatchersAreTrivial extends GuardPresenceFlag

/** Represents the structure of the cross-molecule guard condition for a reaction.
  * A cross-molecule guard constrains values of several molecules at once.
  *
  * @param indices Integer indices of affected molecules in the reaction input.
  * @param symbols Symbols of variables used by the guard condition.
  * @param cond    Partial function that applies to its argument, of type `List[Any]`, if the cross-molecule guard evaluates to `true` on these values.
  *                The arguments of the partial function must correspond to the values of the affected molecules, in the order of the reaction input.
  */
final case class CrossMoleculeGuard(indices: Array[Int], symbols: Array[ScalaSymbol], cond: PartialFunction[List[Any], Unit]) {
  override val toString: String = s"CrossMoleculeGuard([${indices.mkString(",")}], [${symbols.mkString(",")}])"
}

/** Compile-time information about an input molecule pattern in a reaction.
  * This class is immutable.
  *
  * @param molecule The molecule emitter value that represents the input molecule.
  * @param flag     A value of type [[InputPatternType]] that describes the value pattern: wildcard, constant match, etc.
  * @param sha1     Hash sum of the input pattern's source code (desugared Scala representation).
  */
final case class InputMoleculeInfo(molecule: Molecule, index: Int, flag: InputPatternType, sha1: String) {
  private[jc] def admitsValue(molValue: AbsMolValue[_]): Boolean = flag match {
    case WildcardInput | SimpleVarInput(_, None) =>
      true
    case SimpleVarInput(v, Some(cond)) =>
      cond.isDefinedAt(molValue.getValue)
    case ConstInputPattern(v) =>
      v === molValue.getValue
    case OtherInputPattern(_, _, true) =>
      true
    case OtherInputPattern(matcher, _, _) =>
      matcher.isDefinedAt(molValue.getValue)
  }

  /** Determine whether this input molecule pattern is weaker than another pattern.
    * Pattern a(xxx) is weaker than b(yyy) if a==b and if anything matched by yyy will also be matched by xxx.
    *
    * @param info The input molecule info for another input molecule.
    * @return Some(true) if we can surely determine that this matcher is weaker than another;
    *         Some(false) if we can surely determine that this matcher is not weaker than another;
    *         None if we cannot determine anything because information is insufficient.
    */
  private[jc] def matcherIsWeakerThan(info: InputMoleculeInfo): Option[Boolean] = {
    if (molecule =!= info.molecule)
      Some(false)
    else flag match {
      case WildcardInput |
           SimpleVarInput(_, None) |
           OtherInputPattern(_, _, true) =>
        Some(true)
      case SimpleVarInput(_, Some(matcher1)) =>
        info.flag match {
          case ConstInputPattern(c) =>
            Some(matcher1.isDefinedAt(c))
          case SimpleVarInput(_, Some(_)) |
               OtherInputPattern(_, _, false) =>
            None // Cannot reliably determine a weaker matcher.
          case _ =>
            Some(false)
        }
      case OtherInputPattern(matcher1, _, false) =>
        info.flag match {
          case ConstInputPattern(c) =>
            Some(matcher1.isDefinedAt(c))
          case OtherInputPattern(_, _, false) =>
            if (sha1 === info.sha1) Some(true)
            else None // We can reliably determine identical matchers.
          case _ =>
            Some(false) // Here we can reliably determine that this matcher is not weaker.
        }
      case ConstInputPattern(c) =>
        Some(info.flag match {
          case ConstInputPattern(c1) =>
            c === c1
          case _ =>
            false
        })
    }
  }

  private[jc] def matcherIsWeakerThanOutput(info: OutputMoleculeInfo): Option[Boolean] = {
    if (molecule =!= info.molecule) Some(false)
    else flag match {
      case WildcardInput |
           SimpleVarInput(_, None) |
           OtherInputPattern(_, _, true) =>
        Some(true)
      case SimpleVarInput(_, Some(matcher1)) =>
        info.flag match {
          case ConstOutputPattern(c) =>
            Some(matcher1.isDefinedAt(c))
          case _ =>
            None // Here we can't reliably determine whether this matcher is weaker.
        }
      case OtherInputPattern(matcher1, _, false) =>
        info.flag match {
          case ConstOutputPattern(c) =>
            Some(matcher1.isDefinedAt(c))
          case _ =>
            None // Here we can't reliably determine whether this matcher is weaker.
        }
      case ConstInputPattern(c) => info.flag match {
        case ConstOutputPattern(`c`) =>
          Some(true)
        case ConstOutputPattern(_) =>
          Some(false) // definitely not the same constant
        case _ =>
          None // Otherwise, it could be this constant but we can't determine.
      }
    }
  }

  // Here "similar" means either it's definitely weaker or it could be weaker (but it is definitely not stronger).
  private[jc] def matcherIsSimilarToOutput(info: OutputMoleculeInfo): Option[Boolean] = {
    if (molecule =!= info.molecule)
      Some(false)
    else flag match {
      case WildcardInput |
           SimpleVarInput(_, None) |
           OtherInputPattern(_, _, true) =>
        Some(true)
      case SimpleVarInput(_, Some(matcher1)) =>
        info.flag match {
          case ConstOutputPattern(c) =>
            Some(matcher1.isDefinedAt(c))
          case _ =>
            Some(true) // Here we can't reliably determine whether this matcher is weaker, but it's similar (i.e. could be weaker).
        }
      case OtherInputPattern(matcher1, _, false) =>
        info.flag match {
          case ConstOutputPattern(c) =>
            Some(matcher1.isDefinedAt(c))
          case _ =>
            Some(true) // Here we can't reliably determine whether this matcher is weaker, but it's similar (i.e. could be weaker).
        }
      case ConstInputPattern(c) =>
        Some(info.flag match {
          case ConstOutputPattern(`c`) =>
            true
          case ConstOutputPattern(_) =>
            false // definitely not the same constant
          case _ =>
            true // Otherwise, it could be this constant.
        })
    }
  }

  override val toString: String = {
    val printedPattern = flag match {
      case WildcardInput =>
        "_"
      case SimpleVarInput(v, None) =>
        v.name
      case SimpleVarInput(v, Some(_)) =>
        s"${v.name} if ?"
      //      case ConstInputPattern(()) => ""  // We eliminated this case by converting constants of Unit type to Wildcard input flag.
      case ConstInputPattern(c) =>
        c.toString
      case OtherInputPattern(_, vars, isIrrefutable) =>
        s"${if (isIrrefutable) "" else "?"}${vars.map(_.name).mkString(",")}"
    }

    s"$molecule($printedPattern)"
  }

}

/** Compile-time information about an output molecule pattern in a reaction.
  * This class is immutable.
  *
  * @param molecule     The molecule emitter value that represents the output molecule.
  * @param flag         Type of the output pattern: either a constant value or other value.
  * @param environments The code environment in which this output molecule was emitted.
  */
final case class OutputMoleculeInfo(molecule: Molecule, flag: OutputPatternType, environments: List[OutputEnvironment]) {
  val atLeastOnce: Boolean = environments.forall(_.atLeastOne)

  override val toString: String = {
    val printedPattern = flag match {
      case ConstOutputPattern(()) =>
        ""
      case ConstOutputPattern(c) =>
        c.toString
      case OtherOutputPattern =>
        "?"
    }

    s"$molecule($printedPattern)"
  }
}

/** Represents information carried by every Chymyst thread that runs a reaction.
  *
  * @param statics        List of static molecules that this reaction consumes.
  * @param reactionString String representation of the reaction, used for error messages.
  */
final class ChymystThreadInfo(
                               statics: Seq[Molecule] = Seq(),
                               reactionString: String = "<no reaction>"
                             ) {
  override val toString: String = reactionString

  private[jc] val maybeEmit: Molecule => Boolean = {
    val allowedToEmit = mutable.Set[Molecule](statics: _*)

    { m: Molecule => allowedToEmit.remove(m) }
  }

  private[jc] def couldEmit(m: Molecule): Boolean = statics.contains(m)
}

// This class is immutable.
final class ReactionInfo(
                          private[jc] val inputs: Array[InputMoleculeInfo],
                          private[jc] val outputs: Array[OutputMoleculeInfo],
                          private[jc] val shrunkOutputs: Array[OutputMoleculeInfo],
                          private[jc] val guardPresence: GuardPresenceFlag,
                          private[jc] val sha1: String
                        ) {
  private[jc] lazy val staticMols: Seq[Molecule] = inputs.map(_.molecule).filter(_.isStatic)

  // Optimization: avoid pattern-match every time we need to find cross-molecule guards.
  private[jc] val crossGuards: Array[CrossMoleculeGuard] = guardPresence match {
    case GuardPresent(_, _, guards) =>
      guards
    case _ =>
      Array[CrossMoleculeGuard]()
  }

  /** Cross-conditionals are repeated input molecules, such that one of them has a conditional or participates in a cross-molecule guard.
    * This value holds the set of indices for all such molecules, for quick access.
    */
  private[jc] val crossConditionals: Set[Int] = {
    inputs
      .groupBy(_.molecule)
      .filter(_._2.length > 1)
      .values
      .filter { repeatedInput =>
        crossGuards.exists { guard => (guard.indices intersect repeatedInput.map(_.index)).nonEmpty } ||
          repeatedInput.exists { info => !info.flag.isTrivial }
      }
      .flatMap(_.map(_.index))
      .toSet
  }

  // Optimization: this is used often.
  private[jc] lazy val inputMolecules: Array[Molecule] = inputs.map(_.molecule).sortBy(_.toString)

  private[jc] lazy val inputMoleculesSet: Set[Molecule] = inputMolecules.toSet

  // The input pattern sequence is pre-sorted for further use.
  private[jc] val inputsSorted: List[InputMoleculeInfo] = inputs.sortBy { case InputMoleculeInfo(mol, _, flag, sha) =>
    // Wildcard and SimpleVar without a conditional are sorted together; more specific matchers will precede less specific matchers
    val patternPrecedence = flag match {
      case WildcardInput |
           SimpleVarInput(_, None) |
           OtherInputPattern(_, _, true) =>
        3
      case OtherInputPattern(_, _, false) |
           SimpleVarInput(_, Some(_)) =>
        2
      case ConstInputPattern(_) =>
        1
    }

    val molValueString = flag match {
      case ConstInputPattern(v) =>
        v.toString
      case SimpleVarInput(v, _) =>
        v.name
      case _ =>
        ""
    }
    (mol.toString, patternPrecedence, molValueString, sha)
  }.toList

  override val toString: String = {
    val inputsInfo = inputsSorted.map(_.toString).mkString(" + ")
    val guardInfo = guardPresence match {
      case _
        if guardPresence.effectivelyAbsent =>
        ""
      case GuardPresent(_, Some(_), Array()) =>
        " if(?)" // There is a static guard but no cross-molecule guards.
      case GuardPresent(_, _, guards) =>
        val crossGuardsInfo = guards.flatMap(_.symbols).map(_.name).distinct.mkString(",")
        s" if($crossGuardsInfo)"
    }
    val outputsInfo = outputs.map(_.toString).mkString(" + ")
    s"$inputsInfo$guardInfo => $outputsInfo"
  }
}

/** Represents a reaction body. This class is immutable.
  *
  * @param body       Partial function of type `InputMoleculeList => Any`
  * @param threadPool Thread pool on which this reaction will be scheduled. (By default, the common pool is used.)
  * @param retry      Whether the reaction should be run again when an exception occurs in its body. Default is false.
  */
final case class Reaction(
                           private[jc] val info: ReactionInfo,
                           private[jc] val body: ReactionBody,
                           threadPool: Option[Pool],
                           private[jc] val retry: Boolean
                         ) {
  private[jc] def newChymystThreadInfo = new ChymystThreadInfo(info.staticMols, info.toString)

  /** Convenience method to specify thread pools per reaction.
    *
    * Example: go { case a(x) => ... } onThreads threadPool24
    *
    * @param newThreadPool A custom thread pool on which this reaction will be scheduled.
    * @return New reaction value with the thread pool set.
    */
  def onThreads(newThreadPool: Pool): Reaction = copy(threadPool = Some(newThreadPool))

  /** Convenience method to specify the "retry" option for a reaction.
    *
    * @return New reaction value with the "retry" flag set.
    */
  def withRetry: Reaction = copy(retry = true)

  /** Convenience method to specify the "no retry" option for a reaction.
    * (This option is the default.)
    *
    * @return New reaction value with the "retry" flag unset.
    */
  def noRetry: Reaction = copy(retry = false)

  // Optimization: this is used often.
  private[jc] lazy val inputMolecules: Seq[Molecule] = info.inputMolecules

  private[jc] lazy val inputMoleculesSet: Set[Molecule] = info.inputMoleculesSet

  /** Convenience method for debugging.
    *
    * @return String representation of input molecules of the reaction.
    */
  override val toString: String = s"${inputMolecules.map(_.toString).mkString(" + ")} => ...${
    if (retry)
      "/R"
    else ""
  }"

  private type BagMap = Map[Molecule, Map[AbsMolValue[_], Int]]

  private def removeFromBagMap(relevantMap: BagMap, molecule: Molecule, molValue: AbsMolValue[_]) = {
    val valuesMap = relevantMap.getOrElse(molecule, Map())
    val count = valuesMap.getOrElse(molValue, 0)
    if (count >= 2)
      relevantMap.updated(molecule, valuesMap.updated(molValue, count - 1))
    else {
      val newValuesMap = valuesMap.filterKeys(_ != molValue)
      if (newValuesMap.isEmpty)
        relevantMap.filterKeys(_ != molecule)
      else
        relevantMap.updated(molecule, newValuesMap)
    }
  }

  /*
    private def moleculeDependencies(index: Int): Array[Int] = info.guardPresence match {
      case GuardPresent(_, _, crossGuards) =>
        crossGuards.flatMap { case CrossMoleculeGuard(indices, _, _) =>
          if (indices.contains(index))
            indices
          else Array[Int]()
        }.distinct
      case _ => Array[Int]()
    }
  */
  /** Check whether the molecule given by inputInfo has no cross-dependencies, including cross-conditionals implied by a repeated input molecule. */
  // TODO: Implement fully!
  private def moleculeIsIndependent(index: Int): Boolean = !info.crossConditionals.contains(index) && info.crossGuards.forall {
    case CrossMoleculeGuard(indices, _, _) =>
      !indices.contains(index)
  }

  /** Convert a Map[Int, AbsMolValue[_]] to an array of AbsMolValue[_] using these indices.
    * The map should contain all indices from 0 to (info.inputs.length - 1) and no other indices.
    */
  private def assignInputsToArray(valuesMap: Map[Int, AbsMolValue[_]]): InputMoleculeList = {
    val requiredLength = info.inputMolecules.length

    val inputs = new InputMoleculeList(requiredLength)

    valuesMap.foreach {
      case (i, molValue) =>
        val mol = info.inputs(i).molecule
        inputs.update(i, (mol, molValue))
    }
    inputs
  }

  /** Find a set of input molecules for this reaction, under the condition that `m(molValue)` must be one of the input molecules. */
  private[jc] def findInputMolecules(m: Molecule, molValue: AbsMolValue[_], moleculesPresent: MoleculeBag): Option[(Reaction, InputMoleculeList)] = {
    // Evaluate the static guard first. If the static guard fails, we don't need to run the reaction or look for any input molecules.
    if (info.guardPresence.staticGuardFails)
      None
    else {
      // For each input molecule used by the reaction, find all suitable values of this molecule that fit the conditional.
      // This builds a sequence of possible molecule sets for this reaction.
      // Then evaluate cross-molecule guards and filter this sequence. Take `headOption` of the resulting sequence.

      // Map of molecule values for molecules that are inputs to this reaction.
      val initRelevantMap = moleculesPresent.getMap.filterKeys(m => inputMoleculesSet.contains(m))

      // A simpler, non-flatMap algorithm for the case when there are no cross-dependencies of molecule values.
      val foundResult: Option[Map[Int, AbsMolValue[_]]] =
        if (info.crossGuards.isEmpty && info.crossConditionals.isEmpty) {
          // Adding `toStream` so that this becomes `info.inputsSorted.toStream.flatFoldLeft...` will slow down the Game of Life benchmark by 2x.
          info.inputsSorted.flatFoldLeft[(Map[Int, AbsMolValue[_]], BagMap)]((Map(), initRelevantMap)) { (prev, inputInfo) =>
            // Since we are in a flatFoldLeft, we need to return Some(...) if we found a new value, or else return None.
            val (prevValues, prevRelevantMap) = prev
            val valuesMap: Map[AbsMolValue[_], Int] = prevRelevantMap.getOrElse(inputInfo.molecule, Map())
            valuesMap.keysIterator
              .find(v => inputInfo.admitsValue(v))
              .map { newMolValue =>
                val newRelevantMap = removeFromBagMap(prevRelevantMap, inputInfo.molecule, newMolValue)
                val newValues = prevValues.updated(inputInfo.index, newMolValue)
                (newValues, newRelevantMap)
              }
          }.map(_._1) // Now remove BagMap, and only `Option[Map[Int, AbsMolValue[_]]]` is left.
        } else {
          // TODO: only use the `flatMap-fold` separately for the clusters of interdependent molecules, not always for all molecules!
          val found: Stream[Map[Int, AbsMolValue[_]]] =
            info.inputsSorted // We go through all molecules in the order of decreasing strength of conditionals.
              .foldLeft[Stream[(Map[Int, AbsMolValue[_]], BagMap)]](Stream((Map(), initRelevantMap))) { (prev, inputInfo) =>
              // In this `foldLeft` closure:
              // `prev` contains the molecule value assignments we have found so far (`prevValues`), as well as the map `prevRelevantMap` containing molecule values that would remain in the soup after these previous molecule values were removed.
              // `inputInfo` describes the pattern matcher for the input molecule we are currently required to find.
              // We need to find all admissible assignments of values for that input molecule, and return them as a stream of pairs (newValues, newRelevantMap).
              prev.flatMap {
                case (prevValues, prevRelevantMap) =>
                  val valuesMap: Map[AbsMolValue[_], Int] = prevRelevantMap.getOrElse(inputInfo.molecule, Map())
                  val newFound = for {
                    newMolValue <-
                    // This does not work... not sure why.
                    //if (inputInfo.molecule === m) Some(molValue).toStream // In this case, we need to use the given value, which is guaranteed to be in the value map.
                    //              else
                    if (inputInfo.flag.isTrivial && moleculeIsIndependent(inputInfo.index))
                    // If this molecule is independent of others and has a trivial matcher, it suffices to select any of the existing values for that molecule.
                      valuesMap.headOption.map(_._1).toStream
                    else // Do not eagerly evaluate the list of all possible values.
                      valuesMap.keysIterator.toStream.filter(v => inputInfo.admitsValue(v))

                    newRelevantMap = removeFromBagMap(prevRelevantMap, inputInfo.molecule, newMolValue)
                    newValues = prevValues.updated(inputInfo.index, newMolValue)
                  } yield (newValues, newRelevantMap)
                  newFound
              }
            }.map(_._1) // Get rid of BagMap and tuple.

          // The reaction can run if `found` contains at least one admissible list of input molecules; just one is sufficient.

          // Evaluate all cross-molecule guards: they must all pass on the chosen molecule values.
          val filteredAfterCrossGuards = if (info.crossGuards.nonEmpty) found.filter { inputValues =>
            info.crossGuards.forall {
              case CrossMoleculeGuard(indices, _, cond) =>
                cond.isDefinedAt(indices.flatMap(i => inputValues.get(i).map(_.getValue)).toList)
            }
          } else {
            // Here, we don't have any cross-molecule guards, but we do have some cross-molecule conditionals.
            // Those are already taken into account by the `flatMap-fold`. So, we don't need to filter the `found` result any further.
            found
          }

          // Return result if found something. Assign the found molecule values into the `inputs` array.
          filteredAfterCrossGuards.headOption
        }
      foundResult.map { inputValues =>
        val inputs = assignInputsToArray(inputValues)
        (this, inputs)
      }
    }
  }

}

