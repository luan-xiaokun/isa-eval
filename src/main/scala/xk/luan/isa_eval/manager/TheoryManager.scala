package xk.luan.isa_eval
package manager

import java.nio.file.{Path, Paths}

import de.unruh.isabelle.control.{Isabelle, OperationCollection}
import de.unruh.isabelle.mlvalue.MLValue.{compileFunction, compileFunction0}
import de.unruh.isabelle.mlvalue.Version
import de.unruh.isabelle.pure.{Theory, TheoryHeader, ToplevelState, Transition}
import de.unruh.isabelle.mlvalue.Implicits._
import de.unruh.isabelle.pure.Implicits._

import TheoryManager.Ops

class TheoryManager(val sessionName: String)(implicit
    isabelle: Isabelle
) {
  def getThyTransitions(
      thy: Theory,
      thyText: String,
      removeComments: Boolean = false
  ): List[(Transition, String)] = {
    val transitions = Transition.parseOuterSyntax(thy, thyText)
    if (removeComments) transitions.filterNot({ case (_, text) =>
      text.isEmpty || (text.startsWith("(*") && text.endsWith("*)"))
    })
    else transitions
  }

  /** An imported theory may be in one of the following forms:
    *
    * (1) X.Y where X is a session name and Y is the theory name. (2) Y where Y is the theory name. (3) relative path to
    * the current theory file. (4) "~~/src/..." path
    *
    * We treat the first three cases all as a file path (even though X.Y is not a path). First, we get the normalized
    * path of the imported theory relative to the current theory file. Then, we check if the path is in
    * `sessionFilesMap(sessionName)`. If so, the imported theory must be in second or the third form, and we can import
    * it by using `${sessionName}.Y`. Otherwise, the imported theory may be in the first form so that we can import it
    * as it is. It could also be in the second or the third form but not in `sessionFilesMap(sessionName)`, this
    * indicates that this theory is not correctly imported.
    *
    * For the forth case, there is only one well-formatted theory file used this import in AFP. So our solution will be
    * very specific to it. Such usage should be avoided in practice.
    */
  private def getTheorySource(
      name: String,
      sessionFilesMap: Map[String, List[os.Path]]
  ): String = {
    val sanitisedName = name.stripPrefix("\"").stripSuffix("\"")
    // 1. deal with corner case (only one case in AFP)
    if (sanitisedName.startsWith("~~/src/")) {
      if (!sanitisedName.startsWith("~~/src/HOL/Library/"))
        throw new Exception(s"Unsupported import $name")
      return s"HOL-Library.${sanitisedName.stripPrefix("~~/src/HOL/Library/")}"
    }
    // 2. deal with path case and X.Y case
    val thyName = sanitisedName.split("/").last
    val sessionFiles = sessionFilesMap.getOrElse(sessionName, Nil)
    if (sessionFiles.exists(_.last == s"$thyName.thy"))
      s"$sessionName.$thyName"
    else sanitisedName
  }

  def initToplevel(): ToplevelState = Ops.init_toplevel().force.retrieveNow

  def beginTheory(
      text: String,
      path: os.Path,
      sessionFilesMap: Map[String, List[os.Path]]
  ): Theory = {
    val header = TheoryHeader.read(text)
    val masterDir = Option(path.toNIO.getParent).getOrElse(Paths.get(""))
    Ops
      .begin_theory(
        masterDir,
        header,
        header.imports
          .map(header => getTheorySource(header, sessionFilesMap))
          .map(Theory.apply)
      )
      .force
      .retrieveNow
  }

  def applySledgehammer(
      toplevelState: ToplevelState,
      theory: Theory,
      sledgehammerTimeout: Int = 30
  ): (Boolean, List[List[Transition]], List[String]) = {
    val Sledgehammer: String = theory.importMLStructureNow("Sledgehammer")
    val Sledgehammer_Commands: String =
      theory.importMLStructureNow("Sledgehammer_Commands")
    val Sledgehammer_Prover: String =
      theory.importMLStructureNow("Sledgehammer_Prover")
    val provers: String =
      if (Version.from2022) "cvc5 vampire verit e spass z3 zipperposition"
      else "cvc4 vampire verit e spass z3 zipperposition"
    val outcome: String =
      if (Version.from2022)
        f"$Sledgehammer.short_string_of_sledgehammer_outcome (fst (snd result))"
      else "fst (snd result)"
    val apply_sledgehammer = compileFunction[ToplevelState, Theory, List[
      String
    ], List[String], (Boolean, (String, List[String]))](
      s"""fn (state, thy, adds, dels) =>
         |  let
         |    val ret: string list Synchronized.var = Synchronized.var "sledgehammer_output" [];
         |    fun get_refs_and_token_lists (name) = (Facts.named name, []);
         |    val adds_refs_and_token_lists = map get_refs_and_token_lists adds;
         |    val dels_refs_and_token_lists = map get_refs_and_token_lists dels;
         |    val override = {add=adds_refs_and_token_lists,del=dels_refs_and_token_lists,only=false};
         |    val params = $Sledgehammer_Commands.default_params thy
         |                 [("provers","$provers"),("timeout","${sledgehammerTimeout.toString}"),("verbose","true")];
         |    val p_state = Toplevel.proof_of state;
         |    fun hack ret string =
         |      let
         |        fun find c s = CharVector.foldri (fn (i, c', ret) => if c' = c then i :: ret else ret) [] s;
         |        val inds = find #"\\^E" string;
         |      in
         |        if length inds < 4 then tracing string
         |        else let
         |          val i2 = List.nth (inds, 1);
         |          val i3 = List.nth (inds, 2);
         |          val s_prf = String.substring (string, i2+1, i3-1-i2);
         |          val _ = Synchronized.change ret (fn prfs => s_prf :: prfs);
         |        in () end
         |      end;
         |    val result = $Sledgehammer.run_sledgehammer params $Sledgehammer_Prover.Normal (SOME (hack ret)) 1 override p_state;
         |  in
         |    (fst result, ($outcome, Synchronized.value ret))
         |  end""".stripMargin
    )
    val result = apply_sledgehammer(
      toplevelState,
      theory,
      List(),
      List()
    ).force.retrieveNow
    val tactics = result._2._2.map(
      _.stripPrefix("Try this: ").replaceAll(raw" \(\d+ ms\)", "")
    )
    val parseResults =
      tactics.map(tactic => Transition.parseOuterSyntax(theory, tactic))
    val transitions = parseResults.map(_.map(_._1))
    val commands = parseResults.map(_.map(_._2).mkString(" "))
    (result._1, transitions, commands)
  }
}

object TheoryManager extends OperationCollection {
  // noinspection TypeAnnotation
  protected final class Ops(implicit isabelle: Isabelle) {
    val init_toplevel = compileFunction0[ToplevelState](
      if (Version.from2023) "fn () => Toplevel.make_state NONE"
      else "Toplevel.init_toplevel"
    )

    val begin_theory = compileFunction[Path, TheoryHeader, List[
      Theory
    ], Theory](
      "fn (path, header, parents) => Resources.begin_theory path header parents"
    )
  }

  override protected def newOps(implicit isabelle: Isabelle) =
    new this.Ops
}
