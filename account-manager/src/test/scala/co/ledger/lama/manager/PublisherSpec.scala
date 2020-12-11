package co.ledger.lama.manager

import java.util.UUID

import cats.effect.IO
import co.ledger.lama.common.models.implicits._
import co.ledger.lama.common.models.messages.WithBusinessId
import co.ledger.lama.common.utils.IOAssertion
import com.redis.RedisClient
import fs2.Stream
import io.circe.generic.extras.semiauto._
import io.circe.{Decoder, Encoder}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.Inspectors
import org.scalatest.flatspec.AnyFlatSpecLike
import redis.embedded.RedisServer

import scala.collection.mutable

class PublisherSpec extends AnyFlatSpecLike with Matchers with Inspectors with BeforeAndAfterAll {

  val redisServer              = new RedisServer(6380)
  val redisClient: RedisClient = new RedisClient("localhost", 6380)

  val nbMessages: Int = 10

  def publishers: Seq[TestPublisher] =
    (1 to 5).map(i => new TestPublisher(redisClient, nbMessages, i))

  forAll(publishers) { publisher =>
    val maxOnGoingMessages   = publisher.maxOnGoingMessages
    val messages             = publisher.messages
    val countPendingMessages = nbMessages - maxOnGoingMessages

    it should s" have $maxOnGoingMessages published messages and $countPendingMessages pending messages" in IOAssertion {
      Stream
        .emits(messages)
        .evalMap(publisher.enqueue)
        .compile
        .drain
        .map { _ =>
          publisher.countPendingMessages shouldBe Some(countPendingMessages)
          publisher.publishedMessages should have size maxOnGoingMessages
          assert(
            publisher.publishedMessages.containsSlice(publisher.messages.take(maxOnGoingMessages))
          )
        }
    }
  }

  forAll(publishers) { publisher =>
    val maxOnGoingMessages = publisher.maxOnGoingMessages
    val messages           = publisher.messages

    it should s"publish messages $maxOnGoingMessages by $maxOnGoingMessages" in IOAssertion {
      Stream
        .emits(messages)
        .evalMap(publisher.enqueue)
        .zipWithIndex
        .evalMap { case (_, index) =>
          val publishedMessages   = publisher.publishedMessages
          val pendingMessagesSize = publisher.countPendingMessages

          // at each iteration, call dequeue to publish next event
          publisher.dequeue(publisher.key).map { _ =>
            (index, pendingMessagesSize, publishedMessages)
          }
        }
        .map { case (index, pendingMessagesSize, publishedMessages) =>
          pendingMessagesSize shouldBe Some(0)
          publishedMessages shouldBe publisher.messages.take(index.toInt + 1)
        }
        .compile
        .drain
    }
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    redisServer.start()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    redisServer.stop()
  }

}

case class TestMessage(accountId: UUID, eventId: String) extends WithBusinessId[UUID] {
  val businessId: UUID = accountId
}

object TestMessage {
  implicit val decoder: Decoder[TestMessage] = deriveConfiguredDecoder[TestMessage]
  implicit val encoder: Encoder[TestMessage] = deriveConfiguredEncoder[TestMessage]
}

class TestPublisher(
    val redis: RedisClient,
    val nbMessages: Int,
    override val maxOnGoingMessages: Int
)(implicit
    val enc: Encoder[TestMessage],
    val dec: Decoder[TestMessage]
) extends Publisher[UUID, TestMessage] {
  import Publisher._

  val key: UUID = UUID.randomUUID()

  val messages: Seq[TestMessage] = (1 to nbMessages).map(i => TestMessage(key, s"message$i"))

  var publishedMessages: mutable.Seq[TestMessage] = mutable.Seq.empty

  def publish(message: TestMessage): IO[Unit] =
    IO.pure {
      publishedMessages = publishedMessages ++ Seq(message)
    }

  def countPendingMessages: Option[Long] = redis.llen(pendingMessagesKey(key.toString))

}
