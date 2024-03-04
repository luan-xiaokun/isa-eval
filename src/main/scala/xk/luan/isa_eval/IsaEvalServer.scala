package xk.luan.isa_eval

import de.unruh.isabelle.control.IsabelleControllerException
import io.grpc.StatusException
import scalapb.zio_grpc.ServerMain
import scalapb.zio_grpc.ServiceList
import zio.ZIO
import zio.stream.ZStream
import xk.luan.isa_eval.server.{IsabelleOutcome, IsabelleServer}

class IsabelleServerException(status: io.grpc.Status)
    extends StatusException(status)

class IsaEvalServer(val debug: Boolean = false) extends ZioIsaEval.IsaEval {
  var isaServer: Option[IsabelleServer] = None

  private def tryWrapper[T](f: => T): T =
    try {
      f
    } catch {
      case e: IsabelleControllerException =>
        throw new IsabelleServerException(
          io.grpc.Status.INTERNAL.withDescription(e.getMessage)
        )
    }

  private def zioWrapper[T](f: => T): ZIO[Any, IsabelleServerException, T] =
    ZIO.attempt(tryWrapper(f)).refineToOrDie[IsabelleServerException]

  private def makeOutcomeState(outcome: IsabelleOutcome): OutcomeState =
    OutcomeState(
      outcome.stateId,
      outcome.result,
      outcome.getMessage,
      outcome.proofLevel,
      isaServer.get.stateDescription(outcome.stateId)
    )

  def setupIsabelle(
      request: Setup
  ): ZIO[Any, IsabelleServerException, Setup] = {
    for {
      _ <- zioWrapper {
        if (isaServer.nonEmpty) isaServer.get.close()
        isaServer = Some(
          new IsabelleServer(
            os.Path(request.isaPath),
            request.session,
            os.Path(request.workingDirectory),
            if (request.sessionRoots.isEmpty) None
            else Some(os.Path(request.sessionRoots))
          )
        )
      }
    } yield Setup(
      isaServer.get.isaPath.toString(),
      isaServer.get.sessionName,
      isaServer.get.workingDirectory.toString()
    )
  }

  def closeIsabelle(
      request: Empty
  ): ZIO[Any, IsabelleServerException, Empty] = {
    for {
      _ <- zioWrapper {
        if (isaServer.nonEmpty) isaServer.get.close()
        isaServer = None
      }
    } yield Empty()
  }

  def proceedUntil(
      request: TheoryContent
  ): ZIO[Any, IsabelleServerException, OutcomeState] = {
    for {
      outcome <- zioWrapper(
        isaServer.get.proceedUntil(
          os.Path(request.theory),
          request.content,
          after = true,
          request.timeout
        )
      )
    } yield makeOutcomeState(outcome)
  }

  def execute(
      request: ProofCommands
  ): ZIO[Any, IsabelleServerException, OutcomeState] = {
    for {
      outcome <- zioWrapper(
        isaServer.get.executeCommands(
          request.commands,
          request.id,
          request.timeout
        )
      )
    } yield makeOutcomeState(outcome)
  }

  def executeMany(
      request: zio.stream.Stream[StatusException, ProofCommands]
  ): ZIO[Any, IsabelleServerException, OutcomeStateStream] = {
    request.runCollect.map(prfCommands => {
      val outcomes = isaServer.get
        .executeMultipleCommands(
          prfCommands.map(_.commands).toList,
          prfCommands.head.id,
          prfCommands.head.timeout
        )
      val outcomeString = outcomes.map { outcome =>
        s"<STATE>${outcome.stateId}" +
          s"<RESULT>${outcome.result}" +
          s"<MSG>${outcome.getMessage}" +
          s"<LEVEL>${outcome.proofLevel}" +
          s"<DESCR>${isaServer.get.stateDescription(outcome.stateId)}"
      }
      .mkString("<OUTCOME_SEP>")
      OutcomeStateStream(outcomeString)
    }).refineToOrDie[IsabelleServerException]

//
//    ZStream.fromIterableZIO(
//      request.runCollect
//        .map(prfCommands => {
//          tryWrapper(
//            isaServer.get
//              .executeMultipleCommands(
//                prfCommands.map(_.commands).toList,
//                prfCommands.head.id,
//                prfCommands.head.timeout
//              )
//              .map { outcome =>
//                OutcomeState(
//                  outcome.stateId,
//                  outcome.result,
//                  outcome.getMessage,
//                  outcome.proofLevel,
//                  isaServer.get.stateDescription(outcome.stateId)
//                )
//              }
//          )
//        })
//        .refineToOrDie[IsabelleServerException]
//    )
  }

  def callSledgehammer(
      request: SledgehammerRequest
  ): ZIO[Any, IsabelleServerException, OutcomeState] = {
    for {
      outcome <- zioWrapper(
        isaServer.get.callSledgehammer(
          request.id,
          request.timeout,
          request.sledgehammerTimeout
        )
      )
    } yield makeOutcomeState(outcome)
  }

  def getTheoryCommands(
      request: ParseRequest
  ): ZIO[Any, IsabelleServerException, IsabelleCommandStream] = {
    val commands = isaServer.get
        .getTheoryCommands(
          os.Path(request.theory),
          request.onlyStatements,
          request.removeIgnored
        )
    val commandsString = commands.map { case (cmd, name, line) =>
        s"<CMD>$cmd<NAME>$name<LINE>$line"
      }
      .mkString("<CMD_SEP>")
    ZIO.succeed(IsabelleCommandStream(commandsString))

//    ZStream.fromIterableZIO(
//      for {
//        commands <- zioWrapper {
//          isaServer.get
//            .getTheoryCommands(
//              os.Path(request.theory),
//              request.onlyStatements,
//              request.removeIgnored
//            )
//            .map { case (cmd, name, line) =>
//              println("sending", line, cmd.replace('\n', ' '))
//              IsabelleCommand(cmd, name, line)
//            }
//        }
//      } yield commands
//    )
  }

  def cloneState(
      request: StateRequest
  ): ZIO[Any, IsabelleServerException, OutcomeState] = {
    for {
      newStateId <- zioWrapper(isaServer.get.cloneState(request.id))
    } yield OutcomeState(
      newStateId,
      "SUCCESS",
      "",
      isaServer.get.getProofLevel(newStateId),
      isaServer.get.stateDescription(newStateId)
    )
  }

  def removeState(
      request: StateRequest
  ): ZIO[Any, IsabelleServerException, Empty] = {
    for {
      _ <- zioWrapper {
        isaServer.get.removeState(request.id)
      }
    } yield Empty()
  }

  def clearAndRename(
      request: ClearAndRenameRequest
  ): ZIO[Any, IsabelleServerException, OutcomeState] = {
    for {
      _ <- zioWrapper {
        isaServer.get.clearAndRenameState(request.id, request.newId)
      }
    } yield OutcomeState(
      request.newId,
      "SUCCESS",
      "",
      isaServer.get.getProofLevel(request.newId),
      isaServer.get.stateDescription(request.newId)
    )
  }

}

object IsaEvalServer extends ServerMain {
  override def port: Int = 8980

  override def services: ServiceList[Any] =
    ServiceList.add(new IsaEvalServer)
}
