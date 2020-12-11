package co.ledger.lama.manager

import java.time.Instant
import java.util.UUID

import co.ledger.lama.common.models._
import co.ledger.lama.manager.models.implicits._
import doobie.postgres.implicits._
import doobie.util.Read
import io.circe.JsonObject

package object models {

  case class AccountSyncStatus(
      id: UUID,
      key: String,
      coinFamily: CoinFamily,
      coin: Coin,
      syncFrequency: Long,
      label: Option[String],
      syncId: UUID,
      status: Status,
      cursor: Option[JsonObject],
      error: Option[ReportError],
      updated: Instant
  )

  object AccountSyncStatus {
    implicit val doobieRead: Read[AccountSyncStatus] =
      Read[(AccountInfo, UUID, Status, Option[JsonObject], Option[ReportError], Instant)].map {
        case (
              accountInfo,
              syncId,
              status,
              cursor,
              error,
              updated
            ) =>
          AccountSyncStatus(
            accountInfo.id,
            accountInfo.key,
            accountInfo.coinFamily,
            accountInfo.coin,
            accountInfo.syncFrequency,
            accountInfo.label,
            syncId,
            status,
            cursor,
            error,
            updated
          )
      }
  }
}
