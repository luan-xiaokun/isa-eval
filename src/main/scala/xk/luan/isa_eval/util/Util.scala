package xk.luan.isa_eval
package util

import java.nio.file.Path
import scala.util.Random
import scala.collection.mutable.ListBuffer

class HeaderNotFoundException(message: String) extends Exception(message)

object Utils {
  def getIsabelleSessions(isaPath: os.Path): List[String] = {
    val these = os.list(isaPath)
    these.filter(p => os.isFile(p) && p.last == "ROOT").flatMap(getAllSessions).toList ++ these
      .filter(os.isDir)
      .flatMap(p => getIsabelleSessions(p))
  }

  def getThyFiles(file: os.Path): List[os.Path] = {
    val these = os.list(file).toList
    these.filter(os.isFile).filter(_.last.endsWith(".thy")) ++ these
      .filter(os.isDir)
      .flatMap(p => getThyFiles(p))
  }

  def findThyFile(sessionPath: os.Path, theoryName: String): Option[os.Path] = {
    val these = os.list(sessionPath).toList
    val index = these.indexWhere(_.last.contains(f"$theoryName.thy"))
    if (index != -1) Some(these(index))
    else these.filter(os.isDir).flatMap(p => findThyFile(p, theoryName)).headOption
  }

  def isAfpEntry(file: os.Path): Boolean = os.isDir(file) && os.exists(file / "ROOT")

  def getAllSessions(rootPath: os.Path): List[String] = {
    // Step 1. remove comment blocks
    val rootText = os.read(rootPath).replaceAll(raw"\(\*[\s\S]*?\*\)", "")
    // Step 2. find all 'session' definitions in rootText
    val sessionRegex =
      """session\s+([\w"+-]+)\s*(?:\(.*?\)\s*)?(?:in\s*(.*?)\s*)?=.*?\n""".r
    sessionRegex
      .findAllIn(rootText)
      .matchData
      .map(_.group(1).stripPrefix("\"").stripSuffix("\""))
      .toList
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
    sessionRangeList zip sessionNameList zip mainDirectoryList foreach { case ((indices, sessionName), mainDirectory) =>
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
          directorySessionMap ++= directories.map(dir => mainDirectory.resolve(dir).normalize() -> sessionName)
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

  def getCommonAncestor(path1: os.Path, path2: os.Path): os.Path = {
    val path1List = path1.segments
    val path2List = path2.segments
    val commonPrefix = path1List.zip(path2List).takeWhile(Function.tupled(_ == _)).map(_._1)
    os.Path("/" + commonPrefix.mkString("/"))
  }

  def getCommonAncestor(path1: String, path2: String): os.Path = {
    getCommonAncestor(os.Path(path1), os.Path(path2))
  }

  /* this function has side effect! */
  def genTmpThyFilePath(thyPath: os.Path): os.Path = {
    val thyName = thyPath.last.stripSuffix(".thy")
    val rand = new Random
    var tmpThyPath = thyPath
    while (os.exists(tmpThyPath)) {
      val tmpNameSuffix = rand.nextInt().toHexString
      tmpThyPath = thyPath / os.up / f"${thyName}_$tmpNameSuffix.thy"
    }
    tmpThyPath
  }
  def createTmpThyFile(thyPath: os.Path, tmpThyPath: os.Path): Unit = {
    val thyName = thyPath.last.stripSuffix(".thy")
    val thyContent = os.read(thyPath)
    f"(?s)theory\\s*(?:%%invisible)?\\s*\"?$thyName.*?begin".r.findFirstIn(thyContent) match {
      case Some(piece) =>
        val newPiece = piece.replaceFirst(thyName, tmpThyPath.last.stripSuffix(".thy"))
        os.write.over(tmpThyPath, thyContent.replace(piece, newPiece))
      case None =>
        throw new HeaderNotFoundException(s"Cannot find theory header in $thyPath")
    }
  }
}
