/*
 * Copyright 2021 HM Revenue & Customs
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

package v1.controllers

import cats.data.EitherT
import cats.implicits._
import config.{AppConfig, FeatureSwitch}
import javax.inject.{Inject, Singleton}
import play.api.http.MimeTypes
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, AnyContentAsJson, ControllerComponents}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import utils.{IdGenerator, Logging}
import v1.controllers.requestParsers.SubmitSelfEmploymentBsasDataParser
import v1.hateoas.HateoasFactory
import v1.models.audit.{AuditEvent, AuditResponse, GenericAuditDetail}
import v1.models.errors.{FormatAdjustmentValueError, RuleAdjustmentRangeInvalid, _}
import v1.models.request.submitBsas.selfEmployment.SubmitSelfEmploymentBsasRawData
import v1.models.response.SubmitSelfEmploymentBsasHateoasData
import v1.services.{AuditService, EnrolmentsAuthService, MtdIdLookupService, SubmitSelfEmploymentBsasService}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SubmitSelfEmploymentBsasController @Inject()(val authService: EnrolmentsAuthService,
                                                   val lookupService: MtdIdLookupService,
                                                   val appConfig: AppConfig,
                                                   requestParser: SubmitSelfEmploymentBsasDataParser,
                                                   service: SubmitSelfEmploymentBsasService,
                                                   hateoasFactory: HateoasFactory,
                                                   auditService: AuditService,
                                                   cc: ControllerComponents,
                                                   val idGenerator: IdGenerator)(implicit ec: ExecutionContext)
    extends AuthorisedController(cc)
    with BaseController
    with Logging {

  implicit val endpointLogContext: EndpointLogContext =
    EndpointLogContext(
      controllerName = "SubmitSelfEmploymentBsasController",
      endpointName = "submitSelfEmploymentBsas"
    )

  //noinspection ScalaStyle
  def submitSelfEmploymentBsas(nino: String, bsasId: String): Action[JsValue] =
    authorisedAction(nino).async(parse.json) { implicit request =>

      implicit val correlationId: String = idGenerator.generateCorrelationId
      logger.info(
        s"[${endpointLogContext.controllerName}][${endpointLogContext.endpointName}] " +
          s"with CorrelationId: $correlationId")

      val featureSwitch = FeatureSwitch(appConfig.featureSwitch)

      val rawData = SubmitSelfEmploymentBsasRawData(nino, bsasId, AnyContentAsJson(request.body))
      val result =
        for {
          parsedRequest <- EitherT.fromEither[Future](requestParser.parseRequest(rawData))
          response <- {
            if (featureSwitch.isV1R5ErrorMappingEnabled) {
              EitherT(service.submitSelfEmploymentBsas(mappingDesToMtdError)(parsedRequest))
            }
            else {
              EitherT(service.submitSelfEmploymentBsas()(parsedRequest))
            }
          }
          hateoasResponse <- EitherT.fromEither[Future](
            hateoasFactory.wrap(
              response.responseData,
              SubmitSelfEmploymentBsasHateoasData(nino, response.responseData.id)
            ).asRight[ErrorWrapper])
        } yield {
          logger.info(
            s"[${endpointLogContext.controllerName}][${endpointLogContext.endpointName}] - " +
              s"Success response received with CorrelationId: ${response.correlationId}"
          )

          auditSubmission(
            GenericAuditDetail(
              userDetails = request.userDetails,
              params = Map("nino" -> nino, "bsasId" -> bsasId),
              requestBody = Some(request.body),
              `X-CorrelationId` = response.correlationId,
              auditResponse = AuditResponse(httpStatus = OK, response = Right(Some(Json.toJson(hateoasResponse))))
            )
          )

          Ok(Json.toJson(hateoasResponse))
            .withApiHeaders(response.correlationId)
            .as(MimeTypes.JSON)
        }

      result.leftMap { errorWrapper =>
        val resCorrelationId = errorWrapper.correlationId
        val result = errorResult(errorWrapper).withApiHeaders(resCorrelationId)
        logger.info(
          s"[${endpointLogContext.controllerName}][${endpointLogContext.endpointName}] - " +
            s"Error response received with CorrelationId: $resCorrelationId")

        auditSubmission(
          GenericAuditDetail(
            userDetails = request.userDetails,
            params = Map("nino" -> nino, "bsasId" -> bsasId),
            requestBody = Some(request.body),
            `X-CorrelationId` = resCorrelationId,
            auditResponse = AuditResponse(httpStatus = result.header.status, response = Left(errorWrapper.auditErrors))
          )
        )

        result
      }.merge
    }

  private def errorResult(errorWrapper: ErrorWrapper) = {
    (errorWrapper.error: @unchecked) match {
      case BadRequestError | NinoFormatError | BsasIdFormatError |
           CustomMtdError(RuleIncorrectOrEmptyBodyError.code) | RuleBothExpensesError |
           CustomMtdError(FormatAdjustmentValueError.code) |
           CustomMtdError(RuleAdjustmentRangeInvalid.code) => BadRequest(Json.toJson(errorWrapper))
      case RuleSummaryStatusInvalid | RuleSummaryStatusSuperseded |
           RuleBsasAlreadyAdjusted | RuleOverConsolidatedExpensesThreshold |
           RuleTradingIncomeAllowanceClaimed | RuleNotSelfEmployment |
           RuleErrorPropertyAdjusted | RuleResultingValueNotPermitted => Forbidden(Json.toJson(errorWrapper))
      case NotFoundError   => NotFound(Json.toJson(errorWrapper))
      case DownstreamError => InternalServerError(Json.toJson(errorWrapper))
    }
  }

  private def mappingDesToMtdError: Map[String, MtdError] = Map(
    "INVALID_TAXABLE_ENTITY_ID"     -> NinoFormatError,
    "INVALID_CALCULATION_ID"        -> BsasIdFormatError,
    "INVALID_PAYLOAD"               -> DownstreamError,
    "ASC_ID_INVALID"                -> RuleSummaryStatusInvalid,
    "ASC_ALREADY_SUPERSEDED"        -> RuleSummaryStatusSuperseded,
    "ASC_ALREADY_ADJUSTED"          -> RuleBsasAlreadyAdjusted,
    "UNALLOWABLE_VALUE"             -> RuleResultingValueNotPermitted,
    "INCOMESOURCE_TYPE_NOT_MATCHED" -> RuleNotSelfEmployment,
    "BVR_FAILURE_C55316"            -> RuleOverConsolidatedExpensesThreshold,
    "BVR_FAILURE_C15320"            -> RuleTradingIncomeAllowanceClaimed,
    "BVR_FAILURE_C55503"            -> RuleNotSelfEmployment,
    "BVR_FAILURE_C55508"            -> RuleNotSelfEmployment,
    "BVR_FAILURE_C55509"            -> RuleNotSelfEmployment,
    "NO_DATA_FOUND"                 -> NotFoundError,
    "SERVER_ERROR"                  -> DownstreamError,
    "SERVICE_UNAVAILABLE"           -> DownstreamError
  )

  private def auditSubmission(details: GenericAuditDetail)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[AuditResult] = {

    val event = AuditEvent(
      auditType = "submitBusinessSourceAccountingAdjustments",
      transactionName = "submit-self-employment-accounting-adjustments",
      detail = details
    )

    auditService.auditEvent(event)
  }
}
