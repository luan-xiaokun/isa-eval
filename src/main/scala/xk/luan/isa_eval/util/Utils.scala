package xk.luan.isa_eval
package util

import java.nio.file.Path
import scala.collection.mutable.ListBuffer

object Utils {
  private def getThyFiles(file: os.Path): List[os.Path] = {
    val these = os.list(file).toList
    these.filter(os.isFile).filter(_.last.endsWith(".thy")) ++ these
      .filter(os.isDir)
      .flatMap(p => getThyFiles(p))
  }

  def parseRootFile(rootPath: os.Path): Map[String, List[os.Path]] = {
    val projectPath = rootPath / os.up
    // Step 1. remove comment blocks
    val rootText = os.read(rootPath).replaceAll(raw"\(\*[\s\S]*?\*\)", "")

    // Step 2. find all 'session' definitions in rootText
    val directorySessionMap = collection.mutable.Map[Path, String]()
    val sessionNameList = ListBuffer[String]()
    val mainDirectoryList = ListBuffer[Path]()
    val sessionNameIndexList = ListBuffer[Int]()
    val sessionRegex =
      """session\s+([\w_"+-]+)\s*(?:\(.*?\)\s*)?(?:in\s*(.*?)\s*)?=.*?""".r
    sessionRegex
      .findAllIn(rootText)
      .matchData
      .foreach(m => {
        val name = m.group(1).stripPrefix("\"").stripSuffix("\"")
        val mainDirectory = m.group(2) match {
          // getting null means this session is the AFP main session
          case null => ""
          case dir  => dir.stripPrefix("\"").stripSuffix("\"")
        }
        sessionNameList += name
        mainDirectoryList += projectPath.toNIO.resolve(mainDirectory)
        sessionNameIndexList += m.start(1)
      })
    sessionNameIndexList += rootText.length

    // Step 3. find all directories between session definitions
    val directoriesPattern = """directories\s*\n([\s\S]*?)theories""".r
    val sessionRangeList = sessionNameIndexList.sliding(2)
    sessionRangeList zip sessionNameList zip mainDirectoryList foreach {
      case ((indices, sessionName), mainDirectory) =>
        directoriesPattern
          .findFirstMatchIn(rootText.slice(indices.head, indices.last)) match {
          case Some(raw_match) =>
            val directories = raw_match
              .group(1)
              .trim
              .split("\\s+")
              .map(dir => dir.stripSuffix("\"").stripPrefix("\""))
            directorySessionMap += (mainDirectory -> sessionName)
            // note that these directories are relative to mainDirectory!
            directorySessionMap ++= directories.map(dir =>
              mainDirectory.resolve(dir).normalize() -> sessionName
            )
          case None => directorySessionMap += (mainDirectory -> sessionName)
        }
    }

    // Step 4. assign each thy file in the AFP entry directory to its session
    val sessionFilesMap = sessionNameList.map(_ -> ListBuffer[os.Path]()).toMap
    getThyFiles(projectPath).foreach(thyFile => {
      var dir = thyFile.toNIO.getParent
      while (!directorySessionMap.contains(dir))
        dir = dir.getParent
      val sessionName = directorySessionMap(dir)
      sessionFilesMap(sessionName) += thyFile
    })

    sessionFilesMap.transform((_, v) => v.toList)
  }
}
