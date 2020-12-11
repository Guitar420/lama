package co.ledger.lama.manager

import java.util.UUID

import cats.effect.{Blocker, ContextShift, IO, Resource}
import co.ledger.lama.common.models._
import co.ledger.lama.common.utils.{DbUtils, IOAssertion, PostgresConfig, UuidUtils}
import co.ledger.lama.manager.Exceptions.AccountNotFoundException
import co.ledger.lama.manager.config.CoinConfig
import co.ledger.lama.manager.protobuf.{AccountInfoRequest, UpdateAccountRequest}
import co.ledger.lama.manager.{protobuf => pb}
import com.opentable.db.postgres.embedded.EmbeddedPostgres
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import io.circe.JsonObject
import io.grpc.Metadata
import org.postgresql.util.PSQLException
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource
import pureconfig.generic.auto._

import scala.concurrent.ExecutionContext

class ServiceSpec extends AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  val db: EmbeddedPostgres =
    EmbeddedPostgres.start()

  implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  val conf: TestServiceConfig = ConfigSource.default.loadOrThrow[TestServiceConfig]

  val dbUser     = "postgres"
  val dbPassword = ""
  val dbUrl      = db.getJdbcUrl(dbUser, "postgres")

  val transactor: Resource[IO, HikariTransactor[IO]] =
    for {
      ce <- ExecutionContexts.fixedThreadPool[IO](32) // our connect EC
      te <- ExecutionContexts.cachedThreadPool[IO] // our transaction EC
      xa <- HikariTransactor.newHikariTransactor[IO](
        "org.postgresql.Driver",         // driver classname
        dbUrl,                           // connect URL
        dbUser,                          // username
        dbPassword,                      // password
        ce,                              // await connection here
        Blocker.liftExecutionContext(te) // execute JDBC operations here
      )
    } yield xa

  var registeredAccountId: UUID = _
  var registeredSyncId: UUID    = _

  val registerBitcoinAccount: pb.RegisterAccountRequest = {
    pb.RegisterAccountRequest(
      key = "12345",
      coinFamily = pb.CoinFamily.bitcoin,
      coin = pb.Coin.btc
    )
  }

  val updatedSyncFrequency: Long = 10000L

  val accountIdentifier: AccountIdentifier =
    AccountIdentifier(registerBitcoinAccount.key, CoinFamily.Bitcoin, Coin.Btc)

  it should "register a new account" in IOAssertion {
    transactor.use { db =>
      val service                     = new Service(db, conf.coins)
      val defaultBitcoinSyncFrequency = conf.coins.head.syncFrequency.toSeconds

      for {
        response <- service.registerAccount(registerBitcoinAccount, new Metadata())

        accountId     = UuidUtils.bytesToUuid(response.accountId).get
        syncId        = UuidUtils.bytesToUuid(response.syncId).get
        syncFrequency = response.syncFrequency

        event <- getLastEvent(service, pb.AccountInfoRequest(response.accountId))
      } yield {
        registeredAccountId = accountId
        registeredSyncId = syncId

        // it should be an account uuid from extendKey, coinFamily, coin
        accountId shouldBe
          AccountIdentifier(
            registerBitcoinAccount.key,
            CoinFamily.Bitcoin,
            Coin.Btc
          ).id

        // it should be the default sync frequency from the bitcoin config
        syncFrequency shouldBe defaultBitcoinSyncFrequency

        // check event
        event.map(_.accountId) shouldBe Some(accountId)
        event.map(_.syncId) shouldBe Some(syncId)
        event.map(_.status) shouldBe Some(Status.Registered)
        event.flatMap(_.cursor) shouldBe None
        event.flatMap(_.error) shouldBe None
      }
    }
  }

  it should "update a registered account" in IOAssertion {
    transactor.use { db =>
      val service    = new Service(db, conf.coins)
      val fAccountId = UuidUtils.uuidToBytes(registeredAccountId)
      for {
        _ <- service.updateAccount(
          UpdateAccountRequest(fAccountId, updatedSyncFrequency),
          new Metadata()
        )

        newAccountInfo <- service.getAccountInfo(pb.AccountInfoRequest(fAccountId), new Metadata)
      } yield {
        newAccountInfo.syncFrequency shouldBe updatedSyncFrequency
      }
    }
  }

  it should "not allow the registration of an already existing account" in {
    an[PSQLException] should be thrownBy IOAssertion {
      transactor.use { db =>
        val service = new Service(db, conf.coins)

        service
          .registerAccount(
            registerBitcoinAccount.withSyncFrequency(updatedSyncFrequency),
            new Metadata()
          )
      }
    }
  }

  var unregisteredSyncId: UUID                 = _
  var unregisteredEvent: SyncEvent[JsonObject] = _

  lazy val unregisterAccountRequest: pb.UnregisterAccountRequest =
    pb.UnregisterAccountRequest(UuidUtils.uuidToBytes(registeredAccountId))

  it should "unregister an account" in IOAssertion {
    transactor.use { db =>
      val service = new Service(db, conf.coins)

      for {
        response <- service.unregisterAccount(unregisterAccountRequest, new Metadata())

        accountId = UuidUtils.bytesToUuid(response.accountId).get
        syncId    = UuidUtils.bytesToUuid(response.syncId).get

        event <- getLastEvent(service, pb.AccountInfoRequest(response.accountId))
      } yield {
        accountId shouldBe registeredAccountId
        unregisteredSyncId = syncId
        unregisteredSyncId should not be registeredSyncId

        // check event
        unregisteredEvent = event.get
        unregisteredEvent.accountId shouldBe accountId
        unregisteredEvent.syncId shouldBe syncId
        unregisteredEvent.status shouldBe Status.Unregistered
        unregisteredEvent.cursor shouldBe None
        unregisteredEvent.error shouldBe None
      }
    }
  }

  it should "return the same response if already unregistered" in IOAssertion {
    transactor.use { db =>
      val service = new Service(db, conf.coins)
      for {
        response <- service.unregisterAccount(unregisterAccountRequest, new Metadata())

        accountId = UuidUtils.bytesToUuid(response.accountId).get
        syncId    = UuidUtils.bytesToUuid(response.syncId).get

        event <- getLastEvent(service, pb.AccountInfoRequest(response.accountId))

      } yield {
        accountId shouldBe registeredAccountId
        syncId shouldBe unregisteredSyncId
        event shouldBe Some(unregisteredEvent)
      }
    }
  }

  it should "succeed to get info from an existing account" in IOAssertion {
    transactor.use { db =>
      new Service(db, conf.coins)
        .getAccountInfo(
          pb.AccountInfoRequest(UuidUtils.uuidToBytes(registeredAccountId)),
          new Metadata()
        )
        .map { response =>
          val key          = response.key
          val synFrequency = response.syncFrequency
          val lastSyncEvent =
            response.lastSyncEvent.map(SyncEvent.fromProto[JsonObject])

          key shouldBe registerBitcoinAccount.key
          synFrequency shouldBe updatedSyncFrequency
          lastSyncEvent shouldBe Some(unregisteredEvent)
        }
    }
  }

  it should "failed to get info from an unknown account" in {
    an[AccountNotFoundException] should be thrownBy IOAssertion {
      transactor.use { db =>
        new Service(db, conf.coins)
          .getAccountInfo(
            AccountInfoRequest(UuidUtils.uuidToBytes(UUID.randomUUID)),
            new Metadata()
          )
      }
    }
  }

  private def getLastEvent(
      service: Service,
      req: AccountInfoRequest
  ): IO[Option[SyncEvent[JsonObject]]] =
    service
      .getAccountInfo(req, new Metadata())
      .map(_.lastSyncEvent.map(SyncEvent.fromProto[JsonObject]))

  private val migrateDB: IO[Unit] = DbUtils.flywayMigrate(PostgresConfig(dbUrl, dbUrl, dbPassword))

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    migrateDB.unsafeRunSync()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    db.close()
  }

}

case class TestServiceConfig(coins: List[CoinConfig])
