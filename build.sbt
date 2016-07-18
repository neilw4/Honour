name := "async"

version := "1.0"

scalaVersion := "2.11.8"

libraryDependencies += "org.scalaz" %% "scalaz-core" % "7.2.4"
libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.13.2" % "test"

testOptions in Test += Tests.Argument(TestFrameworks.ScalaCheck, "-maxSize", "5", "-minSuccessfulTests", "33", "-workers", "1", "-verbosity", "1")
