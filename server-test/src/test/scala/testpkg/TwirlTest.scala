/*package testpkg

import sbt.internal.bsp._
import sbt.IO
import sbt.internal.protocol.JsonRpcRequestMessage
import sbt.internal.protocol.codec.JsonRPCProtocol._
import sjsonnew.JsonWriter
import sjsonnew.support.scalajson.unsafe.{ CompactPrinter, Converter }

import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

object TwirlTest extends AbstractServerTest {
  import sbt.internal.bsp.codec.JsonProtocol._

  private val idGen: AtomicInteger = new AtomicInteger(0)
  private def nextId(): Int = idGen.getAndIncrement()

  override val testDirectory: String = "buildserver"

  test("buildTarget/compile [diagnostics] clear stale warnings") { _ =>
    val buildTarget = buildTargetUri("twirlProj", "Compile")
    val testFile =
      new File(svr.baseDirectory, s"twirl-proj/src/main/twirl/vHostHttpToHttps.scala.txt")

    compile(buildTarget)

    assert(
      svr.waitForString(10.seconds) { s =>
        s.contains("build/publishDiagnostics") &&
        s.contains("vHostHttpToHttps.scala.txt") &&
        s.contains(""""severity":1""") &&
        s.contains("""not found: value mainDomaiiiiin""")
      },
      "should send publishDiagnostics with serverity 1 for vHostHttpToHttps.scala.txt "
    )
    IO.write(
      testFile,
      """|@(subDomain: String, mainDomain: String, redirectStatusCode: Int)
         |@fullDomain=@{
         |  if(mainDomain) {
         |    mainDomain
         |  } else {
         |    s"$subDomain.$mainDomain"
         |  }
         |}
         |
         |
         |<VirtualHost *:80>
         |  ServerName @fullDomain
         |  RewriteEngine On
         |  RewriteRule (.*) "https://%{HTTP_HOST}%{REQUEST_URI}" [R=@redirectStatusCode,L]
         |
         |</VirtualHost>
         |""".stripMargin
    )

    compile(buildTarget)

    assert(
      svr.waitForString(30.seconds) { s =>
        s.contains("build/publishDiagnostics") &&
        s.contains("vHostHttpToHttps.scala.txt") &&
        s.contains(""""diagnostics":[]""") &&
        s.contains(""""reset":"true"""")
      },
      "should send publishDiagnostics with empty diagnostics"
    )
  }

  private def request[T: JsonWriter](id: Int, method: String, params: T): String = {
    val request = JsonRpcRequestMessage("2.0", id.toString, method, Converter.toJson(params).get)
    val json = Converter.toJson(request).get
    CompactPrinter(json)
  }
  private def compile(buildTarget: URI, id: Int = nextId()): Unit = {
    val params =
      CompileParams(targets = Vector(BuildTargetIdentifier(buildTarget)), None, Vector.empty)
    svr.sendJsonRpc(request(id, "buildTarget/compile", params))
  }

  private def buildTargetUri(project: String, config: String): URI =
    new URI(s"${svr.baseDirectory.getAbsoluteFile.toURI}#$project/$config")
}
 */
