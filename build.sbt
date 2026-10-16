ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "io.github.sosloan"
ThisBuild / version := "0.1.0-SNAPSHOT"

name := "neuralminds"

scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Xlint")

Test / test := (Test / runMain).toTask(" neuralminds.AllTests").value
