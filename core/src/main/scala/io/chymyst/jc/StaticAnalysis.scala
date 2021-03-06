package io.chymyst.jc

import Core._

import scala.annotation.tailrec

private[jc] object StaticAnalysis {

  /** Check that every input molecule matcher of one reaction is weaker than a corresponding input matcher in another reaction.
    * If true, it means that the first reaction can start whenever the second reaction can start, which is an instance of unavoidable nondeterminism.
    *
    * @param input1 Sorted input list for the first reaction.
    * @param input2 Sorted input list  for the second reaction.
    * @return True if the first reaction is weaker than the second.
    */
  @tailrec
  private def allMatchersAreWeakerThan(input1: List[InputMoleculeInfo], input2: List[InputMoleculeInfo]): Boolean = {
    input1 match {
      case Nil =>
        true // input1 has no matchers left
      case info1 :: rest1 => input2 match {
        case Nil =>
          false // input1 has matchers but input2 has no matchers left
        case _ =>
          val isWeaker: InputMoleculeInfo => Boolean =
            i => info1.matcherIsWeakerThan(i).getOrElse(false)

          input2.find(isWeaker) match {
            case Some(correspondingMatcher) =>
              allMatchersAreWeakerThan(rest1, input2 difff List(correspondingMatcher))
            case None => false
          }
      }
    }
  }

  private def inputMatchersWeakerThanOutput(isWeaker: (InputMoleculeInfo, OutputMoleculeInfo) => Option[Boolean])
                                           (input: List[InputMoleculeInfo], output: Array[OutputMoleculeInfo]): Boolean = {
    input.flatFoldLeft[Array[OutputMoleculeInfo]](output) { (acc, inputInfo) =>
      output
        .find(outputInfo => isWeaker(inputInfo, outputInfo).getOrElse(false))
        .map { correspondingMatcher => acc diff Array(correspondingMatcher) }
    }.nonEmpty
  }

  private def inputMatchersSurelyWeakerThanOutput(input: List[InputMoleculeInfo], output: Array[OutputMoleculeInfo]): Boolean =
    inputMatchersWeakerThanOutput((inputInfo, outputInfo) => inputInfo.matcherIsWeakerThanOutput(outputInfo))(input, output.filter(_.atLeastOnce))

  private def inputMatchersAreSimilarToOutput(input: List[InputMoleculeInfo], output: Array[OutputMoleculeInfo]): Boolean =
    inputMatchersWeakerThanOutput((inputInfo, outputInfo) => inputInfo.matcherIsSimilarToOutput(outputInfo))(input, output)

  // Reactions whose inputs are all unconditional matchers and are a subset of inputs of another reaction:
  private def checkReactionShadowing(reactions: Seq[Reaction]): Option[String] = {
    val suspiciousReactions = for {
      r1 <- reactions
      if r1.info.guardPresence.effectivelyAbsent
      r2 <- reactions
      if r2 =!= r1
      if allMatchersAreWeakerThan(r1.info.inputsSorted, r2.info.inputsSorted)
    } yield (r1, r2)

    if (suspiciousReactions.nonEmpty) {
      val errorList = suspiciousReactions.map { case (r1, r2) =>
        s"reaction {${r2.info}} is shadowed by {${r1.info}}"
      }.mkString(", ")
      Some(s"Unavoidable nondeterminism: $errorList")
    } else None
  }

  // There should not be any two reactions whose source code is identical to each other.
  private def findIdenticalReactions(reactions: Seq[Reaction]): Option[String] = {
    val reactionsSha1 = reactions.map(_.info.sha1)
    val repeatedReactionSha1 = (reactionsSha1 difff reactionsSha1.distinct).distinct
    val repeatedReactions = repeatedReactionSha1.flatMap(sha1 => reactions.find(_.info.sha1 == sha1))

    if (repeatedReactions.nonEmpty) {
      val errorList = repeatedReactions.map { r => s"{${r.info}}" }.mkString(", ")
      Some(s"Identical repeated reactions: $errorList")
    } else None
  }

  private def checkSingleReactionLivelock(reactions: Seq[Reaction]): Option[String] = {
    val errorList = reactions
      .filter { r => r.info.guardPresence.effectivelyAbsent && inputMatchersSurelyWeakerThanOutput(r.info.inputsSorted, r.info.shrunkOutputs) }
      .map(r => s"{${r.info.toString}}")
    if (errorList.nonEmpty)
      Some(s"Unavoidable livelock: reaction${if (errorList.size == 1) "" else "s"} ${errorList.mkString(", ")}")
    else None
  }

  private def checkMultiReactionLivelock(reactions: Seq[Reaction]): Option[String] = {
    // TODO: implement
    None
  }

  private def checkSingleReactionLivelockWarning(reactions: Seq[Reaction]): Option[String] = {
    val warningList = reactions
      .filter { r => inputMatchersAreSimilarToOutput(r.info.inputsSorted, r.info.outputs) }
      .map(r => s"{${r.info.toString}}")
    if (warningList.nonEmpty)
      Some(s"Possible livelock: reaction${if (warningList.size == 1) "" else "s"} ${warningList.mkString(", ")}")
    else None
  }

  private def checkInputsForDeadlockWarning(reactions: Seq[Reaction]): Option[String] = {
    // A "possible deadlock" means that an input blocking molecule is consumed together with other molecules that are emitted later by the same reactions that emit the blocking molecule.
    val blockingInputsWithNonblockingInputs: Seq[(InputMoleculeInfo, List[InputMoleculeInfo])] =
      reactions.map(_.info.inputsSorted.partition(_.molecule.isBlocking)).filter(m => m._1.nonEmpty && m._2.nonEmpty)
        .flatMap { case (bInputs, mInputs) => bInputs.map(b => (b, mInputs)) }

    val likelyDeadlocks: Seq[(InputMoleculeInfo, InputMoleculeInfo, Reaction)] = for {
      bmInputs <- blockingInputsWithNonblockingInputs
      bInput = bmInputs._1
      mInput <- bmInputs._2
      possibleReactions = Set(bInput, mInput).flatMap(_.molecule.emittingReactions).toSeq
      reaction <- possibleReactions
      outputs = reaction.info.outputs
      outputMolecules = outputs.map(_.molecule)
      if outputMolecules.nonEmpty
      // Find a reaction that first emits bInput and then emits mInput, with stronger matchers than bInput and mInput respectively.
      if outputMolecules.contains(bInput.molecule)
      bInputIndex = outputMolecules.indexOf(bInput.molecule)
      mInputIndex = outputMolecules.indexOf(mInput.molecule)
      if mInputIndex > bInputIndex
      if bInput.matcherIsSimilarToOutput(outputs(bInputIndex)).getOrElse(false)
      if mInput.matcherIsSimilarToOutput(outputs(mInputIndex)).getOrElse(false)
    } yield {
      (bInput, mInput, reaction)
    }

    val warningList = likelyDeadlocks
      .map { case (bInput, mInput, reaction) => s"molecule (${bInput.molecule}) may deadlock due to (${mInput.molecule}) among the outputs of {${reaction.info}}" }
    if (warningList.nonEmpty)
      Some(s"Possible deadlock${if (warningList.size == 1) "" else "s"}: ${warningList.mkString("; ")}")
    else None
  }

  private def checkOutputsForDeadlockWarning(reactions: Seq[Reaction]): Option[String] = {
    // A "possible deadlock" means that an output blocking molecule is followed by other output molecules that the blocking molecule may be waiting for.
    val possibleDeadlocks: Seq[(OutputMoleculeInfo, List[OutputMoleculeInfo])] =
      reactions.map(_.info.outputs)
        .flatMap {
          _.tails.flatMap(_.toList match {
            case t :: ts => if (t.molecule.isBlocking) Some((t, ts)) else None
            case Nil => None
          })
        }
    // The chemistry is likely to be a deadlock if at least one the other output molecules are consumed together with the blocking molecule in the same reaction.
    val likelyDeadlocks = possibleDeadlocks.map {
      case (info, infos) =>
        (info, info.molecule.consumingReactions.flatMap(
          _.find { r =>
            // For each reaction that consumes the molecule `info.molecule`, check whether this reaction also consumes any of the molecules from infos.map(_.molecule). If so, it's a likely deadlock.
            val uniqueInputsThatAreAmongOutputs = r.info.inputsSorted
              .filter(infos.map(_.molecule) contains _.molecule)
              .groupBy(_.molecule).mapValues(_.lastOption).values.toList.flatten // Among repeated input molecules, choose only one molecule with the weakest matcher.

            uniqueInputsThatAreAmongOutputs.exists(infoInput =>
              infos.exists(infoOutput =>
                infoInput.matcherIsWeakerThanOutput(infoOutput).getOrElse(false))
            )
          }
        )
        )
    }

    val warningList = likelyDeadlocks
      .flatMap { case (info, reactionOpt) => reactionOpt.map(r => s"molecule ${info.molecule} may deadlock due to outputs of {${r.info}}") }
    if (warningList.nonEmpty)
      Some(s"Possible deadlock${if (warningList.size === 1) "" else "s"}: ${warningList.mkString("; ")}")
    else None
  }

  private[jc] def findGeneralErrors(reactions: Seq[Reaction]) = {
    Seq(
      checkReactionShadowing _,
      checkSingleReactionLivelock _,
      checkMultiReactionLivelock _
    ).flatMap(_ (reactions))
  }

  private[jc] def findStaticWarnings(reactions: Seq[Reaction]) = {
    Seq(
      findIdenticalReactions _,
      checkOutputsForDeadlockWarning _,
      checkInputsForDeadlockWarning _,
      checkSingleReactionLivelockWarning _
    ).flatMap(_ (reactions))
  }

  // Each static molecule should occur in some reaction as an input. No static molecule should be consumed twice by a reaction.
  // Each static molecule that is consumed by a reaction should also be emitted by the same reaction.
  private def checkInputsForStaticMols(staticMols: Map[Molecule, Int], reactions: Seq[Reaction]): Option[String] = {
    val staticMolsConsumedMaxTimes: Map[Molecule, (Reaction, Int)] =
      if (reactions.isEmpty)
        Map()
      else
        staticMols.map { case (m, _) => m -> reactions.map(r => (r, r.inputMolecules.count(_ === m))).maxBy(_._2) }

    val wrongConsumed = staticMolsConsumedMaxTimes
      .flatMap {
        case (mol, (reaction, 0)) =>
          Some(s"static molecule ($mol) not consumed by any reactions")
        case (mol, (reaction, 1)) =>
          None
        case (mol, (reaction, countConsumed)) =>
          Some(s"static molecule ($mol) consumed $countConsumed times by reaction ${reaction.info}")
      }

    val wrongOutput = staticMols.map {
      case (m, _) => m -> reactions.find(r => r.inputMolecules.count(_ === m) == 1 && !r.info.outputs.exists(_.molecule === m))
    }.flatMap {
      case (mol, Some(r)) =>
        Some(s"static molecule ($mol) consumed but not emitted by reaction ${r.info}")
      case _ => None
    }

    val errorList = wrongConsumed ++ wrongOutput

    if (errorList.nonEmpty)
      Some(s"Incorrect static molecule declaration: ${errorList.mkString("; ")}")
    else None
  }

  // No static molecule should be output by a reaction that does not consume it.
  // No static molecule should be output more than once by a reaction.
  private def checkOutputsForStaticMols(staticMols: Map[Molecule, Int], reactions: Seq[Reaction]): Option[String] = {
    val errorList = staticMols.flatMap {
      case (m, _) =>
        reactions.flatMap {
          r =>
            val outputTimes = r.info.outputs.count(_.molecule === m)
            if (outputTimes > 1)
              Some(s"static molecule ($m) emitted more than once by reaction ${r.info}")
            else if (outputTimes == 1 && !r.inputMoleculesSet.contains(m))
              Some(s"static molecule ($m) emitted but not consumed by reaction ${r.info}")
            else None
        }
    }

    if (errorList.nonEmpty)
      Some(s"Incorrect static molecule declaration: ${errorList.mkString("; ")}")
    else None
  }

  private[jc] def findStaticMolErrors(staticMols: Map[Molecule, Int], reactions: Seq[Reaction]) = {
    Seq(
      checkOutputsForStaticMols _,
      checkInputsForStaticMols _
    ).flatMap(_ (staticMols, reactions))
  }

  private[jc] def findStaticMolWarnings(staticMols: Map[Molecule, Int], reactions: Seq[Reaction]) = {
    // TODO: implement
    Seq()
  }


  private[jc] def findStaticMolDeclarationErrors(staticReactions: Seq[Reaction]): Seq[String] = {
    val foundErrors = staticReactions.map(_.info).filterNot(_.guardPresence.effectivelyAbsent)
    if (foundErrors.nonEmpty)
      foundErrors.map { info => s"Static reaction {$info} should not have a guard condition" }
    else Seq()
  }

  // Inspect the static molecules actually emitted. Their multiplicities must be not less than the declared multiplicities.
  private[jc] def findStaticMolsEmissionErrors(staticMolsDeclared: Map[Molecule, Int], staticMolsEmitted: Map[Molecule, Int]): Seq[String] = {
    val foundErrors = staticMolsDeclared
      .filter { case (mol, count) => staticMolsEmitted.getOrElse(mol, 0) < count }
      .map { case (mol, count) =>
        val countEmitted = staticMolsEmitted.getOrElse(mol, 0)
        s"$mol emitted $countEmitted times instead of $count"
      }
    if (foundErrors.nonEmpty)
      Seq(s"Too few static molecules emitted: ${foundErrors.toList.sorted.mkString(", ")}")
    else Seq()
  }

  // Inspect the static molecules actually emitted. Their multiplicities must be not more than the declared multiplicities.
  private[jc] def findStaticMolsEmissionWarnings(staticMolsDeclared: Map[Molecule, Int], staticMolsEmitted: Map[Molecule, Int]) = {
    val foundErrors = staticMolsDeclared
      .filter { case (mol, count) => staticMolsEmitted.getOrElse(mol, 0) > count }
      .map { case (mol, count) =>
        val countEmitted = staticMolsEmitted.getOrElse(mol, 0)
        s"$mol emitted $countEmitted times instead of $count"
      }
    if (foundErrors.nonEmpty)
      Seq(s"Possibly too many static molecules emitted: ${foundErrors.toList.sorted.mkString(", ")}")
    else Seq()
  }

}
