package com.sungevity.smt.faces.models

import java.net.URI

import breeze.linalg.DenseMatrix
import com.sungevity.smt.faces.utils.FSUtils._

import scala.io.Source

/**
  * Class that wraps textual representation of point cloud data files.
  * @param input link to the file
  */
case class CloudPoint(input: URI) {

  private def parseLine(line: String) = line.split(" ") match {
    case Array(x: String, y: String, z: String) => (x.toDouble, y.toDouble, z.toDouble)
  }

  /**
    * Various parameters of file
    */
  val (width, height, minX, maxX, minY, maxY, minZ, maxZ) = {

    val (totalPoints, minX, maxX, minY, maxY, minZ, maxZ) = input.open {
      stream =>
        val lines = Source.fromInputStream(stream).getLines

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

    (width, height, minX, maxX, minY, maxY, minZ, maxZ)

  }

  /**
    * Transform cloud point data file into a matrix of doubles
    */
  val denseMatrix: DenseMatrix[Double] = {

    input.open {

      stream =>

        val result = DenseMatrix.fill[Double](width, height)(0.0)

        Source.fromInputStream(stream).getLines().foreach {
          line =>

            val r1 = (maxZ - minZ) / (maxX - minX)
            val r2 = (maxZ - minZ) / (maxY - minY)

            val xR = (width - 1) / (maxX - minX)
            val yR = (height - 1) / (maxY - minY)
            val zR = r1

            val (x, y, z) = parseLine(line)

            val c = xR * (z - minZ)

            val px = (math.round(xR * (x - minX))).toInt
            val py = height - (math.round(yR * (y - minY))).toInt - 1

            result.update(px, py, c)
        }

        result

    }

  }

}
