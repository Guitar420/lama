package co.ledger.lama.bitcoin.api

import co.ledger.lama.common.services.RabbitNotificationService
import cats.effect.{ExitCode, IO, IOApp}
import co.ledger.lama.bitcoin.api.middlewares.LoggingMiddleware._
import co.ledger.lama.bitcoin.interpreter.protobuf.BitcoinInterpreterServiceFs2Grpc
import co.ledger.lama.bitcoin.common.grpc.{
  InterpreterGrpcClientService,
  KeychainGrpcClientService,
  TransactorGrpcClientService
}
import co.ledger.lama.common.utils.RabbitUtils
import co.ledger.lama.common.utils.ResourceUtils.grpcManagedChannel
import co.ledger.lama.manager.protobuf.AccountManagerServiceFs2Grpc
import Config.Config
import co.ledger.lama.bitcoin.api.routes.{AccountController, HealthController, VersionController}
import co.ledger.lama.bitcoin.transactor.protobuf.BitcoinTransactorServiceFs2Grpc
import co.ledger.lama.common.grpc.AccountManagerGrpcClientService
import co.ledger.protobuf.bitcoin.keychain.KeychainServiceFs2Grpc
import co.ledger.protobuf.lama.common.HealthFs2Grpc
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import io.grpc.Metadata
import org.http4s.server.middleware._
import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import pureconfig.ConfigSource

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

object App extends IOApp {

  case class ServiceResources(
      rabbitClient: RabbitClient[IO],
      grpcAccountManagerHealthClient: HealthFs2Grpc[IO, Metadata],
      grpcBitcoinInterpreterHealthClient: HealthFs2Grpc[IO, Metadata],
      grpcBitcoinTransactorHealthClient: HealthFs2Grpc[IO, Metadata],
      grpcAccountClient: AccountManagerServiceFs2Grpc[IO, Metadata],
      grpcKeychainClient: KeychainServiceFs2Grpc[IO, Metadata],
      grpcKeychainHealthClient: HealthFs2Grpc[IO, Metadata],
      grpcBitcoinInterpreterClient: BitcoinInterpreterServiceFs2Grpc[IO, Metadata],
      grpcBitcoinTransactorClient: BitcoinTransactorServiceFs2Grpc[IO, Metadata]
  )

  def run(args: List[String]): IO[ExitCode] = {
    val conf = ConfigSource.default.loadOrThrow[Config]

    val resources = for {

      rabbitClient <- RabbitUtils.createClient(conf.rabbit)

      grpcAccountManagerClient <-
        grpcManagedChannel(conf.accountManager).map(AccountManagerServiceFs2Grpc.stub[IO](_))

      grpcAccountManagerHealthClient <-
        grpcManagedChannel(conf.accountManager).map(HealthFs2Grpc.stub[IO](_))

      grpcKeychainClient <-
        grpcManagedChannel(conf.bitcoin.keychain).map(KeychainServiceFs2Grpc.stub[IO](_))

      grpcKeychainHealthClient <- grpcManagedChannel(conf.bitcoin.keychain)
        .map(HealthFs2Grpc.stub[IO](_))

      grpcBitcoinInterpreterClient <- grpcManagedChannel(conf.bitcoin.interpreter)
        .map(BitcoinInterpreterServiceFs2Grpc.stub[IO](_))

      grpcBitcoinInterpreterHealthClient <- grpcManagedChannel(conf.bitcoin.interpreter)
        .map(HealthFs2Grpc.stub[IO](_))

      grpcBitcoinTransactorClient <- grpcManagedChannel(conf.bitcoin.transactor)
        .map(BitcoinTransactorServiceFs2Grpc.stub[IO](_))

      grpcBitcoinTransactorHealthClient <- grpcManagedChannel(conf.bitcoin.transactor)
        .map(HealthFs2Grpc.stub[IO](_))

    } yield ServiceResources(
      rabbitClient = rabbitClient,
      grpcAccountManagerHealthClient = grpcAccountManagerHealthClient,
      grpcBitcoinInterpreterHealthClient = grpcBitcoinInterpreterHealthClient,
      grpcBitcoinTransactorHealthClient = grpcBitcoinTransactorHealthClient,
      grpcAccountClient = grpcAccountManagerClient,
      grpcKeychainClient = grpcKeychainClient,
      grpcKeychainHealthClient = grpcKeychainHealthClient,
      grpcBitcoinInterpreterClient = grpcBitcoinInterpreterClient,
      grpcBitcoinTransactorClient = grpcBitcoinTransactorClient
    )

    resources.use { serviceResources =>
      val notificationService = new RabbitNotificationService(
        serviceResources.rabbitClient,
        conf.lamaNotificationsExchangeName,
        conf.maxConcurrent
      )

      val methodConfig = CORSConfig(
        anyOrigin = true,
        anyMethod = true,
        allowCredentials = false,
        maxAge = 1.day.toSeconds
      )

      val httpRoutes = Router[IO](
        "accounts" -> CORS(
          loggingMiddleWare(
            AccountController.routes(
              notificationService,
              new KeychainGrpcClientService(serviceResources.grpcKeychainClient),
              new AccountManagerGrpcClientService(serviceResources.grpcAccountClient),
              new InterpreterGrpcClientService(serviceResources.grpcBitcoinInterpreterClient),
              new TransactorGrpcClientService(serviceResources.grpcBitcoinTransactorClient)
            )
          ),
          methodConfig
        ),
        "_health" -> CORS(
          HealthController.routes(
            serviceResources.grpcAccountManagerHealthClient,
            serviceResources.grpcBitcoinInterpreterHealthClient,
            serviceResources.grpcBitcoinTransactorHealthClient,
            serviceResources.grpcKeychainHealthClient
          ),
          methodConfig
        ),
        "_version" -> CORS(VersionController.routes(), methodConfig)
      ).orNotFound

      BlazeServerBuilder[IO](ExecutionContext.global)
        .bindHttp(conf.server.port, conf.server.host)
        .withHttpApp(httpRoutes)
        .serve
        .compile
        .drain
        .as(ExitCode.Success)
    }
  }

}
