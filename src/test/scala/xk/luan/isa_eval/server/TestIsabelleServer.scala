package xk.luan.isa_eval
package server

import org.scalatest.funsuite.AnyFunSuite
import os.Path


class TestIsabelleServer extends AnyFunSuite {
  val isaPath: Path = os.Path("/home/xiaokun/opt/Isabelle2023")
  val sessionRoots: Option[Path] = Some(os.Path("/home1/afp-repo/afp-2023/thys"))

  test("Test initialize, destroy, and proceedUntil") {
    val is = new IsabelleServer(
      isaPath = isaPath,
      sessionName = "Completeness",
      workingDirectory = os.Path("/home1/afp-repo/afp-2023/thys/Completeness"),
      sessionRoots = sessionRoots
    )
    val thyPath = os.Path("/home1/afp-repo/afp-2023/thys/Completeness/Completeness.thy")
    is.proceedUntil(thyPath, 935, after = true, timeout = 300)
    is.proceedUntil(thyPath, "qed")
    is.close()
  }

  test("Test executeCommands") {
    val is = new IsabelleServer(
      isaPath = isaPath,
      sessionName = "Main",
      workingDirectory = isaPath / "src" / "HOL",
      sessionRoots = sessionRoots
    )
    val outcome1 = is.proceedUntil(os.pwd / "src" / "main" / "resources" / "Test.thy", "lemma test: \"p ==> q\n==> p\"")
    println(is.getState(outcome1.stateId).proofStateDescription(is.isabelle))
    val outcome = is.executeCommands("qed", outcome1.stateId)
    println(outcome)
    println(is.getState(outcome.stateId).proofLevel(is.isabelle))
    val newOutcome = is.executeCommands("by simp", outcome1.stateId)
    println(newOutcome)
    println(is.getState(newOutcome.stateId).proofLevel(is.isabelle))
  }

  test("Test executeCommands again") {
    val is = new IsabelleServer(
      isaPath = isaPath,
      sessionName = "Main",
      workingDirectory = isaPath / "src" / "HOL",
      sessionRoots = sessionRoots
    )
    val init = is.proceedUntil(os.pwd / "src" / "main" / "resources" / "Test.thy", 5, after = true, timeout = 300)
    val newStateId = is.cloneState(init.stateId)
    val outcome = is.executeCommands("qed", newStateId)
    println(outcome)
    val anotherOutcome = is.executeCommands("by simp", init.stateId)
    println(anotherOutcome)
    is.removeState(newStateId)
    println(is.stateSummary)
  }

  test("Test executeMultipleCommands") {
    val is = new IsabelleServer(
      isaPath = isaPath,
      sessionName = "Main",
      workingDirectory = isaPath / "src" / "HOL",
      sessionRoots = sessionRoots
    )
    val outcome = is.proceedUntil(os.pwd / "src" / "main" / "resources" / "Test.thy", 5, after = true, timeout = 300)
    println(is.getState(outcome.stateId).proofStateDescription(is.isabelle))
    val outcomes = is.executeMultipleCommands(List("by simp", "by auto", "qed", ".", "qe"), outcome.stateId)
    println(outcomes)
    println(outcomes.map(c => is.getState(c.stateId).proofLevel(is.isabelle)))
    println(is.stateSummary)
    outcomes.filter(_.isFailure).foreach(o => is.removeState(o.stateId))
  }

  test("Test tryCommands") {
    val is = new IsabelleServer(
      isaPath = isaPath,
      sessionName = "Main",
      workingDirectory = isaPath / "src" / "HOL",
      sessionRoots = sessionRoots
    )
    val outcome = is.proceedUntil(os.pwd / "src" / "main" / "resources" / "Test.thy", 5, after = true, timeout = 300)
    val trialResult = is.tryCommands(List("qed", "..", "by simp", "by auto"), outcome.stateId)
    println(trialResult)
    println(is.stateSummary)
  }

  test("Test callSledgehammer") {
    val is = new IsabelleServer(
      isaPath = isaPath,
      sessionName = "Main",
      workingDirectory = isaPath / "src" / "HOL",
      sessionRoots = sessionRoots
    )
    val outcome = is.proceedUntil(os.pwd / "src" / "main" / "resources" / "Test.thy", 5, after = true, timeout = 300)
    val result = is.callSledgehammer(outcome.stateId)
    println(result)
    println(is.stateSummary)
  }
}
