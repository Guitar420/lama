package co.ledger.lama.bitcoin.worker.services

import cats.effect.IO
import co.ledger.lama.bitcoin.common.models.worker.Block
import io.circe.syntax._
import co.ledger.lama.common.logging.IOLogging
import co.ledger.lama.common.models.messages.{ReportMessage, WorkerMessage}
import co.ledger.lama.common.utils.RabbitUtils
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.{ExchangeName, QueueName, RoutingKey}
import fs2.Stream

class SyncEventService(
    rabbitClient: RabbitClient[IO],
    workerQueueName: QueueName,
    lamaExchangeName: ExchangeName,
    lamaRoutingKey: RoutingKey
) extends IOLogging {

  def consumeWorkerMessages: Stream[IO, WorkerMessage[Block]] =
    RabbitUtils.createAutoAckConsumer[WorkerMessage[Block]](rabbitClient, workerQueueName)

  private val publisher: Stream[IO, ReportMessage[Block] => IO[Unit]] =
    RabbitUtils
      .createPublisher[ReportMessage[Block]](rabbitClient, lamaExchangeName, lamaRoutingKey)

  def reportMessage(message: ReportMessage[Block]): IO[Unit] =
    publisher
      .evalMap(p => p(message) *> log.info(s"Published message: ${message.asJson.toString}"))
      .compile
      .drain

}
