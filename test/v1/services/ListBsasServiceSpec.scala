/*
 * Copyright 2020 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package v1.services

import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.http.HeaderCarrier
import utils.DesTaxYear
import v1.controllers.EndpointLogContext
import v1.fixtures.ListBsasFixtures._
import v1.mocks.connectors.MockListBsasConnector
import v1.models.errors._
import v1.models.outcomes.ResponseWrapper
import v1.models.request.ListBsasRequest
import v1.models.response.listBsas.{BsasEntries, ListBsasResponse}

import scala.concurrent.Future

class ListBsasServiceSpec extends ServiceSpec {

  private val nino = Nino("AA123456A")
  private val taxYear = DesTaxYear("2019-20")
  private val incomeSourceIdentifier = Some("IncomeSourceType")
  private val identifierValue = Some("01")

  val request: ListBsasRequest = ListBsasRequest(nino, taxYear, incomeSourceIdentifier, identifierValue)
  val response: ListBsasResponse[BsasEntries] = summaryModel

  trait Test extends MockListBsasConnector {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    implicit val logContext: EndpointLogContext = EndpointLogContext("controller", "listBsas")

    val service = new ListBsasService(mockConnector)
  }

  "ListBsas" should {
    "return a valid response" when {
      "a valid request is supplied" in new Test {
        MockListBsasConnector.listBsas(request)
          .returns(Future.successful(Right(ResponseWrapper(correlationId, response))))

        await(service.listBsas(request)) shouldBe Right(ResponseWrapper(correlationId, response))
      }
    }

    "return error response" when {

      def serviceError(desErrorCode: String, error: MtdError): Unit =
        s"a $desErrorCode error is returned from the service" in new Test {

          MockListBsasConnector.listBsas(request)
            .returns(Future.successful(Left(ResponseWrapper(correlationId, DesErrors.single(DesErrorCode(desErrorCode))))))

          await(service.listBsas(request)) shouldBe Left(ErrorWrapper(correlationId, error))
        }

      val input = Seq(
        ("INVALID_TAXABLE_ENTITY_ID" , NinoFormatError),
        ("NOT_FOUND" , NotFoundError),
        ("INVALID_TAXYEAR" , DownstreamError),
        ("INVALID_INCOMESOURCE_IDENTIFIER" , DownstreamError),
        ("INVALID_IDENTIFIER_VALUE" , DownstreamError),
        ("SERVER_ERROR" , DownstreamError),
        ("SERVICE_UNAVAILABLE" , DownstreamError)
      )

      input.foreach(args => (serviceError _).tupled(args))
    }
  }
}
