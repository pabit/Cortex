package org.thp.cortex.controllers

import javax.inject.{ Inject, Named, Singleton }

import akka.actor.{ ActorRef, ActorSystem }
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import org.elastic4play.controllers.{ Authenticated, Fields, FieldsBodyParser, Renderer }
import org.elastic4play.models.JsonFormat.baseModelEntityWrites
import org.elastic4play.services.JsonFormat.queryReads
import org.elastic4play.services.{ QueryDSL, QueryDef }
import org.elastic4play.utils.RichFuture
import org.thp.cortex.models.{ Job, JobStatus, Roles }
import org.thp.cortex.services.AuditActor.{ JobEnded, Register }
import org.thp.cortex.services.{ JobSrv, UserSrv }
import play.api.http.Status
import play.api.libs.json.{ JsObject, JsString, JsValue, Json }
import play.api.mvc.{ AbstractController, Action, AnyContent, ControllerComponents }

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class JobCtrl @Inject() (
    jobSrv: JobSrv,
    userSrv: UserSrv,
    @Named("audit") auditActor: ActorRef,
    fieldsBodyParser: FieldsBodyParser,
    authenticated: Authenticated,
    renderer: Renderer,
    components: ControllerComponents,
    implicit val ec: ExecutionContext,
    implicit val mat: Materializer,
    implicit val actorSystem: ActorSystem) extends AbstractController(components) with Status {

  def list(dataTypeFilter: Option[String], dataFilter: Option[String], analyzerFilter: Option[String], range: Option[String]): Action[AnyContent] = authenticated(Roles.read).async { implicit request ⇒
    val (jobs, jobTotal) = jobSrv.listForUser(request.userId, dataTypeFilter, dataFilter, analyzerFilter, range)
    renderer.toOutput(OK, jobs, jobTotal)
  }

  def find: Action[Fields] = authenticated(Roles.read).async(fieldsBodyParser) { implicit request ⇒
    import QueryDSL._
    val deleteFilter = "status" ~!= "Deleted"
    val query = request.body.getValue("query").fold[QueryDef](deleteFilter)(q ⇒ and(q.as[QueryDef], deleteFilter))
    val range = request.body.getString("range")
    val sort = request.body.getStrings("sort").getOrElse(Nil)
    val (users, total) = jobSrv.findForUser(request.userId, query, range, sort)
    renderer.toOutput(OK, users, total)

  }

  def get(jobId: String): Action[AnyContent] = authenticated(Roles.read).async { implicit request ⇒
    jobSrv.getForUser(request.userId, jobId).map { job ⇒
      renderer.toOutput(OK, job)
    }
  }

  def delete(jobId: String): Action[AnyContent] = authenticated(Roles.orgAdmin).async { implicit request ⇒
    jobSrv.getForUser(request.userId, jobId)
      .flatMap(job ⇒ jobSrv.delete(job))
      .map(_ ⇒ NoContent)
  }

  def create(analyzerId: String): Action[Fields] = authenticated(Roles.analyze).async(fieldsBodyParser) { implicit request ⇒
    jobSrv.create(analyzerId, request.body)
      .map { job ⇒
        renderer.toOutput(OK, job)
      }
  }

  private def getJobWithReport(userId: String, jobId: String): Future[JsValue] = {
    jobSrv.getForUser(userId, jobId).flatMap(getJobWithReport(userId, _))
  }

  private def getJobWithReport(userId: String, job: Job): Future[JsValue] = {
    (job.status() match {
      case JobStatus.Success ⇒
        for {
          report ← jobSrv.getReport(job)
          (artifactSource, _) = jobSrv.findArtifacts(userId, job.id, QueryDSL.any, Some("all"), Nil)
          artifacts ← artifactSource
            .collect {
              case artifact if artifact.data().isDefined ⇒
                Json.obj(
                  "data" -> artifact.data(),
                  "dataType" -> artifact.dataType(),
                  "message" -> artifact.message(),
                  "tags" -> artifact.tags(),
                  "tlp" -> artifact.tlp())
              case artifact if artifact.attachment().isDefined ⇒
                artifact.attachment().fold(JsObject.empty) { a ⇒
                  Json.obj(
                    "attachment" ->
                      Json.obj(
                        "contentType" -> a.contentType,
                        "id" -> a.id,
                        "name" -> a.name,
                        "size" -> a.size),
                    "message" -> artifact.message(),
                    "tags" -> artifact.tags(),
                    "tlp" -> artifact.tlp())
                }
            }
            .runWith(Sink.seq)
        } yield Json.obj(
          "summary" -> Json.parse(report.summary()),
          "full" -> Json.parse(report.full()),
          "success" -> true,
          "artifacts" -> artifacts)
      case JobStatus.InProgress ⇒ Future.successful(JsString("Running"))
      case JobStatus.Failure ⇒
        val errorMessage = job.errorMessage().getOrElse("")
        Future.successful(Json.obj(
          "errorMessage" → errorMessage,
          "input" → job.input(),
          "success" → false))
      case JobStatus.Waiting ⇒ Future.successful(JsString("Waiting"))
    })
      .map { report ⇒
        Json.toJson(job).as[JsObject] + ("report" -> report)
      }
  }

  def report(jobId: String): Action[AnyContent] = authenticated(Roles.read).async { implicit request ⇒
    getJobWithReport(request.userId, jobId).map(Ok(_))
  }

  def waitReport(jobId: String, atMost: String): Action[AnyContent] = authenticated(Roles.read).async { implicit request ⇒
    jobSrv.getForUser(request.userId, jobId)
      .flatMap {
        case job if job.status() == JobStatus.InProgress || job.status() == JobStatus.Waiting ⇒
          println(s"job status is ${job.status()} => wait")
          val duration = Duration(atMost).asInstanceOf[FiniteDuration]
          implicit val timeout: Timeout = Timeout(duration)
          (auditActor ? Register(jobId, duration))
            .mapTo[JobEnded]
            .map(_ ⇒ ())
            .withTimeout(duration, ())
            .flatMap(_ ⇒ getJobWithReport(request.userId, jobId))
        case job ⇒
          println(s"job status is ${job.status()} => send it directly")
          getJobWithReport(request.userId, job)
      }
      .map(Ok(_))
  }

  def listArtifacts(jobId: String): Action[Fields] = authenticated(Roles.read).async(fieldsBodyParser) { implicit request ⇒
    val query = request.body.getValue("query").fold[QueryDef](QueryDSL.any)(_.as[QueryDef])
    val range = request.body.getString("range")
    val sort = request.body.getStrings("sort").getOrElse(Nil)
    val (artifacts, total) = jobSrv.findArtifacts(request.userId, jobId, query, range, sort)
    renderer.toOutput(OK, artifacts, total)
  }
}