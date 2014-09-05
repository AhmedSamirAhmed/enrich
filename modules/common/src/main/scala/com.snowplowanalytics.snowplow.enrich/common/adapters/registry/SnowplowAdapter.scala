/*
 * Copyright (c) 2012-2014 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics
package snowplow
package enrich
package common
package adapters
package registry

// Jackson
import com.fasterxml.jackson.databind.JsonNode

// Scala
import scala.collection.JavaConversions._

// Iglu
import iglu.client.{
  SchemaKey,
  Resolver
}
import iglu.client.validation.ValidatableJsonMethods._

// Scalaz
import scalaz._
import Scalaz._

// This project
import loaders.CollectorPayload
import utils.JsonUtils

/**
 * Transforms a collector payload which conforms to
 * a known version of the Snowplow Tracker Protocol
 * into raw events.
 */
object SnowplowAdapter {

  /**
   * Version 1 of the Tracker Protocol is GET only.
   * All data comes in on the querystring.
   */
  object Tp1 extends Adapter {

    /**
     * Converts a CollectorPayload instance into raw events.
     * Tracker Protocol 1 only supports a single event in a
     * payload.
     *
     * @param payload The CollectorPaylod containing one or more
     *        raw events as collected by a Snowplow collector
     * @param resolver (implicit) The Iglu resolver used for
     *        schema lookup and validation. Not used
     * @return a Validation boxing either a NEL of RawEvents on
     *         Success, or a NEL of Failure Strings
     */
    def toRawEvents(payload: CollectorPayload)(implicit resolver: Resolver): ValidatedRawEvents = {

      val params = toMap(payload.querystring)
      if (params.isEmpty) {
        "Querystring is empty: no raw event to process".failNel
      } else {
        NonEmptyList(RawEvent(
          vendor       = payload.vendor,
          version      = payload.version,
          parameters   = params,
          contentType  = payload.contentType,
          source       = payload.source,
          context      = payload.context
          )).success
      }
    }
  }

  /**
   * Version 2 of the Tracker Protocol supports GET and POST. Note that
   * with POST, data can still be passed on the querystring.
   */
  object Tp2 extends Adapter {

    // Expected content type for a request body
    private val ContentType = "Content-type: application/json; charset=utf-8"

    // Request body expected to validate against this JSON Schema
    private val PayloadDataSchema = SchemaKey("com.snowplowanalytics.snowplow", "payload_data", "jsonschema", "1-0-0")

    /**
     * Converts a CollectorPayload instance into N raw events.
     *
     * @param payload The CollectorPaylod containing one or more
     *        raw events as collected by a Snowplow collector
     * @param resolver (implicit) The Iglu resolver used for
     *        schema lookup and validation
     * @return a Validation boxing either a NEL of RawEvents on
     *         Success, or a NEL of Failure Strings
     */
    def toRawEvents(payload: CollectorPayload)(implicit resolver: Resolver): ValidatedRawEvents = {
      
      val qsParams = toMap(payload.querystring)

      // Verify: body + content type set; content type matches expected; body contains expected JSON Schema; body passes schema validation
      val validatedParamsNel: Validated[NonEmptyList[RawEventParameters]] =
        (payload.body, payload.contentType) match {
          case (_,         Some(ct)) if ct != ContentType => s"Content type of ${ct} provided, expected ${ContentType}".failNel
          case (None,      None)     if qsParams.isEmpty => s"Request body and querystring parameters empty, expected at least one populated".failNel
          case (Some(_),   None)     => s"Request body provided but content type empty, expected ${ContentType}".failNel
          case (None,      Some(ct)) => s"Content type of ${ct} provided but request body empty".failNel
          case (None,      None)     => NonEmptyList(qsParams).success
          case (Some(bdy), Some(_))  => // Build our NEL of parameters
            for {
              json <- extractAndValidateJson("Body", PayloadDataSchema, bdy)
              nel  <- toParametersNel(json, qsParams)
            } yield nel
        }

      // Validated NEL of parameters -> Validated NEL of raw events
      for {
        paramsNel <- validatedParamsNel
      } yield for {
        params    <- paramsNel
        p         =  payload // Alias to save typing
      } yield RawEvent(p.vendor, p.version, params, p.contentType, p.source, p.context)
    }

    // Parameters in the querystring take priority, i.e.
    // override the same parameter in the POST body
    // TODO: implement this
    private def toParametersNel(instance: JsonNode, mergeWith: RawEventParameters): Validated[NonEmptyList[RawEventParameters]] = {
      NonEmptyList(Map("a" -> "a")).success
    }

    /**
     * Extract the JSON from a String, and
     * validate it against the supplied
     * JSON Schema.
     *
     * @param field The name of the field
     *        containing the JSON instance
     * @param schemaKey The schema that we
     *        expected this self-describing
     *        JSON to conform to
     * @param instance A JSON instance as String
     * @param resolver Our implicit Iglu
     *        Resolver, for schema lookups
     * @return an Option-boxed Validation
     *         containing either a Nel of
     *         JsonNodes error message on
     *         Failure, or a singular
     *         JsonNode on success
     */
    private def extractAndValidateJson(field: String, schemaKey: SchemaKey, instance: String)(implicit resolver: Resolver): Validated[JsonNode] =
      for {
        j <- (JsonUtils.extractJson(field, instance).toValidationNel: Validated[JsonNode])
        v <- j.verifySchemaAndValidate(schemaKey, true).leftMap(_.map(_.toString))
      } yield v

  }
}
