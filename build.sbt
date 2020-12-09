// Build shared info
ThisBuild / organization := "co.ledger"
ThisBuild / scalaVersion := "2.13.3"
ThisBuild / resolvers += Resolver.sonatypeRepo("releases")
ThisBuild / scalacOptions ++= CompilerFlags.all

// Dynver custom version formatting
def versionFmt(out: sbtdynver.GitDescribeOutput): String = {
  if (out.isCleanAfterTag) out.ref.dropPrefix
  else s"${out.ref.dropPrefix}-${out.commitSuffix.sha}"
}

def fallbackVersion(d: java.util.Date): String =
  s"HEAD-${sbtdynver.DynVer timestamp d}"

ThisBuild / dynverVTagPrefix := false
ThisBuild / version := dynverGitDescribeOutput.value.mkVersion(
  versionFmt,
  fallbackVersion(dynverCurrentDate.value)
)
ThisBuild / dynver := {
  val d = new java.util.Date
  sbtdynver.DynVer.getGitDescribeOutput(d).mkVersion(versionFmt, fallbackVersion(d))
}

// Shared Plugins
enablePlugins(BuildInfoPlugin)
ThisBuild / libraryDependencies += compilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3")

lazy val ignoreFiles = List("application.conf.sample")

// Runtime
scalaVersion := "2.13.3"
scalacOptions ++= CompilerFlags.all
resolvers += Resolver.sonatypeRepo("releases")
addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3")

lazy val buildInfoSettings = Seq(
  buildInfoKeys := Seq[BuildInfoKey](version, git.gitHeadCommit),
  buildInfoPackage := "buildinfo"
)

lazy val assemblySettings = Seq(
  test in assembly := {},
  assemblyOutputPath in assembly := file(
    target.value.getAbsolutePath
  ) / "assembly" / (name.value + ".jar"),
  cleanFiles += file(target.value.getAbsolutePath) / "assembly",
  // Remove resources files from the JAR (they will be copied to an external folder)
  assemblyMergeStrategy in assembly := {
    case PathList("META-INF", _) => MergeStrategy.discard
    case PathList("BUILD")       => MergeStrategy.discard
    case path =>
      if (ignoreFiles.contains(path))
        MergeStrategy.discard
      else
        (assemblyMergeStrategy in assembly).value(path)
  }
)

lazy val dockerSettings = Seq(
  imageNames in docker := {
    // Tagging latest + dynamic version
    Seq(
      ImageName(s"docker.pkg.github.com/ledgerhq/lama/${name.value}:latest"),
      ImageName(s"docker.pkg.github.com/ledgerhq/lama/${name.value}:${version.value}")
    )
  },
  // User `docker` to build docker image
  dockerfile in docker := {
    // The assembly task generates a fat JAR file
    val artifact: File     = (assemblyOutputPath in assembly).value
    val artifactTargetPath = s"/app/${(assemblyOutputPath in assembly).value.name}"
    new Dockerfile {
      from("openjdk:14.0.2")
      copy(artifact, artifactTargetPath)
      entryPoint("java", "-jar", artifactTargetPath)
    }
  }
)

lazy val coverageSettings = Seq(
  coverageMinimum := 45,
  coverageFailOnMinimum := false,
  coverageExcludedPackages := ".*App.*;.*ProtobufUtils.*;.*grpc.*;.*BtcProtoUtils.*;.*CoinUtils.*"
)

lazy val sharedSettings =
  assemblySettings ++ dockerSettings ++ Defaults.itSettings ++ coverageSettings

lazy val lamaProtobuf = (project in file("protobuf"))
  .enablePlugins(Fs2Grpc)
  .settings(
    name := "lama-protobuf",
    scalapbCodeGeneratorOptions += CodeGeneratorOption.FlatPackage,
    libraryDependencies ++= Dependencies.commonProtos
  )

// Common lama library
lazy val common = (project in file("common"))
  .configs(IntegrationTest)
  .settings(
    name := "lama-common",
    libraryDependencies ++= (Dependencies.lamaCommon ++ Dependencies.test),
    test in assembly := {}
  )
  .dependsOn(lamaProtobuf)

lazy val accountManager = (project in file("account-manager"))
  .enablePlugins(sbtdocker.DockerPlugin)
  .configs(IntegrationTest)
  .settings(
    name := "lama-account-manager",
    sharedSettings,
    libraryDependencies ++= (Dependencies.accountManager ++ Dependencies.test)
  )
  .dependsOn(common)

lazy val bitcoinProtobuf = (project in file("coins/bitcoin/protobuf"))
  .enablePlugins(Fs2Grpc)
  .settings(
    name := "lama-bitcoin-protobuf",
    scalapbCodeGeneratorOptions += CodeGeneratorOption.FlatPackage,
    libraryDependencies ++= Dependencies.commonProtos,
    PB.protoSources in Compile ++= Seq(
      file("coins/bitcoin/keychain/pb/keychain")
    )
  )

lazy val bitcoinApi = (project in file("coins/bitcoin/api"))
  .enablePlugins(BuildInfoPlugin, sbtdocker.DockerPlugin)
  .configs(IntegrationTest)
  .settings(
    name := "lama-bitcoin-api",
    libraryDependencies ++= (Dependencies.btcApi ++ Dependencies.test),
    sharedSettings,
    buildInfoSettings
  )
  .dependsOn(accountManager, bitcoinCommon, common, bitcoinProtobuf)

lazy val bitcoinCommon = (project in file("coins/bitcoin/common"))
  .configs(IntegrationTest)
  .settings(
    name := "lama-bitcoin-common",
    libraryDependencies ++= Dependencies.btcCommon
  )
  .dependsOn(common, bitcoinProtobuf)

lazy val bitcoinWorker = (project in file("coins/bitcoin/worker"))
  .enablePlugins(sbtdocker.DockerPlugin)
  .configs(IntegrationTest)
  .settings(
    name := "lama-bitcoin-worker",
    sharedSettings,
    libraryDependencies ++= (Dependencies.btcWorker ++ Dependencies.test)
  )
  .dependsOn(common, bitcoinCommon)

lazy val bitcoinInterpreter = (project in file("coins/bitcoin/interpreter"))
  .enablePlugins(sbtdocker.DockerPlugin)
  .configs(IntegrationTest)
  .settings(
    name := "lama-bitcoin-interpreter",
    sharedSettings,
    libraryDependencies ++= (Dependencies.btcInterpreter ++ Dependencies.test),
    parallelExecution in IntegrationTest := false
  )
  .dependsOn(common, bitcoinCommon)

lazy val bitcoinTransactor = (project in file("coins/bitcoin/transactor"))
  .enablePlugins(Fs2Grpc, sbtdocker.DockerPlugin)
  .configs(IntegrationTest)
  .settings(
    name := "lama-bitcoin-transactor",
    sharedSettings,
    libraryDependencies ++= (Dependencies.btcCommon ++ Dependencies.commonProtos ++ Dependencies.test),
    // Proto config
    scalapbCodeGeneratorOptions += CodeGeneratorOption.FlatPackage,
    PB.protoSources in Compile := Seq(
      file("coins/bitcoin/lib-grpc/pb/bitcoin")
    )
  )
  .dependsOn(common, bitcoinCommon)
