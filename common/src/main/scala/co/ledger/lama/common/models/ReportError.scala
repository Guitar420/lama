package co.ledger.lama.common.models

import io.circe.{Decoder, Encoder}
import io.circe.generic.extras.semiauto._

import co.ledger.lama.common.models.implicits._

case class ReportError(code: String, message: String)

object ReportError {
  implicit val encoder: Encoder[ReportError] = deriveConfiguredEncoder[ReportError]
  implicit val decoder: Decoder[ReportError] = deriveConfiguredDecoder[ReportError]
}
