package co.ledger.lama.common.utils

import java.time.Instant

object TimestampProtoUtils {

  def serialize(instant: Instant): com.google.protobuf.timestamp.Timestamp =
    com.google.protobuf.timestamp.Timestamp(
      seconds = instant.getEpochSecond,
      nanos = instant.getNano
    )

  def deserialize(timestamp: com.google.protobuf.timestamp.Timestamp): Instant =
    Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos)

}
