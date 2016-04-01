
val geoToolsVersion = "13.2"

val sparkVersion = "1.5.2"

val slf4jVersion = "1.7.14"

lazy val planeGrowth = (project in file(".")).enablePlugins(JavaAppPackaging).settings (

  name := "plane-growth",

  organization := "com.sungevity.smt",

  version := "1.0.0-SNAPSHOT",

  scalaVersion := "2.10.6",

  resolvers += "Open Source Geospatial Foundation Repository" at "http://download.osgeo.org/webdav/geotools/",

  resolvers += "Artifactory Realm" at "https://sungevity.artifactoryonline.com/sungevity/libs-release-local",

  credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),

  libraryDependencies ++= Seq(
    "com.sungevity.smt" %% "image-toolbox" % "1.0.2",
    "com.sungevity" %% "cma-es-scala" % "1.0.1",
    "org.geotools" % "gt-main" % geoToolsVersion,
    "org.geotools" % "gt-geotiff" % geoToolsVersion,
    "org.geotools" % "gt-shapefile" % geoToolsVersion,
    "org.geotools" % "gt-process" % geoToolsVersion,
    "org.geotools" % "gt-epsg-hsql" % geoToolsVersion,
    "org.geotools" % "gt-process-raster" % geoToolsVersion,
    "org.geotools" % "gt-process-geometry" % geoToolsVersion,
    "org.geotools" % "gt-process-feature" % geoToolsVersion,
    "org.geotools" % "gt-geojson" % geoToolsVersion,
    "org.slf4j" % "slf4j-api" % slf4jVersion,
    "org.slf4j" % "slf4j-log4j12" % slf4jVersion,
    "org.apache.spark" %% "spark-core" % sparkVersion excludeAll (ExclusionRule(organization = "org.spark-project.akka")),
    "org.apache.spark" %% "spark-mllib" % sparkVersion excludeAll (ExclusionRule(organization = "org.spark-project.akka")),
    "org.scalatest" % "scalatest_2.11" % "2.2.4" % "test"
  ),

  mainClass in Compile := Some("com.sungevity.smt.pg.Main")

)
