package com.sungevity.smt.faces

import java.io.Serializable
import java.net.URI

import breeze.linalg._
import breeze.stats._
import com.sungevity.cmaes._
import com.sungevity.smt.faces.models.FeatureCollection
import com.sungevity.smt.faces.utils.FSUtils._
import com.typesafe.config.Config
import org.apache.commons.io.FilenameUtils
import org.apache.spark.SparkContext
import org.slf4j.LoggerFactory

object Optimizer extends Serializable {

  private object Log extends Serializable {
    @transient lazy val logger = LoggerFactory.getLogger(Optimizer.getClass)
  }

  def run()(implicit sc: SparkContext, config: Config) {

    val heightMapsFolder = config.getString("com.sungevity.smt.facets.input.height-maps")

    val roofOutlinesFolder = config.getString("com.sungevity.smt.facets.input.roofs-outlines-path")

    val rasterFolder = config.getString("com.sungevity.smt.facets.input.raster")

    val goldFolder = config.getString("com.sungevity.smt.facets.input.ground-thruth")

    val heightMaps = new URI(heightMapsFolder).listFiles.filter(_.getPath.endsWith(".txt"))

    val roofOutlines = new URI(roofOutlinesFolder).listFiles.filter(_.getPath.endsWith(".tif"))

    val raster = new URI(rasterFolder).listFiles.filter(_.getPath.endsWith(".tif"))

    val groundTruth = new URI(goldFolder).listFiles.filter(_.getPath.endsWith(".geojson")).map(FeatureCollection(_)).flatMap(_.geometries).toArray

    val inputs =
      (for {
        hm <- heightMaps
        id = FilenameUtils.getBaseName(hm.getPath)
        ro <- roofOutlines.find(ro => FilenameUtils.getBaseName(ro.getPath) == id)
        ra <- raster.find(ra => FilenameUtils.getBaseName(ra.getPath) == id)
      } yield {
        (hm, ro, ra)
      }) toArray

    val roofPixelColor = config.getInt("com.sungevity.smt.facets.roof-pixels-color")

    val pg = new PlainGrowth(roofPixelColor)

    val fitFunction: PartialFunction[DenseVector[Double], Double] =  {

        case x: DenseVector[Double] if x.length == 7 => {

          val smoothnessThreshold = math.abs(x(0))
          val curvatureThreshold = math.abs(x(1))
          val colorThreshold = math.abs(x(2))
          val knn = math.abs(x(3)).toInt
          val regionSizeLimit = math.abs(x(4)).toInt
          val slopeThreshold = math.abs(x(5))
          val azimuthThreshold = math.abs(x(6))

          val geometries = inputs.flatMap {
            input =>
              pg.segment(input,
                smoothnessThreshold,
                curvatureThreshold,
                colorThreshold,
                knn,
                regionSizeLimit,
                slopeThreshold,
                azimuthThreshold
              ).geometries
          }

          PlainGrowth.evaluate(groundTruth, geometries)

        }

    }

    val driver = CMAESDriver( {
        case population: DenseMatrix[Double] => {

          val populationSeq = population(*, ::).iterator.toSeq

          val scores = DenseVector {
            sc.parallelize(populationSeq).map {
              x => fitFunction.apply(x)
            } collect()
          }

          Log.logger.debug(s"scores: min [${min(scores)}], max [${max(scores)}], mean [${mean(scores)}], variance [${variance(scores)}], ")

          scores

        }
      }
    )

    val solution = driver.optimize(30,
      DenseVector(0.98, 0.0008, 15.0, 64.0, 50.0, 2.0, 15.0),
      DenseVector(0.02, 0.0002, 1.0, 1.0, 1.0, 0.5, 1.0),
      iterationsExceeded(6000) orElse lowVariance(1e-14) orElse
        minFitnessReached(1e-14) orElse proceed
    )

    Log.logger.info(s"Optimal solution: ${solution.toArray.toList}")

  }

}
