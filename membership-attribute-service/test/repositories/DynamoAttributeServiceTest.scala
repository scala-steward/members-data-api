package repositories

import java.util.UUID

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest
import com.github.dwhjames.awswrap.dynamodb.{AmazonDynamoDBScalaClient, AmazonDynamoDBScalaMapper, Schema}
import models.Attributes
import org.specs2.mutable.Specification
import repositories.MembershipAttributesSerializer.AttributeNames
import services.DynamoAttributeService

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
 * Depends upon DynamoDB Local to be running on the default port of 8000.
 *
 * Amazon's embedded version doesn't work with an async client, so using https://github.com/grahamar/sbt-dynamodb
 */
class DynamoAttributeServiceTest extends Specification {

  private val awsDynamoClient = new AmazonDynamoDBAsyncClient(new BasicAWSCredentials("foo", "bar"))
  awsDynamoClient.setEndpoint("http://localhost:8000")

  private val dynamoClient = new AmazonDynamoDBScalaClient(awsDynamoClient)
  private val dynamoMapper = AmazonDynamoDBScalaMapper(dynamoClient)
  private val testTable = "MembershipAttributes-TEST"
  implicit private val serializer = MembershipAttributesSerializer(testTable)
  private val repo = DynamoAttributeService(dynamoMapper)(serializer)

  val tableRequest =
    new CreateTableRequest()
      .withTableName(testTable)
      .withProvisionedThroughput(
        Schema.provisionedThroughput(10L, 5L))
      .withAttributeDefinitions(
        Schema.stringAttribute(AttributeNames.userId))
      .withKeySchema(
        Schema.hashKey(AttributeNames.userId))

  val createTableResult = Await.result(dynamoClient.createTable(tableRequest), 5.seconds)

  "get" should {
    "retrieve attributes for given user" in {
      val userId = UUID.randomUUID().toString
      val attributes = Attributes(userId, "patron", Some("abc"))
      val result = for {
        insertResult <- repo.set(attributes)
        retrieved <- repo.get(userId)
      } yield retrieved

      Await.result(result, 5.seconds) shouldEqual Some(attributes)
    }

    "retrieve not found api error when attributes not found for user" in {
      val result = for {
        retrieved <- repo.get(UUID.randomUUID().toString)
      } yield retrieved

      Await.result(result, 5.seconds) shouldEqual None
    }
  }

}
