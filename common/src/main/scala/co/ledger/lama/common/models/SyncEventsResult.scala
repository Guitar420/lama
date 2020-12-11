package co.ledger.lama.common.models

import co.ledger.lama.manager.protobuf
import co.ledger.lama.common.models.implicits._
import io.circe.{Decoder, Encoder}
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}

case class SyncEventsResult[T](syncEvents: List[SyncEvent[T]], total: Int)

object SyncEventsResult {

  implicit def decoder[T: Decoder]: Decoder[SyncEventsResult[T]] =
    deriveConfiguredDecoder[SyncEventsResult[T]]
  implicit def encoder[T: Encoder]: Encoder[SyncEventsResult[T]] =
    deriveConfiguredEncoder[SyncEventsResult[T]]

  def fromProto[T](
      proto: protobuf.GetSyncEventsResult
  )(implicit dec: Decoder[T]): SyncEventsResult[T] =
    SyncEventsResult[T](
      proto.syncEvents.map(SyncEvent.fromProto[T]).toList,
      proto.total
    )

}
