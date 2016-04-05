import play.twirl.sbt.SbtTwirl

scalaVersion in Global := "2.11.8"
initialize := {
  val _ = initialize.value
  if (sys.props("java.specification.version") != "1.8")
    sys.error("Java 8 is required for CM-Well!")
}
updateOptions := updateOptions.value.withCachedResolution(true)
updateOptions := updateOptions.value.withCircularDependencyLevel(CircularDependencyLevel.Error)
scalacOptions in Global ++= Seq("-unchecked", "-feature", "-deprecation", "-target:jvm-1.8")
cancelable in Global := true

lazy val util = (project in file("akka-http-test-util")).settings(Seq(Keys.fork in Test := true))
lazy val ws   = (project in file("akka-http-test-ws")).enablePlugins(play.PlayScala, SbtTwirl).settings(Seq(Keys.fork in Test := true))
lazy val it   = (project in file("akka-http-test-it")).settings(Seq(Keys.fork in Test := true)).dependsOn(util).configs(IntegrationTest)

