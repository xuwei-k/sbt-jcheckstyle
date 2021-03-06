package xerial.sbt.jcheckstyle

import com.puppycrawl.tools.checkstyle.api.{AuditEvent, AuditListener, SeverityLevel}
import com.puppycrawl.tools.checkstyle.{PackageNamesLoader, Checker, ConfigurationLoader, PropertiesExpander}
import sbt.Keys._
import sbt._
import sbt.plugins.JvmPlugin

import scala.collection.JavaConverters._

object JCheckStyle extends AutoPlugin {

  trait JCheckStyleKeys {
    val jcheckStyleConfig = settingKey[String]("Check style type: google (default), facebook, sun or path to checkstyle.xml")
    val jcheckStyleStrict = settingKey[Boolean]("Issue an error when style check fails. default = true")
    val jcheckStyle = taskKey[Boolean]("Run checkstyle")
  }

  object JCheckStyleKeys extends JCheckStyleKeys {
  }

  object autoImport extends JCheckStyleKeys {
  }

  override def trigger = allRequirements
  override def requires = JvmPlugin
  override def projectSettings = jcheckStyleSettings

  import autoImport._

  lazy val jcheckStyleSettings = Seq[Setting[_]](
    jcheckStyleConfig := "google",
    jcheckStyleStrict := true,
    jcheckStyle in Compile <<= runCheckStyle(Compile),
    jcheckStyle in Test <<= runCheckStyle(Test)
  )

  private def relPath(file: File, base: File): File =
    file.relativeTo(base).getOrElse(file)


  private def findStyleFile(style:String, targetDir:File): File = {
    val styleResource = this.getClass.getResource(s"/xerial/sbt/jcheckstyle/${style}.xml")
    if(styleResource != null) {
      val in = styleResource.openStream()
      try {
        val configFileBytes = IO.readBytes(in)
        val path = targetDir / "jcheckstyle" / s"${style.toLowerCase}.xml"
        path.getParentFile.mkdirs()
        IO.write(path, configFileBytes)
        path
      }
      finally {
        in.close()
      }
    }
    else {
      new File(style)
    }
  }

  def runCheckStyle(conf: Configuration): Def.Initialize[Task[Boolean]] = Def.task {
    val log = streams.value.log

    if (!scala.util.Properties.isJavaAtLeast("1.7")) {
      log.warn(s"checkstyle requires Java 1.7 or higher.")
    }
    else {
      val javaSrcDir = (javaSource in conf).value
      log.info(s"Running checkstyle: ${relPath(javaSrcDir, baseDirectory.value)}")

      // Find checkstyle configuration
      val styleFile = findStyleFile(jcheckStyleConfig.value, target.value)
      if (!styleFile.exists()) {
        sys.error(s"${styleFile} does not exist. jcheckStyleConfig must be airlift, google, sun or path to config.xml")
      }

      log.info(s"Using checkstyle configuration: ${jcheckStyleConfig.value}")

      val javaFiles = (sources in conf).value.filter(_.getName endsWith ".java").asJava
      val loader = ConfigurationLoader.loadConfiguration(styleFile.getPath, new PropertiesExpander(System.getProperties))
      val checker = new Checker()
      try {
        checker.setModuleClassLoader(classOf[Checker].getClassLoader)
        checker.configure(loader)
        checker.addListener(new StyleCheckListener(baseDirectory.value, log))
        val totalNumberOfErrors = checker.process(javaFiles)
        if (totalNumberOfErrors > 0) {
          if (jcheckStyleStrict.value) {
            sys.error(s"Found ${totalNumberOfErrors} style error(s)")
          }
        }
        else {
          log.info(s"checkstyle has succeeded")
        }
      }
      finally {
        checker.destroy()
      }
    }
    true
  }

  class StyleCheckListener(baseDir: File, log: Logger) extends AuditListener {
    override def addError(evt: AuditEvent): Unit = {

      def message: String = s"${relPath(new File(evt.getFileName), baseDir)}:${evt.getLine}: ${evt.getMessage}"

      evt.getSeverityLevel match {
        case SeverityLevel.ERROR =>
          log.error(message)
        case SeverityLevel.WARNING =>
          log.warn(message)
        case _ =>
      }
    }

    override def fileStarted(evt: AuditEvent): Unit = {
      log.debug(s"checking ${relPath(new File(evt.getFileName), baseDir)}")
    }
    override def auditStarted(evt: AuditEvent): Unit = {}

    override def fileFinished(evt: AuditEvent): Unit = {}

    override def addException(evt: AuditEvent, throwable: Throwable): Unit = {
    }

    override def auditFinished(evt: AuditEvent): Unit = {

    }
  }


}
