package com.sungevity.smt.faces

import com.sungevity.analytics.utils.Configuration._
import com.sungevity.analytics.utils.Reflection._
import org.apache.spark.{SparkConf, SparkContext}

import scala.util.Try

/**
  * Object-dispatcher that sets up Spark environment and calls specified command. Among available commands are:
  * plane-growth - segment roofs using plane-growth algorithm ([[PlainGrowth]])
  * txt2dem - convert textual representation of point cloud data into 24-bit raster images
  * optimize - detect best parameters for the plane-growth algorithm ([[Optimizer]])
  */
object Main {

  def main(argv: Array[String]) {

    if (argv.size < 1) {
      sys.error("Please use one of following: [plain-growth | txt2dem | optimize].")
      sys.exit(1)
    }

    val mode = argv.head
    val args = argv.tail
    val partitions = args.headOption.flatMap { x => Try(x.toInt).toOption }

    if (args.size < partitions.map(x => 2).getOrElse(1)) {
      sys.error("You forgot to specify a config file.")
      sys.exit(1)
    }

    implicit val config = configuration(if (partitions.isDefined) args.tail else args).resolve()

    val sparkConf = this.getClass.jarFile map {
      jar =>
        val conf = new SparkConf().setAppName(s"Plane Growth Algorithm: $mode").
          setJars(Seq(jar.getAbsolutePath))
        config.getOptionalString("com.sungevity.smt.classifier.master-url") match {
          case Some(value) => conf.setMaster(value)
          case _ => conf
        }
    } getOrElse {
      new SparkConf().setAppName(s"Image Classifier: ${args(0)}").setMaster("local[4]")
    }

    (sys.env.get("AWS_ACCESS_KEY_ID"), sys.env.get("AWS_SECRET_ACCESS_KEY")) match {
      case (Some(key), Some(secret)) =>
        sparkConf.set("spark.hadoop.fs.s3n.awsAccessKeyId", key)
        sparkConf.set("spark.hadoop.fs.s3n.awsSecretAccessKey", secret)
        sparkConf.set("spark.hadoop.fs.s3.awsAccessKeyId", key)
        sparkConf.set("spark.hadoop.fs.s3.awsSecretAccessKey", secret)
      case _ =>
    }

    config.getOptionalString("com.sungevity.smt.classifier.worker-memory") match {
      case Some(mem) => sparkConf.set("spark.executor.memory", mem)
      case _ =>
    }

    implicit val sc = new SparkContext(sparkConf)

    mode.trim.toLowerCase() match {
      case "plane-growth" => PlainGrowth.run
      case "txt2dem" => Text2Dem.run
      case "optimize" => Optimizer.run
      case other => println(s"Unknown command: $other")
    }

  }


}
