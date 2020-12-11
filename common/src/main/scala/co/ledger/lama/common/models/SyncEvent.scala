package co.ledger.lama.common.models

import java.time.Instant
import java.util.UUID

import io.circe.{Decoder, Encoder}
import io.circe.generic.extras.semiauto._
import io.circe.syntax.EncoderOps
import co.ledger.lama.manager.protobuf
import co.ledger.lama.common.models.implicits._
import co.ledger.lama.common.utils.{ByteStringUtils, TimestampProtoUtils, UuidUtils}

sealed trait SyncEvent[T] {
  def accountId: UUID
  def syncId: UUID
  def status: Status
  def cursor: Option[T]
  def error: Option[ReportError]
  def time: Instant

  def toProto(implicit enc: Encoder[T]): protobuf.SyncEvent =
    protobuf.SyncEvent(
      accountId = UuidUtils.uuidToBytes(accountId),
      syncId = UuidUtils.uuidToBytes(syncId),
      status = status.name,
      cursor = ByteStringUtils.serialize[T](cursor),
      error = ByteStringUtils.serialize[ReportError](error),
      time = Some(TimestampProtoUtils.serialize(time))
    )
}

object SyncEvent {

  implicit def encoder[T: Encoder]: Encoder[SyncEvent[T]] =
    Encoder.instance {
      case re: ReportableEvent[T]  => re.asJson
      case we: WorkableEvent[T]    => we.asJson
      case te: TriggerableEvent[T] => te.asJson
      case ne: FlaggedEvent[T]     => ne.asJson
    }

  implicit def decoder[T: Decoder]: Decoder[SyncEvent[T]] = {
    Decoder[ReportableEvent[T]]
      .map[SyncEvent[T]](identity)
      .or(Decoder[WorkableEvent[T]].map[SyncEvent[T]](identity))
      .or(Decoder[TriggerableEvent[T]].map[SyncEvent[T]](identity))
      .or(Decoder[FlaggedEvent[T]].map[SyncEvent[T]](identity))
  }

  def apply[T](
      accountId: UUID,
      syncId: UUID,
      status: Status,
      cursor: Option[T],
      error: Option[ReportError],
      time: Instant
  ): SyncEvent[T] =
    status match {
      case ws: WorkableStatus =>
        WorkableEvent(accountId, syncId, ws, cursor, error, time)
      case rs: ReportableStatus =>
        ReportableEvent(accountId, syncId, rs, cursor, error, time)
      case ts: TriggerableStatus =>
        TriggerableEvent(accountId, syncId, ts, cursor, error, time)
      case ns: FlaggedStatus =>
        FlaggedEvent(accountId, syncId, ns, cursor, error, time)
    }

  def fromProto[T](proto: protobuf.SyncEvent)(implicit dec: Decoder[T]): SyncEvent[T] =
    SyncEvent[T](
      UuidUtils.bytesToUuid(proto.accountId).get,
      UuidUtils.bytesToUuid(proto.syncId).get,
      Status.fromKey(proto.status).get,
      ByteStringUtils.deserialize[T](proto.cursor),
      ByteStringUtils.deserialize[ReportError](proto.error),
      TimestampProtoUtils.deserialize(proto.time.get)
    )

}

case class WorkableEvent[T](
    accountId: UUID,
    syncId: UUID,
    status: WorkableStatus,
    cursor: Option[T],
    error: Option[ReportError],
    time: Instant
) extends SyncEvent[T] {
  def asPublished: FlaggedEvent[T] =
    FlaggedEvent[T](
      accountId,
      syncId,
      Status.Published,
      cursor,
      error,
      Instant.now()
    )

  def asReportableSuccessEvent(newCursor: Option[T]): ReportableEvent[T] =
    ReportableEvent(
      accountId,
      syncId,
      status.success,
      newCursor,
      None,
      Instant.now()
    )

  def asReportableFailureEvent(error: ReportError): ReportableEvent[T] = {
    ReportableEvent(
      accountId,
      syncId,
      status.failure,
      cursor,
      Some(error),
      Instant.now()
    )
  }
}

object WorkableEvent {
  implicit def encoder[T: Encoder]: Encoder[WorkableEvent[T]] =
    deriveConfiguredEncoder[WorkableEvent[T]]

  implicit def decoder[T: Decoder]: Decoder[WorkableEvent[T]] =
    deriveConfiguredDecoder[WorkableEvent[T]]
}

case class ReportableEvent[T](
    accountId: UUID,
    syncId: UUID,
    status: ReportableStatus,
    cursor: Option[T],
    error: Option[ReportError],
    time: Instant
) extends SyncEvent[T]

object ReportableEvent {
  implicit def encoder[T: Encoder]: Encoder[ReportableEvent[T]] =
    deriveConfiguredEncoder[ReportableEvent[T]]

  implicit def decoder[T: Decoder]: Decoder[ReportableEvent[T]] =
    deriveConfiguredDecoder[ReportableEvent[T]]
}

case class TriggerableEvent[T](
    accountId: UUID,
    syncId: UUID,
    status: TriggerableStatus,
    cursor: Option[T],
    error: Option[ReportError],
    time: Instant
) extends SyncEvent[T] {
  def nextWorkable: WorkableEvent[T] =
    WorkableEvent[T](
      accountId,
      UUID.randomUUID(),
      status.nextWorkable,
      cursor,
      error,
      Instant.now()
    )
}

object TriggerableEvent {
  implicit def encoder[T: Encoder]: Encoder[TriggerableEvent[T]] =
    deriveConfiguredEncoder[TriggerableEvent[T]]

  implicit def decoder[T: Decoder]: Decoder[TriggerableEvent[T]] =
    deriveConfiguredDecoder[TriggerableEvent[T]]
}

case class FlaggedEvent[T](
    accountId: UUID,
    syncId: UUID,
    status: FlaggedStatus,
    cursor: Option[T],
    error: Option[ReportError],
    time: Instant
) extends SyncEvent[T]

object FlaggedEvent {
  implicit def encoder[T: Encoder]: Encoder[FlaggedEvent[T]] =
    deriveConfiguredEncoder[FlaggedEvent[T]]

  implicit def decoder[T: Decoder]: Decoder[FlaggedEvent[T]] =
    deriveConfiguredDecoder[FlaggedEvent[T]]
}
