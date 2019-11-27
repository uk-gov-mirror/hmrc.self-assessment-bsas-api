/*
 * Copyright 2019 HM Revenue & Customs
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

package v1.controllers.requestParsers

import mocks.validators.MockTriggerBSASValidator
import play.api.libs.json.Json
import play.api.mvc.AnyContentAsJson
import support.UnitSpec
import uk.gov.hmrc.domain.Nino
import v1.models.domain.TypeOfBusiness
import v1.models.errors._
import v1.models.request.AccountingPeriod
import v1.models.request.triggerBsas.{TriggerBsasRawData, TriggerBsasRequest, TriggerBsasRequestBody}

class TriggerBSASRequestParserSpec extends UnitSpec {


  val nino = "AA123456A"

  def triggerBsasRawDataBody(accountingPeriod: String = "2019-20",
                             startDate: String = "2019-05-05",
                             endDate: String = "2020-05-06",
                             typeOfBusiness: String = TypeOfBusiness.`self-employment`.toString,
                             selfEmploymentId: Option[String] = Some("XAIS12345678901")): AnyContentAsJson = {


    AnyContentAsJson(Json.obj(
      "accountingPeriod" -> accountingPeriod,
      "startDate" -> startDate,
      "endDate" -> endDate,
      "typeOfBusiness" -> typeOfBusiness
    ) ++ selfEmploymentId.fold(Json.obj())(selfEmploymentId => Json.obj("selfEmploymentId" -> selfEmploymentId)))
  }

  def triggerBsasRequestDataBody(accountingPeriod: String = "2019-20",
                                 startDate: String = "2019-05-05",
                                 endDate: String = "2020-05-06",
                                 typeOfBusiness: TypeOfBusiness = TypeOfBusiness.`self-employment`,
                                 selfEmploymentId: Option[String] = Some("XAIS12345678901")): TriggerBsasRequestBody = {
    TriggerBsasRequestBody(AccountingPeriod(startDate, endDate), typeOfBusiness = typeOfBusiness, selfEmploymentId = selfEmploymentId)

  }


  trait Test extends MockTriggerBSASValidator {
    lazy val parser = new TriggerBSASRequestParser(mockValidator)
  }

  "parser" should {
    "accept a valid input" when {
      s"convert ${TypeOfBusiness.`uk-property-non-fhl`.toString} to incomeSourceType 04" in new Test {
        val inputData = TriggerBsasRawData(nino,
          triggerBsasRawDataBody(typeOfBusiness = TypeOfBusiness.`uk-property-non-fhl`.toString,
            selfEmploymentId = None)
        )


        MockValidator
          .validate(inputData)
          .returns(Nil)

        val result = parser.parseRequest(inputData)
        result shouldBe Right(TriggerBsasRequest(Nino(nino), triggerBsasRequestDataBody(typeOfBusiness = TypeOfBusiness.`uk-property-non-fhl`,
          selfEmploymentId = None)))
      }

      s"convert ${TypeOfBusiness.`uk-property-fhl`.toString} to incomeSourceType 02" in new Test {
        val inputData = TriggerBsasRawData(nino,
          triggerBsasRawDataBody(typeOfBusiness = TypeOfBusiness.`uk-property-fhl`.toString,
            selfEmploymentId = None))


        MockValidator
          .validate(inputData)
          .returns(Nil)

        val result = parser.parseRequest(inputData)
        result shouldBe Right(TriggerBsasRequest(Nino(nino), triggerBsasRequestDataBody(typeOfBusiness = TypeOfBusiness.`uk-property-fhl`,
          selfEmploymentId = None)))

      }

      s"convert ${TypeOfBusiness.`self-employment`.toString} to self employment Id" in new Test {
        val inputData = TriggerBsasRawData(nino, triggerBsasRawDataBody())


        MockValidator
          .validate(inputData)
          .returns(Nil)

        val result = parser.parseRequest(inputData)
        result shouldBe Right(TriggerBsasRequest(Nino(nino), triggerBsasRequestDataBody()))
      }
    }

    "reject invalid input" when {
      "a single error is present" in new Test {
        val inputData = TriggerBsasRawData(nino, AnyContentAsJson(Json.obj()))


        MockValidator
          .validate(inputData)
          .returns(List(RuleIncorrectOrEmptyBodyError))

        val result = parser.parseRequest(inputData)
        result shouldBe Left(ErrorWrapper(None, RuleIncorrectOrEmptyBodyError, None))
      }

      "multiple errors are present" in new Test {
        val inputData = TriggerBsasRawData(nino, triggerBsasRawDataBody(selfEmploymentId = None, startDate = "2020-05-07"))


        MockValidator
          .validate(inputData)
          .returns(List(SelfEmploymentIdFormatError, EndBeforeStartDateError))

        val result = parser.parseRequest(inputData)
        result shouldBe Left(ErrorWrapper(None, BadRequestError, Some(Seq(SelfEmploymentIdFormatError, EndBeforeStartDateError))))
      }
    }
  }
}



