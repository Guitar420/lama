package co.ledger.lama.bitcoin.interpreter.models

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

import cats.data.NonEmptyList
import co.ledger.lama.bitcoin.common.models.explorer.Output
import co.ledger.lama.bitcoin.common.models.interpreter._
import doobie._
import doobie.postgres.implicits._
import doobie.implicits.javasql._

import scala.math.BigDecimal.javaBigDecimal2bigDecimal

object implicits {

  implicit def getNel[A](implicit ev: Get[List[A]]): Get[NonEmptyList[A]] =
    ev.map(NonEmptyList.fromList(_).getOrElse(sys.error("oops")))

  implicit def putNel[A](implicit ev: Put[List[A]]): Put[NonEmptyList[A]] =
    ev.contramap(_.toList)

  implicit val bigIntType: Meta[BigInt] =
    Meta.BigDecimalMeta.imap[BigInt](_.toBigInt)(BigDecimal(_).bigDecimal)

  implicit val instantType: Meta[Instant] =
    TimestampMeta.imap[Instant] { ts =>
      Instant.ofEpochMilli(ts.getTime)
    }(Timestamp.from)

  implicit val operationTypeMeta: Meta[OperationType] =
    pgEnumStringOpt("operation_type", OperationType.fromKey, _.toString.toLowerCase())

  implicit val changeTypeMeta: Meta[ChangeType] =
    pgEnumStringOpt("change_type", ChangeType.fromKey, _.toString.toLowerCase())

  implicit val writeOutput: Write[Output] =
    Write[(BigInt, BigInt, String, String)].contramap { o =>
      (o.outputIndex, o.value, o.address, o.scriptHex)
    }

  // Needs implicits because of the Block
  implicit lazy val readTransactionView: Read[TransactionView] =
    Read[(String, String, String, Long, Instant, Instant, Long, BigInt, Int)]
      .map {
        case (
              id,
              hash,
              blockHash,
              blockHeight,
              blockTime,
              receivedAt,
              lockTime,
              fees,
              confirmations
            ) =>
          TransactionView(
            id = id,
            hash = hash,
            receivedAt = receivedAt,
            lockTime = lockTime,
            fees = fees,
            inputs = Seq(),
            outputs = Seq(),
            block = BlockView(blockHash, blockHeight, blockTime),
            confirmations = confirmations
          )
      }

  // Needs implicit because of the Transaction "None"
  implicit lazy val readOperation: Read[Operation] =
    Read[(UUID, String, OperationType, BigInt, BigInt, Instant)]
      .map { case (accountId, hash, operationType, value, fees, time) =>
        Operation(accountId, hash, None, operationType, value, fees, time)
      }
}
