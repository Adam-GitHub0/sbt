/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt.internal.server

import java.nio.file.Path

import sbt.StandardMain
import sbt.internal.bsp._
import sbt.internal.util.ManagedLogger
import sbt.internal.server.BuildServerProtocol.BspCompileState
import xsbti.compile.CompileAnalysis
import xsbti.{
  FileConverter,
  Problem,
  Reporter,
  Severity,
  VirtualFile,
  VirtualFileRef,
  Position => XPosition
}

import scala.collection.JavaConverters._
import scala.collection.mutable

sealed trait BuildServerReporter extends Reporter {
  private final val sigFilesWritten = "[sig files written]"
  private final val pureExpression = "a pure expression does nothing in statement position"

  protected def isMetaBuild: Boolean

  protected def logger: ManagedLogger

  protected def underlying: Reporter

  protected def publishDiagnostic(problem: Problem): Unit

  def sendSuccessReport(
      analysis: CompileAnalysis,
  ): Unit

  def sendFailureReport(sources: Array[VirtualFile]): Unit

  override def reset(): Unit = underlying.reset()

  override def hasErrors: Boolean = underlying.hasErrors

  override def hasWarnings: Boolean = underlying.hasWarnings

  override def printSummary(): Unit = underlying.printSummary()

  override def problems(): Array[Problem] = underlying.problems()

  override def log(problem: Problem): Unit = {
    if (problem.message == sigFilesWritten) {
      logger.debug(sigFilesWritten)
    } else if (isMetaBuild && problem.message.startsWith(pureExpression)) {
      // work around https://github.com/scala/bug/issues/12112 by ignoring it in the reporter
      logger.debug(problem.message)
    } else {
      publishDiagnostic(problem)
      underlying.log(problem)
    }
  }

  override def comment(pos: XPosition, msg: String): Unit = underlying.comment(pos, msg)
}

final class BuildServerReporterImpl(
    buildTarget: BuildTargetIdentifier,
    bspCompileState: BspCompileState,
    converter: FileConverter,
    sourcePositionMapper: xsbti.Position => xsbti.Position,
    protected override val isMetaBuild: Boolean,
    protected override val logger: ManagedLogger,
    protected override val underlying: Reporter
) extends BuildServerReporter {
  import sbt.internal.bsp.codec.JsonProtocol._
  import sbt.internal.inc.JavaInterfaceUtil._

  private lazy val exchange = StandardMain.exchange
  private val problemsByFile = mutable.Map[VirtualFileRef, Vector[Problem]]()

  // sometimes the compiler returns a fake position such as <macro>
  // on Windows, this causes InvalidPathException (see #5994 and #6720)
  private def toSafePath(ref: VirtualFileRef): Option[Path] =
    if (ref.id().contains("<")) None
    else Some(converter.toPath(ref))

  /**
   * Send diagnostics from the compilation to the client.
   * Do not send empty diagnostics if previous ones were also empty ones.
   *
   * @param analysis current compile analysis
   */
  override def sendSuccessReport(
      analysis: CompileAnalysis,
  ): Unit = {
    val shouldReportAllProblems = !bspCompileState.compiledAtLeastOnce.getAndSet(true)
    for {
      (source, infos) <- analysis.readSourceInfos.getAllSourceInfos.asScala
      filePath <- toSafePath(source)
    } {
      // clear problems for current file
      val oldDocuments = bspCompileState.hasAnyProblems.getAndUpdate(_ - source).getOrElse(source, Seq.empty)

      val reportedProblems = infos.getReportedProblems.toVector
      val problems = reportedProblems

      // publish diagnostics if:
      // 1. file had any problems previously - we might want to update them with new ones
      // 2. file has fresh problems - we might want to update old ones
      // 3. build project is compiled first time - shouldReportAllProblems is set
      val shouldPublish = oldDocuments.nonEmpty || problems.nonEmpty || shouldReportAllProblems

      if (shouldPublish) {
        val diagsByDocuments = problems
          .flatMap(mapProblemToDiagnostic)
          .groupBy { case (document, _) => document }
          .mapValues(_.map { case (_, diag) => diag })

        val newDocuments = diagsByDocuments.keySet

        bspCompileState.hasAnyProblems.updateAndGet(_ + (source -> newDocuments.toVector))
        
        (newDocuments ++ oldDocuments).foreach { document =>
          val diags: Vector[Diagnostic] = diagsByDocuments
            .getOrElse(document, Vector.empty)
          val params = PublishDiagnosticsParams(
            document,
            buildTarget,
            originId = None,
            diags,
            reset = true
          )
          exchange.notifyEvent("build/publishDiagnostics", params)
        }
      }
    }
  }
  
  
  override def sendFailureReport(sources: Array[VirtualFile]): Unit = {
    val shouldReportAllProblems = !bspCompileState.compiledAtLeastOnce.get
    for {
      source <- sources // scala
      //id <- sourcePositionMapper(problem.position).sourcePath.toOption
      //filePath <- toSafePath(VirtualFileRef.of(id))
    } {

      // twirl => twirl (scala)
      // 1. filePath: twirl
      // 2. problemByFiles: scala => twirl
      val problems = problemsByFile.getOrElse(source, Vector.empty)

      val oldDocuments = bspCompileState.hasAnyProblems.getAndUpdate(_ - source).getOrElse(source, Seq.empty)
      val shouldPublish = oldDocuments.nonEmpty || problems.nonEmpty || shouldReportAllProblems

      if (shouldPublish) {
        val diagsByDocuments = problems
          .flatMap(mapProblemToDiagnostic)
          .groupBy { case (document, _) => document }
          .mapValues(_.map { case (_, diag) => diag })

        val newDocuments = diagsByDocuments.keySet

        bspCompileState.hasAnyProblems.updateAndGet(_ + (source -> newDocuments.toVector))
        
        (newDocuments ++ oldDocuments).foreach { document =>
          val diags: Vector[Diagnostic] = diagsByDocuments
            .getOrElse(document, Vector.empty)
          val params = PublishDiagnosticsParams(
            document,
            buildTarget,
            originId = None,
            diags,
            reset = true
          )
          exchange.notifyEvent("build/publishDiagnostics", params)
        }
      }
    }
  }

  protected override def publishDiagnostic(problem: Problem): Unit = {
    for {
      id <- problem.position.sourcePath.toOption
      (document, diagnostic) <- mapProblemToDiagnostic(problem)
    } {
      val fileRef = VirtualFileRef.of(id)
      problemsByFile(fileRef) = problemsByFile.getOrElse(fileRef, Vector.empty) :+ problem

      val params = PublishDiagnosticsParams(
        document,
        buildTarget,
        originId = None,
        Vector(diagnostic),
        reset = false
      )
      exchange.notifyEvent("build/publishDiagnostics", params)
    }

  }

  def mapProblemToDiagnostic(problem: Problem): Option[(TextDocumentIdentifier, Diagnostic)] = {
    val mappedPosition = sourcePositionMapper(problem.position)
    for {
      id <- mappedPosition.sourcePath.toOption
      path <- toSafePath(VirtualFileRef.of(id))
    } yield {
        (TextDocumentIdentifier(path.toUri), toDiagnostic(mappedPosition, problem))
    }
  }

  

  private def toDiagnostic(position: xsbti.Position, problem: Problem): Diagnostic = {
    val startLineOpt = position.startLine.toOption.map(_.toLong - 1)
    val startColumnOpt = position.startColumn.toOption.map(_.toLong)
    val endLineOpt = position.endLine.toOption.map(_.toLong - 1)
    val endColumnOpt = position.endColumn.toOption.map(_.toLong)
    val lineOpt = position.line.toOption.map(_.toLong - 1)
    val columnOpt = position.pointer.toOption.map(_.toLong)

    def toPosition(lineOpt: Option[Long], columnOpt: Option[Long]): Option[Position] =
      lineOpt.map(line => Position(line, columnOpt.getOrElse(0L)))

    val startPos = toPosition(startLineOpt, startColumnOpt)
      .orElse(toPosition(lineOpt, columnOpt))
      .getOrElse(Position(0L, 0L))
    val endPosOpt = toPosition(endLineOpt, endColumnOpt)
    val range = Range(startPos, endPosOpt.getOrElse(startPos))

    Diagnostic(
      range,
      Option(toDiagnosticSeverity(problem.severity)),
      problem.diagnosticCode().toOption.map(_.code),
      Option("sbt"),
      problem.message
    )
  }

  private def toDiagnosticSeverity(severity: Severity): Long = severity match {
    case Severity.Info  => DiagnosticSeverity.Information
    case Severity.Warn  => DiagnosticSeverity.Warning
    case Severity.Error => DiagnosticSeverity.Error
  }
}

final class BuildServerForwarder(
    protected override val isMetaBuild: Boolean,
    protected override val logger: ManagedLogger,
    protected override val underlying: Reporter
) extends BuildServerReporter {

  override def sendSuccessReport(
      analysis: CompileAnalysis,
  ): Unit = ()

  override def sendFailureReport(sources: Array[VirtualFile]): Unit = ()

  protected override def publishDiagnostic(problem: Problem): Unit = ()
}
