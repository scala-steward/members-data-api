package services
import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.Subscription
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.memsub.subsv2.reads.SubPlanReads.anyPlanReads
import com.gu.monitoring.SafeLogger
import com.gu.monitoring.SafeLogger._
import com.gu.scanamo.error.DynamoReadError
import com.gu.zuora.rest.ZuoraRestService.{AccountObject, GetAccountsQueryResponse, PaymentMethodId, PaymentMethodResponse}
import loghandling.LoggingWithLogstashFields
import models._
import org.joda.time.{DateTime, LocalDate}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scalaz.std.list._
import scalaz.std.scalaFuture._
import scalaz.syntax.traverse._
import scalaz.{Disjunction, EitherT, \/}

class AttributesFromZuora(implicit val executionContext: ExecutionContext) extends LoggingWithLogstashFields {
  def getAttributesFromZuoraWithCacheFallback(
     identityId: String,
     identityIdToAccounts: String => Future[String \/ GetAccountsQueryResponse],
     subscriptionsForAccountId: AccountId => SubPlanReads[AnyPlan] => Future[Disjunction[String, List[Subscription[AnyPlan]]]],
     paymentMethodForPaymentMethodId: PaymentMethodId => Future[\/[String, PaymentMethodResponse]],
     dynamoAttributeService: AttributeService,
     forDate: LocalDate = LocalDate.now()
  ): Future[(String, Option[Attributes])] = {

    def maybeUpdateCache(zuoraAttributes: Option[ZuoraAttributes], dynamoAttributes: Option[DynamoAttributes], attributes: Option[Attributes]): Unit = {
      def twoWeekExpiry = forDate.toDateTimeAtStartOfDay.plusDays(14)
      if(dynamoUpdateRequired(dynamoAttributes, zuoraAttributes, identityId, twoWeekExpiry)) {
        log.info(s"Attempting to update cache for $identityId ...")
        updateCache(identityId, attributes, dynamoAttributeService, twoWeekExpiry) onComplete {
          case Success(_) =>
            log.info(s"updated cache for $identityId")
          case Failure(e) =>
            SafeLogger.error(scrub"Future failed when attempting to update cache.", e)
            log.warn(s"Future failed when attempting to update cache. Attributes from Zuora: $attributes")
        }
      }
    }

    val attributesFromDynamo = // eagerly prepare cache
      dynamoAttributeService
        .get(identityId)
        .andThen {
          case Success(attrib) => dynamoAttributeService.ttlAgeCheck(attrib, identityId, forDate)
          case Failure(e) => SafeLogger.error(scrub"Failed to retrieve attributes from cache for $identityId because $e")
        }

    (for { // try zuora first and fallback to cache
      accounts         <- EitherT(identityIdToAccounts(identityId))
      subscriptions    <- EitherT(if (accounts.size > 0) getSubscriptions(accounts, subscriptionsForAccountId) else Future.successful(\/.right[String, List[AccountWithSubscriptions]](Nil)))
      maybeZAttributes <- EitherT(AttributesMaker.zuoraAttributes(identityId, subscriptions, paymentMethodForPaymentMethodId, forDate).map(\/.right[String, Option[ZuoraAttributes]]))
    } yield { // Zuora takes precedence
      val maybeAttributes = maybeZAttributes.map(ZuoraAttributes.asAttributes(_))
      attributesFromDynamo.foreach(maybeUpdateCache(maybeZAttributes, _, maybeAttributes))
      Future.successful("Zuora", maybeAttributes)
    }).leftMap { zuoraError => // fallback to Dynamo if Zuora fails
      SafeLogger.error(scrub"Failed to retrieve attributes from Zuora for $identityId because $zuoraError so falling back on cache.")
      attributesFromDynamo.map(maybeDynamoAttributes => ("Dynamo", maybeDynamoAttributes.map(DynamoAttributes.asAttributes(_))))
    }.merge.flatten // both branches return same type so can be merged
  }

  def dynamoUpdateRequired(dynamoAttributes: Option[DynamoAttributes], zuoraAttributes: Option[ZuoraAttributes], identityId: String, twoWeekExpiry: => DateTime): Boolean = {

    def ttlUpdateRequired(currentExpiry: DateTime) = twoWeekExpiry.isAfter(currentExpiry.plusDays(1))
    def calculateExpiry(currentExpiry: Option[DateTime]): DateTime = currentExpiry match {
      case Some(expiry) if ttlUpdateRequired(expiry) =>
        log.info (s"TTL update required for user $identityId with current expiry $expiry. New expiry is $twoWeekExpiry.")
        twoWeekExpiry
      case Some(expiry) =>
        log.info(s"No TTL update required for user $identityId with current expiry $expiry.")
        expiry
      case None =>
        log.info(s"Record for user $identityId has no TTL so setting TTL to $twoWeekExpiry.")
        twoWeekExpiry
    }

    val currentExpiry: Option[DateTime] = dynamoAttributes.map { attributes => TtlConversions.secondsToDateTime(attributes.TTLTimestamp) }
    val newExpiry: DateTime = calculateExpiry(currentExpiry)

    def expiryShouldChange(dynamoAttributes: Option[DynamoAttributes], currentExpiry: Option[DateTime], newExpiry: DateTime) =
      dynamoAttributes.isDefined && !currentExpiry.contains(newExpiry)

    expiryShouldChange(dynamoAttributes, currentExpiry, newExpiry) || !dynamoAndZuoraAgree(dynamoAttributes, zuoraAttributes, identityId)
  }

  private def updateCache(identityId: String, maybeAttributes: Option[Attributes], dynamoAttributeService: AttributeService, twoWeekExpiry: => DateTime): Future[Unit] = {
    val attributesWithTTL: Option[DynamoAttributes] = maybeAttributes map { attributes =>
      DynamoAttributes(
        UserId = attributes.UserId,
        Tier = attributes.Tier,
        RecurringContributionPaymentPlan = attributes.RecurringContributionPaymentPlan,
        MembershipJoinDate = attributes.MembershipJoinDate,
        DigitalSubscriptionExpiryDate = attributes.DigitalSubscriptionExpiryDate,
        PaperSubscriptionExpiryDate = attributes.PaperSubscriptionExpiryDate,
        GuardianWeeklySubscriptionExpiryDate = attributes.GuardianWeeklySubscriptionExpiryDate,
        TTLTimestamp = TtlConversions.toDynamoTtlInSeconds(twoWeekExpiry)
      )
    }

    attributesWithTTL match {
      case Some(attributes) =>
        dynamoAttributeService.update(attributes).map { result =>
          result.left.map { error: DynamoReadError =>
            log.warn(s"Tried updating attributes for $identityId but then ${DynamoReadError.describe(error)}")
            SafeLogger.error(scrub"Tried updating attributes with updated values from Zuora but there was a dynamo error.")
          }
        }
      case None =>
        dynamoAttributeService.delete(identityId) map { result =>
          log.info(s"Deleting the dynamo entry for $identityId. $result")
        }
    }
  }

  def getSubscriptions(
    getAccountsResponse: GetAccountsQueryResponse,
    subscriptionsForAccountId: AccountId => SubPlanReads[AnyPlan] => Future[Disjunction[String, List[Subscription[AnyPlan]]]]
  ): Future[Disjunction[String, List[AccountWithSubscriptions]]] = {

    def accountWithSubscriptions(account: AccountObject)(implicit reads: SubPlanReads[AnyPlan]): Future[Disjunction[String, AccountWithSubscriptions]] =
      EitherT(subscriptionsForAccountId(account.Id)(anyPlanReads))
        .map(AccountWithSubscriptions(account, _))
        .run

    getAccountsResponse
      .records
      .traverse(accountWithSubscriptions(_)(anyPlanReads))
      .map(_.sequenceU)
  }

  //if Zuora attributes have changed at all, we should update the cache even if the ttl is not stale
  def dynamoAndZuoraAgree(maybeDynamoAttributes: Option[DynamoAttributes], maybeZuoraAttributes: Option[ZuoraAttributes], identityId: String): Boolean = {

    val zuoraAttributesAsAttributes = maybeZuoraAttributes map (ZuoraAttributes.asAttributes(_))
    val dynamoAttributesAsAttributes = maybeDynamoAttributes map (DynamoAttributes.asAttributes(_))

    val dynamoAndZuoraAgree = dynamoAttributesAsAttributes == zuoraAttributesAsAttributes

    if (!dynamoAndZuoraAgree)
      log.info(s"We looked up attributes via Zuora for $identityId and Zuora and Dynamo disagreed." +
        s" Zuora attributes as attributes: $zuoraAttributesAsAttributes. Dynamo attributes as attributes: $dynamoAttributesAsAttributes.")
    else if (zuoraAttributesAsAttributes.isEmpty) {
      log.info(s"There are no Zuora attributes for $identityId")
    }

    dynamoAndZuoraAgree
  }

  def queryToAccountIds(response: GetAccountsQueryResponse): List[AccountId] =  response.records.map(_.Id)
}

