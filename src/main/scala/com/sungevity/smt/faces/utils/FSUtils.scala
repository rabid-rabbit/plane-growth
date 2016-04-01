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

    def listFiles: Seq[URI] ={
      {
        path match {
          case path if fs.isDirectory(hPath) => fs.listFiles(hPath, true).map(_.getPath.toUri).toSeq
          case path => Seq(hPath.toUri)
        }
      }
    }

    def exists: Boolean = fs.exists(hPath)

    def isDirectory: Boolean = fs.isDirectory(hPath)

    def remove: Boolean = {
      fs.delete(hPath, true)
    }

    def open[R](f: InputStream => R): R = {
      val stream = inputStream
      try {
        f(stream)
      } finally {
        stream.close()
      }
    }

    def inputStream = fs.open(hPath)

    def outputStream(overwrite: Boolean) = fs.create(hPath, overwrite)

    def create[R](overwrite: Boolean)(f: OutputStream => R): R = {
      val stream = outputStream(overwrite)
      try{
        f(stream)
      } finally {
        stream.close()
      }
    }

    def +(child: String): URI = {
      new URI(path.toString + "/" + child)
    }

    def name: String = FilenameUtils.getName(path.getPath)

    def baseName: String = FilenameUtils.getBaseName(path.getPath)

    def folderName: String = FilenameUtils.getFullPath(path.toString)

    def cacheLocally[T](f: File => T): T = cacheLocally(FilenameUtils.getName(path.getPath))(f)

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
