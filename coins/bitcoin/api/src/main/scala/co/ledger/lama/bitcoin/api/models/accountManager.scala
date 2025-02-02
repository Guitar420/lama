package co.ledger.lama.bitcoin.api.models

import java.util.UUID

import cats.syntax.functor._
import co.ledger.lama.bitcoin.common.models.{BitcoinNetwork, Scheme}
import co.ledger.lama.common.models.implicits._
import io.circe.{Decoder, Encoder, JsonObject}
import io.circe.generic.extras.semiauto._
import co.ledger.lama.common.models.{Coin, CoinFamily, SyncEvent}

object accountManager {

  case class AccountWithBalance(
      accountId: UUID,
      coinFamily: CoinFamily,
      coin: Coin,
      syncFrequency: Long,
      lastSyncEvent: Option[SyncEvent[JsonObject]],
      balance: BigInt,
      utxos: Int,
      received: BigInt,
      sent: BigInt,
      label: Option[String]
  )

  object AccountWithBalance {
    implicit val decoder: Decoder[AccountWithBalance] =
      deriveConfiguredDecoder[AccountWithBalance]
    implicit val encoder: Encoder[AccountWithBalance] =
      deriveConfiguredEncoder[AccountWithBalance]
  }

  sealed trait UpdateRequest
  case class UpdateSyncFrequencyAndLabel(syncFrequency: Long, label: String) extends UpdateRequest
  case class UpdateSyncFrequency(syncFrequency: Long)                        extends UpdateRequest
  case class UpdateLabel(label: String)                                      extends UpdateRequest

  object UpdateRequest {
    implicit val decoder: Decoder[UpdateRequest] =
      List[Decoder[UpdateRequest]](
        deriveConfiguredDecoder[UpdateSyncFrequencyAndLabel].widen,
        deriveConfiguredDecoder[UpdateSyncFrequency].widen,
        deriveConfiguredDecoder[UpdateLabel].widen
      ).reduceLeft(_ or _)

    implicit val updateSyncFrequencyEncoder: Encoder[UpdateSyncFrequency] =
      deriveConfiguredEncoder[UpdateSyncFrequency]
    implicit val updateLabelEncoder: Encoder[UpdateLabel] = deriveConfiguredEncoder[UpdateLabel]
  }

  case class CreationRequest(
      extendedPublicKey: String,
      label: Option[String],
      scheme: Scheme,
      lookaheadSize: Int,
      network: BitcoinNetwork,
      coinFamily: CoinFamily,
      coin: Coin,
      syncFrequency: Option[Long]
  )

  object CreationRequest {
    implicit val encoder: Encoder[CreationRequest] = deriveConfiguredEncoder[CreationRequest]
    implicit val decoder: Decoder[CreationRequest] = deriveConfiguredDecoder[CreationRequest]
  }

}
