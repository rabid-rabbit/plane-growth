package com.sungevity.smt.faces

import java.net.URI

import breeze.linalg.DenseMatrix
import com.sungevity.analytics.imageio.Image
import com.sungevity.smt.faces.models.CloudPoint
import com.sungevity.smt.faces.utils.FSUtils._
import com.typesafe.config.Config
import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageWriterSpi
import org.apache.commons.io.FilenameUtils
import org.apache.spark.SparkContext
import org.slf4j.LoggerFactory

import scala.io.Source

/**
  * A tool for converting textual representation of point cloud data into 24 raster images.
  */
object Text2Dem {

  private object Log extends Serializable {
    @transient lazy val logger = LoggerFactory.getLogger(Text2Dem.getClass)
  }

  private def parseLine(line: String) = line.split(" ") match {
    case Array(x: String, y: String, z: String) => (x.toDouble, y.toDouble, z.toDouble)
  }

  def run()(implicit sc: SparkContext, config: Config) {

    val inputFolder = config.getString("com.sungevity.smt.txt2dem.input.path")

    val outputFolder = config.getString("com.sungevity.smt.txt2dem.output.path")

    val inputs = new URI(inputFolder).listFiles.filter(_.getPath.endsWith(".txt"))

    val count = sc.parallelize(inputs).map {

      input =>

        val name = FilenameUtils.getBaseName(input.getPath)

        Log.logger.debug(s"Processing $name")

        val cp = CloudPoint(input)

        val lines = Source.fromFile(input).getLines()

        if(lines.hasNext) {

          val (totalPoints, minX, maxX, minY, maxY, minZ, maxZ) = {
            var totalPoints = 1
            var minX = Double.MaxValue
            var maxX = Double.MinValue
            var minY = Double.MaxValue
            var maxY = Double.MinValue
            var minZ = Double.MaxValue
            var maxZ = Double.MinValue

            lines.foreach {
              line =>
                val (x, y, z) = parseLine(line)
                totalPoints += 1
                minX = math.min(minX, x)
                maxX = math.max(maxX, x)
                minY = math.min(minY, y)
                maxY = math.max(maxY, y)
                minZ = math.min(minZ, z)
                maxZ = math.max(maxZ, z)
            }

            (totalPoints, minX, maxX, minY, maxY, minZ, maxZ)

          }

          val (width, height) = {
            val r = (maxX - minX) / (maxY - minY)
            (math.round(math.sqrt(totalPoints * r)).toInt, math.round(math.sqrt(totalPoints / r)).toInt)
          }

          val r1 = (maxZ - minZ) / (maxX - minX)

          val xR = (width - 1) / (maxX - minX)
          val yR = (height - 1) / (maxY - minY)
          val zR = xR * 1000

          val pixels = DenseMatrix.fill[Int](width, height)(0xff000000)

          Source.fromFile(input).getLines().foreach {
            line =>

              val (x, y, z) = parseLine(line)

              val c = 0xff000000 + (zR * z).toInt

              val px = (math.round(xR * (x - minX))).toInt
              val py = height - (math.round(yR * (y - minY))).toInt - 1

              pixels.update(px, py, c)
          }

          val outputFile = new URI(outputFolder) + s"$name.tif"

          outputFile.create(true) {
            stream =>
              Image.fromPixels(pixels).write(stream)(new TIFFImageWriterSpi)
          }

          outputFile

        }

    } count()

    Log.logger.info(s"Processed $count files")


  }

}
