package co.ledger.lama.bitcoin.api.models

import java.util.UUID

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

  case class UpdateRequest(syncFrequency: Long)

  object UpdateRequest {
    implicit val encoder: Encoder[UpdateRequest] = deriveConfiguredEncoder[UpdateRequest]
    implicit val decoder: Decoder[UpdateRequest] = deriveConfiguredDecoder[UpdateRequest]
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
