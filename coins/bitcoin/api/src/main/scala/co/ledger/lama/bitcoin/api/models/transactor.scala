package co.ledger.lama.bitcoin.api.models

import co.ledger.lama.bitcoin.common.models.transactor.{
  CoinSelectionStrategy,
  PrepareTxOutput,
  RawTransaction
}
import co.ledger.lama.common.models.implicits._
import io.circe.{Decoder, Encoder}
import io.circe.generic.extras.semiauto._

object transactor {

  case class CreateTransactionRequest(
      coinSelection: CoinSelectionStrategy,
      outputs: List[PrepareTxOutput]
  )

  object CreateTransactionRequest {
    implicit val encoder: Encoder[CreateTransactionRequest] =
      deriveConfiguredEncoder[CreateTransactionRequest]
    implicit val decoder: Decoder[CreateTransactionRequest] =
      deriveConfiguredDecoder[CreateTransactionRequest]
  }

  case class BroadcastTransactionRequest(
      coinSelection: CoinSelectionStrategy,
      rawTransaction: RawTransaction,
      signatures: List[Array[Byte]]
  )

  object BroadcastTransactionRequest {
    implicit val encoder: Encoder[BroadcastTransactionRequest] =
      deriveConfiguredEncoder[BroadcastTransactionRequest]
    implicit val decoder: Decoder[BroadcastTransactionRequest] =
      deriveConfiguredDecoder[BroadcastTransactionRequest]
  }

  case class GenerateSignaturesRequest(
      rawTransaction: RawTransaction,
      privKey: String
  )

  object GenerateSignaturesRequest {
    implicit val encoder: Encoder[GenerateSignaturesRequest] =
      deriveConfiguredEncoder[GenerateSignaturesRequest]
    implicit val decoder: Decoder[GenerateSignaturesRequest] =
      deriveConfiguredDecoder[GenerateSignaturesRequest]
  }

}
