package com.sungevity.smt.faces.models

import java.io.{File, OutputStream, Serializable}
import java.net.URI

import com.sungevity.smt.faces.utils.FSUtils._
import com.vividsolutions.jts.geom._
import org.apache.commons.io.FilenameUtils
import org.geotools.coverage.grid.GridCoverage2D
import org.geotools.data.collection.ListFeatureCollection
import org.geotools.data.shapefile.{ShapefileDataStore, ShapefileDataStoreFactory}
import org.geotools.data.simple.{SimpleFeatureCollection, SimpleFeatureStore}
import org.geotools.data.{DataStore, DefaultTransaction}
import org.geotools.feature.FeatureIterator
import org.geotools.feature.simple.{SimpleFeatureBuilder, SimpleFeatureTypeBuilder}
import org.geotools.geojson.feature.FeatureJSON
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.opengis.feature.Feature
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter
import org.opengis.referencing.crs.CoordinateReferenceSystem

import scala.collection.JavaConversions._
import scala.util.Try

case class FeatureCollection(crs: CoordinateReferenceSystem, geometries: Seq[Geometry] = Seq.empty, attributes: Seq[Map[String, AnyRef]] = Seq.empty) {

  private val GEOM: String = "geom"

  private val THE_GEOM: String = "the_" + GEOM

  private implicit def simpleFeatureIterator[F <: Feature](it: FeatureIterator[F]) = new Iterator[F] {

    override def hasNext: Boolean = if (!it.hasNext) {
      it.close()
      false
    } else true

    override def next(): F = it.next()

  }

  def features(schema: Map[String, Class[_]]) = {
    val featureType = createLocalFeatureType(crs, classOf[Polygon], "name", schema)

    val featureCollection = createFeatureCollection(featureType, { (builder: SimpleFeatureBuilder, geom: Geometry, attributes: Map[String, AnyRef]) =>
      builder.set(THE_GEOM, geom)
      attributes.foreach{
        attr => builder.set(attr._1, attr._2)

      }
    })

    featureCollection
  }

  def writeGeoJSON(out: OutputStream, schema: Map[String, Class[_]]): Unit ={
    new FeatureJSON().writeFeatureCollection(features(schema), out)
  }

  def ++(polygons: Seq[Geometry], attributes: Seq[Map[String, AnyRef]] = Seq.empty): FeatureCollection = {
    copy(
      geometries = this.geometries ++ polygons,
      attributes = this.attributes ++ attributes
    )
  }

  def writeShapefile(path: String, schema: Map[String, Class[_]]) = {
    val featureType = createLocalFeatureType(crs, classOf[Polygon], FilenameUtils.getName(path), schema)


    val featureCollection = createFeatureCollection(featureType, { (builder: SimpleFeatureBuilder, geom: Geometry, attributes: Map[String, AnyRef]) =>
      builder.set(THE_GEOM, geom)
      attributes.foreach{
        attr => builder.set(attr._1, attr._2)

      }
    })

    val dataStoreFactory = new ShapefileDataStoreFactory()

    val params = Map[String, Serializable](
      "url" -> new File(s"$path.shp").toURI.toURL,
      "create spatial index" -> java.lang.Boolean.TRUE
    )

    val newDataStore = dataStoreFactory.createNewDataStore(params)

    try {
      writeFeatures(newDataStore, featureCollection, Some(crs))
    } finally {
      newDataStore.dispose()
    }
  }

  def createFeatureCollection(featureType: SimpleFeatureType, featureSetter: (SimpleFeatureBuilder, Geometry, Map[String, AnyRef]) => Unit): SimpleFeatureCollection = {
    val result = new ListFeatureCollection(featureType)

    val featureBuilder = new SimpleFeatureBuilder(featureType)

    for (((geom, attrs), idx) <- geometries.zip(attributes).zipWithIndex) {

      featureSetter(featureBuilder, geom, attrs)

      val f = featureBuilder.buildFeature(idx.toString)

      f.getAttributes.iterator().foreach {
        case g: Geometry => g.setSRID(srid(crs))
        case _ =>
      }

      result.add(f)
    }

    result
  }

  def writeFeatures(dataStore: DataStore, featureCollection: SimpleFeatureCollection, coordinateReferenceSystem: Option[CoordinateReferenceSystem]): Unit = {
    createSchema(dataStore, featureCollection.getSchema, coordinateReferenceSystem)

    val transaction = new DefaultTransaction("create")

    try {
      val featureSource = dataStore.getFeatureSource(featureCollection.getSchema.getTypeName)

      featureSource match {
        case featureStore: SimpleFeatureStore =>
          featureStore.setTransaction(transaction)
          featureStore.addFeatures(featureCollection)
          transaction.commit()
        case _ =>
          throw new RuntimeException(s"${featureCollection.getSchema.getTypeName} does not support read/write access")
      }
    } finally {
      transaction.close()
    }
  }

  def createSchema(dataStore: DataStore, featureType: SimpleFeatureType, coordinateReferenceSystem: Option[CoordinateReferenceSystem]): Unit = dataStore match {
    case ds: ShapefileDataStore =>
      ds.createSchema(featureType)
      coordinateReferenceSystem.foreach(ds.forceSchemaCRS)
  }

  def createLocalFeatureType(crs: CoordinateReferenceSystem, geometryClass: Class[_ <: Geometry], name: String, schema: Map[String, Class[_]]): SimpleFeatureType = {
    require(geometryClass != null && name != null)

    val builder = new SimpleFeatureTypeBuilder

    builder.setName(name)
    builder.setNamespaceURI("http://sungevity.com/")
    builder.setCRS(crs)
    builder.setDefaultGeometry(THE_GEOM)
    builder.add(THE_GEOM, geometryClass)
    schema.foreach {
      case (name, t) => builder.add(name, t)
    }

    builder.buildFeatureType
  }

  def srid(crs: CoordinateReferenceSystem) = Try {
    val e = CRS.lookupEpsgCode(crs, false)
    e.intValue()
  }.getOrElse(0)

}

object FeatureCollection {

  private implicit def simpleFeatureIterator[F <: Feature](it: FeatureIterator[F]) = new Iterator[F] {

    override def hasNext: Boolean = if (!it.hasNext) {
      it.close()
      false
    } else true

    override def next(): F = it.next()

  }

  def apply(file: File, targetSrid: Option[String]): FeatureCollection = {

    val dataStore = new ShapefileDataStore(file.toURI().toURL())

    val typeName = dataStore.getTypeNames()(0)
    val source = dataStore.getFeatureSource(typeName)

    val filter = Filter.INCLUDE

    val transform = {
      g: Geometry => targetSrid.map {
        srid =>
          JTS.transform(g, CRS.findMathTransform(source.getSchema.getCoordinateReferenceSystem(), CRS.decode(s"EPSG:$srid")))
      } getOrElse(g)
    }

    val collection = source.getFeatures(filter)

    val features = collection.features() map {
      feature =>
        val geometry = feature.getDefaultGeometry.asInstanceOf[Geometry]
        transform(geometry)
    }

    new FeatureCollection(source.getSchema.getCoordinateReferenceSystem(), features.toSeq)

  }

  def apply(source: URI): FeatureCollection = {

    val reader = new FeatureJSON()

    val crs = source.open{
      stream =>
        reader.readCRS(stream)
    }

    val features = source.open{
      stream =>
        reader.readFeatureCollection(stream).features().map(v => v.asInstanceOf[SimpleFeature].getDefaultGeometry.asInstanceOf[Geometry])
    }

    new FeatureCollection(crs, features.toSeq)

  }

  def apply(coverage: GridCoverage2D): FeatureCollection = {

    new FeatureCollection(coverage.getCoordinateReferenceSystem)

  }
}
