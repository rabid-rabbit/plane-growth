package com.sungevity.smt.faces

import java.awt.geom.AffineTransform
import java.io.File
import java.net.URI

import breeze.linalg._
import breeze.numerics._
import com.sungevity.analytics.imageio.Image
import com.sungevity.analytics.imageio.linalg.BreezeExtension._
import com.sungevity.analytics.imageio.utils.KDTree
import com.sungevity.smt.faces.models.{FeatureCollection, CloudPoint}
import com.sungevity.smt.faces.utils.FSUtils._
import com.typesafe.config.Config
import com.vividsolutions.jts.geom.util.AffineTransformation
import com.vividsolutions.jts.geom.{Geometry, Polygon}
import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageReaderSpi
import org.apache.commons.io.FilenameUtils
import org.apache.spark.SparkContext
import org.geotools.coverage.grid.GridCoverage2D
import org.geotools.gce.geotiff.GeoTiffReader
import org.opengis.metadata.spatial.PixelOrientation
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.collection.{immutable, mutable}

/**
  * A tool that uses plain-growth algorithm to segment roof into faces. It uses data from 3 sources:
  * roof outlines produced by image classifier
  * roof's raster orthogonal snapshot
  * roof's point could snapshot
  * @param roofPixelColor A color value of a roof on the roof outlines produced by image classifier
  */
class PlainGrowth(roofPixelColor: Int) extends Serializable {

  val up = DenseVector(0.0, 0.0, 1.0)

  private def cosineDistance(a: DenseVector[Double], b: DenseVector[Double]) = {
    val dot: Double = a.dot(b)
    dot / (math.sqrt(sum(a :* a)) * math.sqrt(sum(b :* b)))
  }

  private def readCoverage(file: File): GridCoverage2D = new GeoTiffReader(file, null).read(null)

  private def extractBand(img: Image, band: Int): Image = {

    img.withPixels {
      img.pixels.map {
        p =>
          if ((0x00ffffff & p) == band) 0xffffffff
          else 0
      }
    }

  }

  /**
    * run this tool
    * @param input a tuple of: roof outlines produced by image classifier, roof's raster orthogonal snapshot, roof's
    *              point could snapshot
    * @param smoothnessThreshold smoothness threshold
    * @param curvatureThreshold curvature threshold
    * @param colorThreshold color threshold
    * @param knn k nearest neighbours
    * @param regionSizeLimit minimum size of a pixel area for the region to be considered a face
    * @param slopeThreshold slope threshold that is used to merge similar faces
    * @param azimuthThreshold azimuth threshold that is used to merge similar faces
    * @return an instance of [[FeatureCollection]] with detected faces
    */
  def segment(input: (URI, URI, URI), smoothnessThreshold: Double, curvatureThreshold: Double, colorThreshold: Double, knn: Int, regionSizeLimit: Int, slopeThreshold: Double, azimuthThreshold: Double) = {
    val (heightMap, roofOutlineMap, rasterMap) = input

    val apn =  FilenameUtils.getBaseName(heightMap.getPath)

    val heightMapImage = CloudPoint(heightMap)

    PlainGrowth.Log.logger.info(s"Processing $apn")

    val coverage = rasterMap.cacheLocally {

      localFile =>

      readCoverage(localFile)

    }

    roofOutlineMap.open{

      outlineStream =>

        rasterMap.open{

          rasterStream =>

            val roofOutlineImage = Image(outlineStream)(new TIFFImageReaderSpi).resize(heightMapImage.width, heightMapImage.height)

            val rawRasterImage = Image(rasterStream)(new TIFFImageReaderSpi)

            val rasterImage = rawRasterImage.resize(heightMapImage.width, heightMapImage.height)

            val rasterIntensities = rasterImage.intensities

            val roofOutlineFilter = roofOutlineImage.pixels.map {
              v =>
                if ((v & 0x00ffffff) == roofPixelColor) {
                  1
                } else 0
            }

            val heightMapImageIntensity = {

              heightMapImage.denseMatrix

            }

            val tree = KDTree.fromSeq {

              heightMapImageIntensity.iterator.map {
                case ((x, y), _) => (x, y)
              } toSeq

            }

            def pca(k: Int, intensities: DenseMatrix[Double]) = {

              val pca = intensities.mapPairs {
                case ((x, y), v) =>
                  if (roofOutlineFilter(x, y) > 0) {
                    val nearest = tree.findNearest((x, y), k)
                    val t = nearest.map(_._1.toDouble)
                    val n = nearest.map(_._1.toDouble) ++ nearest.map(_._2.toDouble) ++ nearest.map(v => intensities(v._1.toInt, v._2.toInt))
                    val nn = new DenseMatrix(nearest.size, 3, n.toArray)
                    val pca = princomp(nn)
                    val norm = pca.loadings(2, ::).inner
                    norm.update(2, math.abs(norm(2)))
                    ((pca.eigenvalues(2) / sum(pca.eigenvalues)), norm)
                  } else {
                    (0.0, up)
                  }
              }
              (pca.map(_._1), pca.map(_._2))
            }

            val (curvatures, normals) = pca(knn, heightMapImageIntensity)

            val normalizedCurvatures: DenseMatrix[Double] = {
              val max = breeze.linalg.max(curvatures)

              val min = breeze.linalg.min(curvatures)

              val ratio = 1.0 / (max - min)

              (curvatures :- min) :* ratio

            }

            val mean: Double = breeze.stats.mean(normalizedCurvatures)

            PlainGrowth.Log.logger.debug(s"$apn max [${max(normalizedCurvatures)}]")
            PlainGrowth.Log.logger.debug(s"$apn min [${min(normalizedCurvatures)}]")
            PlainGrowth.Log.logger.debug(s"$apn mean [${mean}]")

            val unVisited = mutable.LinkedHashSet.empty[(Int, Int)]

            normalizedCurvatures.iterator.toList.sortBy(_._2).foreach {
              case ((x, y), v) =>
                if (roofOutlineFilter(x, y) > 0) {
                  unVisited += ((x, y))
                }
            }

            class Region(p: Seq[(Int, Int)]) {

              var pp = p

              private var pSize = points.size

              var pCurvatureSum = points.map(v => normalizedCurvatures(v._1, v._2)).sum

              var xSum = points.map(_._1.toLong).sum

              var ySum = points.map(_._2.toLong).sum

              var colorSum = points.map(v => rasterIntensities(v._1, v._2)).sum

              def curvature = pCurvatureSum / pSize

              def +=(point: (Int, Int)) = {
                this.pp = this.pp :+ point
                pSize += 1
                pCurvatureSum += normalizedCurvatures(point._1, point._2)
                xSum += point._1
                ySum += point._2
                colorSum += rasterIntensities(point._1, point._2)
                this
              }

              def ++=(r: Region) = {
                this.pp = this.pp ++ r.pp
                pSize += r.size
                pCurvatureSum += r.pCurvatureSum
                xSum += r.xSum
                ySum += r.ySum
                colorSum += r.colorSum
              }

              def attributes(): (Double, Double) = {

                val (_, norm) = this.pca

                val azimuth = (norm(0), norm(1)) match {
                  case (x, y) if (x < 0 && y < 0) => math.toDegrees((2.0 * math.Pi) - math.atan(math.abs(x / y)))
                  case (x, y) if (x < 0 && y >= 0) => math.toDegrees((3.0 / 2.0 * math.Pi) - math.atan(math.abs(x / y)))
                  case (x, y) if (x >= 0 && y >= 0) => math.toDegrees((math.Pi) - math.atan(math.abs(x / y)))
                  case (x, y) if (x >= 0 && y < 0) => math.toDegrees(math.atan(math.abs(x / y)))
                }

                (
                  math.toDegrees(math.acos(abs(norm).dot(up))),
                  azimuth
                  )
              }

              def size() = pSize

              def center = {
                ((xSum / pSize).toInt, (ySum / pSize).toInt)
              }

              def color = (colorSum / pSize.toDouble)

              def points = pp

              def pca() = {

                if (pSize > 1) {
                  val n = pp.map(_._1.toDouble) ++ pp.map(_._2.toDouble) ++ pp.map(v => heightMapImageIntensity(v._1.toInt, v._2.toInt))
                  val nn = new DenseMatrix(pSize, 3, n.toArray)
                  val pca = princomp(nn)
                  val norm = pca.loadings(2, ::).inner
                  norm.update(2, math.abs(norm(2)))
                  ((pca.eigenvalues(2) / sum(pca.eigenvalues)), norm)
                } else {
                  (normalizedCurvatures(pp.head._1, pp.head._2), up)
                }

              }

            }

            val regions = ArrayBuffer.empty[Region]

            while (!unVisited.isEmpty) {

              val seedPoints = mutable.Queue.empty[(Int, Int)]
              val region = new Region(immutable.Vector.empty[(Int, Int)])

              val seedPoint = unVisited.head

              seedPoints += ((seedPoint._1, seedPoint._2))
              region += ((seedPoint._1, seedPoint._2))
              unVisited.remove(seedPoint)

              while (!seedPoints.isEmpty) {

                val (x, y) = seedPoints.dequeue()

                val z = rasterIntensities(x, y).toInt

                val nn = tree.findNearest((x, y), knn).filter(a => a._1 != x || a._2 != y)

                nn map {
                  case (nx, ny) =>
                    if (unVisited.contains((nx, ny))) {
                      val nxyN = normals(nx, ny)
                      val xyN = normals(x, y)
                      if (cosineDistance(nxyN, xyN) > smoothnessThreshold) {
                        if (math.abs(normalizedCurvatures(nx, ny) - normalizedCurvatures(x, y)) < curvatureThreshold) {
                          if (math.abs(rasterIntensities(nx, ny) - region.color) < colorThreshold) {
                            seedPoints += ((nx, ny))
                          }
                        }
                        region += ((nx, ny))
                        unVisited.remove((nx, ny))
                      }
                    }
                }

              }

              regions += region

            }

            @tailrec
            def mergeRegions(regions: ArrayBuffer[Region]): ArrayBuffer[Region] = {

              val sorted = mutable.LinkedHashSet.empty[(Int, Int)]

              val result = ArrayBuffer.empty[Region]

              regions.sortBy(-_.points.size).foreach {

                region => sorted += region.center

              }

              val regionsMap = regions.map(r => r.center -> r).toMap

              val regionsTree = KDTree.fromSeq(regions.map(_.center))

              while (!sorted.isEmpty) {
                val next = sorted.head
                sorted.remove(next)

                val region = regionsMap(next)

                val (slope, azimuth) = region.attributes()

                val neighbours = regionsTree.findNearest(next, 5).filter(v => v._1 != next._1 && v._2 != next._2)

                neighbours.foreach {
                  n =>
                    val nRegion = regionsMap(n)
                    if (sorted.contains(nRegion.center)) {
                      val (nSlope, nAzimuth) = nRegion.attributes()
                      if (math.abs(nSlope - slope) > slopeThreshold) {
                        if (math.abs(nAzimuth - azimuth) < azimuthThreshold) {
                          sorted.remove(n)
                          region ++= nRegion
                        }
                      }
                    }

                }

                result += region


              }

              if (regions.size > result.size) {
                mergeRegions(result)
              } else {
                result
              }

            }

            val biggerRegions = regions.filter(_.size() > regionSizeLimit)
            val smallerRegions = regions.filter(_.size() <= regionSizeLimit)

            val finalRegions = mergeRegions(biggerRegions)

            val regionsMap =
              (for {
                region <- finalRegions
                point <- region.points
              } yield {
                point -> region
              }).toMap

            val regionsTree = KDTree.fromSeq(regionsMap.keys.toSeq)

            val attrs = finalRegions.map(_.attributes)

            for {
              region <- smallerRegions
              point <- region.points
            } {
              regionsTree.findNearest((point._1, point._2), 1).headOption.map {
                nearest => regionsMap(nearest) += point
              }
            }

            val outPixels = DenseMatrix.fill(heightMapImage.width, heightMapImage.height)(0)

            val colorRatio = 16777215.0 / biggerRegions.size + 1

            val colors = finalRegions.zipWithIndex.map {
              case (_, idx) => ((idx + 1) * colorRatio).toInt
            }

            for {
              regionColor <- finalRegions.zip(colors)
              (region, color) = regionColor
              pixel <- region.points
              (x, y) = pixel
            } {
              outPixels.update(x, y, 0xff000000 + color)
            }

            val outImage = rawRasterImage.withPixels(outPixels.resize(rawRasterImage.width, rawRasterImage.height))

            var shapefile = FeatureCollection(coverage)

            def transformPolygon(polygon: Geometry): Geometry = {

              val jtsTransformation = {
                val mt2D = coverage.getGridGeometry.getGridToCRS2D(PixelOrientation.UPPER_LEFT).asInstanceOf[AffineTransform]

                new AffineTransformation(
                  mt2D.getScaleX,
                  mt2D.getShearX,
                  mt2D.getTranslateX,
                  mt2D.getShearY,
                  mt2D.getScaleY,
                  mt2D.getTranslateY)
              }

              val p = polygon.clone.asInstanceOf[Polygon]

              p.apply(jtsTransformation)

              p
            }

            for {
              ((color, region), attrs) <- colors.zip(finalRegions).zip(attrs)
              extracted = extractBand(outImage, color)
              polygons = extracted.vectorize(filterThreshold = regionSizeLimit).map(transformPolygon)
            } {
              shapefile = shapefile ++(polygons, polygons.map(_ => Map("apn" -> apn, "slope" -> attrs._1.asInstanceOf[AnyRef], "azimuth" -> attrs._2.asInstanceOf[AnyRef])))
            }

            shapefile

        }

    }

  }

}

object PlainGrowth {

  private object Log extends Serializable {
    @transient lazy val logger = LoggerFactory.getLogger(PlainGrowth.getClass)
  }

  /**
    * Run plain [[PlainGrowth]] tool. Get all necessary parameters from configuration file.
    * @param sc Spark context
    * @param config an instance of [[Config]] from which all parameters are read
    */
  def run()(implicit sc: SparkContext, config: Config) {

    val heightMapsFolder = config.getString("com.sungevity.smt.facets.input.height-maps")

    val roofOutlinesFolder = config.getString("com.sungevity.smt.facets.input.roofs-outlines-path")

    val rasterFolder = config.getString("com.sungevity.smt.facets.input.raster")

    val outputFolder = config.getString("com.sungevity.smt.facets.output.path")

    val heightMaps = new URI(heightMapsFolder).listFiles.filter(_.getPath.endsWith(".txt"))

    val roofOutlines = new URI(roofOutlinesFolder).listFiles.filter(_.getPath.endsWith(".tif"))

    val raster = new URI(rasterFolder).listFiles.filter(_.getPath.endsWith(".tif"))

    val inputs =
      for {
        hm <- heightMaps
        id = FilenameUtils.getBaseName(hm.getPath)
        ro <- roofOutlines.find(ro => FilenameUtils.getBaseName(ro.getPath) == id)
        ra <- raster.find(ra => FilenameUtils.getBaseName(ra.getPath) == id)
      } yield {
        (hm, ro, ra)
      }

    val knn = config.getInt("com.sungevity.smt.facets.knn")

    val roofPixelColor = config.getInt("com.sungevity.smt.facets.roof-pixels-color")

    val regionSizeLimit = config.getInt("com.sungevity.smt.facets.region-pixel-area-threshold")

    val curvatureThreshold = config.getDouble("com.sungevity.smt.facets.threshold.curvature")

    val smoothnessThreshold = config.getDouble("com.sungevity.smt.facets.threshold.smoothness")

    val colorThreshold = config.getDouble("com.sungevity.smt.facets.threshold.color")

    val slopeThreshold = config.getDouble("com.sungevity.smt.facets.threshold.slope")

    val azimuthThreshold = config.getDouble("com.sungevity.smt.facets.threshold.azimuth")

    val pg = new PlainGrowth(roofPixelColor)

    val count = sc.parallelize(inputs).map {

      input =>

        val name = FilenameUtils.getBaseName(input._1.getPath)

        val features = pg.segment(input,
          smoothnessThreshold,
          curvatureThreshold,
          colorThreshold,
          knn,
          regionSizeLimit,
          slopeThreshold,
          azimuthThreshold
        )

        val outputFile = new URI(outputFolder) + s"$name.geojson"

        outputFile.create(true) {
          stream =>
            features.writeGeoJSON(stream, Map("apn" -> classOf[java.lang.String], "slope" -> classOf[java.lang.Double], "azimuth" -> classOf[java.lang.Double]))
        }

        outputFile

    } count

    Log.logger.info(s"Processed $count files")

  }

  /**
    * Evaluate how close result faces are to the ground thruth faces
    * @param gold ground thruth faces
    * @param result result faces
    * @return a double value from 0.0 to 1.0 where zero means similar
    */
  def evaluate(gold: Seq[Geometry], result: Seq[Geometry]): Double = {
    val m = gold.size
    val n = result.size

    val intersections = for{
      g <- gold
      s <- result
    } yield {
      if(g.intersects(s)) Some(g -> s)
      else None
    }

    val gIntersections = intersections.flatten.groupBy(_._1).map {
      v =>
        val key = v._1
        val values = v._2.map(_._2).sortBy(key.intersection(_).getArea).reverse
        key -> values.head
    }

    val restG = gold.filter(g => !gIntersections.exists(gi => gi._1 == g))

    val restR = result.filter(r => !gIntersections.exists(ri => ri._2 == r))

    val iComponents = gIntersections.map{
      case (g, s) =>
        val intersection = g.intersection(s)
        val rg = g.difference(intersection).getArea
        val sg = s.difference(intersection).getArea
        (rg / g.getArea + sg / s.getArea) / 2
    }.sum

    (iComponents + restG.size + restR.size) / (m + n)

  }

}
