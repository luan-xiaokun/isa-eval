package xk.luan.isa_eval
package server

case class IsabelleOutcome(
    stateId: String,
    result: String,
    proofLevel: Int,
    message: Option[String] = None
) {
  def isSuccess: Boolean = result == "SUCCESS"
  def isFailure: Boolean = result == "ERROR"
  def getMessage: String = message.getOrElse("")
}

case class IsabelleTrialResult(
    outcome: IsabelleOutcome,
    command: Option[String] = None
) {
  def isSuccess: Boolean = outcome.isSuccess
  def isFailure: Boolean = outcome.isFailure
  def getMessage: String = outcome.getMessage
}
