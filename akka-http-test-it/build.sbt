name := "akka-http-test-it"

Defaults.itSettings

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-http-core" % "2.4.2" % "it",
  "com.typesafe.akka" %% "akka-stream" % "2.4.2" % "it",
  "org.scalatest" %% "scalatest" % "2.2.3" % "it")

fork in IntegrationTest := true

testOptions in IntegrationTest ++= {
  val stageDir = (stage in LocalProject("ws")).value
  val sh = stageDir / "bin" / "akka-http-test-ws"
  val pidFile = stageDir / "RUNNING_PID"
  val log = streams.value.log
  Seq(
    Tests.Setup(() => {
      Process(sh.getAbsolutePath).run()
      Thread.sleep(5000)
      log.info("initiating tests")
    }),
    Tests.Cleanup(() => {
      val pid = scala.io.Source.fromFile(pidFile).mkString.trim
      s"kill $pid" !
    }))
}
