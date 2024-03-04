package xk.luan.isa_eval
package server

import scala.util.{Failure, Success}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{Duration, SECONDS}

import de.unruh.isabelle.control.{
  Isabelle,
  IsabelleMLException,
  OperationCollection
}
import de.unruh.isabelle.pure.{Theory, ToplevelState, Transition}
import de.unruh.isabelle.pure.ToplevelState.Modes
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._
import de.unruh.isabelle.mlvalue.MLValue.compileFunction
import de.unruh.isabelle.control.Isabelle.executionContext

import IsabelleServer.Ops
import manager.TheoryManager
import util.Utils

object IsabelleCommandCollection {
  val proofCommands: List[String] = List(
    "lemma",
    "theorem",
    "corollary",
    "proposition",
    "schematic_goal",
    "interpretation",
    "global_interpretation",
    "sublocale",
    "instance",
    "notepad",
    "function",
    "termination",
    "specification",
    "old_rep_datatype",
    "typedef",
    "functor",
    "quotient_type",
    "lift_definition",
    "quotient_definition",
    "bnf",
    "subclass"
  )
}

class IsabelleServer(
    val isaPath: os.Path,
    val sessionName: String,
    val workingDirectory: os.Path,
    val sessionRoots: Option[os.Path] = None
) {
  private val setup: Isabelle.Setup = Isabelle.Setup(
    isabelleHome = isaPath.toNIO,
    logic = sessionName,
    userDir = None,
    workingDirectory = workingDirectory.toNIO,
    sessionRoots =
      if (sessionRoots.isEmpty) Nil else Seq(sessionRoots.get.toNIO),
    build = false
  )
  implicit val isabelle: Isabelle = new Isabelle(setup)
  private val theoryManager = new TheoryManager(sessionName)
  val sessionFilesMap: Map[String, List[os.Path]] = {
    if (os.exists(workingDirectory / "ROOT"))
      Utils.parseRootFile(workingDirectory / "ROOT")
    else {
      Map(
        sessionName ->
          os.list(workingDirectory).filter(_.toString.endsWith(".thy")).toList
      )
    }
  }

  private val stateMap: collection.mutable.Map[String, ToplevelState] =
    collection.mutable.Map()

  private def cloneState(state: ToplevelState, newId: String): Unit = {
    val clone = state.mlValue.force.retrieveNow
    stateMap(newId) = clone
  }

  def cloneState(stateId: String): String = {
    val newId = java.util.UUID.randomUUID.toString
    cloneState(stateMap(stateId), newId)
    newId
  }

  def cloneState(stateId: String, repeat: Int): List[String] =
    (1 to repeat)
      .map(_ => {
        cloneState(stateId)
      })
      .toList

  def getState: ToplevelState = stateMap("default")

  def getState(stateId: String): ToplevelState = stateMap(stateId)

  def removeState(stateId: String): Unit =
    stateMap.remove(stateId)

  def clearAndRenameState(stateId: String, newStateId: String): Unit = {
    if (stateId == newStateId) {
      stateMap.keys.foreach(k => if (k != stateId) stateMap.remove(k))
    } else {
      val state = stateMap(stateId).mlValue.force.retrieveNow
      stateMap.clear()
      stateMap(newStateId) = state
    }
  }

  def getProofLevel(stateId: String): Int = stateMap(stateId).proofLevel

  def stateDescription(stateId: String): String = {
    val state = stateMap(stateId)
    val mode = state.mode match {
      case Modes.Proof        => "Proof"
      case Modes.Toplevel     => "Toplevel"
      case Modes.Theory       => "Theory"
      case Modes.LocalTheory  => "LocalTheory"
      case Modes.SkippedProof => "SkippedProof"
    }
    val description = s"Mode: $mode"
    if (state.mode == Modes.Proof) {
      val level = state.proofLevel(isabelle)
      s"$description\nLevel: $level\n${state.proofStateDescription}"
    } else {
      description
    }
  }

  def stateSummary: String = {
    stateMap
      .map { case (id, state) =>
        s"Id: $id\n${stateDescription(id)}\n"
      }
      .mkString("\n")
  }

  private def initialize(
      thyPath: os.Path,
      removeComments: Boolean = true
  ): (Theory, ToplevelState, List[(Transition, String)]) = {
    val thyText = os.read(thyPath)
    val thy = theoryManager.beginTheory(thyText, thyPath, sessionFilesMap)
    val toplevelState = theoryManager.initToplevel()
    val transitionTextPairs =
      theoryManager.getThyTransitions(thy, thyText, removeComments)
    (thy, toplevelState, transitionTextPairs)
  }

  def close(): Unit = isabelle.destroy()

  def executeCommands(
      commands: String,
      stateId: String = "default",
      timeout: Int = 30
  ): IsabelleOutcome = {
    var message: Option[String] = None
    val state = stateMap(stateId)
    val newState =
      try {
        Transition.parseOuterSyntax(state.theory, commands).foldLeft(state) {
          case (st, (tr, _)) =>
            tr.execute(st, timeout = Duration(timeout, SECONDS))
        }
      } catch {
        case e: IsabelleMLException =>
          message = Some(e.getMessage)
          state
      }
    stateMap.update(stateId, newState)
    IsabelleOutcome(
      stateId,
      getResult(message),
      newState.proofLevel,
      message
    )
  }

  def executeMultipleCommands(
      commands: List[String],
      stateId: String = "default",
      timeout: Int = 30
  ): List[IsabelleOutcome] = {
    val state = stateMap(stateId)
    val originProofLevel = state.proofLevel
    val newStateIds = cloneState(stateId, commands.length)
    val statesFuture = Future.traverse(
      newStateIds zip commands.map(Transition.parseOuterSyntax(state.theory, _))
    ) { case (id, trs) =>
      Future(
        try {
          Success(asyncExecute(trs.map(_._1), stateMap(id), timeout))
        } catch {
          case e: IsabelleMLException => Failure(e)
        }
      )
    }
    val outcomeFuture = statesFuture.map { states =>
      states.zip(newStateIds).map { case (st, id) =>
        if (st.isSuccess) {
          stateMap.update(id, st.get)
          IsabelleOutcome(id, "SUCCESS", st.get.proofLevel)
        } else {
          val message = Some(st.failed.get.getMessage)
          IsabelleOutcome(id, getResult(message), originProofLevel, message)
        }
      }
    }
    Await.result(outcomeFuture, Duration.Inf)
  }

  def tryCommands(
      commands: List[String],
      stateId: String = "default",
      timeout: Int = 30
  ): IsabelleTrialResult = {
    val outcomes = executeMultipleCommands(commands, stateId, timeout)
    if (outcomes.forall(o => !o.isSuccess)) {
      // remove all new states
      outcomes.foreach(o => stateMap.remove(o.stateId))
      // clone the given state
      val clonedStateId = cloneState(stateId)
      val outcome = IsabelleOutcome(
        clonedStateId,
        "ERROR", // note: we return "ERROR" even if there is a timeout
        stateMap(clonedStateId).proofLevel,
        Some(
          outcomes.map(_.message.getOrElse("")).mkString("MSG:\n", "MSG:\n", "")
        )
      )
      IsabelleTrialResult(outcome)
    } else {
      val (outcome, command) = outcomes.zip(commands).find(_._1.isSuccess).get
      outcomes.foreach(o =>
        if (o.stateId != outcome.stateId) stateMap.remove(o.stateId)
      )
      IsabelleTrialResult(outcome, Some(command))
    }
  }

  def callSledgehammer(
      stateId: String,
      timeout: Int = 10,
      sledgehammerTimeout: Int = 30
  ): IsabelleOutcome = {
    val state = stateMap(stateId)
    val (found, _, commands) =
      theoryManager.applySledgehammer(state, state.theory, sledgehammerTimeout)
    if (!found) {
      val clonedStateId = cloneState(stateId)
      IsabelleOutcome(
        clonedStateId,
        "ERROR",
        stateMap(clonedStateId).proofLevel,
        Some("No proof found")
      )
    } else {
      val trailResult = tryCommands(commands, stateId, timeout)
      if (trailResult.isSuccess) {
        // note: this is the only exception where a "SUCCESS" comes with a nonempty message
        IsabelleOutcome(
          trailResult.outcome.stateId,
          "SUCCESS",
          trailResult.outcome.proofLevel,
          Some(trailResult.command.get)
        )
      } else
        trailResult.outcome
    }
  }

  def proceedUntil(
      thyPath: os.Path,
      lineNum: Int,
      after: Boolean,
      timeout: Int
  ): IsabelleOutcome = {
    stateMap.clear()
    val (_, state, transitions) = initialize(thyPath)
    stateMap("default") = state
    var message: Option[String] = None
    val newState =
      transitions
        .takeWhile { case (tr, _) =>
          tr.position.line.getOrElse(0) + (if (after) 0 else 1) <= lineNum
        }
        .foldLeft(state) { case (st, (tr, _)) =>
          tr.execute(st, timeout = Duration(timeout, SECONDS))
        }
    stateMap.update("default", newState)
    IsabelleOutcome(
      "default",
      getResult(message),
      newState.proofLevel,
      message
    )
  }

  def proceedUntil(
      thyPath: os.Path,
      content: String,
      after: Boolean = true,
      timeout: Int = 300
  ): IsabelleOutcome = {
    val thyText = os.read(thyPath)
    val contentCharIndex = thyText.indexOf(content)
    val numOfLines =
      thyText.substring(0, contentCharIndex + content.length).count(_ == '\n')
    proceedUntil(thyPath, numOfLines + 1, after, timeout)
  }

  private def asyncExecute(
      transitions: List[Transition],
      state: ToplevelState,
      timeout: Int = 30
  ): ToplevelState = {
    Ops
      .commandExceptionWithTimeout(
        timeout * 1000 * transitions.length,
        true,
        transitions,
        state
      )
      .retrieveNow
      .force
  }

  def getTheoryCommands(
      thyPath: os.Path,
      onlyStatements: Boolean = false,
      removeIgnored: Boolean = true
  ): List[(String, String, Int)] = {
    val (_, _, transitions) = initialize(thyPath)
    val filteredTransitions = transitions.filter { case (tr, _) =>
      val notIgnored = if (removeIgnored) !tr.isIgnored else true
      val retained =
        if (onlyStatements)
          IsabelleCommandCollection.proofCommands.contains(tr.name)
        else true
      notIgnored && retained
    }
    filteredTransitions
      .map { case (tr, cmd) =>
        (cmd, tr.name, tr.position.line.getOrElse(0))
      }
  }

  private def getResult(
      message: Option[String]
  ): String = {
    if (message.isEmpty) "SUCCESS"
    else if (message.get.contains("Timeout after")) "TIMEOUT"
    else "ERROR"
  }
}

object IsabelleServer extends OperationCollection {
  // noinspection TypeAnnotation
  protected final class Ops(implicit isabelle: Isabelle) {
    lazy val commandExceptionWithTimeout =
      compileFunction[Long, Boolean, List[
        Transition
      ], ToplevelState, ToplevelState]("""fn (timeout, int, trs, st) =>
          |  Timeout.apply (Time.fromMilliseconds timeout) (fold (Toplevel.command_exception int) trs) st
        """.stripMargin)
  }

  override protected def newOps(implicit isabelle: Isabelle) =
    new this.Ops
}
