package actions

import configuration.Config.BackendConfig
import models.ApiError._
import models.ApiErrors.unauthorized
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.{ActionRefiner, Request, Result}
import repositories.MembershipAttributesSerializer
import services.DynamoAttributeService

import scala.concurrent.Future
import scalaz.syntax.std.option._

object WithBackendFromSalesforceAction extends ActionRefiner[Request, BackendRequest] {
  private val salesforceSecretParam = "secret"

  override protected def refine[A](request: Request[A]): Future[Either[Result, BackendRequest[A]]] = {
    import BackendConfig._
    Future {
      val backendConf = Seq(test, default).find(_.salesforceConfig.secret.some == request.getQueryString("secret"))

      backendConf.map { conf =>
        val attrService = DynamoAttributeService(MembershipAttributesSerializer(conf.dynamoTable))

        Right(new BackendRequest[A](conf, attrService, request))
      }.getOrElse {
        Logger.error("Unauthorized call from salesforce: the secret didn't match that of any backend")
        Left(unauthorized)
      }
    }
  }
}