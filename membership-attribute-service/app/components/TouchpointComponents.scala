package components

import akka.actor.ActorSystem
import com.gu.aws.ProfileName
import com.gu.config
import com.gu.i18n.Country
import com.gu.identity.IdapiService
import com.gu.memsub.services.PaymentService
import com.gu.memsub.subsv2.services.SubscriptionService.CatalogMap
import com.gu.memsub.subsv2.services._
import com.gu.monitoring.SafeLogger._
import com.gu.monitoring.{SafeLogger, ZuoraMetrics}
import com.gu.okhttp.RequestRunners
import com.gu.salesforce.SimpleContactRepository
import com.gu.stripe.{BasicStripeService, StripeService, StripeServiceConfig}
import com.gu.touchpoint.TouchpointBackendConfig
import com.gu.zuora.ZuoraSoapService
import com.gu.zuora.api.{InvoiceTemplate, InvoiceTemplates, PaymentGateway}
import com.gu.zuora.rest.{SimpleClient, ZuoraRestService}
import com.gu.zuora.soap.ClientWithFeatureSupplier
import configuration.Config
import scalaz.std.scalaFuture._
import services._
import software.amazon.awssdk.auth.credentials.{
  AwsCredentialsProviderChain,
  EnvironmentVariableCredentialsProvider,
  InstanceProfileCredentialsProvider,
  ProfileCredentialsProvider,
}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.{DynamoDbAsyncClient, DynamoDbAsyncClientBuilder}

import java.util.concurrent.TimeUnit.SECONDS
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}

class TouchpointComponents(stage: String)(implicit system: ActorSystem, executionContext: ExecutionContext) {
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  lazy val conf = Config.config.getConfig("touchpoint.backend")
  lazy val environmentConf = conf.getConfig(s"environments.$stage")

  lazy val digitalPackConf = environmentConf.getConfig(s"zuora.ratePlanIds.digitalpack")
  lazy val paperCatalogConf = environmentConf.getConfig(s"zuora.productIds.subscriptions")
  lazy val membershipConf = environmentConf.getConfig(s"zuora.ratePlanIds.membership")
  lazy val supporterProductDataTable = environmentConf.getString("supporter-product-data.table")
  lazy val invoiceTemplatesConf = environmentConf.getConfig(s"zuora.invoiceTemplateIds")

  lazy val digitalPackPlans = config.DigitalPackRatePlanIds.fromConfig(digitalPackConf)
  lazy val productIds = config.SubsV2ProductIds(environmentConf.getConfig("zuora.productIds"))
  lazy val membershipPlans = config.MembershipRatePlanIds.fromConfig(membershipConf)
  lazy val subsProducts = config.SubscriptionsProductIds(paperCatalogConf)

  lazy val tpConfig = TouchpointBackendConfig.byEnv(stage, conf)
  implicit lazy val _bt: TouchpointBackendConfig = tpConfig

  lazy val patronsStripeService = new BasicStripeService(tpConfig.stripePatrons, RequestRunners.futureRunner)
  lazy val ukStripeService = new StripeService(tpConfig.stripeUKMembership, RequestRunners.futureRunner)
  lazy val auStripeService = new StripeService(tpConfig.stripeAUMembership, RequestRunners.futureRunner)
  lazy val allStripeServices = Seq(ukStripeService, auStripeService)
  lazy val stripeServicesByPaymentGateway: Map[PaymentGateway, StripeService] = allStripeServices.map(s => s.paymentGateway -> s).toMap
  lazy val stripeServicesByPublicKey: Map[String, StripeService] = allStripeServices.map(s => s.publicKey -> s).toMap
  lazy val invoiceTemplateIdsByCountry: Map[Country, InvoiceTemplate] =
    InvoiceTemplates.fromConfig(invoiceTemplatesConf).map(it => (it.country, it)).toMap

  lazy val contactRepo: SimpleContactRepository = new SimpleContactRepository(tpConfig.salesforce, system.scheduler, Config.applicationName)
  lazy val salesforceService: SalesforceService = new SalesforceService(contactRepo)

  lazy val CredentialsProvider = AwsCredentialsProviderChain.builder
    .credentialsProviders(
      ProfileCredentialsProvider.builder.profileName(ProfileName).build,
      InstanceProfileCredentialsProvider.builder.asyncCredentialUpdateEnabled(false).build,
      EnvironmentVariableCredentialsProvider.create(),
    )
    .build

  lazy val dynamoClientBuilder: DynamoDbAsyncClientBuilder = DynamoDbAsyncClient.builder
    .credentialsProvider(CredentialsProvider)
    .region(Region.EU_WEST_1)

  lazy val mapper = new SupporterRatePlanToAttributesMapper(stage)
  lazy val supporterProductDataService = new SupporterProductDataService(dynamoClientBuilder.build(), supporterProductDataTable, mapper)

  private val zuoraMetrics = new ZuoraMetrics(stage, Config.applicationName)
  private lazy val zuoraSoapClient =
    new ClientWithFeatureSupplier(
      featureCodes = Set.empty,
      apiConfig = tpConfig.zuoraSoap,
      httpClient = RequestRunners.configurableFutureRunner(timeout = Duration(30, SECONDS)),
      extendedHttpClient = RequestRunners.futureRunner,
      metrics = zuoraMetrics,
    )
  lazy val zuoraService = new ZuoraSoapService(zuoraSoapClient) with HealthCheckableService {
    override def checkHealth: Boolean = zuoraSoapClient.isReady
  }

  private lazy val zuoraRestClient = new SimpleClient[Future](tpConfig.zuoraRest, RequestRunners.configurableFutureRunner(30.seconds))
  lazy val zuoraRestService = new ZuoraRestService[Future]()(futureInstance(ec), zuoraRestClient)

  lazy val catalogRestClient = new SimpleClient[Future](tpConfig.zuoraRest, RequestRunners.configurableFutureRunner(60.seconds))
  lazy val catalogService = new CatalogService[Future](productIds, FetchCatalog.fromZuoraApi(catalogRestClient), Await.result(_, 60.seconds), stage)

  lazy val futureCatalog: Future[CatalogMap] = catalogService.catalog
    .map(_.fold[CatalogMap](error => { println(s"error: ${error.list.toList.mkString}"); Map() }, _.map))
    .recover { case error =>
      SafeLogger.error(scrub"Failed to load the product catalog from Zuora due to: $error")
      throw error
    }

  lazy val subService = new SubscriptionService[Future](productIds, futureCatalog, zuoraRestClient, zuoraService.getAccountIds)
  lazy val paymentService = new PaymentService(zuoraService, catalogService.unsafeCatalog.productMap)

  lazy val idapiService = new IdapiService(tpConfig.idapi, RequestRunners.futureRunner)
  lazy val identityAuthService = new IdentityAuthService(tpConfig.idapi)

}
