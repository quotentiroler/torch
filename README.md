# TORCH - Transfer Of Resources in Clinical Healthcare

**T**ransfer **O**f **R**esources in **C**linical **H**ealthcare

## Goal

The goal of this project is to provide a service that allows the execution of data extraction queries on a FHIR-server.

The tool will take an DEQ, Whitelists and CDS Profiles to extract defined Patient ressources from a FHIR Server
and then apply the data extraction with the Filters defined in the **DEQ**. Additionally Whitelists can be applied.

The tool internally uses the [HAPI](https://hapifhir.io/) implementation for handling FHIR Resources.

## CRTDL

The **C**linical **R**esource **T**ransfer **D**efinition **L**anguage or **CRTDL** is a JSON format that describes
attributes to be extracted with attributes filter.

## Prerequisites

Local FHIR Server with [test data][4] and a [Flare server with a cohort Endpoint][3] Torch interacts with these
components for the data extraction.

## Build

```sh
mvn clean install
```

## Run

```sh
java -jar target/torch-0.0.1-SNAPSHOT.jar 
```

```sh
mvn spring-boot:run
```

## Environment Variables

| Name                                                       | Default                                 | Description                                           |
|:-----------------------------------------------------------|:----------------------------------------|:------------------------------------------------------|
| SERVER_PORT                                                | 8080                                    | The Port of the server to use                         |
| TORCH_FHIR_URL                                             | http://localhost:8082/fhir              | The base URL of the FHIR server to use.               |
| TORCH_FLARE_URL                                            | http://localhost:8084                   | The base URL of the FLARE server to use.              |
| TORCH_PROFILE_DIR                                          | src/test/resources/StructureDefinitions | The directory for profile definitions.                |
| TORCH_RESULTS_DIR                                          | output/                                 | The directory for storing results.                    |
| TORCH_RESULTS_PERSISTENCE                                  | PT12H30M5S                              | Time Block for result persistence in ISO 8601 format. |
| TORCH_BATCHSIZE                                            | 100                                     | The batch size used for processing data.              |
| NGINX_SERVERNAME                                           | localhost:8080                          | The server name configuration for NGINX.              |
| LOG_LEVEL_org_springframework_web_reactive_function_client | info                                    | Log level for Spring Web Reactive client functions.   |
| LOG_LEVEL_reactor_netty                                    | info                                    | Log level for Netty reactor-based networking.         |
| LOG_LEVEL_reactor                                          | info                                    | Log level for Reactor framework.                      |
| LOG_LEVEL_de_medizininformatikinitiative_torch_util        | ${LOG_LEVEL:info}                       | Log level for torch utility.                          |
| LOG_LEVEL_de_medizininformatikinitiative_torch             | ${LOG_LEVEL:info}                       | Log level for torch core functionality.               |
| LOG_LEVEL_de_medizininformatikinitiative_torch_rest        | ${LOG_LEVEL:info}                       | Log level for torch REST services.                    |
| LOG_LEVEL_org_springframework                              | ${LOG_LEVEL:info}                       | Log level for Spring Framework.                       |

## Examples of Using Torch

Below, you will find examples for typical use cases.
To demonstrate the simplicity of the RESTful API,
the command line tool curl is used in the following examples for direct HTTP communication.

## Flare REST API

Torch implements the FHIR [Asynchronous Bulk Data Request Pattern][2].

### $extract-data

The $extract-data endpoint implements the kick-off request in the Async Bulk Pattern. It receives a FHIR parameters
resource with a CRTDL parameter containing a valueBase64Binary.

```sh
scripts/create-parameters.sh src/test/resources/CRTDL/CRTDL_observation.json | curl -s 'http://localhost:8086/fhir/$extract-data' -H "Content-Type: application/fhir+json" -d @- -v
```

The Parameters resource created by `scripts/create-parameters.sh` look like this:

```
{
  "resourceType": "Parameters",
  "parameter": [
    {
      "name": "crtdl",
      "valueBase64Binary": "<Base64 encoded CRTDL>"
    }
  ]
}
```

#### Response - Error (e.g. unsupported search parameter)

* HTTP Status Code of 4XX or 5XX

#### Response - Success

* HTTP Status Code of 202 Accepted
* Content-Location header with the absolute URL of an endpoint for subsequent status requests (polling location)

That location header can be used in the following status query:
E.g. location:"/fhir/__status/1234"

### Status Request

Torch provides a Status Request Endpoint which can be called using the location from the extract Data Endpoint.

```sh
curl -s http://localhost:8080/fhir/__status/{location} 
```

#### Response - In-Progress

* HTTP Status Code of 202 Accepted

#### Response - Error

* HTTP status code of 4XX or 5XX
* Content-Type header of application/fhir+json

#### Response - Complete

* HTTP status of 200 OK
* Content-Type header of application/fhir+json
* A body containing a JSON file describing the file links to the batched transformation results

```sh
curl -s 'http://localhost:8080/fhir/$extract-data' -H "Content-Type: application/fhir+json" -d '<query>'
```

the result is a looks something like this:

```json
{
  "requiresAccessToken": false,
  "output": [
    {
      "type": "Bundle",
      "url": "localhost:8080/da4a1c56-f5d9-468c-b57a-b8186ea4fea8/6c88f0ff-0e9a-4cf7-b3c9-044c2e844cfc.ndjson"
    },
    {
      "type": "Bundle",
      "url": "localhost:8080/da4a1c56-f5d9-468c-b57a-b8186ea4fea8/a4dd907c-4d98-4461-9d4c-02d62fc5a88a.ndjson"
    },
    {
      "type": "Bundle",
      "url": "localhost:8080/da4a1c56-f5d9-468c-b57a-b8186ea4fea8/f33634bd-d51b-463c-a956-93409d96935f.ndjson"
    }
  ],
  "request": "localhost:8080//fhir/$extract-data",
  "deleted": [],
  "transactionTime": "2024-09-05T12:30:32.711151718Z",
  "error": []
}

```

### Metadata

#### Response -Success

* HTTP status of 200 OK
* Content-Type header of application/fhir+json
* A body containing a Bundle Resource with a type of batch-response.


## Supported Features
- Multiple Profiles per Resource (greedy talkes first CDS conforming one)
- Loading of CDS StructureDefinitions
- Redacting and Copying of Ressources
- Parsing CRTDL
- Interaction with a Flare and FHIR Server 

## Outstanding Features

- CQL functionalities
- Loading of Whitelists
- Verifiyng against the CDS Profiles


## License

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
License. You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "
AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
language governing permissions and limitations under the License.

[1]: <https://en.wikipedia.org/wiki/ISO_8601>

[2]: <http://hl7.org/fhir/R5/async-bulk.html>

[3]: <https://github.com/medizininformatik-initiative/flare/releases/tag/v2.4.0-alpha.1>

[4]: <https://github.com/medizininformatik-initiative/feasibility-deploy/tree/main/feasibility-triangle>
