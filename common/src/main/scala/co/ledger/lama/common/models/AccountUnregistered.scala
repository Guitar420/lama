package co.ledger.lama.common.models

import java.util.UUID

import co.ledger.lama.common.utils.UuidUtils
import co.ledger.lama.manager.protobuf
import co.ledger.lama.common.models.implicits._
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}
import io.circe.{Decoder, Encoder}

case class AccountUnregistered(accountId: UUID, syncId: UUID)

object AccountUnregistered {
  implicit val decoder: Decoder[AccountUnregistered] =
    deriveConfiguredDecoder[AccountUnregistered]
  implicit val encoder: Encoder[AccountUnregistered] =
    deriveConfiguredEncoder[AccountUnregistered]

  def fromProto(proto: protobuf.UnregisterAccountResult): AccountUnregistered =
    AccountUnregistered(
      accountId = UuidUtils.bytesToUuid(proto.accountId).get,
      syncId = UuidUtils.bytesToUuid(proto.syncId).get
    )
}
