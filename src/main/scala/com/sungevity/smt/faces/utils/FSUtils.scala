package com.sungevity.smt.faces.utils

import java.io.{OutputStream, File, InputStream}
import java.net.URI

import org.apache.commons.io.FilenameUtils
import org.apache.hadoop.fs.{FileSystem, Path, RemoteIterator, FileStatus}
import org.apache.spark.SparkEnv
import org.apache.spark.deploy.SparkHadoopUtil

/**
  * Helper class that wraps Hadoop's FileSystem facade
  */
object FSUtils {

  implicit def RemoteIterator[T <: FileStatus](it: RemoteIterator[T]) = new Iterator[T] {

    override def hasNext: Boolean = if (!it.hasNext) {
      false
    } else true

    override def next(): T = it.next()

  }


  implicit class FSUtilsRichPath(path: URI){

    private val fs = FileSystem.get(path, SparkHadoopUtil.get.newConfiguration(SparkEnv.get.conf))
    private val hPath = new Path(path)

    /**
      * List resources referred by given URI
      * @return a sequence of child resources
      */
    def listFiles: Seq[URI] ={
      {
        path match {
          case path if fs.isDirectory(hPath) => fs.listFiles(hPath, true).map(_.getPath.toUri).toSeq
          case path => Seq(hPath.toUri)
        }
      }
    }

    /**
      * Verify whether given resource is available
      * @return true if available otherwise false
      */
    def exists: Boolean = fs.exists(hPath)

    /**
      * Verify whether given resource is a directory
      * @return true if resource is a directory otherwise false
      */
    def isDirectory: Boolean = fs.isDirectory(hPath)

    /**
      * Remove a resource
      * @return true if removed, false otherwise
      */
    def remove: Boolean = {
      fs.delete(hPath, true)
    }

    /**
      * Open a resource for reading
      * @param f a function that processes resource's input stream
      * @tparam R Function's result
      * @return Function's result
      */
    def open[R](f: InputStream => R): R = {
      val stream = inputStream
      try {
        f(stream)
      } finally {
        stream.close()
      }
    }

    /**
      * Get input stream of resource
      * @return resource's input stream
      */
    def inputStream = fs.open(hPath)

    /**
      * Get output stream of resource
      * @param overwrite true if resource can be overridden
      * @return resource's output stream
      */
    def outputStream(overwrite: Boolean) = fs.create(hPath, overwrite)

    /**
      * Open resource for writing
      * @param overwrite true if resource can be overridden
      * @param f a function that processes resulting output stream
      * @tparam R function's return type
      * @return function's result
      */
    def create[R](overwrite: Boolean)(f: OutputStream => R): R = {
      val stream = outputStream(overwrite)
      try{
        f(stream)
      } finally {
        stream.close()
      }
    }

    /**
      * Append path to a base resource
      * @param child a child resource relative to the given
      * @return a new combined [[URI]]
      */
    def +(child: String): URI = {
      new URI(path.toString + "/" + child)
    }

    /**
      * Get resource file name
      * @return resource's file name
      */
    def name: String = FilenameUtils.getName(path.getPath)

    /**
      * Get resource's base name
      * @return resource's base name
      */
    def baseName: String = FilenameUtils.getBaseName(path.getPath)

    /**
      * Get resource's folder name
      * @return resource's folder name
      */
    def folderName: String = FilenameUtils.getFullPath(path.toString)

    /**
      * Cache and process resource locally
      * @param f function to process resource locally
      * @tparam T function's return type
      * @return function's return value
      */
    def cacheLocally[T](f: File => T): T = cacheLocally(FilenameUtils.getName(path.getPath))(f)

    /**
      * Cache and process resource locally
      * @param fileName user this file name when caching resource locally
      * @param f function to process resource locally
      * @tparam T function's return type
      * @return function's return value
      */
    def cacheLocally[T](fileName: String)(f: File => T): T = fs.getScheme match {
      case "file" => f {
        path.isAbsolute match {
          case true => new File(path)
          case false => new File(path.getPath)
        }
      }
      case scheme => {
        val localPath = new File(new File(System.getProperty("java.io.tmpdir")), s"${SparkEnv.get.executorId}-$fileName")
        localPath.deleteOnExit()
        fs.copyToLocalFile(hPath, new Path(localPath.getAbsolutePath))
        try {
          f(localPath)
        } finally {
          localPath.delete()
        }
      }
    }

  }

}
