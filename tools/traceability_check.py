#!/usr/bin/env python3
"""Identity check of one project section of the root TRACEABILITY.md.

The script parses the original Mule example (deploy descriptor, active
configurations, RAML files, Java sources, JUnit and MUnit tests), the scenario
list of the converted project and the compiled output of ``<example>-java``
read through ``javap -p -v``. It compares them with the forward, backward and
generated-types tables of the ``## <example>-java`` section, prints every gap,
writes the result line at the end of the section and exits 0 only when every
figure is complete and no failure line is printed.

See DECISIONS.md D-075 and D-132 to D-136.
"""
from __future__ import annotations

import argparse
import errno
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, NoReturn, Optional, Set, Tuple

try:
    import fcntl
except ImportError:
    fcntl = None

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

CORE_NS = "http://www.mulesoft.org/schema/mule/core"
MUNIT_NS = "http://www.mulesoft.org/schema/mule/munit"
EXCLUDED_TEXT = "EXCLUDED \u2014 pending sapjco3.jar"
RESULT_PREFIX = "Forward: "
BUILD_HINT = "run mvn -B clean package in {project} first"

FORWARD_HEADER = ("source identity", "target", "test", "decision")
BACKWARD_HEADER = ("target identity", "maps to source identity or d-id")
GENERATED_HEADER = ("wsdl or xsd", "generated package", "generated classes", "replaces source")

RAML_METHODS = frozenset(("get", "post", "put", "delete", "patch", "head", "options", "trace"))
ROUTER_SCENARIOS = (
    "unknown-path-404",
    "wrong-method-405",
    "unsupported-media-415",
    "not-acceptable-406",
    "invalid-request-400",
    "console-try-it",
)
NO_ENTRY = frozenset(("n/a", "none", "\u2014", "-"))
JAVAP_BATCH = 100
JAVAP_TIMEOUT = 300

# Raw XML scan: markup whose text is replaced by its newlines only.
MARKUP_BLANK_RE = re.compile(
    r"<!--.*?-->|<!\[CDATA\[.*?\]\]>|<\?.*?\?>|<!DOCTYPE(?:[^\[>]|\[.*?\])*>", re.S
)
START_TAG_RE = re.compile(r"<([A-Za-z_][\w.\-]*(?::[A-Za-z_][\w.\-]*)?)(?=[\s/>])")

# TRACEABILITY.md cell grammar (DECISIONS.md D-132).
LOCATOR_RE = re.compile(r"^(?P<path>[^\s:#\[\]]+):(?P<line>\d+)(?:-\d+)?$")
ID_ROW_RE = re.compile(
    r"^(?P<kind>DW|SC)-(?P<num>\d+)\s+(?P<loc>(?P<path>[^\s:#\[\]]+):(?P<line>\d+)(?:-(?P<end>\d+))?)$"
)
BARE_ID_RE = re.compile(r"^(?:DW|SC)-\d+$")
SCENARIO_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9-]*_[A-Za-z0-9][A-Za-z0-9-]*$")
LOCATOR_GROUP_RE = re.compile(r"^(?P<identity>.*?)\s*\[(?P<group>[^\[\]]*)\]$", re.S)
D_ID_RE = re.compile(r"^D-\d{3,}$")
LIST_SPLIT_RE = re.compile(r"<br\s*/?>|[;,]", re.I)
LIST_SPLIT_WS_RE = re.compile(r"<br\s*/?>|[;,\s]", re.I)
CELL_SPLIT_RE = re.compile(r"(?<!\\)\|")
DELIMITER_ROW_RE = re.compile(r"^\s*\|?\s*:?-+:?\s*(?:\|\s*:?-+:?\s*)*\|?\s*$")

# Java source scanning.
PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.M)
JAVA_SPECIAL_RE = re.compile(r'//|/\*|"""|"|\'')
_ANNOTATION_ARGS = r"(?:\s*\((?:[^()]|\([^()]*\))*\))?"
JUNIT_TEST_RE = re.compile(
    r"@(?:org\.junit\.)?Test\b" + _ANNOTATION_ARGS
    + r"(?:\s*@[\w.]+" + _ANNOTATION_ARGS
    + r"|\s+(?:public|protected|private|static|final|synchronized|abstract|strictfp|native))*"
    + r"\s+void\s+([A-Za-z_$][\w$]*)\s*\("
)

# Tier 1 test methods and their display names (D-148).
JAVA_IDENT_RE = re.compile(r"(?:[^\W\d]|\$)[\w$]*")
JAVA_PACKAGE_RE = re.compile(r"package\s+((?:[\w$]+\s*\.\s*)*[\w$]+)\s*;")
JAVA_IMPORT_RE = re.compile(r"import\s+(static\s+)?((?:[\w$]+\s*\.\s*)*[\w$]+)(\s*\.\s*\*)?\s*;")
JAVA_MODIFIERS = frozenset((
    "public", "protected", "private", "static", "final", "abstract", "synchronized",
    "native", "strictfp", "default", "transient", "volatile", "sealed",
))
JAVA_TYPE_KEYWORDS = frozenset(("class", "interface", "enum"))
# Jupiter testable annotation -> required return: "void", or "value" for @TestFactory.
JUPITER_TEST_KINDS: Dict[str, str] = {
    "org.junit.jupiter.api.Test": "void",
    "org.junit.jupiter.api.RepeatedTest": "void",
    "org.junit.jupiter.api.TestTemplate": "void",
    "org.junit.jupiter.params.ParameterizedTest": "void",
    "org.junit.jupiter.api.TestFactory": "value",
}
JUPITER_DISPLAY_NAME = "org.junit.jupiter.api.DisplayName"
JUPITER_DISABLED = "org.junit.jupiter.api.Disabled"
JUPITER_NESTED = "org.junit.jupiter.api.Nested"
JUPITER_RELEVANT = frozenset(JUPITER_TEST_KINDS) | {JUPITER_DISABLED, JUPITER_NESTED}
JAVA_RETENTION = "java.lang.annotation.Retention"
JAVA_RETENTION_POLICY = "java.lang.annotation.RetentionPolicy"
RETENTION_POLICIES = frozenset(("SOURCE", "CLASS", "RUNTIME"))
# @Retention argument: optional "value =", then one name inside any number of balanced parentheses.
RETENTION_VALUE_RE = re.compile(r"\s*(?:value\s*=\s*)?((?:\(\s*)*)((?:[\w$]+\s*\.\s*)*[\w$]+)((?:\s*\))*)\s*")
DISPLAY_VALUE_RE = re.compile(r'\s*(?:value\s*=\s*)?"((?:[^"\\\n]|\\.)*)"\s*')
DISPLAY_ESCAPE_RE = re.compile(r"\\(?:u+([0-9A-Fa-f]{4})|([0-3][0-7]{0,2}|[4-7][0-7]?)|(.))", re.S)
DISPLAY_ESCAPES = {"b": "\b", "t": "\t", "n": "\n", "f": "\f", "r": "\r", "s": " ", '"': '"', "'": "'", "\\": "\\"}

# RAML 0.8 indentation scan.
RAML_KEY_RE = re.compile(
    r"^(?P<indent>\s*)(?P<dash>-\s+)?(?P<key>\"[^\"]*\"|'[^']*'|[^\s:#][^:]*?)\s*:(?:\s+(?P<value>.*?))?\s*$"
)
RAML_HEADER = "#%RAML 0.8"
RAML_ITEM_RE = re.compile(r"^\s*-(?:\s+(?P<value>.*?))?\s*$")
RAML_RESOURCE_RE = re.compile(r"^/(?:[^\s{}]|\{[^\s{}/]+\})*$")
RAML_STATUS_RE = re.compile(r"^\d{3}$")
# Property keys of each RAML mapping, each with the kind of its value: "scalar"
# holds no indented key or sequence entry, "sequence" holds scalar sequence
# entries, "opaque" content is not checked, "parameters" holds named
# parameters, "body" holds media types and body properties, "responses" holds
# status codes.
RAML_ROOT_KEYS: Dict[str, str] = {
    "title": "scalar", "version": "scalar", "baseUri": "scalar", "mediaType": "scalar",
    "baseUriParameters": "parameters", "uriParameters": "parameters", "protocols": "opaque",
    "schemas": "opaque", "securitySchemes": "opaque", "securedBy": "opaque", "documentation": "opaque",
    "resourceTypes": "opaque", "traits": "opaque",
}
RAML_RESOURCE_KEYS: Dict[str, str] = {
    "displayName": "scalar", "description": "scalar", "type": "opaque", "is": "opaque",
    "securedBy": "opaque", "uriParameters": "parameters", "baseUriParameters": "parameters",
}
RAML_METHOD_KEYS: Dict[str, str] = {
    "description": "scalar", "headers": "parameters", "protocols": "opaque", "queryParameters": "parameters",
    "body": "body", "responses": "responses", "is": "opaque", "securedBy": "opaque",
    "baseUriParameters": "parameters",
}
RAML_RESPONSE_KEYS: Dict[str, str] = {"description": "scalar", "headers": "parameters", "body": "body"}
RAML_BODY_KEYS: Dict[str, str] = {"schema": "scalar", "example": "scalar", "formParameters": "parameters"}
RAML_PARAMETER_KEYS: Dict[str, str] = {
    "displayName": "scalar", "description": "scalar", "type": "scalar", "enum": "sequence",
    "pattern": "scalar", "minLength": "scalar", "maxLength": "scalar", "minimum": "scalar",
    "maximum": "scalar", "example": "scalar", "repeat": "scalar", "required": "scalar", "default": "scalar",
}
# Kind of a nested mapping -> its property keys and their name in errors.
RAML_NESTED_KEYS: Dict[str, Tuple[Dict[str, str], str]] = {
    "response": (RAML_RESPONSE_KEYS, "response property"),
    "body": (RAML_BODY_KEYS, "media type or body property"),
    "media type": (RAML_BODY_KEYS, "body property"),
    "parameter": (RAML_PARAMETER_KEYS, "named-parameter property"),
}
RAML_NULL_VALUES = frozenset(("", "~", "null", "Null", "NULL"))
BLOCK_SCALAR_RE = re.compile(r"^[|>](?:[+-]?\d?|\d[+-])$")

# javap -p -v output.
_JAVAP_CLASS_NAME = r'("(?:[^"\\]|\\.)*"|[^"\s]\S*)'
THIS_CLASS_RE = re.compile(r"^\s*this_class:\s*#\d+\s*//\s*" + _JAVAP_CLASS_NAME)
SUPER_CLASS_RE = re.compile(r"^\s*super_class:\s*#\d+\s*//\s*" + _JAVAP_CLASS_NAME)
JAVAP_ESCAPE_RE = re.compile(r"\\(.)")
JAVAP_ESCAPES = {"n": "\n", "t": "\t"}
JAVAP_SECTION_RE = re.compile(r"^[A-Za-z][A-Za-z ]*:")
JAVAP_INSTRUCTION_RE = re.compile(r"^\d+: ([a-z][a-z0-9_]*)(.*)$")
JAVAP_LINE_NUMBER_RE = re.compile(r"^line (\d+): \d+$")
JAVAP_INDY_RE = re.compile(r"//\s*InvokeDynamic\s+#(\d+):([^:\s]+):")
JAVAP_FIELD_REF_RE = re.compile(r"^#\d+\s*//\s*Field\s+(\S+)$")
JAVAP_BOOTSTRAP_RE = re.compile(r"^  (\d+): #\d+ (.+)$")
JAVAP_SOURCE_FILE_RE = re.compile(r'^SourceFile: "(.*)"$')
OBJECT_METHODS_BOOTSTRAP = "REF_invokeStatic java/lang/runtime/ObjectMethods.bootstrap:"
RECORD_OBJECT_METHODS = {"equals": "(Ljava/lang/Object;)Z", "hashCode": "()I", "toString": "()Ljava/lang/String;"}
RETURN_OPCODES = {
    "Z": "ireturn", "B": "ireturn", "C": "ireturn", "S": "ireturn", "I": "ireturn",
    "J": "lreturn", "F": "freturn", "D": "dreturn",
}

# Transform inventory of the delivered examples (D-149). ID_INVENTORY maps each
# DataWeave setter, script and template ID to (path, first line, last line);
# MEL_INVENTORY maps a configuration path to its MEL line numbers. The six
# setters of sap-data-retrieval have no entry.
ID_INVENTORY: Dict[str, Tuple[str, int, int]] = {
    "DW-01": ("adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml", 12, 39),
    "DW-02": ("adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml", 44, 48),
    "DW-03": ("dataweave-with-flowreflookup/src/main/app/dataweave-with-flowref.xml", 9, 19),
    "DW-04": ("get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml", 13, 32),
    "DW-05": ("implementing-a-choice-exception-strategy/src/main/app/choice-error-handling.xml", 20, 20),
    "DW-06": ("implementing-a-choice-exception-strategy/src/main/app/choice-error-handling.xml", 24, 24),
    "DW-07": ("implementing-a-choice-exception-strategy/src/main/app/choice-error-handling.xml", 35, 35),
    "DW-08": ("implementing-a-choice-exception-strategy/src/main/app/choice-error-handling.xml", 39, 39),
    "DW-09": ("import-contacts-into-ms-dynamics/src/main/app/import-contacts-into-ms-dynamics.xml", 9, 18),
    "DW-10": ("import-contacts-into-salesforce/src/main/app/contacts-to-SFDC.xml", 12, 21),
    "DW-11": ("import-leads-into-salesforce/src/main/app/import-leads-into-salesforce.xml", 13, 21),
    "DW-12": ("importing-a-CSV-file-into-Mongo-DB/src/main/app/csv-to-mongodb.xml", 11, 20),
    "DW-13": ("importing-an-email-attachment-using-the-IMAP-connector/src/main/app/imap-to-xml.xml", 17, 29),
    "DW-14": ("importing-an-email-attachment-using-the-POP3-connector/src/main/app/pop-to-xml.xml", 13, 25),
    "DW-15": ("legacy-modernization/src/main/app/FufillmentWebService.xml", 12, 32),
    "DW-16": ("mule-expression-language-basics/src/main/app/greeting.xml", 47, 54),
    "DW-17": ("netsuite-data-retrieval/src/main/app/netsuite-api.xml", 27, 39),
    "DW-18": ("netsuite-data-retrieval/src/main/app/netsuite-api.xml", 44, 47),
    "DW-19": ("netsuite-data-retrieval/src/main/app/netsuite-api.xml", 56, 66),
    "DW-20": ("netsuite-data-retrieval/src/main/app/netsuite-api.xml", 70, 73),
    "DW-21": ("netsuite-data-retrieval/src/main/app/netsuite-api.xml", 81, 95),
    "DW-22": ("netsuite-data-retrieval/src/main/app/netsuite-api.xml", 99, 103),
    "DW-23": ("processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml", 14, 29),
    "DW-24": ("processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml", 30, 37),
    "DW-25": ("querying-a-db-and-attaching-results-to-an-email/src/main/app/attachments.xml", 20, 30),
    "DW-26": ("salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml", 12, 19),
    "DW-27": ("salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml", 15, 23),
    "DW-28": ("scatter-gather-flow-control/src/main/app/scatter-gather.xml", 47, 54),
    "DW-29": ("sending-a-csv-file-through-email-using-smtp/src/main/app/csv-to-smtp.xml", 8, 17),
    "DW-30": ("service-orchestration-and-choice-routing/src/main/app/fulfillment.xml", 53, 59),
    "DW-31": ("service-orchestration-and-choice-routing/src/main/app/fulfillment.xml", 68, 77),
    "DW-32": ("service-orchestration-and-choice-routing/src/main/app/mule-config.xml", 9, 16),
    "DW-33": ("service-orchestration-and-choice-routing/src/main/app/mule-config.xml", 27, 34),
    "DW-34": ("upload-to-ftp-after-converting-json-to-xml/src/main/app/upload-to-ftp.xml", 9, 26),
    "DW-35": ("web-service-consumer/src/main/app/tshirt-service-consumer.xml", 12, 16),
    "DW-36": ("web-service-consumer/src/main/app/tshirt-service-consumer.xml", 17, 23),
    "DW-37": ("web-service-consumer/src/main/app/tshirt-service-consumer.xml", 28, 31),
    "DW-38": ("web-service-consumer/src/main/app/tshirt-service-consumer.xml", 41, 45),
    "DW-39": ("xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml", 26, 46),
    "DW-40": ("xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml", 53, 61),
    "DW-41": ("xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml", 73, 80),
    "DW-42": ("xml-only-soap-webservice/src/main/app/mocks.xml", 12, 19),
    "DW-43": ("xml-only-soap-webservice/src/main/app/mocks.xml", 24, 40),
    "DW-44": ("xml-only-soap-webservice/src/main/app/mocks.xml", 52, 66),
    "DW-45": ("xml-only-soap-webservice/src/main/app/mocks.xml", 71, 80),
    "SC-01": ("addition-using-javascript-transformer/src/main/app/javascript-calculator.xml", 7, 13),
    "SC-02": ("dataweave-with-flowreflookup/src/main/app/dataweave-with-flowref.xml", 27, 51),
    "SC-03": ("document-integration-using-the-cmis-connector/src/main/app/cmis-document-integration.xml", 9, 12),
    "SC-04": ("salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml", 63, 63),
    "SC-05": ("service-orchestration-and-choice-routing/src/main/app/fulfillment.xml", 80, 91),
    "SC-06": ("service-orchestration-and-choice-routing/src/main/app/fulfillment.xml", 116, 122),
    "SC-07": ("track-a-custom-business-event/src/main/app/custom-business-events.xml", 12, 22),
    "SC-08": ("track-a-custom-business-event/src/main/app/custom-business-events.xml", 8, 10),
    "SC-09": ("get-customer-list-from-netsuite/src/main/resources/customer/index.html", 23, 23),
    "SC-10": ("cache-scope-with-fibonacci/src/main/app/cache-scope.xml", 4, 30),
}
MEL_INVENTORY: Dict[str, Tuple[int, ...]] = {
    "adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml": (52,),
    "addition-using-javascript-transformer/src/main/app/javascript-calculator.xml": (15,),
    "authenticating-salesforce-using-oauth2/src/main/app/salesforce-oauth.xml": (17,),
    "cache-scope-with-fibonacci/src/main/app/cache-scope.xml": (35, 51, 53, 56, 63, 65),
    "content-based-routing/src/main/app/content-based-routing.xml": (7, 8, 10, 13),
    "dataweave-with-flowreflookup/src/main/app/dataweave-with-flowref.xml": (22,),
    "exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml": (
        12, 18, 22, 23, 29, 31, 34, 36, 38,
    ),
    "extracting-data-from-LDAP-directory/src/main/app/ldap.xml": (11, 12, 13, 14),
    "foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml": (
        6, 23, 29, 30, 38, 42, 46, 47, 48, 58, 63, 69, 76, 82, 83, 85, 86, 88, 93, 98, 100, 110, 115, 133,
    ),
    "get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml": (9, 10),
    "http-multipart-request/src/main/app/http-multipart-request.xml": (19, 23, 25, 27),
    "http-oauth-provider/src/main/app/http-oauth-provider.xml": (47, 56),
    "http-request-response-with-logger/src/main/app/echo.xml": (6,),
    "implementing-a-choice-exception-strategy/src/main/app/choice-error-handling.xml": (6, 10, 12, 13, 16, 29, 31, 44),
    "import-contacts-into-ms-dynamics/src/main/app/import-contacts-into-ms-dynamics.xml": (24,),
    "import-contacts-into-salesforce/src/main/app/contacts-to-SFDC.xml": (25,),
    "import-leads-into-salesforce/src/main/app/import-leads-into-salesforce.xml": (27, 28, 31, 35),
    "importing-a-CSV-file-into-Mongo-DB/src/main/app/csv-to-mongodb.xml": (24, 28, 31, 37),
    "importing-a-csv-file-into-ms-sharepoint/src/main/app/importing-a-csv-file-into-ms-sharepoint.xml": (8, 10, 12),
    "importing-an-email-attachment-using-the-IMAP-connector/src/main/app/imap-to-xml.xml": (11,),
    "importing-an-email-attachment-using-the-POP3-connector/src/main/app/pop-to-xml.xml": (7,),
    "jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml": (27, 40, 44),
    "legacy-modernization/src/main/app/FufillmentWebService.xml": (35,),
    "login-form-using-the-http-connector/src/main/app/login-form-using-the-http-connector.xml": (21, 38),
    "mule-component-bindings/src/main/app/mule-component-bindings.xml": (59,),
    "mule-expression-language-basics/src/main/app/greeting.xml": (
        10, 19, 20, 23, 33, 34, 35, 36, 44, 45, 57, 59, 67, 68, 69, 70, 79, 80, 81, 82,
    ),
    "munit-short-tutorial/src/main/app/production-code.xml": (8, 14, 15, 18, 25, 35, 39),
    "oauth2-authorization-code-using-the-HTTP-connector/src/main/app/http-authorization-code-web.xml": (15, 47),
    "oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml": (18, 41),
    "processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml": (41,),
    "proxying-a-rest-api/src/main/app/proxying-a-rest-api.xml": (14, 16, 22),
    "querying-a-db-and-attaching-results-to-an-email/src/main/app/attachments.xml": (10, 12, 16, 35),
    "querying-a-mysql-database/src/main/app/database-to-json.xml": (8,),
    "salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml": (27, 28, 31, 34),
    "salesforce-data-synchronization-using-watermarking-and-batch-processing/src/main/app/watermarking.xml": (18,),
    "salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml": (
        28, 32, 38, 40, 44, 46, 63, 64,
    ),
    "scatter-gather-flow-control/src/main/app/scatter-gather.xml": (10, 27, 59, 62),
    "service-orchestration-and-choice-routing/src/main/app/fulfillment.xml": (16, 32, 62, 65, 103, 114, 135, 138),
    "service-orchestration-and-choice-routing/src/main/app/mule-config.xml": (45,),
    "soap-webservice-security/src/main/app/service-clients.xml": (10, 11, 13),
    "testing-apikit-with-munit/src/main/app/api.xml": (47, 51, 55, 59),
    "using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml": (14,),
    "xml-only-soap-webservice/src/main/app/mocks.xml": (7, 10, 48, 50),
}

# Scenario inventory of the delivered examples (D-151). SCENARIO_INVENTORY maps
# each non-RAML-backed example to its complete list of scenario suffixes, the
# lines of its SCENARIOS.txt without the "<example>_" prefix;
# RAML_SCENARIO_INVENTORY maps each RAML-backed example to the suffixes named
# beside its mechanical RAML scenarios. sap-data-retrieval has no entry.
SCENARIO_INVENTORY: Dict[str, Tuple[str, ...]] = {
    "adding-a-new-customer-to-workday-revenue-management": ("add-customer", "workday-fault"),
    "addition-using-javascript-transformer": ("sum", "malformed-list"),
    "authenticating-salesforce-using-oauth2": (
        "authorize-redirect", "callback-token-exchange", "contacts-query", "callback-error",
    ),
    "cache-scope-with-fibonacci": (
        "favicon-filtered", "n-below-20-miss", "n-below-20-hit", "n-below-20-nocache", "n-20-or-more",
    ),
    "content-based-routing": ("favicon-filtered", "spanish", "french", "default-language"),
    "dataweave-with-flowreflookup": (
        "companies-file", "companies-file-failure", "north-east", "south-east", "mid-west", "south-west",
        "west-coast", "unlisted-state-unknown", "null-state-unknown",
    ),
    "document-integration-using-the-cmis-connector": ("upload-logo", "cmis-error"),
    "exposing-a-restful-resource-using-the-HTTP-connector": (
        "list-persons", "create-person", "get-person-found", "get-person-missing-404", "get-person-non-numeric-500",
    ),
    "extracting-data-from-LDAP-directory": ("users-found", "no-users"),
    "filtering-a-message": (
        "wrong-method-filtered", "wrong-payload-type-filtered", "premium-granted", "premium-denied",
        "standard-up-to-6-months-granted", "standard-up-to-6-months-denied", "standard-7-to-11-months-granted",
        "standard-7-to-11-months-denied", "standard-12-months-plus-granted", "standard-12-months-plus-denied",
    ),
    "foreach-processing-and-choice-routing": (
        "missing-name", "missing-ssn", "missing-amount", "missing-term", "amount-20000-plus", "amount-below-20000",
        "bank5-unreachable", "lowest-quote", "processing-error", "credit-agency-soap", "bank-soap-bank1",
        "bank-soap-bank2", "bank-soap-bank3", "bank-soap-bank4", "bank-soap-bank5", "wsdl-credit-agency",
        "wsdl-bank",
    ),
    "get-customer-list-from-netsuite": ("last-name-missing-filtered", "customers-found", "no-customers"),
    "hello-world": ("hello",),
    "http-multipart-request": ("render-form", "upload-file", "upload-without-file"),
    "http-oauth-provider": (
        "authorize-login-page", "authorize-login-valid", "authorize-login-invalid", "token-code-exchange",
        "resources-with-token", "resources-without-token", "redirect-code-flow",
    ),
    "http-request-response-with-logger": ("echo-path",),
    "implementing-a-choice-exception-strategy": (
        "valid", "invalid-email-filtered", "units-below-1-400", "price-negative-400", "units-non-numeric-400",
        "email-missing-400", "units-missing-400", "malformed-json-500",
    ),
    "import-contacts-into-ms-dynamics": ("contacts-file", "contacts-file-failure"),
    "import-contacts-into-salesforce": ("contacts-file", "contacts-file-failure"),
    "import-leads-into-salesforce": (
        "lead-exists-skipped", "lead-new-created", "lead-failure-logged", "on-complete-counts",
    ),
    "importing-a-CSV-file-into-Mongo-DB": (
        "collection-missing-created", "collection-exists-logged", "csv-file-failure",
    ),
    "importing-a-csv-file-into-ms-sharepoint": (
        "folder-missing-created", "folder-present", "file-present-overwritten", "csv-file-failure",
    ),
    "importing-an-email-attachment-using-the-IMAP-connector": (
        "one-attachment", "two-attachments-first-used", "no-attachment", "processing-failure-not-retried",
    ),
    "importing-an-email-attachment-using-the-POP3-connector": (
        "one-attachment", "two-attachments-first-used", "no-attachment", "processing-failure-not-retried",
    ),
    "jms-message-rollback-and-redelivery": (
        "deliveries-1-to-4-rolled-back", "delivery-5-published", "other-exception-committed", "redelivery-delays",
    ),
    "legacy-modernization": ("put-shipping-order", "wsdl-fulfillment"),
    "login-form-using-the-http-connector": ("login-page", "login-valid", "login-invalid-403", "requester-login"),
    "mule-component-bindings": ("twitter-search", "stock-stats", "wrong-port-404"),
    "mule-expression-language-basics": (
        "greet1", "greet2-empty-username", "greet2-username", "greet3", "greet4", "greet5", "greet6",
    ),
    "munit-short-tutorial": ("payload-1-var-value-1", "other-payload-var-value-2"),
    "oauth2-authorization-code-using-the-HTTP-connector": (
        "web-login", "authorization-redirect", "redirect-url-success", "redirect-url-no-code-400",
        "redirect-url-token-failure-500", "login-done-search",
    ),
    "oauth2-client-credentials-using-the-HTTP-connector": ("oauth-box-call", "oauth-error-500", "fim-forward"),
    "proxying-a-rest-api": ("proxy-get-with-query", "proxy-post-with-body", "proxy-upstream-error-status"),
    "proxying-a-soap-api": ("proxy-soap-call", "proxy-soap-fault"),
    "querying-a-db-and-attaching-results-to-an-email": (
        "employees-report", "no-employees", "name-with-double-quote", "single-word-name",
    ),
    "querying-a-mysql-database": (
        "lastname-found", "lastname-none", "lastname-with-double-quote", "lastname-with-backslash", "lastname-missing",
    ),
    "salesforce-data-retrieval": (
        "show-form", "query-without-fields", "query-with-fields", "query-paged", "invalid-query",
    ),
    "salesforce-data-synchronization-using-watermarking-and-batch-processing": (
        "first-poll-default-yesterday", "poll-advances-watermark", "poll-no-records-unchanged",
        "poll-failure-unchanged", "record-logged", "watermark-survives-restart",
    ),
    "salesforce-to-MySQL-DB-using-Batch-Processing": (
        "contact-inserted", "contact-updated", "contact-error-logged", "on-complete-logged", "first-poll-default",
        "poll-advances-watermark", "poll-failure-unchanged",
    ),
    "scatter-gather-flow-control": ("merged-report",),
    "sending-a-csv-file-through-email-using-smtp": ("csv-file-mailed", "csv-file-failure"),
    "sending-json-data-to-a-amqp-queue": ("post-sales",),
    "sending-json-data-to-a-jms-queue": ("post-sales",),
    "service-orchestration-and-choice-routing": (
        "samsung-order", "inhouse-order", "samsung-non-200", "mixed-order", "order-error", "audit-insert",
        "audit-failure", "price-post-api-404", "price-get-404", "samsung-service", "populate-first",
        "populate-again", "order-request", "order-proxy", "order-proxy-fault", "manufacturers",
        "order-request-failure-reply", "docroot-entry", "docroot-tests-pages", "page-order-success",
        "page-order-failure", "wsdl-orders", "wsdl-samsung",
    ),
    "soap-webservice-security": (
        "unsecure", "username-token-valid", "username-token-wrong-password", "signed-valid",
        "signed-missing-signature", "encrypted-valid", "saml-valid", "saml-untrusted-issuer", "signed-saml-valid",
        "client-unsecure", "client-username-token", "client-username-token-signed",
        "client-username-token-encrypted", "client-saml-token", "client-saml-token-signed",
        "client-type-unsupported", "client-error", "wsdl-greeter",
    ),
    "track-a-custom-business-event": (
        "shoes", "jeans", "jackets", "unlisted-item-after-listed", "unlisted-item-first",
    ),
    "upload-to-ftp-after-converting-json-to-xml": ("upload-xml", "ftp-error-500"),
    "using-transactional-scope-in-jms-to-database": ("order-rolled-back", "redelivery-limit"),
    "web-service-consumer": ("order-tshirt", "list-inventory", "order-tshirt-fault"),
    "websphere-mq": (
        "enqueue", "process-appends", "process-failure-rolled-back", "dequeue-live-two-subscribers",
        "dequeue-cached-before-subscribe", "dequeue-cache-overflow", "dequeue-dropped-without-subscriber",
        "unsubscribe", "docroot-entry", "page-enqueue-and-receive",
    ),
    "xml-only-soap-webservice": (
        "admit-patient", "admit-patient-without-dates", "patient-upsert", "patient-other-operation",
        "ehr-create-episode", "ehr-other-operation", "wsdl-admission", "wsdl-patient", "wsdl-ehr",
        "xsd-soa-message",
    ),
}
RAML_SCENARIO_INVENTORY: Dict[str, Tuple[str, ...]] = {
    "netsuite-data-retrieval": (
        "name-with-quote-refused", "title-with-quote-refused", "items-multi-line-plan", "collection-paging-two-pages",
    ),
    "processing-orders-with-dataweave-and-APIkit": ("order-file", "order-file-failure"),
    "rest-api-with-apikit": ("post-teams-409-conflict",),
    "testing-apikit-with-munit": (),
}

# Branch outcomes of the delivered examples (D-151). BRANCH_INVENTORY maps the
# start-tag locator of every when, otherwise and outermost *-filter element of
# the active configurations to the scenario suffixes of each outcome: "branch"
# for a when or otherwise, "reject" and "accept" for a filter.
BRANCH_INVENTORY: Dict[str, Dict[str, Tuple[str, ...]]] = {
    "cache-scope-with-fibonacci/src/main/app/cache-scope.xml:45": {
        "reject": ("favicon-filtered",),
        "accept": ("n-below-20-miss", "n-below-20-hit", "n-below-20-nocache", "n-20-or-more"),
    },
    "cache-scope-with-fibonacci/src/main/app/cache-scope.xml:51": {
        "branch": ("n-below-20-miss", "n-below-20-hit", "n-below-20-nocache"),
    },
    "cache-scope-with-fibonacci/src/main/app/cache-scope.xml:55": {"branch": ("n-20-or-more",)},
    "content-based-routing/src/main/app/content-based-routing.xml:7": {
        "reject": ("favicon-filtered",),
        "accept": ("spanish", "french", "default-language"),
    },
    "content-based-routing/src/main/app/content-based-routing.xml:10": {"branch": ("spanish",)},
    "content-based-routing/src/main/app/content-based-routing.xml:13": {"branch": ("french",)},
    "content-based-routing/src/main/app/content-based-routing.xml:16": {"branch": ("default-language",)},
    "exposing-a-restful-resource-using-the-HTTP-connector/src/main/app/http-restful-resource.xml:33": {
        "reject": ("get-person-missing-404",),
        "accept": ("get-person-found",),
    },
    "filtering-a-message/src/main/app/filtering.xml:9": {
        "reject": ("wrong-method-filtered", "wrong-payload-type-filtered"),
        "accept": (
            "premium-granted", "premium-denied", "standard-up-to-6-months-granted", "standard-up-to-6-months-denied",
            "standard-7-to-11-months-granted", "standard-7-to-11-months-denied", "standard-12-months-plus-granted",
            "standard-12-months-plus-denied",
        ),
    },
    "filtering-a-message/src/main/app/filtering.xml:15": {
        "reject": (
            "premium-denied", "standard-up-to-6-months-denied", "standard-7-to-11-months-denied",
            "standard-12-months-plus-denied",
        ),
        "accept": (
            "premium-granted", "standard-up-to-6-months-granted", "standard-7-to-11-months-granted",
            "standard-12-months-plus-granted",
        ),
    },
    "foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:29": {
        "branch": ("amount-20000-plus", "amount-below-20000", "lowest-quote", "processing-error"),
    },
    "foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:57": {
        "branch": ("missing-name", "missing-ssn", "missing-amount", "missing-term"),
    },
    "foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:82": {"branch": ("amount-20000-plus",)},
    "foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:85": {"branch": ("amount-below-20000",)},
    "foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:88": {"branch": ("bank5-unreachable",)},
    "get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:9": {
        "reject": ("last-name-missing-filtered",),
        "accept": ("customers-found", "no-customers"),
    },
    "implementing-a-choice-exception-strategy/src/main/app/choice-error-handling.xml:9": {
        "reject": (
            "units-below-1-400", "price-negative-400", "units-non-numeric-400", "email-missing-400",
            "units-missing-400",
        ),
        "accept": ("valid", "invalid-email-filtered"),
    },
    "implementing-a-choice-exception-strategy/src/main/app/choice-error-handling.xml:13": {
        "reject": ("invalid-email-filtered",),
        "accept": ("valid",),
    },
    "importing-a-CSV-file-into-Mongo-DB/src/main/app/csv-to-mongodb.xml:28": {
        "branch": ("collection-missing-created",),
    },
    "importing-a-CSV-file-into-Mongo-DB/src/main/app/csv-to-mongodb.xml:31": {
        "branch": ("collection-exists-logged",),
    },
    "jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:27": {
        "branch": ("delivery-5-published", "other-exception-committed"),
    },
    "jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:30": {
        "branch": ("deliveries-1-to-4-rolled-back", "redelivery-delays"),
    },
    "login-form-using-the-http-connector/src/main/app/login-form-using-the-http-connector.xml:20": {
        "reject": ("login-invalid-403",),
        "accept": ("login-valid", "requester-login"),
    },
    "mule-expression-language-basics/src/main/app/greeting.xml:19": {"branch": ("greet2-empty-username",)},
    "mule-expression-language-basics/src/main/app/greeting.xml:22": {"branch": ("greet2-username",)},
    "munit-short-tutorial/src/main/app/production-code.xml:14": {"branch": ("payload-1-var-value-1",)},
    "munit-short-tutorial/src/main/app/production-code.xml:17": {"branch": ("other-payload-var-value-2",)},
    "munit-short-tutorial/src/main/app/production-code.xml:25": {"branch": ("payload-1-var-value-1",)},
    "munit-short-tutorial/src/main/app/production-code.xml:28": {"branch": ("other-payload-var-value-2",)},
    "salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml:38": {
        "branch": ("contact-inserted",),
    },
    "salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml:44": {
        "branch": ("contact-updated",),
    },
    "salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml:50": {
        "branch": ("contact-error-logged",),
    },
    "service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:35": {
        "branch": ("samsung-order", "samsung-non-200", "mixed-order"),
    },
    "service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:38": {
        "branch": ("inhouse-order", "mixed-order"),
    },
    "service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:62": {
        "reject": ("samsung-non-200",),
        "accept": ("samsung-order", "mixed-order"),
    },
    "soap-webservice-security/src/main/app/service-clients.xml:13": {"branch": ("client-unsecure",)},
    "soap-webservice-security/src/main/app/service-clients.xml:16": {"branch": ("client-username-token",)},
    "soap-webservice-security/src/main/app/service-clients.xml:19": {"branch": ("client-username-token-signed",)},
    "soap-webservice-security/src/main/app/service-clients.xml:22": {"branch": ("client-username-token-encrypted",)},
    "soap-webservice-security/src/main/app/service-clients.xml:25": {"branch": ("client-saml-token",)},
    "soap-webservice-security/src/main/app/service-clients.xml:28": {"branch": ("client-saml-token-signed",)},
    "soap-webservice-security/src/main/app/service-clients.xml:31": {"branch": ("client-type-unsupported",)},
    "xml-only-soap-webservice/src/main/app/mocks.xml:10": {
        "branch": ("patient-upsert", "admit-patient", "admit-patient-without-dates"),
    },
    "xml-only-soap-webservice/src/main/app/mocks.xml:22": {"branch": ("patient-other-operation",)},
    "xml-only-soap-webservice/src/main/app/mocks.xml:50": {
        "branch": ("ehr-create-episode", "admit-patient", "admit-patient-without-dates"),
    },
    "xml-only-soap-webservice/src/main/app/mocks.xml:69": {"branch": ("ehr-other-operation",)},
}
# Outcomes of a branch inventory entry for the binding rules "branch" and "filter".
BRANCH_OUTCOMES: Dict[str, Tuple[str, ...]] = {"branch": ("branch",), "filter": ("reject", "accept")}


class CheckError(Exception):
    """An environment, usage or parse error; ``code`` is the exit status (2 or 3)."""

    def __init__(self, message: str, code: int = 2) -> None:
        super().__init__(message)
        self.code = code


# --------------------------------------------------------------------------
# Small text helpers
# --------------------------------------------------------------------------


def read_text(path: Path, encoding: str = "utf-8", errors: str = "strict") -> str:
    """Return the decoded content of ``path``; read failures raise CheckError."""
    try:
        with open(path, "r", encoding=encoding, errors=errors, newline="") as handle:
            return handle.read()
    except (OSError, UnicodeDecodeError) as exc:
        raise CheckError("cannot read {}: {}".format(path, exc)) from None


def line_content(line: str) -> str:
    """Return ``line`` without its line terminator."""
    return line.rstrip("\r\n")


def split_physical_lines(text: str) -> List[str]:
    """Split ``text`` into lines that keep their terminators."""
    return re.findall(r"[^\n]*\n|[^\n]+\Z", text)


def parse_properties(text: str) -> Dict[str, str]:
    """Parse Java properties text: CR removed, ``\\`` continuations, ``=``/``:``/blank separators."""
    logical: List[str] = []
    pending: Optional[str] = None
    for raw in text.replace("\r", "").split("\n"):
        line = raw.lstrip(" \t\f") if pending is not None else raw
        if pending is None:
            stripped = line.lstrip(" \t\f")
            if not stripped or stripped[0] in "#!":
                continue
            line = stripped
        trailing = len(line) - len(line.rstrip("\\"))
        if trailing % 2 == 1:
            pending = (pending or "") + line[:-1]
            continue
        logical.append((pending or "") + line)
        pending = None
    if pending is not None:
        logical.append(pending)
    props: Dict[str, str] = {}
    for entry in logical:
        key_chars: List[str] = []
        index = 0
        while index < len(entry):
            char = entry[index]
            if char == "\\" and index + 1 < len(entry):
                key_chars.append(entry[index + 1])
                index += 2
                continue
            if char in "=: \t\f":
                break
            key_chars.append(char)
            index += 1
        rest = entry[index:].lstrip(" \t\f")
        if rest[:1] in ("=", ":"):
            rest = rest[1:].lstrip(" \t\f")
        props["".join(key_chars)] = rest
    return props


# --------------------------------------------------------------------------
# Mule XML with start-tag line numbers
# --------------------------------------------------------------------------


@dataclass(eq=False)
class Element:
    """One element of an active Mule configuration."""

    path: str
    raw_qname: str
    ns: str
    local: str
    line: int
    attrib: Dict[str, str]
    parent: Optional["Element"] = field(default=None, repr=False)
    children: List["Element"] = field(default_factory=list, repr=False)

    @property
    def qname(self) -> str:
        """Identity name: bare local name in the core namespace, else ``prefix:local``."""
        return self.local if self.ns == CORE_NS else self.raw_qname

    @property
    def locator(self) -> str:
        return "{}:{}".format(self.path, self.line)


def split_tag(tag: str) -> Tuple[str, str]:
    """Split an ElementTree ``{uri}local`` tag into namespace URI and local name."""
    if tag.startswith("{"):
        uri, _, local = tag[1:].partition("}")
        return uri, local
    return "", tag


def parse_mule_xml(abs_path: Path, rel_path: str) -> List[Element]:
    """Parse one configuration and attach the start-tag line to every element.

    The k-th start tag of the raw text (comments, CDATA, processing
    instructions and DOCTYPE blanked to their newlines) is aligned with the
    k-th element of ``root.iter()``; count and local names must agree.
    """
    raw = read_text(abs_path, encoding="utf-8-sig", errors="replace")
    try:
        root = ET.parse(str(abs_path)).getroot()
    except (ET.ParseError, LookupError) as exc:
        raise CheckError("XML parse error in {}: {}".format(rel_path, exc)) from None
    except OSError as exc:
        raise CheckError("cannot read {}: {}".format(rel_path, exc)) from None
    blanked = MARKUP_BLANK_RE.sub(lambda match: "\n" * match.group(0).count("\n"), raw)
    tags: List[Tuple[str, int]] = []
    line = 1
    position = 0
    for match in START_TAG_RE.finditer(blanked):
        line += blanked.count("\n", position, match.start())
        position = match.start()
        tags.append((match.group(1), line))
    et_elements = list(root.iter())
    for index in range(max(len(tags), len(et_elements))):
        if index >= len(tags) or index >= len(et_elements):
            raise CheckError(
                "line-alignment mismatch in {}: {} start tags, {} elements".format(
                    rel_path, len(tags), len(et_elements)
                )
            )
        local = split_tag(et_elements[index].tag)[1]
        if tags[index][0].split(":")[-1] != local:
            raise CheckError(
                "line-alignment mismatch in {}: start tag <{}> at line {} versus element {}".format(
                    rel_path, tags[index][0], tags[index][1], local
                )
            )
    by_id: Dict[int, Element] = {}
    elements: List[Element] = []
    for et_element, (raw_qname, tag_line) in zip(et_elements, tags):
        uri, local = split_tag(et_element.tag)
        element = Element(rel_path, raw_qname, uri, local, tag_line, dict(et_element.attrib))
        by_id[id(et_element)] = element
        elements.append(element)
    for et_element in et_elements:
        parent = by_id[id(et_element)]
        for et_child in et_element:
            child = by_id[id(et_child)]
            child.parent = parent
            parent.children.append(child)
    return elements


# --------------------------------------------------------------------------
# Java source scanning
# --------------------------------------------------------------------------


def _blank(text: str) -> str:
    """Replace every character except newlines with a space."""
    return re.sub(r"[^\n]", " ", text)


def strip_java_comments(text: str) -> str:
    """Blank ``//`` and ``/* */`` comment bodies; strings, chars and text blocks stay intact."""
    out: List[str] = []
    index = 0
    length = len(text)
    while index < length:
        match = JAVA_SPECIAL_RE.search(text, index)
        if match is None:
            out.append(text[index:])
            break
        out.append(text[index:match.start()])
        token = match.group(0)
        start = match.start()
        if token == "//":
            end = text.find("\n", start)
            end = length if end < 0 else end
            out.append(_blank(text[start:end]))
        elif token == "/*":
            close = text.find("*/", start + 2)
            end = length if close < 0 else close + 2
            out.append(_blank(text[start:end]))
        elif token == '"""':
            end = start + 3
            while end < length:
                if text[end] == "\\":
                    end += 2
                    continue
                if text.startswith('"""', end):
                    end += 3
                    break
                end += 1
            end = min(end, length)
            out.append(text[start:end])
        else:
            end = start + 1
            while end < length and text[end] != token and text[end] != "\n":
                end += 2 if text[end] == "\\" else 1
            end = min(end, length)
            if end < length and text[end] == token:
                end += 1
            out.append(text[start:end])
        index = end
    return "".join(out)


def java_fqcn(path: Path, stripped: Optional[str] = None) -> str:
    """Return ``package.Stem`` of a Java source (``Stem`` in the default package)."""
    if stripped is None:
        stripped = strip_java_comments(read_text(path, errors="replace"))
    match = PACKAGE_RE.search(stripped)
    stem = path.name[: -len(".java")] if path.name.endswith(".java") else path.stem
    return "{}.{}".format(match.group(1), stem) if match else stem


def sorted_files(base: Path, pattern: str) -> List[Path]:
    """Files under ``base`` matching ``pattern``, sorted by POSIX path."""
    if not base.is_dir():
        return []
    return sorted((p for p in base.glob(pattern) if p.is_file()), key=lambda p: p.as_posix())


# --------------------------------------------------------------------------
# Tier 1 test methods and display names
# --------------------------------------------------------------------------


@dataclass(eq=False)
class JavaAnnotation:
    """One annotation: its name without whitespace, the text inside its parentheses (None without) and its line."""

    name: str
    args: Optional[str]
    line: int


@dataclass(eq=False)
class JavaScope:
    """A brace scope: a type body (class, interface, enum, record or @interface) or any other block.

    ``qualified`` is the dotted name inside the unit (``Outer.Inner``), empty
    for a block and for a type declared inside a block.
    """

    kind: str
    name: str = ""
    qualified: str = ""
    annotations: List[JavaAnnotation] = field(default_factory=list)
    modifiers: Set[str] = field(default_factory=set)


@dataclass(eq=False)
class JavaDeclaration:
    """What an annotation run precedes: ``kind`` method, type, other or error."""

    kind: str
    offset: int
    name: str = ""
    returns_void: bool = False
    has_body: bool = False
    message: str = ""


@dataclass(eq=False)
class JavaRun:
    """Consecutive annotations and modifiers, the declaration after them and the scopes enclosing them."""

    annotations: List[JavaAnnotation]
    modifiers: Set[str]
    declaration: JavaDeclaration
    scopes: List[JavaScope]


@dataclass(eq=False)
class JavaUnit:
    """Package, imports, type declarations and annotation runs of one Java source."""

    path: str
    package: str = ""
    single_imports: Dict[str, str] = field(default_factory=dict)
    on_demand: List[str] = field(default_factory=list)
    types: List[JavaScope] = field(default_factory=list)
    runs: List[JavaRun] = field(default_factory=list)

    def fqcn(self, scope: JavaScope) -> str:
        """Canonical name of a member or top-level type declared in this unit."""
        return "{}.{}".format(self.package, scope.qualified) if self.package else scope.qualified


@dataclass
class DisplayNames:
    """``@DisplayName`` values of the Tier 1 test sources, each with its ``<path>:<line>#<method>`` locations.

    ``tests`` holds the Tier 1 test methods; ``skipped`` holds every other
    declaration carrying the value, each followed by the reason in parentheses.
    """

    tests: Dict[str, List[str]] = field(default_factory=dict)
    skipped: Dict[str, List[str]] = field(default_factory=dict)


class JavaUnitScanner:
    """Reads one comment-stripped Java source into a JavaUnit; malformed text raises CheckError at ``<path>:<line>``."""

    def __init__(self, text: str, path: str) -> None:
        self.text = text
        self.path = path
        self.unit = JavaUnit(path)

    def line(self, offset: int) -> int:
        """1-based line number of ``offset``."""
        return self.text.count("\n", 0, offset) + 1

    def error(self, offset: int, message: str) -> CheckError:
        """CheckError ``<path>:<line>: <message>`` for ``offset``."""
        return CheckError("{}:{}: {}".format(self.path, self.line(offset), message))

    def skip_space(self, index: int) -> int:
        """First index at or after ``index`` that holds no whitespace."""
        text = self.text
        while index < len(text) and text[index].isspace():
            index += 1
        return index

    def skip_literal(self, index: int) -> int:
        """Index after the string, character or text-block literal opening at ``index``."""
        text = self.text
        if text.startswith('"""', index):
            end = index + 3
            while end < len(text):
                if text[end] == "\\":
                    end += 2
                elif text.startswith('"""', end):
                    return end + 3
                else:
                    end += 1
            raise self.error(index, "unterminated text block")
        quote = text[index]
        end = index + 1
        while end < len(text) and text[end] != quote and text[end] != "\n":
            end += 2 if text[end] == "\\" else 1
        if end >= len(text) or text[end] != quote:
            raise self.error(index, "unterminated {} literal".format("string" if quote == '"' else "character"))
        return end + 1

    def skip_parens(self, index: int) -> int:
        """Index after the parenthesised group opening at ``index``; literals inside it are skipped."""
        text = self.text
        depth = 0
        end = index
        while end < len(text):
            char = text[end]
            if char in "\"'":
                end = self.skip_literal(end)
                continue
            if char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    return end + 1
            end += 1
        raise self.error(index, "unbalanced parentheses")

    def skip_angles(self, index: int) -> Optional[int]:
        """Index after the type-argument list opening at ``index``; None when a brace, ``;`` or ``=`` comes first."""
        text = self.text
        depth = 0
        end = index
        while end < len(text):
            char = text[end]
            if char in "\"'":
                end = self.skip_literal(end)
                continue
            if char == "(":
                end = self.skip_parens(end)
                continue
            if char == "<":
                depth += 1
            elif char == ">":
                depth -= 1
                if depth == 0:
                    return end + 1
            elif char in "{};=":
                return None
            end += 1
        return None

    def read_name(self, index: int) -> Tuple[str, int]:
        """Dotted name at ``index`` without whitespace and the index after it; ``""`` when none starts there."""
        text = self.text
        match = JAVA_IDENT_RE.match(text, index)
        if match is None:
            return "", index
        parts = [match.group(0)]
        end = match.end()
        while True:
            dot = self.skip_space(end)
            if not text.startswith(".", dot):
                break
            following = JAVA_IDENT_RE.match(text, self.skip_space(dot + 1))
            if following is None:
                break
            parts.append(following.group(0))
            end = following.end()
        return ".".join(parts), end

    def interface_end(self, index: int) -> Optional[int]:
        """Index after ``@interface`` when the ``@`` at ``index`` opens one, else None."""
        match = JAVA_IDENT_RE.match(self.text, self.skip_space(index + 1))
        return match.end() if match is not None and match.group(0) == "interface" else None

    def read_annotation(self, index: int) -> Tuple[JavaAnnotation, int]:
        """The annotation whose ``@`` is at ``index``, and the index after it."""
        name, end = self.read_name(self.skip_space(index + 1))
        if not name:
            raise self.error(index, "annotation without a name")
        args: Optional[str] = None
        start = self.skip_space(end)
        if self.text.startswith("(", start):
            end = self.skip_parens(start)
            args = self.text[start + 1:end - 1]
        return JavaAnnotation(name, args, self.line(index)), end

    def read_modifier(self, index: int) -> Optional[Tuple[str, int]]:
        """The modifier keyword at ``index`` and the index after it, else None."""
        match = JAVA_IDENT_RE.match(self.text, index)
        if match is None:
            return None
        if match.group(0) == "non" and self.text.startswith("-sealed", match.end()):
            return "non-sealed", match.end() + len("-sealed")
        return (match.group(0), match.end()) if match.group(0) in JAVA_MODIFIERS else None

    def record_follows(self, index: int) -> bool:
        """True when the word ``record`` ending at ``index`` opens a record declaration."""
        match = JAVA_IDENT_RE.match(self.text, self.skip_space(index))
        if match is None:
            return False
        following = self.skip_space(match.end())
        return self.text.startswith("(", following) or self.text.startswith("<", following)

    def read_run(self, index: int, modifiers: Iterable[str]) -> Tuple[List[JavaAnnotation], Set[str], int]:
        """Annotations and modifiers from the ``@`` at ``index``, and the index of the declaration after them."""
        annotations: List[JavaAnnotation] = []
        found = set(modifiers)
        while True:
            index = self.skip_space(index)
            if self.text.startswith("@", index) and self.interface_end(index) is None:
                annotation, index = self.read_annotation(index)
                annotations.append(annotation)
                continue
            modifier = self.read_modifier(index)
            if modifier is None:
                return annotations, found, index
            found.add(modifier[0])
            index = modifier[1]

    def method_body(self, index: int) -> Optional[bool]:
        """After a parameter list ending at ``index``: True for a body, False for ``;`` or ``default``, else None."""
        text = self.text
        while True:
            index = self.skip_space(index)
            if index >= len(text):
                return None
            char = text[index]
            if char == "{":
                return True
            if char == ";":
                return False
            if char == "@":
                index = self.read_annotation(index)[1]
                continue
            match = JAVA_IDENT_RE.match(text, index)
            if match is not None:
                if match.group(0) == "default":
                    return False
                index = match.end()
                continue
            if char in ".,[]":
                index += 1
                continue
            return None

    def read_declaration(self, index: int) -> JavaDeclaration:
        """Classify the declaration that starts at ``index``, right after an annotation run."""
        text = self.text
        index = self.skip_space(index)
        if text.startswith("@", index):
            return JavaDeclaration("type", index)
        if text.startswith("<", index):
            end = self.skip_angles(index)
            if end is None:
                return JavaDeclaration("error", index, message="unbalanced type parameters")
            index = end
        words: List[str] = []
        while True:
            index = self.skip_space(index)
            if index >= len(text):
                return JavaDeclaration("error", index, message="no declaration follows the annotations")
            char = text[index]
            match = JAVA_IDENT_RE.match(text, index)
            if match is not None:
                word = match.group(0)
                if not words and (
                    word in JAVA_TYPE_KEYWORDS or (word == "record" and self.record_follows(match.end()))
                ):
                    return JavaDeclaration("type", index)
                words.append(word)
                index = match.end()
            elif char == "@":
                index = self.read_annotation(index)[1]
            elif char == ".":
                index += 1
            elif char == "<":
                end = self.skip_angles(index)
                if end is None:
                    return JavaDeclaration("other", index)
                index = end
            elif char == "[" and text.startswith("]", self.skip_space(index + 1)):
                index = self.skip_space(index + 1) + 1
            elif char == "(" and words:
                body = self.method_body(self.skip_parens(index))
                if body is None:
                    return JavaDeclaration(
                        "error", index, message="neither a body nor ';' follows {}(...)".format(words[-1])
                    )
                return JavaDeclaration("method", index, words[-1], len(words) > 1 and words[-2] == "void", body)
            else:
                return JavaDeclaration("other", index)

    def type_scope(
        self,
        kind: str,
        start: int,
        end: int,
        stack: List[JavaScope],
        runs: Dict[int, Tuple[List[JavaAnnotation], Set[str]]],
        modifiers: Iterable[str],
    ) -> Tuple[JavaScope, int]:
        """The type whose keyword spans ``start``..``end``, and the index after its name."""
        match = JAVA_IDENT_RE.match(self.text, self.skip_space(end))
        if match is None:
            raise self.error(start, "{} declaration without a name".format(kind))
        annotations, found = runs.pop(start, ([], set(modifiers)))
        name = match.group(0)
        outer = stack[-1] if stack else None
        if outer is None:
            qualified = name
        elif outer.kind != "block" and outer.qualified:
            qualified = "{}.{}".format(outer.qualified, name)
        else:
            qualified = ""
        return JavaScope(kind, name, qualified, annotations, found), match.end()

    def read_header(self, index: int, keyword: str) -> int:
        """Record the package or import declaration at ``index``; return the index after it."""
        pattern = JAVA_PACKAGE_RE if keyword == "package" else JAVA_IMPORT_RE
        match = pattern.match(self.text, index)
        if match is None:
            raise self.error(index, "unparseable {} declaration".format(keyword))
        if keyword == "package":
            self.unit.package = re.sub(r"\s+", "", match.group(1))
        elif match.group(3):
            self.unit.on_demand.append(re.sub(r"\s+", "", match.group(2)))
        else:
            name = re.sub(r"\s+", "", match.group(2))
            self.unit.single_imports.setdefault(name.rsplit(".", 1)[-1], name)
        return match.end()

    def scan(self) -> JavaUnit:
        """Walk the source once, tracking brace scopes, imports, type declarations and annotation runs."""
        text = self.text
        unit = self.unit
        stack: List[JavaScope] = []
        pending: Optional[JavaScope] = None
        runs: Dict[int, Tuple[List[JavaAnnotation], Set[str]]] = {}
        modifiers: List[str] = []
        previous = ""
        index = 0
        while index < len(text):
            char = text[index]
            if char.isspace():
                index += 1
                continue
            token = char
            end = index + 1
            if char in "\"'":
                end = self.skip_literal(index)
            elif char == "@":
                interface_end = self.interface_end(index)
                if interface_end is None:
                    annotations, found, end = self.read_run(index, modifiers)
                    declaration = self.read_declaration(end)
                    if declaration.kind == "type":
                        runs[declaration.offset] = (annotations, found)
                    unit.runs.append(JavaRun(annotations, found, declaration, list(stack)))
                else:
                    pending, end = self.type_scope("@interface", index, interface_end, stack, runs, modifiers)
            elif char == "{":
                stack.append(pending if pending is not None else JavaScope("block"))
                if pending is not None:
                    unit.types.append(pending)
                pending = None
            elif char == "}":
                if not stack:
                    raise self.error(index, "unbalanced '}'")
                stack.pop()
            else:
                modifier = self.read_modifier(index)
                if modifier is not None:
                    modifiers.append(modifier[0])
                    previous = modifier[0]
                    index = modifier[1]
                    continue
                match = JAVA_IDENT_RE.match(text, index)
                if match is not None:
                    token = match.group(0)
                    end = match.end()
                    if previous != "." and (
                        token in JAVA_TYPE_KEYWORDS or (token == "record" and self.record_follows(end))
                    ):
                        pending, end = self.type_scope(token, index, end, stack, runs, modifiers)
                    elif not stack and pending is None and token in ("package", "import"):
                        end = self.read_header(index, token)
            previous = token
            modifiers = []
            index = end
        if stack or pending is not None:
            raise self.error(len(text), "unbalanced '{'")
        return unit


def display_value(annotation: JavaAnnotation) -> Optional[str]:
    """The value of ``@DisplayName("...")`` or ``@DisplayName(value = "...")``; None for any other argument."""
    match = DISPLAY_VALUE_RE.fullmatch(annotation.args) if annotation.args is not None else None
    if match is None:
        return None

    def unescape(escape: re.Match[str]) -> str:
        if escape.group(1) is not None:
            return chr(int(escape.group(1), 16))
        if escape.group(2) is not None:
            return chr(int(escape.group(2), 8))
        if escape.group(3) not in DISPLAY_ESCAPES:
            raise ValueError(escape.group(0))
        return DISPLAY_ESCAPES[escape.group(3)]

    try:
        return DISPLAY_ESCAPE_RE.sub(unescape, match.group(1))
    except ValueError:
        return None


@dataclass(eq=False)
class AnnotationClosure:
    """Canonical names of a declaration's run-time-visible annotations and meta-annotations (D-148).

    ``hidden`` maps each ``@interface`` of the Tier 1 sources reached without
    RUNTIME retention to the JUnit Jupiter names its meta-annotations carry;
    its meta-annotations are absent from ``names``.
    """

    names: Set[str]
    hidden: Dict[str, Set[str]] = field(default_factory=dict)

    def hidden_by(self, jupiter: Iterable[str]) -> Optional[str]:
        """The first ``hidden`` ``@interface`` carrying one of ``jupiter``, else None."""
        wanted = set(jupiter)
        return next((name for name in sorted(self.hidden) if self.hidden[name] & wanted), None)


def tier1_rejection(run: JavaRun, method: AnnotationClosure, scopes: List[AnnotationClosure]) -> Optional[str]:
    """Why the method after ``run`` is no Tier 1 test method, or None when it is one (D-148).

    ``method`` holds the run-time-visible annotations of the method and their
    meta-annotations, ``scopes`` the same for each enclosing scope.
    """
    declaration = run.declaration
    names = method.names
    returns = {JUPITER_TEST_KINDS[name] for name in names if name in JUPITER_TEST_KINDS}
    if not returns:
        composed = method.hidden_by(JUPITER_TEST_KINDS)
        if composed is not None:
            return "composed annotation {} is not RUNTIME-retained".format(composed)
        return "no JUnit Jupiter test annotation"
    if not declaration.has_body:
        return "no method body"
    if "private" in run.modifiers or "static" in run.modifiers:
        return "private or static method"
    if ("void" if declaration.returns_void else "value") not in returns:
        return "return type does not match its test annotation"
    if JUPITER_DISABLED in names:
        return "@Disabled method"
    if not run.scopes or any(scope.kind == "block" for scope in run.scopes):
        return "not a member of a named type"
    for scope, closure in zip(run.scopes, scopes):
        if JUPITER_DISABLED in closure.names:
            return "enclosing type {} is @Disabled".format(scope.name)
    for outer, inner, closure in zip(run.scopes, run.scopes[1:], scopes[1:]):
        inner_class = inner.kind == "class" and outer.kind == "class" and "static" not in inner.modifiers
        if not inner_class or JUPITER_NESTED not in closure.names:
            reason = "enclosing type {} is not a @Nested inner class".format(inner.name)
            composed = closure.hidden_by((JUPITER_NESTED,)) if inner_class else None
            if composed is not None:
                reason += ": composed annotation {} is not RUNTIME-retained".format(composed)
            return reason
    return None


def index_display_names(units: List[JavaUnit]) -> DisplayNames:
    """Sort the JUnit Jupiter ``@DisplayName`` values of ``units`` into Tier 1 test methods and skipped declarations.

    Annotation names, and the head of a dotted name, resolve through the
    unit's own types, its single-type and static imports, the top-level types
    of its package in ``units`` and its on-demand imports. An ``@interface``
    of ``units`` contributes its meta-annotations only when its
    ``java.lang.annotation.Retention`` names ``RetentionPolicy.RUNTIME``; any
    other ``@interface`` ends the chain and is recorded as hidden. A
    ``@DisplayName`` on a method whose argument is not one string literal, or
    on no method or type declaration, raises CheckError naming
    ``<path>:<line>``, as does a ``@Retention`` argument that is not one
    ``RetentionPolicy`` constant on an ``@interface`` reached from a labelled
    method or its enclosing types whose meta-annotations lead to a JUnit
    Jupiter test annotation, ``@Disabled`` or ``@Nested``.
    """
    known: Set[str] = set(JUPITER_TEST_KINDS) | {JUPITER_DISPLAY_NAME, JUPITER_DISABLED, JUPITER_NESTED}
    known |= {JAVA_RETENTION, JAVA_RETENTION_POLICY}
    known |= {"{}.{}".format(JAVA_RETENTION_POLICY, policy) for policy in RETENTION_POLICIES}
    package_types: Dict[Tuple[str, str], str] = {}
    contexts: List[Tuple[JavaUnit, Dict[str, str]]] = []
    for unit in units:
        members: Dict[str, str] = {}
        for scope in unit.types:
            if not scope.qualified:
                continue
            fqcn = unit.fqcn(scope)
            known.add(fqcn)
            members.setdefault(scope.name, fqcn)
            if "." not in scope.qualified:
                package_types.setdefault((unit.package, scope.name), fqcn)
        contexts.append((unit, members))

    def resolve(unit: JavaUnit, members: Dict[str, str], name: str) -> Optional[str]:
        head, dot, rest = name.partition(".")
        base = members.get(head) or unit.single_imports.get(head) or package_types.get((unit.package, head))
        if base is None:
            candidates = {"{}.{}".format(prefix, head) for prefix in unit.on_demand} & known
            base = candidates.pop() if len(candidates) == 1 else None
        if base is None:
            return name if dot else None
        return base + dot + rest

    def resolve_all(unit: JavaUnit, members: Dict[str, str], annotations: List[JavaAnnotation]) -> Set[str]:
        resolved = (resolve(unit, members, annotation.name) for annotation in annotations)
        return {name for name in resolved if name is not None}

    def retention_of(unit: JavaUnit, members: Dict[str, str], scope: JavaScope) -> Tuple[str, str]:
        """The RetentionPolicy constant ``@Retention`` gives ``scope``, CLASS without one.

        An argument that is not one constant gives ``("", "<path>:<line>: <message>")``.
        """
        found = [item for item in scope.annotations if resolve(unit, members, item.name) == JAVA_RETENTION]
        if not found:
            return "CLASS", ""
        where = "{}:{}".format(unit.path, found[-1].line)
        if len(found) > 1:
            return "", "{}: second @Retention on @interface {}".format(where, unit.fqcn(scope))
        match = RETENTION_VALUE_RE.fullmatch(found[0].args) if found[0].args is not None else None
        if match is not None and match.group(1).count("(") == match.group(3).count(")"):
            constant = resolve(unit, members, re.sub(r"\s+", "", match.group(2))) or ""
            prefix = JAVA_RETENTION_POLICY + "."
            policy = constant[len(prefix):] if constant.startswith(prefix) else ""
            if policy in RETENTION_POLICIES:
                return policy, ""
        return "", "{}: @Retention argument of @interface {} is not one {} constant".format(
            where, unit.fqcn(scope), JAVA_RETENTION_POLICY
        )

    meta: Dict[str, Set[str]] = {}
    retention: Dict[str, Tuple[str, str]] = {}
    for unit, members in contexts:
        for scope in unit.types:
            if scope.kind == "@interface" and scope.qualified:
                meta[unit.fqcn(scope)] = resolve_all(unit, members, scope.annotations)
                retention[unit.fqcn(scope)] = retention_of(unit, members, scope)

    carried_cache: Dict[str, Set[str]] = {}

    def carried(name: str) -> Set[str]:
        """JUnit Jupiter names reachable from the meta-annotations of ``@interface`` ``name``, any retention."""
        if name not in carried_cache:
            seen: Set[str] = set()
            todo = list(meta[name])
            while todo:
                current = todo.pop()
                if current not in seen:
                    seen.add(current)
                    todo.extend(meta.get(current, ()))
            carried_cache[name] = seen & JUPITER_RELEVANT
        return carried_cache[name]

    def closure(unit: JavaUnit, members: Dict[str, str], annotations: List[JavaAnnotation]) -> AnnotationClosure:
        found = AnnotationClosure(set())
        todo = list(resolve_all(unit, members, annotations))
        while todo:
            name = todo.pop()
            if name in found.names:
                continue
            found.names.add(name)
            if name not in meta:
                continue
            policy, error = retention[name]
            if policy == "RUNTIME":
                todo.extend(meta[name])
            elif carried(name):
                if error:
                    raise CheckError(error)
                found.hidden[name] = carried(name)
        return found

    result = DisplayNames()
    for unit, members in contexts:
        for run in unit.runs:
            labels = [label for label in run.annotations if resolve(unit, members, label.name) == JUPITER_DISPLAY_NAME]
            if not labels:
                continue
            where = "{}:{}".format(unit.path, labels[0].line)
            if len(labels) > 1:
                raise CheckError("{}:{}: second @DisplayName on one declaration".format(unit.path, labels[1].line))
            declaration = run.declaration
            if declaration.kind == "error":
                raise CheckError("{}: @DisplayName declaration undetermined: {}".format(where, declaration.message))
            if declaration.kind == "other":
                raise CheckError("{}: @DisplayName annotates no method or type declaration".format(where))
            value = display_value(labels[0])
            if declaration.kind == "type":
                if value is not None:
                    result.skipped.setdefault(value, []).append("{} (type-level @DisplayName)".format(where))
                continue
            if value is None:
                raise CheckError("{}: @DisplayName argument is not a single string literal".format(where))
            location = "{}#{}".format(where, declaration.name)
            reason = tier1_rejection(
                run,
                closure(unit, members, run.annotations),
                [closure(unit, members, scope.annotations) for scope in run.scopes],
            )
            if reason is None:
                result.tests.setdefault(value, []).append(location)
            else:
                result.skipped.setdefault(value, []).append("{} ({})".format(location, reason))
    return result


# --------------------------------------------------------------------------
# Markdown helpers
# --------------------------------------------------------------------------


def clean_cell(cell: str) -> str:
    """Unescape ``\\|``, trim, drop backticks, then drop surrounding ``**`` or ``__``."""
    value = cell.replace("\\|", "|").strip().replace("`", "").strip()
    for marker in ("**", "__"):
        if len(value) >= 4 and value.startswith(marker) and value.endswith(marker):
            value = value[2:-2].strip()
    return value


def split_cells(line: str) -> List[str]:
    """Split a table row on unescaped pipes and drop the empty outer cells."""
    text = line.strip()
    parts = CELL_SPLIT_RE.split(text)
    if text.startswith("|") and parts:
        parts = parts[1:]
    if text.endswith("|") and not text.endswith("\\|") and parts:
        parts = parts[:-1]
    return [clean_cell(part) for part in parts]


def normalize_header(cells: Iterable[str]) -> Tuple[str, ...]:
    """Header cells lower-cased with whitespace collapsed."""
    return tuple(" ".join(cell.split()).lower() for cell in cells)


def list_entries(cell: str, whitespace: bool = False, strip_call: bool = False) -> List[str]:
    """Split a list cell; ``n/a``, ``none``, an em dash and ``-`` are no entry."""
    splitter = LIST_SPLIT_WS_RE if whitespace else LIST_SPLIT_RE
    entries: List[str] = []
    for part in splitter.split(cell):
        entry = part.strip()
        if not entry or entry.lower() in NO_ENTRY:
            continue
        if strip_call and entry.endswith("()"):
            entry = entry[:-2].rstrip()
        if entry:
            entries.append(entry)
    return entries


@dataclass
class Table:
    """A Markdown table: normalized header and body rows as (line number, cells)."""

    header: Tuple[str, ...]
    rows: List[Tuple[int, List[str]]]


def parse_tables(lines: List[Tuple[int, str]]) -> List[Table]:
    """Collect header row, delimiter row and body rows of every table in ``lines``."""
    tables: List[Table] = []
    index = 0
    while index < len(lines):
        text = lines[index][1]
        if (
            "|" in text
            and index + 1 < len(lines)
            and "|" in lines[index + 1][1]
            and DELIMITER_ROW_RE.match(lines[index + 1][1])
        ):
            header = normalize_header(split_cells(text))
            rows: List[Tuple[int, List[str]]] = []
            cursor = index + 2
            while cursor < len(lines) and lines[cursor][1].strip() and "|" in lines[cursor][1]:
                rows.append((lines[cursor][0], split_cells(lines[cursor][1])))
                cursor += 1
            tables.append(Table(header, rows))
            index = cursor
        else:
            index += 1
    return tables


def table_rows(tables: List[Table], header: Tuple[str, ...]) -> List[Tuple[int, List[str]]]:
    """Concatenate the body rows of every table whose header equals ``header``.

    Rows are padded to the header width; a row with more cells than the
    header raises CheckError.
    """
    rows: List[Tuple[int, List[str]]] = []
    for table in tables:
        if table.header != header:
            continue
        for lineno, cells in table.rows:
            if len(cells) > len(header):
                raise CheckError(
                    "TRACEABILITY.md:{}: row has {} cells, its table header has {}".format(
                        lineno, len(cells), len(header)
                    )
                )
            rows.append((lineno, cells + [""] * (len(header) - len(cells))))
    return rows


def find_section(lines: List[str], example: str) -> Optional[Tuple[int, int]]:
    """Return (heading index, end index) of ``## <example>-java``; the end is exclusive."""
    heading = re.compile(r"^##[ \t]+`?" + re.escape(example + "-java") + r"`?[ \t]*$")
    starts = [
        index for index, text in enumerate(lines) if heading.match(text.lstrip("\ufeff") if index == 0 else text)
    ]
    if not starts:
        return None
    if len(starts) > 1:
        raise CheckError(
            "TRACEABILITY.md holds the section ## {}-java {} times".format(example, len(starts))
        )
    start = starts[0]
    end = len(lines)
    for index in range(start + 1, len(lines)):
        if lines[index].startswith("# ") or lines[index].startswith("## "):
            end = index
            break
    return start, end


def parse_decisions(text: str) -> Set[str]:
    """D-IDs of DECISIONS.md table rows whose first cell is ``D-nnn``."""
    ids: Set[str] = set()
    for raw in text.split("\n"):
        line = line_content(raw)
        if "|" not in line:
            continue
        cells = split_cells(line)
        if not cells:
            continue
        first = cells[0].replace("*", "").replace("`", "").strip()
        if D_ID_RE.match(first):
            ids.add(first)
    return ids



# --------------------------------------------------------------------------
# RAML 0.8 resource-methods
# --------------------------------------------------------------------------


@dataclass
class RamlMethod:
    """One RAML resource-method with its declared response statuses."""

    raml: str
    method: str
    resource: str
    statuses: List[str] = field(default_factory=list)

    @property
    def identity(self) -> str:
        return "{}#{} {}".format(self.raml, self.method, self.resource)


@dataclass
class _RamlResource:
    indent: int
    path: str
    child: Optional[int] = None


@dataclass
class _RamlMethodContext:
    indent: int
    record: RamlMethod
    child: Optional[int] = None
    responses: Optional[int] = None
    status_indent: Optional[int] = None


@dataclass
class _RamlNode:
    """A property, status or sequence entry below the root, resource, method and responses keys."""

    column: int
    kind: str
    label: str
    lineno: int
    indentless: bool = False
    child: Optional[int] = None


def _bracket_delta(text: str) -> int:
    """Opening minus closing brackets of ``text``, quoted text excluded."""
    depth = 0
    quote: Optional[str] = None
    for char in text:
        if quote is not None:
            if char == quote:
                quote = None
            continue
        if char in "\"'":
            quote = char
        elif char in "{[":
            depth += 1
        elif char in "}]":
            depth -= 1
    return depth


def _indent_of(line: str) -> int:
    """Number of leading whitespace characters."""
    return len(line) - len(line.lstrip())


def parse_raml(text: str, rel_path: str) -> List[RamlMethod]:
    """Return the resource-methods of a RAML 0.8 file read by indentation.

    The first line, after an optional byte-order mark, is the ``#%RAML 0.8``
    header. Blank lines, ``#`` comments and ``---`` are skipped; block scalars
    skip the lines indented deeper than their key; flow collections skip lines
    until their brackets balance; ``!include`` values are not followed. Every
    other line is a ``key:`` line, a ``-`` sequence entry, or a further line of
    a plain scalar indented deeper than the key or entry that starts it; such a
    further line is not only a lowercase method, a three-digit status code or a
    ``/`` token. The keys of the root, of a resource, of a method and of its
    ``responses`` align with their siblings and are a RAML 0.8 property, a
    lowercase method, a ``/`` resource path or a three-digit status code; a
    resource, method or ``responses`` key holds an indented mapping and no
    inline value other than a YAML null, and a ``/`` key stands only in the
    root or a resource. Below these mappings every key or entry aligns with its
    siblings and fits the kind of its parent (RAML_*_KEYS): a status code holds
    only ``description``, ``headers`` and ``body``; a body holds media types
    (keys containing ``/``) and ``schema``, ``example`` and ``formParameters``,
    and a media type holds only those three; ``headers``, ``queryParameters``,
    ``uriParameters``, ``baseUriParameters`` and ``formParameters`` hold named
    parameters, each holding only RAML 0.8 parameter properties; ``enum`` holds
    scalar sequence entries; a scalar property, such as ``title``,
    ``version``, ``baseUri``, ``mediaType``, ``displayName``, ``description``,
    ``schema``, ``example`` or a parameter's ``type``, holds no indented key or
    sequence entry. The content of ``schemas``, ``traits``, ``resourceTypes``,
    ``securitySchemes``, ``documentation``, ``protocols``, ``securedBy``,
    ``is`` and a resource's ``type`` is checked only for the line forms above
    and the ``/`` key rule. Any other line raises CheckError naming
    ``<file>:<line>``, and a file without a resource-method raises CheckError
    naming the file (D-150).
    """
    if text.startswith("\ufeff"):
        text = text[1:]
    lines = text.replace("\r", "").split("\n")
    if lines[0].rstrip() != RAML_HEADER:
        raise CheckError("{}:1: first line is not the RAML 0.8 header {}".format(rel_path, RAML_HEADER))
    resources: List[_RamlResource] = []
    methods: List[RamlMethod] = []
    context: Optional[_RamlMethodContext] = None
    nested: List[_RamlNode] = []
    root: Optional[int] = None
    scalar: Optional[Tuple[int, bool, int]] = None
    index = 1
    while index < len(lines):
        line = lines[index].rstrip()
        lineno = index + 1
        index += 1
        content = line.lstrip()
        if not content:
            continue
        if content.startswith("#") or content == "---":
            scalar = None
            continue
        column = _indent_of(line)
        if scalar is not None and column > scalar[0]:
            if scalar[1] and RAML_KEY_RE.match(line) is None:
                token = re.sub(r"\s+#.*$", "", content)
                if token in RAML_METHODS or RAML_STATUS_RE.match(token) or re.match(r"/\S*$", token):
                    raise CheckError(
                        "{}:{}: plain scalar line {!r} continuing line {} reads as a method, status code or "
                        "resource without its colon".format(rel_path, lineno, token, scalar[2])
                    )
                continue
            raise CheckError(
                "{}:{}: line indented under the value of line {}".format(rel_path, lineno, scalar[2])
            )
        scalar = None
        match = RAML_KEY_RE.match(line)
        item = RAML_ITEM_RE.match(line) if match is None else None
        if match is None and item is None:
            raise CheckError(
                "{}:{}: not a RAML key, sequence entry or plain scalar line".format(rel_path, lineno)
            )
        dashed = match is None or bool(match.group("dash"))
        if match is not None:
            key_indent = len(match.group("indent")) + len(match.group("dash") or "")
            key = match.group("key").strip("\"'")
            value = match.group("value") or ""
        else:
            key_indent = column
            key = ""
            value = item.group("value") or ""
        value = "" if value.startswith("#") else re.sub(r"\s+#.*$", "", value)

        while resources and resources[-1].indent >= column:
            resources.pop()
        if context is not None and column <= context.indent:
            context = None
        if context is not None and context.responses is not None and column <= context.responses:
            context.responses = None
            context.status_indent = None
        if context is not None and context.responses is not None:
            if context.status_indent is None:
                context.status_indent = column
            sibling = context.status_indent
        elif context is not None:
            if context.child is None:
                context.child = column
            sibling = context.child
        elif resources:
            if resources[-1].child is None:
                resources[-1].child = column
            sibling = resources[-1].child
        else:
            if root is None:
                root = column
            sibling = root
        while nested and (
            nested[-1].column > column or (nested[-1].column == column and not (dashed and nested[-1].indentless))
        ):
            nested.pop()
        if column < sibling:
            raise CheckError(
                "{}:{}: indentation does not match the sibling keys at column {}".format(
                    rel_path, lineno, sibling
                )
            )
        if dashed and column == sibling and not nested:
            raise CheckError("{}:{}: sequence entry where a mapping key is expected".format(rel_path, lineno))

        mapping = False
        if not dashed and column == sibling:
            kind: Optional[str] = None
            if context is not None and context.responses is not None:
                if not RAML_STATUS_RE.match(key):
                    raise CheckError("{}:{}: response key {!r} is not a status code".format(rel_path, lineno, key))
                if key not in context.record.statuses:
                    context.record.statuses.append(key)
                kind = "response"
            elif context is not None:
                if key not in RAML_METHOD_KEYS:
                    raise CheckError(
                        "{}:{}: {!r} is not a RAML 0.8 method property".format(rel_path, lineno, key)
                    )
                if key == "responses":
                    mapping = True
                    context.responses = column
                else:
                    kind = RAML_METHOD_KEYS[key]
            elif resources and key in RAML_METHODS:
                mapping = True
                record = RamlMethod(rel_path, key.upper(), resources[-1].path)
                methods.append(record)
                context = _RamlMethodContext(column, record)
            elif key.startswith("/"):
                if not RAML_RESOURCE_RE.match(key):
                    raise CheckError("{}:{}: malformed resource path {!r}".format(rel_path, lineno, key))
                mapping = True
                parent = resources[-1].path if resources else ""
                resources.append(_RamlResource(column, parent + key))
            elif key not in (RAML_RESOURCE_KEYS if resources else RAML_ROOT_KEYS):
                raise CheckError(
                    "{}:{}: {!r} is not a RAML 0.8 {}".format(
                        rel_path,
                        lineno,
                        key,
                        "resource property, method or resource" if resources else "root property or resource",
                    )
                )
            else:
                kind = (RAML_RESOURCE_KEYS if resources else RAML_ROOT_KEYS)[key]
            if kind is not None:
                nested.append(_RamlNode(column, kind, repr(key), lineno, not value))
        elif key.startswith("/"):
            raise CheckError(
                "{}:{}: resource {!r} outside the root and resource mappings".format(rel_path, lineno, key)
            )
        else:
            owner = nested[-1]
            if owner.kind == "scalar":
                raise CheckError(
                    "{}:{}: {} on line {} takes a scalar value, not an indented key or sequence entry".format(
                        rel_path, lineno, owner.label, owner.lineno
                    )
                )
            if owner.kind != "opaque":
                if owner.child is None:
                    owner.child = column
                elif column != owner.child:
                    raise CheckError(
                        "{}:{}: indentation does not match the sibling keys at column {}".format(
                            rel_path, lineno, owner.child
                        )
                    )
                if owner.kind == "sequence":
                    if match is not None:
                        raise CheckError(
                            "{}:{}: {} on line {} takes a sequence of scalars, not a mapping key".format(
                                rel_path, lineno, owner.label, owner.lineno
                            )
                        )
                    nested.append(_RamlNode(column, "scalar", "the sequence entry", lineno))
                elif dashed:
                    raise CheckError(
                        "{}:{}: sequence entry where a mapping key is expected".format(rel_path, lineno)
                    )
                elif owner.kind == "parameters":
                    nested.append(_RamlNode(column, "parameter", repr(key), lineno, not value))
                elif owner.kind == "body" and "/" in key:
                    nested.append(_RamlNode(column, "media type", repr(key), lineno, not value))
                else:
                    properties, noun = RAML_NESTED_KEYS[owner.kind]
                    if key not in properties:
                        raise CheckError("{}:{}: {!r} is not a RAML 0.8 {}".format(rel_path, lineno, key, noun))
                    nested.append(_RamlNode(column, properties[key], repr(key), lineno, not value))
        if mapping and value not in RAML_NULL_VALUES:
            raise CheckError(
                "{}:{}: {!r} takes an indented mapping, not an inline value".format(rel_path, lineno, key)
            )

        if BLOCK_SCALAR_RE.match(value):
            while index < len(lines):
                following = lines[index]
                if not following.strip() or _indent_of(following) > key_indent:
                    index += 1
                    continue
                break
        elif value.startswith("{") or value.startswith("["):
            depth = _bracket_delta(value)
            while depth > 0:
                if index >= len(lines):
                    raise CheckError("unbalanced flow collection in {}:{}".format(rel_path, lineno))
                depth += _bracket_delta(lines[index])
                index += 1
            scalar = (key_indent, False, lineno)
        elif value:
            scalar = (key_indent, not value.startswith(("\"", "'")), lineno)
    if not methods:
        raise CheckError("{}: declares no resource-method".format(rel_path))
    return methods


def raml_minimum(example: str, methods: List[RamlMethod]) -> List[str]:
    """Mechanical RAML scenario identities: one per declared status plus the router cases."""
    identities: List[str] = []
    for method in methods:
        segments = [part.replace("{", "").replace("}", "").lower() for part in method.resource.split("/")]
        stem = "-".join([method.method.lower()] + [part for part in segments if part])
        for status in method.statuses:
            identities.append("{}_{}-{}".format(example, stem, status))
    identities.extend("{}_{}".format(example, case) for case in ROUTER_SCENARIOS)
    return list(dict.fromkeys(identities))


# --------------------------------------------------------------------------
# Source set
# --------------------------------------------------------------------------


@dataclass
class BindItem:
    """An element that needs binding through locators under ``rule``."""

    element: Element
    rule: str

    @property
    def locator(self) -> str:
        return self.element.locator


@dataclass
class SourceModel:
    """Identities parsed from the original example, its inventory entries and the scenario list.

    ``id_entries`` and ``mel_lines`` are the example's ID_INVENTORY entries and
    MEL_INVENTORY lines; ``configs`` are the active configurations and
    ``start_lines`` the start-tag lines of their elements. ``scenarios`` are
    the example's scenario identities of the scenario inventory;
    ``scenario_lines`` the ``(line, identity)`` entries of its SCENARIOS.txt at
    ``scenario_path`` (read in a non-RAML-backed project only) and
    ``scenario_file_present`` whether that file exists. ``branch_map`` maps the
    locator of each when, otherwise and outermost filter to its outcomes and
    their scenario identities.
    """

    example: str
    exact: List[str]
    ambiguous: List[str]
    bind_items: List[BindItem]
    anchorable: Set[str]
    raml_files: List[str]
    raml_methods: List[RamlMethod]
    scenarios: List[str]
    id_entries: Dict[str, Tuple[str, int, int]] = field(default_factory=dict)
    mel_lines: List[Tuple[str, int]] = field(default_factory=list)
    configs: List[str] = field(default_factory=list)
    start_lines: Set[Tuple[str, int]] = field(default_factory=set)
    scenario_path: str = ""
    scenario_lines: List[Tuple[int, str]] = field(default_factory=list)
    scenario_file_present: bool = False
    branch_map: Dict[str, Dict[str, Tuple[str, ...]]] = field(default_factory=dict)

    def __post_init__(self) -> None:
        self.exact_set: Set[str] = set(self.exact)
        self.unique_exact: List[str] = list(dict.fromkeys(self.exact))
        self.bind_by_line: Dict[Tuple[str, int], List[BindItem]] = defaultdict(list)
        for item in self.bind_items:
            self.bind_by_line[(item.element.path, item.element.line)].append(item)
        self.scenario_set: Set[str] = set(self.scenarios)
        self.raml_backed = bool(self.raml_files)
        self.raml_minimum: List[str] = (
            raml_minimum(self.example, self.raml_methods) if self.raml_backed else []
        )
        self.id_by_identity: Dict[str, str] = {
            id_identity(key, entry): key for key, entry in self.id_entries.items()
        }
        self.mel_set: Set[str] = {"{}:{}".format(path, line) for path, line in self.mel_lines}
        self.config_set: Set[str] = set(self.configs)
        self.inventory_lines: Set[Tuple[str, int]] = set(self.mel_lines) | {
            (path, start) for path, start, _end in self.id_entries.values()
        }


def rel_path(repo: Path, path: Path) -> str:
    """Repository-relative POSIX path of ``path``."""
    return path.relative_to(repo).as_posix()


def id_identity(key: str, entry: Tuple[str, int, int]) -> str:
    """Forward identity ``<ID> <path>:<first line>`` of an ID_INVENTORY entry."""
    return "{} {}:{}".format(key, entry[0], entry[1])


def file_lines(path: Path) -> Optional[List[str]]:
    """Lines of ``path`` without terminators, or None when ``path`` is not a file."""
    if not path.is_file():
        return None
    return read_text(path, errors="replace").replace("\r\n", "\n").replace("\r", "\n").split("\n")


def element_name(element: Element) -> str:
    """The ``name`` attribute of a flow, sub-flow or batch element; absence raises CheckError."""
    name = element.attrib.get("name")
    if name is None:
        raise CheckError("<{}> at {} has no name attribute".format(element.raw_qname, element.locator))
    return name


def strategy_branches(element: Element, base: str) -> List[str]:
    """Branch identities ``<base>/<n>`` of a choice or APIkit mapping strategy."""
    if element.local == "choice-exception-strategy":
        branches = [child for child in element.children if child.local.endswith("-exception-strategy")]
    elif element.local == "mapping-exception-strategy" and element.ns.endswith("/apikit"):
        branches = [child for child in element.children if child.local == "mapping" and child.ns == element.ns]
    else:
        return []
    identities: List[str] = []
    for number, branch in enumerate(branches, 1):
        identity = "{}/{}".format(base, number)
        identities.append(identity)
        identities.extend(strategy_branches(branch, identity))
    return identities


def outermost_filter(element: Element) -> Element:
    """The topmost ``*-filter`` element among ``element`` and its ancestors (``element`` itself when none is)."""
    outermost = element
    ancestor = element.parent
    while ancestor is not None:
        if ancestor.local.endswith("-filter"):
            outermost = ancestor
        ancestor = ancestor.parent
    return outermost


def bind_rule(element: Element) -> Optional[str]:
    """The binding rule of an element, or None when it needs no binding."""
    if element.ns.endswith("/ee/dw") and element.local in ("set-payload", "set-variable", "set-property"):
        return "exactly-one-DW"
    if element.ns.endswith("/scripting"):
        ancestor = element.parent
        while ancestor is not None:
            if ancestor.ns.endswith("/scripting"):
                return None
            ancestor = ancestor.parent
        return "exactly-one-SC"
    if element.ns == CORE_NS and element.local == "expression-component":
        return "one-or-more"
    if element.ns == CORE_NS and element.local in ("when", "otherwise"):
        return "branch"
    if element.local.endswith("-filter"):
        return "nested-filter" if outermost_filter(element) is not element else "filter"
    if element.ns == CORE_NS and element.local == "choice":
        return "choice"
    if element.ns.endswith("/apikit") and element.local == "flow-mapping":
        return "flow-mapping"
    return None


def is_config_anchor(element: Element) -> bool:
    """True for a direct child of the root ``<mule>`` other than a flow, sub-flow, batch job or strategy."""
    root = element.parent
    if root is None or root.parent is not None or root.ns != CORE_NS or root.local != "mule":
        return False
    if element.ns == CORE_NS and element.local in ("flow", "sub-flow"):
        return False
    if element.ns.endswith("/batch") and element.local == "job":
        return False
    return not element.local.endswith("-exception-strategy")


def check_inventory(
    repo: Path,
    bind_items: List[BindItem],
    id_entries: Dict[str, Tuple[str, int, int]],
    mel_lines: List[Tuple[str, int]],
) -> None:
    """Compare the example's inventory entries with its parsed source; a mismatch raises CheckError.

    Every DataWeave setter and outermost scripting element starts on the first
    line of exactly one DW or SC entry of its kind, and no entry starts two of
    them; every DW entry starts on a setter. Every entry spans lines of an
    existing file of the example folder with a non-blank first line, and every
    MEL line is a non-blank line of its file.
    """
    starts: Dict[Tuple[str, int], List[str]] = defaultdict(list)
    for key, (path, start, _end) in id_entries.items():
        starts[(path, start)].append(key)
    matched: Dict[str, BindItem] = {}
    for item in bind_items:
        if item.rule not in ("exactly-one-DW", "exactly-one-SC"):
            continue
        prefix = item.rule[-2:] + "-"
        keys = sorted(key for key in starts.get((item.element.path, item.element.line), []) if key.startswith(prefix))
        if len(keys) != 1:
            raise CheckError(
                "inventory mismatch: <{}> at {} starts {} of the transform inventory (D-149)".format(
                    item.element.raw_qname, item.locator, " and ".join(keys) if keys else "no " + prefix + "nn entry"
                )
            )
        first = matched.get(keys[0])
        if first is not None:
            raise CheckError(
                "inventory mismatch: {} starts both <{}> at {} and <{}> at {}".format(
                    keys[0], first.element.raw_qname, first.locator, item.element.raw_qname, item.locator
                )
            )
        matched[keys[0]] = item
    cache: Dict[str, Optional[List[str]]] = {}

    def check_lines(label: str, path: str, start: int, end: int) -> None:
        if path not in cache:
            cache[path] = file_lines(repo / path)
        lines = cache[path]
        if lines is None:
            raise CheckError("inventory mismatch: {} names {}, which is not a file".format(label, path))
        if not 1 <= start <= end <= len(lines):
            raise CheckError("inventory mismatch: {} lies outside the {} lines of {}".format(label, len(lines), path))
        if not lines[start - 1].strip():
            raise CheckError("inventory mismatch: {} starts on a blank line".format(label))

    for key, (path, start, end) in sorted(id_entries.items()):
        label = "{} {}:{}-{}".format(key, path, start, end)
        if key.startswith("DW-") and key not in matched:
            raise CheckError("inventory mismatch: {} starts on no DataWeave setter".format(label))
        check_lines(label, path, start, end)
    for path, line in mel_lines:
        check_lines("MEL line {}:{}".format(path, line), path, line, line)


def inventory_scenarios(example: str, raml_files: List[str]) -> List[str]:
    """The example's scenario identities of the scenario inventory (D-151).

    A non-RAML-backed example takes its SCENARIO_INVENTORY entry and a
    RAML-backed one its RAML_SCENARIO_INVENTORY entry. A missing entry, an
    entry in the other table and a suffix that gives no unique
    ``<example>_<scenario>`` identity raise CheckError.
    """
    table, other = (
        (RAML_SCENARIO_INVENTORY, SCENARIO_INVENTORY) if raml_files else (SCENARIO_INVENTORY, RAML_SCENARIO_INVENTORY)
    )
    suffixes = table.get(example)
    if suffixes is None:
        if example in other:
            raise CheckError(
                "inventory mismatch: the scenario inventory (D-151) lists {} as {}, but {}".format(
                    example,
                    "not RAML-backed" if raml_files else "RAML-backed",
                    ("it has RAML files " + ", ".join(raml_files)) if raml_files else "it has no RAML file",
                )
            )
        raise CheckError("the scenario inventory (D-151) has no entry for {}".format(example))
    identities = ["{}_{}".format(example, suffix) for suffix in suffixes]
    for identity in identities:
        if not SCENARIO_RE.match(identity):
            raise CheckError("inventory mismatch: scenario {} is no <example>_<scenario> identity".format(identity))
    if len(set(identities)) != len(identities):
        raise CheckError("inventory mismatch: the scenario inventory entry of {} repeats a scenario".format(example))
    return identities


def check_branch_inventory(
    example: str, bind_items: List[BindItem], scenarios: Set[str]
) -> Dict[str, Dict[str, Tuple[str, ...]]]:
    """Compare the example's BRANCH_INVENTORY entries with its parsed branches; return them as identities.

    Every when, otherwise and outermost filter of the active configurations
    starts on a line of its own and has exactly one entry; every entry of the
    example names such an element; a when or otherwise has the outcome
    ``branch``, a filter the outcomes ``reject`` and ``accept``; every outcome
    lists one or more distinct scenarios of ``scenarios``, and no scenario is
    both rejected and accepted by one filter. A violation raises CheckError.
    """
    prefix = example + "/"
    entries = {key: value for key, value in BRANCH_INVENTORY.items() if key.startswith(prefix)}
    items: Dict[str, BindItem] = {}
    for item in bind_items:
        if item.rule not in BRANCH_OUTCOMES:
            continue
        first = items.get(item.locator)
        if first is not None:
            raise CheckError(
                "inventory mismatch: <{}> and <{}> both start on {} (D-151)".format(
                    first.element.raw_qname, item.element.raw_qname, item.locator
                )
            )
        items[item.locator] = item
    branch_map: Dict[str, Dict[str, Tuple[str, ...]]] = {}
    for locator, item in items.items():
        label = "<{}> at {}".format(item.element.raw_qname, locator)
        entry = entries.get(locator)
        if entry is None:
            raise CheckError("inventory mismatch: {} has no branch inventory entry (D-151)".format(label))
        outcomes = BRANCH_OUTCOMES[item.rule]
        if sorted(entry) != sorted(outcomes):
            raise CheckError(
                "inventory mismatch: the branch inventory entry of {} has the outcomes {}, not {}".format(
                    label, ", ".join(sorted(entry)) or "(none)", ", ".join(outcomes)
                )
            )
        mapped: Dict[str, Tuple[str, ...]] = {}
        for outcome in outcomes:
            identities = tuple("{}_{}".format(example, suffix) for suffix in entry[outcome])
            if not identities or len(set(identities)) != len(identities):
                raise CheckError(
                    "inventory mismatch: the {} outcome of {} lists {} scenario".format(
                        outcome, label, "a repeated" if identities else "no"
                    )
                )
            unknown = [identity for identity in identities if identity not in scenarios]
            if unknown:
                raise CheckError(
                    "inventory mismatch: the {} outcome of {} names {}, no scenario of {}".format(
                        outcome, label, ", ".join(unknown), example
                    )
                )
            mapped[outcome] = identities
        both = sorted(set(mapped.get("reject", ())) & set(mapped.get("accept", ())))
        if both:
            raise CheckError("inventory mismatch: {} both rejects and accepts {}".format(label, ", ".join(both)))
        branch_map[locator] = mapped
    stray = sorted(set(entries) - set(items))
    if stray:
        raise CheckError(
            "inventory mismatch: branch inventory entry {} names no when, otherwise or outermost *-filter "
            "start tag of an active configuration of {} (D-151)".format(stray[0], example)
        )
    return branch_map


def build_source_model(repo: Path, example: str, project: Path) -> SourceModel:
    """Parse the original example into S_exact, S_bind and the anchorable set; check its inventories.

    The scenario identities of S_exact come from the scenario inventory
    (D-151). A non-RAML-backed project's SCENARIOS.txt is read: a missing or
    unreadable file, or one without an identity line, raises CheckError. A
    RAML-backed project's SCENARIOS.txt is only noted as present or absent.
    """
    example_dir = repo / example
    app_dir = example_dir / "src" / "main" / "app"
    deploy = app_dir / "mule-deploy.properties"
    deploy_rel = rel_path(repo, deploy)
    if not deploy.is_file():
        raise CheckError("{} missing".format(deploy_rel))
    resources = [
        name.strip()
        for name in parse_properties(read_text(deploy)).get("config.resources", "").split(",")
        if name.strip()
    ]
    if not resources:
        raise CheckError("{} has no config.resources".format(deploy_rel))
    elements: List[Element] = []
    configs: List[str] = []
    for name in resources:
        config = app_dir / name
        config_rel = rel_path(repo, config)
        if not config.is_file():
            raise CheckError("config {} listed in config.resources is missing".format(config_rel))
        elements.extend(parse_mule_xml(config, config_rel))
        configs.append(config_rel)

    exact: List[str] = [deploy_rel]
    for element in elements:
        if element.ns == CORE_NS and element.local in ("flow", "sub-flow"):
            exact.append("{}#{}:{}".format(element.path, element.local, element_name(element)))
        elif element.ns.endswith("/batch") and element.local in ("job", "step"):
            exact.append("{}#batch:{}:{}".format(element.path, element.local, element_name(element)))
    for element in elements:
        if not element.local.endswith("-exception-strategy"):
            continue
        if element.parent is not None and element.parent.local == "choice-exception-strategy":
            continue
        base = "{}#{}:{}".format(element.path, element.qname, element.line)
        exact.append(base)
        exact.extend(strategy_branches(element, base))

    prefix = example + "/"
    id_entries = {key: entry for key, entry in ID_INVENTORY.items() if entry[0].startswith(prefix)}
    mel_lines = [(path, line) for path, lines in MEL_INVENTORY.items() if path.startswith(prefix) for line in lines]
    exact.extend(id_identity(key, entry) for key, entry in sorted(id_entries.items()))
    exact.extend("{}:{}".format(path, line) for path, line in mel_lines)

    raml_paths = sorted(
        set(sorted_files(example_dir / "src" / "main" / "api", "**/*.raml"))
        | set(sorted_files(example_dir / "src" / "main" / "resources", "**/*.raml")),
        key=lambda p: p.as_posix(),
    )
    raml_files = [rel_path(repo, path) for path in raml_paths]
    raml_methods: List[RamlMethod] = []
    for path, raml_rel in zip(raml_paths, raml_files):
        raml_methods.extend(parse_raml(read_text(path, encoding="utf-8-sig", errors="replace"), raml_rel))
    exact.extend(method.identity for method in raml_methods)

    for path in sorted_files(example_dir / "src" / "main" / "java", "**/*.java"):
        exact.append(java_fqcn(path))

    for path in sorted_files(example_dir / "src" / "test" / "java", "**/*.java"):
        stripped = strip_java_comments(read_text(path, errors="replace"))
        fqcn = java_fqcn(path, stripped)
        exact.extend("{}#{}".format(fqcn, match.group(1)) for match in JUNIT_TEST_RE.finditer(stripped))

    for path in sorted_files(example_dir / "src" / "test" / "munit", "*.xml"):
        munit_rel = rel_path(repo, path)
        try:
            root = ET.parse(str(path)).getroot()
        except (ET.ParseError, LookupError) as exc:
            raise CheckError("XML parse error in {}: {}".format(munit_rel, exc)) from None
        except OSError as exc:
            raise CheckError("cannot read {}: {}".format(munit_rel, exc)) from None
        for test in root.iter("{{{}}}test".format(MUNIT_NS)):
            name = test.get("name")
            if name is None:
                raise CheckError("munit:test without name in {}".format(munit_rel))
            exact.append("{}#{}".format(munit_rel, name))

    scenarios = inventory_scenarios(example, raml_files)
    exact.extend(scenarios)
    scenarios_path = project / "src" / "test" / "resources" / "fixtures" / "SCENARIOS.txt"
    scenario_rel = rel_path(repo, scenarios_path)
    scenario_lines: List[Tuple[int, str]] = []
    scenario_file_present = os.path.lexists(str(scenarios_path))
    if not raml_files:
        if not scenarios_path.is_file():
            raise CheckError(
                "{} missing or not a regular file (required in a non-RAML-backed project, D-151)".format(scenario_rel)
            )
        for number, raw in enumerate(read_text(scenarios_path, encoding="utf-8-sig").split("\n"), 1):
            entry = raw.strip()
            if entry and not entry.startswith("#"):
                scenario_lines.append((number, entry))
        if not scenario_lines:
            raise CheckError("{} lists no scenario identity (D-151)".format(scenario_rel))

    seen: Set[str] = set()
    ambiguous: List[str] = []
    for identity in exact:
        if identity in seen:
            ambiguous.append(identity)
        seen.add(identity)

    bind_items: List[BindItem] = []
    anchorable: Set[str] = set()
    start_lines: Set[Tuple[str, int]] = set()
    for element in elements:
        rule = bind_rule(element)
        if rule is not None:
            bind_items.append(BindItem(element, rule))
        start_lines.add((element.path, element.line))
        if is_config_anchor(element):
            anchorable.add("{}#{}:{}".format(element.path, element.qname, element.line))
            if "name" in element.attrib:
                anchorable.add("{}#{}:{}".format(element.path, element.qname, element.attrib["name"]))
    check_inventory(repo, bind_items, id_entries, mel_lines)
    scenario_universe = set(scenarios) | set(raml_minimum(example, raml_methods) if raml_files else [])
    branch_map = check_branch_inventory(example, bind_items, scenario_universe)

    return SourceModel(
        example=example,
        exact=exact,
        ambiguous=ambiguous,
        bind_items=bind_items,
        anchorable=anchorable,
        raml_files=raml_files,
        raml_methods=raml_methods,
        scenarios=scenarios,
        id_entries=id_entries,
        mel_lines=mel_lines,
        configs=configs,
        start_lines=start_lines,
        scenario_path=scenario_rel,
        scenario_lines=scenario_lines,
        scenario_file_present=scenario_file_present,
        branch_map=branch_map,
    )



# --------------------------------------------------------------------------
# TRACEABILITY.md section
# --------------------------------------------------------------------------


@dataclass(eq=False)
class ForwardRow:
    """A forward-table row; ``kind`` is exact, id, locator, scenario, anchor, rejected or extra."""

    lineno: int
    identity: str
    locators: List[str]
    targets: List[str]
    tests: List[str]
    decision: str
    duplicate: bool = False
    kind: str = "extra"
    id_key: Optional[str] = None
    valid: bool = True

    @property
    def label(self) -> str:
        return self.identity or "(empty identity, TRACEABILITY.md:{})".format(self.lineno)


@dataclass(eq=False)
class BackwardRow:
    """A backward-table row: target identity and its links."""

    lineno: int
    identity: str
    links: List[str]


@dataclass(eq=False)
class GeneratedRow:
    """A generated-types row: WSDL or XSD, packages, classes and replaced source identities."""

    lineno: int
    source: str
    packages: List[str]
    classes: List[str]
    replaces: List[str]


@dataclass
class Matrix:
    """The three tables of one TRACEABILITY.md section."""

    forward: List[ForwardRow]
    backward: List[BackwardRow]
    generated: List[GeneratedRow]


def capture_pattern(example: str) -> re.Pattern[str]:
    """Capture-harness path, optionally prefixed with ``<example>-java/``."""
    return re.compile(r"^(?:" + re.escape(example) + r"-java/)?(src/test/capture/(?:[^/]+/)*[^/]+\.java)$")


def normalize_capture(entry: str, pattern: re.Pattern[str]) -> str:
    """Project-relative ``src/test/capture/...`` form of a capture path; other entries unchanged."""
    match = pattern.match(entry)
    return match.group(1) if match else entry


def parse_matrix(section: List[Tuple[int, str]], example: str) -> Matrix:
    """Read the forward, backward and generated-types tables of one section."""
    tables = parse_tables(section)
    capture = capture_pattern(example)
    forward: List[ForwardRow] = []
    for lineno, cells in table_rows(tables, FORWARD_HEADER):
        identity = cells[0].strip()
        locators: List[str] = []
        group = LOCATOR_GROUP_RE.match(identity)
        if group:
            identity = group.group("identity").strip()
            locators = [part.strip() for part in re.split(r"[;,]", group.group("group")) if part.strip()]
        forward.append(
            ForwardRow(
                lineno=lineno,
                identity=identity,
                locators=locators,
                targets=list_entries(cells[1], strip_call=True),
                tests=list_entries(cells[2], strip_call=True),
                decision=cells[3],
            )
        )
    backward: List[BackwardRow] = []
    for lineno, cells in table_rows(tables, BACKWARD_HEADER):
        backward.append(BackwardRow(lineno, normalize_capture(cells[0].strip(), capture), list_entries(cells[1])))
    generated: List[GeneratedRow] = []
    for lineno, cells in table_rows(tables, GENERATED_HEADER):
        generated.append(
            GeneratedRow(
                lineno=lineno,
                source=cells[0],
                packages=list_entries(cells[1]),
                classes=list_entries(cells[2], whitespace=True),
                replaces=list_entries(cells[3]),
            )
        )
    return Matrix(forward, backward, generated)


# --------------------------------------------------------------------------
# Compiled output through javap
# --------------------------------------------------------------------------


@dataclass
class ClassInfo:
    """Members of one class file as listed by ``javap -p -v``.

    ``methods`` holds every method name, ``public_methods`` the public ones required as backward rows.
    ``line_accessors`` maps each record accessor whose compiled form and line match an implicit one to that
    line, outside ``public_methods`` until a source check; ``source_file`` is the ``SourceFile`` attribute.

    See DECISIONS.md D-144.
    """

    name: str
    kind: str
    methods: Set[str] = field(default_factory=set)
    public_methods: Set[str] = field(default_factory=set)
    line_accessors: Dict[str, int] = field(default_factory=dict)
    source_file: Optional[str] = None


def _is_member_declaration(line: str) -> bool:
    """True for a two-space-indented javap member declaration ending with ``;``."""
    return len(line) > 2 and line.startswith("  ") and not line[2].isspace() and line.endswith(";")


def _decode_javap_name(token: str) -> str:
    """Internal class name of a javap class-name token, without javap's quotes and backslash escapes.

    See DECISIONS.md D-144.
    """
    if len(token) >= 2 and token.startswith('"') and token.endswith('"'):
        return JAVAP_ESCAPE_RE.sub(lambda match: JAVAP_ESCAPES.get(match.group(1), match.group(1)), token[1:-1])
    return token


def _javap_attributes(lines: List[str]) -> Dict[str, List[str]]:
    """Class attributes printed after the member list: first occurrence of each name to its indented lines."""
    attributes: Dict[str, List[str]] = {}
    current: Optional[List[str]] = None
    for line in lines:
        if not line:
            continue
        if not line[0].isspace():
            current = []
            attributes.setdefault(line.split(":", 1)[0], current)
        elif current is not None:
            current.append(line)
    return attributes


def _javap_code(body: List[str]) -> Tuple[List[Tuple[str, str]], Set[int]]:
    """``(mnemonic, operand)`` instructions of a method's ``Code`` and the lines of its ``LineNumberTable``."""
    instructions: List[Tuple[str, str]] = []
    line_numbers: Set[int] = set()
    section = ""
    for line in body:
        text = line.strip()
        if len(line) - len(text) <= 6 and JAVAP_SECTION_RE.match(text):
            section = text.split(":", 1)[0]
        elif section == "Code":
            instruction = JAVAP_INSTRUCTION_RE.match(text)
            if instruction:
                instructions.append((instruction.group(1), instruction.group(2).strip()))
        elif section == "LineNumberTable":
            number = JAVAP_LINE_NUMBER_RE.match(text)
            if number:
                line_numbers.add(int(number.group(1)))
    return instructions, line_numbers


def _record_components(lines: List[str]) -> Dict[str, str]:
    """Component name to field descriptor, from the lines of a ``Record`` attribute."""
    components: Dict[str, str] = {}
    name: Optional[str] = None
    for line in lines:
        if _is_member_declaration(line):
            tokens = line[:-1].split()
            name = tokens[-1] if tokens else None
        elif name is not None and line.startswith("    descriptor:") and name not in components:
            components[name] = line.strip()[len("descriptor:"):].strip()
            name = None
    return components


def _implicit_members(
    kind: str, internal: str, methods: List[Tuple[str, str, Set[str], List[str]]], trailer: List[str]
) -> Tuple[Set[int], Dict[int, int]]:
    """Positions in ``methods`` of members javac declares implicitly, by their compiled form.

    The set holds the members proven by their code. Enum: static ``values()`` returning
    ``[L<internal>;`` and static ``valueOf(String)`` returning ``L<internal>;``. Record: ``equals``,
    ``hashCode`` and ``toString`` whose code runs an ``invokedynamic`` bootstrapped by
    ``java/lang/runtime/ObjectMethods.bootstrap``. The mapping holds the record's public instance
    accessors of a ``Record`` component whose code is ``aload_0``, ``getfield`` of that component and
    the matching return, compiled at the single line those members carry, each mapped to that line.
    An explicit accessor written on that line with ``return <component>;`` enters the mapping too.

    See DECISIONS.md D-144.
    """
    implicit: Set[int] = set()
    line_accessors: Dict[int, int] = {}
    if kind == "enum":
        signatures = {("values", "()[L{};".format(internal)), ("valueOf", "(Ljava/lang/String;)L{};".format(internal))}
        for position, (name, descriptor, flags, _body) in enumerate(methods):
            if "ACC_STATIC" in flags and (name, descriptor) in signatures:
                implicit.add(position)
        return implicit, line_accessors
    if kind != "record":
        return implicit, line_accessors
    attributes = _javap_attributes(trailer)
    bootstraps: Dict[str, str] = {}
    for line in attributes.get("BootstrapMethods", []):
        entry = JAVAP_BOOTSTRAP_RE.match(line)
        if entry:
            bootstraps[entry.group(1)] = entry.group(2)
    components = _record_components(attributes.get("Record", []))
    codes = [_javap_code(body) for _name, _descriptor, _flags, body in methods]
    record_lines: Set[int] = set()
    for position, (name, descriptor, flags, _body) in enumerate(methods):
        if RECORD_OBJECT_METHODS.get(name) != descriptor or "ACC_STATIC" in flags:
            continue
        for mnemonic, operand in codes[position][0]:
            indy = JAVAP_INDY_RE.search(operand) if mnemonic == "invokedynamic" else None
            bootstrap = bootstraps.get(indy.group(1), "") if indy else ""
            if indy and indy.group(2) == name and bootstrap.startswith(OBJECT_METHODS_BOOTSTRAP):
                implicit.add(position)
                record_lines |= codes[position][1]
                break
    if len(record_lines) != 1:
        return implicit, line_accessors
    record_line = next(iter(record_lines))
    for position, (name, descriptor, flags, _body) in enumerate(methods):
        component = components.get(name)
        instructions, line_numbers = codes[position]
        if (
            component is None
            or "ACC_PUBLIC" not in flags
            or "ACC_STATIC" in flags
            or descriptor != "()" + component
            or line_numbers != record_lines
            or len(instructions) != 3
        ):
            continue
        field_ref = JAVAP_FIELD_REF_RE.match(instructions[1][1]) if instructions[1][0] == "getfield" else None
        if (
            instructions[0] == ("aload_0", "")
            and field_ref is not None
            and field_ref.group(1) in ("{}:{}".format(name, component), "{}.{}:{}".format(internal, name, component))
            and instructions[2] == (RETURN_OPCODES.get(component, "areturn"), "")
        ):
            line_accessors[position] = record_line
    return implicit, line_accessors


def parse_javap_block(block: str) -> ClassInfo:
    """Parse the text of one ``Classfile`` block of ``javap -p -v``.

    Every method that is not synthetic or a bridge enters ``methods``; public ones enter ``public_methods``
    except the members ``_implicit_members`` proves implicit by code and the accessors it proves by line,
    which enter ``line_accessors``. The ``SourceFile`` attribute value, without its quotes, is ``source_file``.

    See DECISIONS.md D-144.
    """
    lines = [line.rstrip() for line in block.split("\n")]
    origin = lines[0].strip() if lines else "(unknown class file)"
    this_internal: Optional[str] = None
    super_name: Optional[str] = None
    header = ""
    open_index: Optional[int] = None
    for index in range(1, len(lines)):
        line = lines[index]
        if line == "{":
            open_index = index
            break
        this_match = THIS_CLASS_RE.match(line)
        if this_match and this_internal is None:
            this_internal = _decode_javap_name(this_match.group(1))
        super_match = SUPER_CLASS_RE.match(line)
        if super_match and super_name is None:
            super_name = _decode_javap_name(super_match.group(1)).replace("/", ".")
        if not header and line and not line[0].isspace() and line != "Constant pool:":
            header = line
    if this_internal is None or open_index is None:
        raise CheckError("unparseable javap output for {}: this_class or member list missing".format(origin))
    this_name = this_internal.replace("/", ".")
    close_index: Optional[int] = None
    for index in range(open_index + 1, len(lines)):
        if lines[index] == "}":
            close_index = index
            break
    if close_index is None:
        raise CheckError("unparseable javap output for {}: member list not closed".format(origin))

    if super_name == "java.lang.Enum" or " extends java.lang.Enum" in header:
        kind = "enum"
    elif super_name == "java.lang.Record" or " extends java.lang.Record" in header:
        kind = "record"
    elif re.search(r"\binterface\b", header):
        kind = "interface"
    else:
        kind = "class"

    methods: List[Tuple[str, str, Set[str], List[str]]] = []
    index = open_index + 1
    while index < close_index:
        line = lines[index]
        if not _is_member_declaration(line):
            index += 1
            continue
        declaration = line.strip()
        descriptor: Optional[str] = None
        flags: Optional[str] = None
        cursor = index + 1
        while cursor < close_index and not _is_member_declaration(lines[cursor]):
            stripped = lines[cursor].strip()
            if descriptor is None and stripped.startswith("descriptor:"):
                descriptor = stripped[len("descriptor:"):].strip()
            elif flags is None and stripped.startswith("flags:"):
                flags = stripped[len("flags:"):].strip()
            cursor += 1
        body = lines[index + 1:cursor]
        index = cursor
        if descriptor is None or flags is None:
            raise CheckError(
                "unparseable javap output for {}: member '{}' lacks descriptor or flags".format(this_name, declaration)
            )
        flag_set = set(re.findall(r"ACC_[A-Z_]+", flags))
        if "ACC_SYNTHETIC" in flag_set or "ACC_BRIDGE" in flag_set:
            continue
        if not descriptor.startswith("(") or declaration == "static {};":
            continue
        head = declaration.split("(", 1)[0].split()
        name = head[-1] if head else ""
        if not name or "." in name or name == this_name:
            continue
        methods.append((name, descriptor, flag_set, body))

    info = ClassInfo(this_name, kind)
    trailer = lines[close_index + 1:]
    for line in trailer:
        source_match = JAVAP_SOURCE_FILE_RE.match(line)
        if source_match:
            info.source_file = source_match.group(1)
            break
    implicit, line_accessors = _implicit_members(kind, this_internal, methods, trailer)
    for position, (name, _descriptor, flag_set, _body) in enumerate(methods):
        info.methods.add(name)
        if position in line_accessors:
            info.line_accessors[name] = line_accessors[position]
        elif "ACC_PUBLIC" in flag_set and position not in implicit:
            info.public_methods.add(name)
    return info


def parse_javap_output(text: str) -> List[ClassInfo]:
    """Split ``javap -p -v`` output on ``Classfile`` lines and parse each block."""
    return [parse_javap_block(block) for block in re.split(r"(?m)^Classfile ", text)[1:]]


def resolve_javap(option: Optional[str]) -> str:
    """``--javap``, else ``$JAVA_HOME/bin/javap``, else ``javap`` on PATH."""
    if option:
        if os.path.isfile(option) and os.access(option, os.X_OK):
            return option
        found = shutil.which(option)
        if found:
            return found
        raise CheckError("javap not found: {}".format(option))
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / "javap"
        if candidate.is_file():
            return str(candidate)
    found = shutil.which("javap")
    if found:
        return found
    raise CheckError("javap not found: set JAVA_HOME or pass --javap")


def run_javap(javap: str, files: List[Path], label: str) -> List[ClassInfo]:
    """Run ``javap -p -v`` in batches; one progress line per batch on stderr."""
    infos: List[ClassInfo] = []
    batches = [files[start:start + JAVAP_BATCH] for start in range(0, len(files), JAVAP_BATCH)]
    for number, batch in enumerate(batches, 1):
        print(
            "javap {} batch {}/{}: {} class files".format(label, number, len(batches), len(batch)),
            file=sys.stderr,
            flush=True,
        )
        try:
            completed = subprocess.run(
                [javap, "-p", "-v"] + [str(path) for path in batch],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=JAVAP_TIMEOUT,
                check=False,
            )
        except subprocess.TimeoutExpired:
            raise CheckError(
                "javap exceeded {} s on {} batch {}/{}".format(JAVAP_TIMEOUT, label, number, len(batches))
            ) from None
        except OSError as exc:
            raise CheckError("cannot run javap {}: {}".format(javap, exc)) from None
        if completed.returncode != 0:
            messages = [
                line.strip()
                for line in completed.stderr.decode("utf-8", "replace").splitlines()
                if line.strip() and not line.startswith("NOTE: Picked up")
            ]
            raise CheckError(
                "javap exited {} on {} batch {}/{}: {}".format(
                    completed.returncode, label, number, len(batches), messages[0] if messages else "no message"
                )
            )
        parsed = parse_javap_output(completed.stdout.decode("utf-8", "replace"))
        if len(parsed) != len(batch):
            raise CheckError(
                "javap listed {} classes for {} class files in {} batch {}/{}".format(
                    len(parsed), len(batch), label, number, len(batches)
                )
            )
        infos.extend(parsed)
    return infos


def package_of(fqcn: str) -> str:
    """Package part of a fully qualified name (empty for the default package)."""
    return fqcn.rsplit(".", 1)[0] if "." in fqcn else ""


def is_anonymous_or_local(binary: str) -> bool:
    """True when any ``$`` segment of the simple binary name starts with a digit."""
    return any(segment[:1].isdigit() for segment in binary.rsplit(".", 1)[-1].split("$")[1:])


@dataclass
class CompiledModel:
    """Generated set G, every class file G covers, and the handwritten classes."""

    generated: Dict[str, Path]
    generated_files: Dict[str, Path]
    handwritten: Dict[str, ClassInfo]
    generated_info: Dict[str, ClassInfo] = field(default_factory=dict)

    def covering(self, binary: str) -> Optional[str]:
        """The G member that covers ``binary`` (itself or its outermost class), or None."""
        top = binary.split("$", 1)[0]
        return top if top in self.generated else None


def _declared_accessors(source_root: Path, info: ClassInfo) -> Set[str]:
    """Names in ``info.line_accessors`` declared with a body on their line of the class's source file.

    The source file is ``<source_root>/<package directories>/<SourceFile>``, read with its comments
    blanked; ``<name>()`` followed by an optional ``throws`` clause and ``{`` declares the accessor.
    Every accessor counts as declared when the ``SourceFile`` attribute is absent or not a plain file
    name, the file cannot be read, or the line lies outside the file.

    See DECISIONS.md D-144.
    """
    if not info.line_accessors:
        return set()
    every = set(info.line_accessors)
    file_name = info.source_file
    if not file_name or file_name in (".", "..") or any(char in file_name for char in "/\\\0"):
        return every
    package = package_of(info.name)
    directory = source_root.joinpath(*package.split(".")) if package else source_root
    try:
        text = read_text(directory / file_name, errors="replace")
    except CheckError:
        return every
    lines = strip_java_comments(text.replace("\r\n", "\n").replace("\r", "\n")).split("\n")
    declared: Set[str] = set()
    for accessor, line in info.line_accessors.items():
        declaration = re.compile(r"(?<![\w$.])" + re.escape(accessor) + r"\s*\(\s*\)\s*(?:throws\s[^{;]*)?\{")
        if not 1 <= line <= len(lines) or declaration.search(lines[line - 1]):
            declared.add(accessor)
    return declared


def load_compiled(project: Path, example: str, javap: str) -> CompiledModel:
    """Build G from the JAXB sources and run javap over the handwritten classes.

    Each record accessor of ``ClassInfo.line_accessors`` that ``_declared_accessors`` finds declared in
    the source tree of its class directory, ``src/main/java`` for ``target/classes`` and ``src/test/java``
    for ``target/test-classes``, joins the class's ``public_methods``.

    See DECISIONS.md D-144.
    """
    classes_dir = project / "target" / "classes"
    test_classes_dir = project / "target" / "test-classes"
    for directory in (classes_dir, test_classes_dir):
        if not directory.is_dir():
            raise CheckError(
                "{}-java/target/{} missing: {}".format(
                    example, directory.name, BUILD_HINT.format(project="{}-java".format(example))
                )
            )
    generated: Dict[str, Path] = {}
    for source in sorted_files(project / "target" / "generated-sources" / "jaxb", "**/*.java"):
        fqcn = java_fqcn(source)
        class_file = classes_dir / (fqcn.replace(".", "/") + ".class")
        if class_file.is_file():
            generated[fqcn] = class_file
    model = CompiledModel(generated, {}, {})
    handwritten_files: List[Path] = []
    source_roots: List[Path] = []
    for root, source_root in (
        (classes_dir, project / "src" / "main" / "java"),
        (test_classes_dir, project / "src" / "test" / "java"),
    ):
        for class_file in sorted_files(root, "**/*.class"):
            if class_file.name == "module-info.class":
                continue
            binary = class_file.relative_to(root).as_posix()[: -len(".class")].replace("/", ".")
            if model.covering(binary) is not None:
                if root == classes_dir:
                    model.generated_files[binary] = class_file
                continue
            if is_anonymous_or_local(binary):
                continue
            handwritten_files.append(class_file)
            source_roots.append(source_root)
    for source_root, info in zip(source_roots, run_javap(javap, handwritten_files, "handwritten")):
        info.public_methods |= _declared_accessors(source_root, info)
        known = model.handwritten.get(info.name)
        if known is None:
            model.handwritten[info.name] = info
        else:
            known.methods |= info.methods
            known.public_methods |= info.public_methods
    return model


def load_generated_members(model: CompiledModel, javap: str, names: Iterable[str]) -> None:
    """Run javap over the G class files named with ``#method`` in forward cells."""
    pending = sorted(
        {name for name in names if name not in model.generated_info and name in model.generated_files}
    )
    if not pending:
        return
    files = [model.generated_files[name] for name in pending]
    for info in run_javap(javap, files, "generated"):
        model.generated_info[info.name] = info


# jaxb2-maven-plugin xjc inputs and the namespaces of their schemas.
XSD_NS = "http://www.w3.org/2001/XMLSchema"
WSDL11_NS = "http://schemas.xmlsoap.org/wsdl/"
JAXB_PLUGIN = "jaxb2-maven-plugin"
JAXB_SOURCE_SUFFIXES = {"wsdl": ".wsdl", "xmlschema": ".xsd"}
POM_PROPERTY_RE = re.compile(r"\$\{([^}]*)\}")
URI_SCHEME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]+:")
XML_SCHEMA_ANNOTATION_RE = re.compile(r"@(?:[\w$]+\s*\.\s*)*XmlSchema\s*\(")
NAMESPACE_ARGUMENT_RE = re.compile(r'\bnamespace\s*=\s*"((?:[^"\\\n]|\\.)*)"')
JAVA_ESCAPE_RE = re.compile(r"\\(u+[0-9A-Fa-f]{4}|[0-3][0-7]{0,2}|[4-7][0-7]?|.)")
JAVA_ESCAPES = {"b": "\b", "t": "\t", "n": "\n", "f": "\f", "r": "\r", "s": " "}
XSD_COMPONENT_KINDS = {
    "{%s}element" % XSD_NS: "element",
    "{%s}complexType" % XSD_NS: "type",
    "{%s}simpleType" % XSD_NS: "type",
}
JAXB_BINDING_NAMESPACES = frozenset(("https://jakarta.ee/xml/ns/jaxb", "http://java.sun.com/xml/ns/jaxb"))
EPISODE_CLASS_TAGS = frozenset(("class", "typesafeEnumClass"))
EPISODE_SCD_RE = re.compile(r"^(~?)(?:([^\s:/@~]+):)?([^\s:/@~]+)$")
XML_REGISTRY_RE = re.compile(r"@(?:[\w$]+\s*\.\s*)*XmlRegistry\b")

# Top-level schema component: (kind "element" or "type", namespace, name).
Component = Tuple[str, str, str]


@dataclass
class JaxbInput:
    """One xjc input and what its schemas declare.

    ``path`` is the project-relative POSIX path, ``namespaces`` the schema
    target namespaces, ``packages`` the configured packageName values,
    ``components`` the top-level schema components and ``namespace_packages``
    whether an execution without packageName reads the input.
    """

    path: str
    namespaces: Set[str] = field(default_factory=set)
    packages: Set[str] = field(default_factory=set)
    components: Set[Component] = field(default_factory=set)
    namespace_packages: bool = False


def _local_name(tag: object) -> str:
    """Tag without its ``{namespace}`` part; empty for comments and processing instructions."""
    return tag.rsplit("}", 1)[-1] if isinstance(tag, str) else ""


def _pom_elements(element: Optional[ET.Element], *names: str) -> List[ET.Element]:
    """Elements reached from ``element`` along the child path ``names``, matched by local name."""
    current = [element] if element is not None else []
    for name in names:
        current = [child for parent in current for child in parent if _local_name(child.tag) == name]
    return current


def _pom_text(element: ET.Element) -> str:
    """Stripped text content of ``element``."""
    return (element.text or "").strip()


def _pom_configuration(element: ET.Element) -> Dict[str, ET.Element]:
    """Children of the ``configuration`` child of ``element`` by local name."""
    return {
        _local_name(child.tag): child
        for configuration in _pom_elements(element, "configuration")
        for child in configuration
        if _local_name(child.tag)
    }


def _project_label(project: Path, path: Path) -> str:
    """``<project folder>/<relative path>`` of ``path``, else ``path`` itself."""
    try:
        return "{}/{}".format(project.name, path.relative_to(project).as_posix())
    except ValueError:
        return str(path)


def parse_xml_root(path: Path, label: str) -> ET.Element:
    """Root element of the XML file ``path``; read and parse failures raise CheckError naming ``label``."""
    try:
        return ET.parse(str(path)).getroot()
    except (ET.ParseError, LookupError) as exc:
        raise CheckError("XML parse error in {}: {}".format(label, exc)) from None
    except OSError as exc:
        raise CheckError("cannot read {}: {}".format(label, exc)) from None


def resolve_pom_value(value: str, properties: Dict[str, str], pom_label: str, active: Tuple[str, ...] = ()) -> str:
    """``value`` with every ``${name}`` replaced from ``properties``; unknown or cyclic names raise CheckError."""

    def replace(match: re.Match[str]) -> str:
        name = match.group(1).strip()
        if name not in properties:
            raise CheckError("{}: unresolved ${{{}}} in {} configuration".format(pom_label, name, JAXB_PLUGIN))
        if name in active:
            raise CheckError("{}: property cycle at ${{{}}}".format(pom_label, name))
        return resolve_pom_value(properties[name], properties, pom_label, active + (name,))

    return POM_PROPERTY_RE.sub(replace, value)


def _pom_setting(config: Dict[str, ET.Element], name: str, properties: Dict[str, str], pom_label: str) -> Optional[str]:
    """Resolved text of configuration element ``name``; None when it is absent."""
    element = config.get(name)
    return None if element is None else resolve_pom_value(_pom_text(element), properties, pom_label)


def _local_reference(current: Path, location: str, label: str, attribute: str) -> Optional[Path]:
    """Local file a schema or WSDL reference names; None for a URI with a scheme.

    A local file that does not exist raises CheckError.
    """
    reference = location.split("#", 1)[0]
    if not reference or URI_SCHEME_RE.match(reference):
        return None
    target = Path(os.path.normpath(os.path.join(str(current.parent), reference)))
    if not target.is_file():
        raise CheckError("{}: {} {} does not exist".format(label, attribute, location))
    return target


def schema_namespaces(project: Path, path: Path, components: Optional[Set[Component]] = None) -> Set[str]:
    """targetNamespace of every xs:schema of ``path`` and of the local files it imports, includes or redefines.

    A schema without targetNamespace contributes the namespace of the schema
    that includes or redefines it, else the empty namespace. wsdl:import
    locations are followed the same way. ``components`` receives the named
    xs:element (kind ``element``), xs:complexType and xs:simpleType (kind
    ``type``) children of each xs:schema with that schema's namespace.
    """
    namespaces: Set[str] = set()
    visited: Set[Tuple[str, Optional[str]]] = set()
    pending: List[Tuple[Path, Optional[str]]] = [(path, None)]
    schema_tag = "{%s}schema" % XSD_NS
    references = {"{%s}import" % XSD_NS: False, "{%s}include" % XSD_NS: True, "{%s}redefine" % XSD_NS: True}
    while pending:
        current, inherited = pending.pop()
        key = (str(current), inherited)
        if key in visited:
            continue
        visited.add(key)
        label = _project_label(project, current)
        root = parse_xml_root(current, label)
        for element in root.iter():
            if element.tag == schema_tag:
                namespace = element.get("targetNamespace")
                if namespace is None:
                    namespace = inherited if inherited is not None else ""
                namespaces.add(namespace)
                for child in element:
                    if child.tag in references:
                        target = _local_reference(current, child.get("schemaLocation", ""), label, "schemaLocation")
                        if target is not None:
                            pending.append((target, namespace if references[child.tag] else None))
                    elif components is not None and child.tag in XSD_COMPONENT_KINDS:
                        name = (child.get("name") or "").strip()
                        if name:
                            components.add((XSD_COMPONENT_KINDS[child.tag], namespace, name))
            elif element.tag == "{%s}import" % WSDL11_NS:
                target = _local_reference(current, element.get("location", ""), label, "location")
                if target is not None:
                    pending.append((target, None))
    return namespaces


def jaxb_inputs(project: Path) -> List[JaxbInput]:
    """xjc inputs of the pom.xml jaxb2-maven-plugin executions writing target/generated-sources/jaxb.

    The inputs are sorted by path and carry their schema namespaces and
    top-level schema components.
    Configuration is the plugin-level configuration overridden per element by
    the execution's. ``sources`` defaults to ``src/main/xsd``; a directory
    source contributes its ``*.wsdl`` (sourceType ``wsdl``) or ``*.xsd``
    (sourceType ``xmlschema``, the default) files. Executions bound to phase
    ``none`` or with ``skipXjc`` true are ignored. ``${project.basedir}``,
    ``${basedir}``, ``${project.build.directory}`` and the pom's
    ``<properties>`` are resolved; any other ``${...}``, an unparseable pom.xml,
    an unsupported sourceType and a configured source that does not exist
    inside the project raise CheckError.

    See DECISIONS.md D-147.
    """
    pom_label = "{}/pom.xml".format(project.name)
    root = parse_xml_root(project / "pom.xml", pom_label)
    properties: Dict[str, str] = {
        _local_name(child.tag): _pom_text(child)
        for container in _pom_elements(root, "properties")
        for child in container
        if _local_name(child.tag)
    }
    properties.update(
        {
            "project.basedir": str(project),
            "basedir": str(project),
            "project.build.directory": str(project / "target"),
        }
    )
    output = os.path.normpath(str(project / "target" / "generated-sources" / "jaxb"))
    found: Dict[str, JaxbInput] = {}
    locations: Dict[str, Path] = {}
    for plugin in _pom_elements(root, "build", "plugins", "plugin"):
        if [_pom_text(item) for item in _pom_elements(plugin, "artifactId")] != [JAXB_PLUGIN]:
            continue
        shared = _pom_configuration(plugin)
        for execution in _pom_elements(plugin, "executions", "execution"):
            if "xjc" not in [_pom_text(goal) for goal in _pom_elements(execution, "goals", "goal")]:
                continue
            phases = [_pom_text(item) for item in _pom_elements(execution, "phase")]
            if [resolve_pom_value(phase, properties, pom_label) for phase in phases] == ["none"]:
                continue
            config = dict(shared)
            config.update(_pom_configuration(execution))
            if (_pom_setting(config, "skipXjc", properties, pom_label) or "").lower() == "true":
                continue
            directory = _pom_setting(config, "outputDirectory", properties, pom_label)
            if directory and os.path.normpath(os.path.join(str(project), directory)) != output:
                continue
            source_type = (_pom_setting(config, "sourceType", properties, pom_label) or "xmlschema").lower()
            if source_type not in JAXB_SOURCE_SUFFIXES:
                raise CheckError("{}: {} sourceType {} unsupported".format(pom_label, JAXB_PLUGIN, source_type))
            package = _pom_setting(config, "packageName", properties, pom_label) or ""
            configured = [
                resolve_pom_value(_pom_text(item), properties, pom_label)
                for item in _pom_elements(config.get("sources"), "source")
            ]
            if "" in configured:
                raise CheckError("{}: {} configuration has an empty <source>".format(pom_label, JAXB_PLUGIN))
            for source in configured or ["src/main/xsd"]:
                candidate = Path(os.path.normpath(os.path.join(str(project), source)))
                try:
                    candidate.relative_to(project)
                except ValueError:
                    raise CheckError(
                        "{}: {} source {} is outside {}".format(pom_label, JAXB_PLUGIN, source, project.name)
                    ) from None
                if candidate.is_file():
                    files = [candidate]
                elif candidate.is_dir():
                    files = [
                        path
                        for path in sorted_files(candidate, "**/*")
                        if path.name.endswith(JAXB_SOURCE_SUFFIXES[source_type])
                    ]
                elif configured:
                    raise CheckError("{}: {} source {} does not exist".format(pom_label, JAXB_PLUGIN, source))
                else:
                    files = []
                for path in files:
                    relative = path.relative_to(project).as_posix()
                    item = found.setdefault(relative, JaxbInput(relative))
                    locations[relative] = path
                    if package:
                        item.packages.add(package)
                    else:
                        item.namespace_packages = True
    for relative, item in found.items():
        item.namespaces = schema_namespaces(project, locations[relative], item.components)
    return [found[relative] for relative in sorted(found)]


def _annotation_arguments(text: str, start: int) -> str:
    """Text from ``start`` up to the parenthesis that closes the one opened just before it."""
    depth = 1
    index = start
    while index < len(text):
        char = text[index]
        if char in "\"'":
            index += 1
            while index < len(text) and text[index] != char:
                index += 2 if text[index] == "\\" else 1
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return text[start:index]
        index += 1
    return text[start:]


def decode_java_string(body: str) -> str:
    """Value of a Java string literal body with its escape sequences decoded."""

    def replace(match: re.Match[str]) -> str:
        token = match.group(1)
        if token[0] == "u":
            return chr(int(token[-4:], 16))
        if token[0] in "01234567":
            return chr(int(token, 8))
        return JAVA_ESCAPES.get(token, token)

    return JAVA_ESCAPE_RE.sub(replace, body)


def generated_package_namespace(project: Path, package: str) -> str:
    """``@XmlSchema`` namespace of ``target/generated-sources/jaxb/<package>/package-info.java``.

    Empty when the file, the annotation or its ``namespace`` element is absent.
    """
    base = project / "target" / "generated-sources" / "jaxb"
    path = base.joinpath(*package.split(".")) / "package-info.java" if package else base / "package-info.java"
    if not path.is_file():
        return ""
    text = strip_java_comments(read_text(path, errors="replace"))
    annotation = XML_SCHEMA_ANNOTATION_RE.search(text)
    if annotation is None:
        return ""
    namespace = NAMESPACE_ARGUMENT_RE.search(_annotation_arguments(text, annotation.end()))
    return decode_java_string(namespace.group(1)) if namespace else ""


def match_jaxb_input(cell: str, example: str, inputs: List[JaxbInput]) -> Optional[str]:
    """Input path a ``WSDL or XSD`` cell names; None when it names no input.

    The cell is the project path, the ``<example>-java/`` path or a file name
    that exactly one input has.
    """
    paths = [item.path for item in inputs]
    prefix = "{}-java/".format(example)
    if cell.startswith(prefix):
        return cell[len(prefix):] if cell[len(prefix):] in paths else None
    if cell in paths:
        return cell
    named = [path for path in paths if "/" not in cell and path.rsplit("/", 1)[-1] == cell]
    return named[0] if len(named) == 1 else None


def _scd_component(scd: str, scope: Dict[str, str]) -> Optional[Component]:
    """Component of an element scd ``<prefix>:<name>`` or a type scd ``~<prefix>:<name>``; None for other scds.

    The prefix resolves through the declarations in ``scope``; an absent
    prefix is the empty namespace and an undeclared prefix yields None.
    """
    match = EPISODE_SCD_RE.match(scd.strip())
    if match is None:
        return None
    tilde, prefix, name = match.groups()
    if prefix is None:
        namespace = ""
    elif prefix in scope:
        namespace = scope[prefix]
    else:
        return None
    return ("type" if tilde else "element", namespace, name)


def jaxb_episode_classes(project: Path) -> Dict[str, Set[Component]]:
    """Schema components the xjc episode files map to each class name.

    The files are ``target/generated-sources/jaxb/META-INF/JAXB/*.xjb``. A
    ``class`` or ``typesafeEnumClass`` element of the Jakarta or javax JAXB
    binding namespace maps its ``ref`` to the component that the ``scd`` of
    its enclosing ``bindings`` element names. An unreadable or unparseable
    file raises CheckError.
    """
    classes: Dict[str, Set[Component]] = defaultdict(set)
    directory = project / "target" / "generated-sources" / "jaxb" / "META-INF" / "JAXB"
    for path in sorted_files(directory, "*.xjb"):
        label = _project_label(project, path)
        scopes: List[Dict[str, str]] = [{}]
        enclosing: List[Optional[Component]] = [None]
        declared: Dict[str, str] = {}
        try:
            for event, item in ET.iterparse(str(path), events=("start-ns", "start", "end")):
                if event == "start-ns":
                    declared[item[0]] = item[1]
                    continue
                if event == "end":
                    scopes.pop()
                    enclosing.pop()
                    continue
                scope = dict(scopes[-1])
                scope.update(declared)
                declared = {}
                component = enclosing[-1]
                uri, local = split_tag(item.tag)
                if uri in JAXB_BINDING_NAMESPACES:
                    scd = item.get("scd")
                    ref = (item.get("ref") or "").strip()
                    if local == "bindings" and scd is not None:
                        component = _scd_component(scd, scope)
                    elif local in EPISODE_CLASS_TAGS and component is not None and ref:
                        classes[ref].add(component)
                scopes.append(scope)
                enclosing.append(component)
        except (ET.ParseError, LookupError) as exc:
            raise CheckError("XML parse error in {}: {}".format(label, exc)) from None
        except OSError as exc:
            raise CheckError("cannot read {}: {}".format(label, exc)) from None
    return dict(classes)


def _carries_xml_registry(project: Path, name: str) -> bool:
    """True when ``target/generated-sources/jaxb/<name as path>.java`` exists and carries ``@XmlRegistry``."""
    parts = name.split(".")
    path = project.joinpath("target", "generated-sources", "jaxb", *parts[:-1]) / (parts[-1] + ".java")
    if not path.is_file():
        return False
    return XML_REGISTRY_RE.search(strip_java_comments(read_text(path, errors="replace"))) is not None


def generated_origins(
    project: Path, inputs: List[JaxbInput], names: Iterable[str]
) -> Dict[str, Tuple[List[str], bool]]:
    """Paths of the inputs each top-level generated class comes from, and whether the class is package-level.

    A class other than ``package-info`` that an episode file maps comes from
    the inputs declaring one of its components. Any other class comes from
    the inputs contributing its package: an input of an execution with
    packageName contributes that package, an input of an execution without
    packageName each package whose ``package-info`` ``@XmlSchema`` namespace
    is one of its target namespaces. ``package-info`` and an unmapped class
    whose generated source carries ``@XmlRegistry`` are package-level.
    """
    episodes = jaxb_episode_classes(project)
    package_namespaces: Dict[str, str] = {}
    origins: Dict[str, Tuple[List[str], bool]] = {}
    for name in sorted(names):
        package_info = name.rsplit(".", 1)[-1] == "package-info"
        components = set() if package_info else episodes.get(name, set())
        if components:
            origins[name] = ([item.path for item in inputs if item.components & components], False)
            continue
        package = package_of(name)
        if package not in package_namespaces:
            package_namespaces[package] = generated_package_namespace(project, package)
        namespace = package_namespaces[package]
        sources = [
            item.path
            for item in inputs
            if package in item.packages or (item.namespace_packages and namespace in item.namespaces)
        ]
        origins[name] = (sources, package_info or _carries_xml_registry(project, name))
    return origins



# --------------------------------------------------------------------------
# Checks and figures
# --------------------------------------------------------------------------


@dataclass
class Figure:
    """An ``ok/total`` figure of the result line."""

    ok: int = 0
    total: int = 0

    def add(self, passed: bool) -> None:
        """Count one item, ok when ``passed``."""
        self.total += 1
        if passed:
            self.ok += 1

    @property
    def complete(self) -> bool:
        return self.ok == self.total

    def __str__(self) -> str:
        return "{}/{}".format(self.ok, self.total)


# Resolved Target/Test entry: (category "h" or "g", normalized entry, top-level G class covering it or None).
Resolved = Tuple[str, str, Optional[str]]


class Checker:
    """Runs the forward, edge, backward, generated and reciprocity checks of one section."""

    def __init__(
        self,
        repo: Path,
        example: str,
        project: Path,
        model: SourceModel,
        matrix: Matrix,
        decisions: Set[str],
        compiled: CompiledModel,
        javap: str,
    ) -> None:
        self.repo = repo
        self.example = example
        self.project = project
        self.model = model
        self.matrix = matrix
        self.decisions = decisions
        self.compiled = compiled
        self.javap = javap
        self.capture = capture_pattern(example)
        self.failures: List[Tuple[str, str]] = []
        self.identities = Figure()
        self.edges = Figure()
        self.classes = Figure()
        self.generated = Figure()
        self.reciprocal = Figure()
        self.rows_by_identity: Dict[str, ForwardRow] = {}
        self.id_rows: Dict[str, ForwardRow] = {}
        self.resolved_entries: List[Tuple[ForwardRow, Resolved]] = []
        self.backward_pairs: Set[Tuple[str, str]] = set()
        self.coverage: Dict[str, Set[int]] = defaultdict(set)
        self.required: Set[str] = set()
        self._file_lines: Dict[str, Optional[List[str]]] = {}
        self._display_names = DisplayNames()

    # -- reporting -----------------------------------------------------------

    def fail(self, kind: str, detail: str, separator: str = ": ") -> None:
        """Record one failure line ``<kind><separator><detail>`` grouped under ``kind``."""
        self.failures.append((kind, kind + separator + detail))

    @property
    def figures(self) -> List[Figure]:
        return [self.identities, self.edges, self.classes, self.generated, self.reciprocal]

    @property
    def passed(self) -> bool:
        return not self.failures and all(figure.complete for figure in self.figures)

    def result_line(self) -> str:
        """The result line written at the end of the section."""
        return "Forward: {} identities, {} edges; Backward: {} classes, {} generated; Reciprocal: {} edges".format(
            self.identities, self.edges, self.classes, self.generated, self.reciprocal
        )

    def report_lines(self) -> List[str]:
        """Failure lines grouped by kind and sorted, then PASS or FAIL, then the result line."""
        grouped: Dict[str, List[str]] = defaultdict(list)
        for kind, line in self.failures:
            grouped[kind].append(line)
        lines: List[str] = []
        for kind in sorted(grouped):
            lines.extend(sorted(grouped[kind]))
        lines.append("PASS" if self.passed else "FAIL")
        lines.append(self.result_line())
        return lines

    # -- orchestration -------------------------------------------------------

    def run(self) -> None:
        """Run every check in order and fill the five figures."""
        self._display_names = self.scan_display_names()
        active = self.index_forward_rows()
        bindings = self.resolve_forward_rows(active)
        self.check_bindings(bindings)
        self.identity_items(active)
        self.scenario_file_items()
        names = [
            entry.split("#", 1)[0]
            for row in self.matrix.forward
            for entry in row.targets + row.tests
            if "#" in entry
        ]
        load_generated_members(
            self.compiled,
            self.javap,
            [name for name in names if name not in self.compiled.handwritten and name in self.compiled.generated_files],
        )
        self.check_edges()
        self.required = self.backward_required()
        self.check_backward()
        self.check_generated()
        self.check_replaces()
        self.check_reciprocity()

    # -- forward table -------------------------------------------------------

    def index_forward_rows(self) -> List[ForwardRow]:
        """Mark duplicate rows and classify every first occurrence."""
        active: List[ForwardRow] = []
        for row in self.matrix.forward:
            first = self.rows_by_identity.get(row.identity)
            if first is not None:
                row.duplicate = True
                row.valid = False
                self.fail(
                    "DUPLICATE forward",
                    "{} (TRACEABILITY.md:{} and :{})".format(row.label, first.lineno, row.lineno),
                )
                continue
            self.rows_by_identity[row.identity] = row
            self.classify(row)
            active.append(row)
        return active

    def classify(self, row: ForwardRow) -> None:
        """Set ``row.kind``: id, rejected, locator, exact, scenario, anchor or extra (first match wins).

        A ``DW-nn``/``SC-nn`` row is ``id`` when the example's inventory holds
        the ID at the written path and first line (and last line, when
        written), else ``rejected``; a bare locator row is ``locator`` when it
        is a MEL line of the inventory, else ``extra``.
        """
        identity = row.identity
        id_match = ID_ROW_RE.match(identity)
        if not identity:
            row.kind = "extra"
        elif id_match is not None:
            key = "{}-{}".format(id_match.group("kind"), id_match.group("num"))
            entry = self.model.id_entries.get(key)
            end = id_match.group("end")
            problem: Optional[str] = None
            if entry is None:
                problem = "{} is no inventory ID of {}".format(key, self.example)
            elif (id_match.group("path"), int(id_match.group("line"))) != (entry[0], entry[1]) or (
                end is not None and int(end) != entry[2]
            ):
                problem = "{} is {}:{}-{}".format(key, *entry)
            if problem is not None:
                row.kind = "rejected"
                row.valid = False
                self.fail("ID mismatch", "{} (TRACEABILITY.md:{}): {}".format(row.label, row.lineno, problem))
                return
            row.kind = "id"
            row.id_key = key
            row.locators = [id_match.group("loc")] + row.locators
            first = self.id_rows.get(row.id_key)
            if first is not None:
                row.valid = False
                self.fail(
                    "DUPLICATE forward",
                    "ID {} on TRACEABILITY.md:{} and :{}".format(row.id_key, first.lineno, row.lineno),
                )
            else:
                self.id_rows[row.id_key] = row
        elif LOCATOR_RE.match(identity):
            if identity in self.model.mel_set:
                row.kind = "locator"
                row.locators = [identity] + row.locators
            else:
                row.kind = "extra"
        elif identity in self.model.exact_set:
            row.kind = "exact"
        elif SCENARIO_RE.match(identity):
            row.kind = "scenario" if self.model.raml_backed else "extra"
        elif identity in self.model.anchorable:
            row.kind = "anchor"
        else:
            row.kind = "extra"

    def valid_anchor(self, path: str, line: int) -> bool:
        """True when ``path`` is a file of the example folder and ``line`` is a non-blank line of it."""
        if line < 1 or ".." in path.split("/"):
            return False
        if path not in self._file_lines:
            self._file_lines[path] = file_lines(self.repo / path)
        lines = self._file_lines[path]
        return lines is not None and line <= len(lines) and bool(lines[line - 1].strip())

    def resolve_locator(self, locator: str) -> Optional[List[BindItem]]:
        """Bindable elements on the locator line, [] for another resolving line, None when unresolved.

        In an active configuration an element start-tag line or an inventory
        line resolves; in any other file of the example folder a non-blank
        line resolves.
        """
        match = LOCATOR_RE.match(locator)
        if match is None:
            return None
        path = match.group("path")
        line = int(match.group("line"))
        if not path.startswith(self.example + "/"):
            return None
        items = self.model.bind_by_line.get((path, line))
        if items:
            return list(items)
        if path in self.model.config_set:
            resolved = (path, line) in self.model.start_lines or (path, line) in self.model.inventory_lines
        else:
            resolved = self.valid_anchor(path, line)
        return [] if resolved else None

    def resolve_forward_rows(self, active: List[ForwardRow]) -> Dict[int, List[ForwardRow]]:
        """Resolve every locator; return the rows binding each bind item (keyed by item id)."""
        bindings: Dict[int, List[ForwardRow]] = defaultdict(list)
        for row in active:
            bound: List[BindItem] = []
            for locator in row.locators:
                hits = self.resolve_locator(locator)
                if hits is None:
                    row.valid = False
                    self.fail("UNRESOLVED locator", "{} -> {}".format(row.label, locator))
                    continue
                for item in hits:
                    if row not in bindings[id(item)]:
                        bindings[id(item)].append(row)
                    bound.append(item)
            if row.kind == "id" and row.id_key is not None and row.id_key.startswith("DW-"):
                if not any(item.rule == "exactly-one-DW" for item in bound):
                    row.valid = False
                    self.fail("DW row binds no setter", row.label)
        return bindings

    def is_scenario_row(self, row: ForwardRow) -> bool:
        """True for a row of a scenario identity: one of the scenario inventory or a RAML scenario row."""
        return row.identity in self.model.scenario_set or row.kind == "scenario"

    def branch_gaps(
        self, bindings: Dict[int, List[ForwardRow]], item_by_element: Dict[int, BindItem]
    ) -> Dict[int, List[str]]:
        """Unbound outcomes of every when, otherwise and outermost filter, keyed by item id (D-151).

        An outcome is bound by a scenario row of one of its scenarios that
        locates the element's start-tag line. A scenario row locating a when,
        otherwise or filter (a nested filter through its outermost filter)
        whose branch inventory entry does not list that scenario is a failed
        item of the identities figure (``SCENARIO misbound``).
        """
        gaps: Dict[int, List[str]] = {}
        for item in self.model.bind_items:
            if item.rule not in BRANCH_OUTCOMES and item.rule != "nested-filter":
                continue
            owner = item_by_element[id(outermost_filter(item.element))] if item.rule == "nested-filter" else item
            entry = self.model.branch_map.get(owner.locator, {})
            listed = {identity for identities in entry.values() for identity in identities}
            rows = [row for row in bindings.get(id(item), []) if self.is_scenario_row(row)]
            located_element = "{} {}".format(item.locator, item.element.qname)
            if owner is not item:
                located_element += " (outermost filter {} {})".format(owner.locator, owner.element.qname)
            for row in rows:
                if row.identity not in listed:
                    self.fail(
                        "SCENARIO misbound",
                        "{} (TRACEABILITY.md:{}) locates {}, whose outcomes list {}".format(
                            row.identity, row.lineno, located_element, ", ".join(sorted(listed))
                        ),
                    )
                    self.identities.add(False)
            if item.rule == "nested-filter":
                continue
            located = {row.identity for row in rows}
            gaps[id(item)] = [
                outcome for outcome in BRANCH_OUTCOMES[item.rule] if not located & set(entry.get(outcome, ()))
            ]
        return gaps

    def check_bindings(self, bindings: Dict[int, List[ForwardRow]]) -> None:
        """Apply the binding rule of every S_bind element.

        A DataWeave setter or outermost scripting element is bound by exactly
        one row of its inventory ID; an expression-component or
        apikit:flow-mapping by any row locating it. A when or otherwise is
        bound by a scenario row of one of its scenarios locating it; an
        outermost filter by such a row of a rejected and one of an accepted
        scenario; a nested filter when its outermost filter is bound; a choice
        when every when and otherwise child is bound (D-151).
        """
        item_by_element: Dict[int, BindItem] = {id(item.element): item for item in self.model.bind_items}
        gaps = self.branch_gaps(bindings, item_by_element)
        for item in self.model.bind_items:
            rows = bindings.get(id(item), [])
            description = "{} {} ({})".format(item.locator, item.element.qname, item.rule)
            if item.rule in ("exactly-one-DW", "exactly-one-SC"):
                prefix = "DW-" if item.rule == "exactly-one-DW" else "SC-"
                id_rows = [
                    row for row in rows if row.kind == "id" and row.id_key is not None and row.id_key.startswith(prefix)
                ]
                if not id_rows:
                    self.fail("UNBOUND", description)
                    self.identities.add(False)
                elif len(id_rows) > 1:
                    self.fail(
                        "MULTIPLY BOUND",
                        "{} by {}".format(description, "; ".join(row.label for row in id_rows)),
                    )
                    self.identities.add(False)
                else:
                    self.identities.add(True)
                continue
            if item.rule in BRANCH_OUTCOMES:
                entry = self.model.branch_map.get(item.locator, {})
                for outcome in gaps[id(item)]:
                    self.fail(
                        "UNBOUND",
                        "{}: {} outcome needs a row of {} locating it".format(
                            description, outcome, " or ".join(entry.get(outcome, ()))
                        ),
                    )
                self.identities.add(not gaps[id(item)])
                continue
            if item.rule == "nested-filter":
                owner = item_by_element[id(outermost_filter(item.element))]
                bound = not gaps[id(owner)]
                if not bound:
                    self.fail(
                        "UNBOUND",
                        "{}: outermost filter {} {} is unbound".format(description, owner.locator, owner.element.qname),
                    )
                self.identities.add(bound)
                continue
            if item.rule == "choice":
                branches = [
                    item_by_element[id(child)]
                    for child in item.element.children
                    if id(child) in item_by_element and child.ns == CORE_NS and child.local in ("when", "otherwise")
                ]
                unbound = [branch for branch in branches if gaps[id(branch)]]
                bound = bool(branches) and not unbound
                if not branches:
                    self.fail("UNBOUND", "{}: no when or otherwise child".format(description))
                elif unbound:
                    self.fail(
                        "UNBOUND",
                        "{}: branch {} unbound".format(
                            description, ", ".join("{} {}".format(b.locator, b.element.qname) for b in unbound)
                        ),
                    )
                self.identities.add(bound)
                continue
            if item.rule == "one-or-more":
                bound = bool(rows)
            else:
                action = item.element.attrib.get("action")
                resource = item.element.attrib.get("resource")
                bound = bool(rows)
                if not bound and action and resource:
                    bound = any(
                        "{}#{} {}".format(raml, action.upper(), resource) in self.rows_by_identity
                        for raml in self.model.raml_files
                    )
            if not bound:
                self.fail("UNBOUND", description)
            self.identities.add(bound)

    def scan_display_names(self) -> DisplayNames:
        """Index the JUnit Jupiter ``@DisplayName`` values of the Tier 1 test sources (D-148).

        Tier 1 sources are the ``src/test/java`` files outside ``it`` and
        ``capture`` directories, other than ``*IT.java`` and
        ``FixtureParityTest.java``. A value counts for a Tier 1 test method: a
        method with a body, neither private nor static, carrying ``@Test``,
        ``@RepeatedTest``, ``@ParameterizedTest`` or ``@TestTemplate`` and
        returning void, or ``@TestFactory`` and returning a value, written
        qualified, imported, or through an ``@interface`` of these sources
        meta-annotated with one of them. An ``@interface`` passes its
        meta-annotations on, ``@Disabled`` and ``@Nested`` included, only when
        it carries ``@Retention(RetentionPolicy.RUNTIME)``. The method and its
        enclosing types are not ``@Disabled``, and it is a member of a top-level
        type or of a non-static ``@Nested`` inner class. Every other declaration
        carrying a value is kept as skipped with its reason. Locations are
        ``<path>:<line>#<method>`` with ``<path>`` relative to ``<example>-java``.
        A method ``@DisplayName`` whose argument is not one string literal, a
        ``@DisplayName`` on no method or type declaration, a ``@Retention``
        argument that is not one ``RetentionPolicy`` constant where it decides
        what such an ``@interface`` passes on, or a malformed source raises
        CheckError naming ``<path>:<line>``.
        """
        base = self.project / "src" / "test" / "java"
        units: List[JavaUnit] = []
        for path in sorted_files(base, "**/*.java"):
            relative = path.relative_to(base)
            if any(part in ("it", "capture") for part in relative.parts[:-1]):
                continue
            if path.name.endswith("IT.java") or path.name == "FixtureParityTest.java":
                continue
            text = strip_java_comments(read_text(path, errors="replace"))
            units.append(JavaUnitScanner(text, path.relative_to(self.project).as_posix()).scan())
        return index_display_names(units)

    def display_ok(self, identity: str) -> bool:
        """True when exactly one Tier 1 test method carries ``identity`` as its display name.

        Otherwise the failure line lists each counted method after ``at`` and
        each skipped declaration carrying the value after ``not counted:``.
        """
        tests = self._display_names.tests.get(identity, [])
        if len(tests) != 1:
            detail = "count={}: {}".format(len(tests), identity)
            if tests:
                detail += " at " + ", ".join(tests)
            skipped = self._display_names.skipped.get(identity, [])
            if skipped:
                detail += "; not counted: " + ", ".join(skipped)
            self.fail("SCENARIO display-name", detail, separator=" ")
        return len(tests) == 1

    def identity_items(self, active: List[ForwardRow]) -> None:
        """Add the S_exact, row, duplicate and RAML-minimum items of the identities figure.

        An inventory ID identity is met by the row of that ID, written with or
        without its last line; every other S_exact identity by the row of the
        same identity. A row meeting an S_exact identity is counted once.
        """
        counted: Set[int] = set()
        for identity in self.model.unique_exact:
            row = self.rows_by_identity.get(identity)
            if row is None and identity in self.model.id_by_identity:
                row = self.id_rows.get(self.model.id_by_identity[identity])
            scenario_ok = self.display_ok(identity) if identity in self.model.scenario_set else True
            if row is None:
                self.fail("MISSING forward", identity)
                self.identities.add(False)
                continue
            counted.add(id(row))
            self.identities.add(row.valid and scenario_ok)
        for identity in self.model.ambiguous:
            self.fail("AMBIGUOUS source identity", identity)
            self.identities.add(False)
        for row in active:
            if id(row) in counted:
                continue
            if row.kind == "extra":
                self.fail("EXTRA forward", "{} (TRACEABILITY.md:{})".format(row.label, row.lineno))
                self.identities.add(False)
                continue
            ok = row.valid
            if row.kind == "scenario":
                ok = self.display_ok(row.identity) and ok
            self.identities.add(ok)
        for row in self.matrix.forward:
            if row.duplicate:
                self.identities.add(False)
        scenario_rows = [identity for identity in self.rows_by_identity if SCENARIO_RE.match(identity)]
        for identity in self.model.raml_minimum:
            satisfied = any(row == identity or row.startswith(identity + "-") for row in scenario_rows)
            if not satisfied:
                self.fail("MISSING scenario", identity)
            self.identities.add(satisfied)

    def scenario_file_items(self) -> None:
        """Compare SCENARIOS.txt with the scenario inventory (D-151); each gap is a failed identities item.

        In a non-RAML-backed project every inventory identity is one line of
        the file: an absent identity, a line outside the inventory and a
        repeated line each fail. In a RAML-backed project the file is absent.
        """
        model = self.model
        path = model.scenario_path
        if model.raml_backed:
            if model.scenario_file_present:
                self.fail("SCENARIOS.txt unexpected", "{} (RAML-backed project)".format(path))
                self.identities.add(False)
            return
        first: Dict[str, int] = {}
        for number, entry in model.scenario_lines:
            if entry in first:
                self.fail("SCENARIOS.txt duplicate", "{} ({}:{} and :{})".format(entry, path, first[entry], number))
                self.identities.add(False)
                continue
            first[entry] = number
            if entry not in model.scenario_set:
                self.fail("SCENARIOS.txt extra", "{} ({}:{})".format(entry, path, number))
                self.identities.add(False)
        for identity in model.scenarios:
            if identity not in first:
                self.fail("SCENARIOS.txt missing", "{} ({})".format(identity, path))
                self.identities.add(False)

    # -- edges ---------------------------------------------------------------

    def resolve_entry(self, entry: str) -> Optional[Resolved]:
        """Resolve a Target/Test entry against the compiled output and capture harnesses.

        See DECISIONS.md D-145.
        """
        capture = self.capture.match(entry)
        if capture:
            relative = capture.group(1)
            if ".." in relative.split("/"):
                return None
            return ("h", relative, None) if (self.project / relative).is_file() else None
        compiled = self.compiled
        if "#" in entry:
            owner, method = entry.split("#", 1)
            info = compiled.handwritten.get(owner)
            if info is not None:
                return ("h", entry, None) if method in info.methods else None
            generated_info = compiled.generated_info.get(owner)
            if owner in compiled.generated_files and generated_info is not None and method in generated_info.methods:
                return ("g", entry, compiled.covering(owner))
            return None
        if entry in compiled.handwritten:
            return ("h", entry, None)
        top = compiled.covering(entry)
        if top is not None and (entry in compiled.generated or entry in compiled.generated_files):
            return ("g", entry, top)
        return None

    def check_edges(self) -> None:
        """Count the distinct Target/Test mapping edges and validate every whole Decision cell.

        The edges figure counts one item per distinct (source identity, entry)
        pair, the entry normalized by resolve_entry when it resolves. A Decision
        cell that is empty or one no-entry marker is no decision; any other cell
        is split on LIST_SPLIT_RE with empty items kept, and every trimmed item
        must be a D-nnn row of DECISIONS.md. Empty items fail once with the
        whole cell; any other item that is not a D-ID fails with the item. A
        row without Target and Test entries needs one well-formed D-ID.

        See DECISIONS.md D-146.
        """
        seen: Set[Tuple[str, str]] = set()
        for row in self.matrix.forward:
            for entry in row.targets + row.tests:
                resolved = self.resolve_entry(entry)
                pair = (row.identity, resolved[1] if resolved is not None else entry)
                if pair in seen:
                    continue
                seen.add(pair)
                self.edges.add(resolved is not None)
                if resolved is None:
                    self.fail("EDGE unresolved", "{} -> {}".format(row.label, entry))
                else:
                    self.resolved_entries.append((row, resolved))
            cited = False
            cell = row.decision.strip()
            if cell and cell.lower() not in NO_ENTRY:
                items = [part.strip() for part in LIST_SPLIT_RE.split(cell)]
                if not all(items):
                    self.fail("DECISION malformed", "{} -> {}".format(row.label, cell))
                for item in items:
                    if not item:
                        continue
                    if not D_ID_RE.match(item):
                        self.fail("DECISION malformed", "{} -> {}".format(row.label, item))
                        continue
                    cited = True
                    if item not in self.decisions:
                        self.fail("DECISION unknown", "{} -> {}".format(row.label, item))
            if not row.targets and not row.tests and not cited:
                self.fail("DECISION required", row.label)

    # -- backward table ------------------------------------------------------

    def backward_required(self) -> Set[str]:
        """R: handwritten classes and public methods, named handwritten methods and generated entries, capture files.

        See DECISIONS.md D-145.
        """
        required: Set[str] = set(self.compiled.handwritten)
        for info in self.compiled.handwritten.values():
            required.update("{}#{}".format(info.name, method) for method in info.public_methods)
        for _, (category, entry, _top) in self.resolved_entries:
            if category == "g" or "#" in entry:
                required.add(entry)
        capture_dir = self.project / "src" / "test" / "capture"
        for path in sorted_files(capture_dir, "**/*.java"):
            required.add("src/test/capture/" + path.relative_to(capture_dir).as_posix())
        return required

    def accepted_identities(self) -> Set[str]:
        """Identities of forward rows classified exact, id, locator, scenario or anchor."""
        return {
            identity
            for identity, row in self.rows_by_identity.items()
            if identity and row.kind not in ("extra", "rejected")
        }

    def resolve_link(self, link: str, accepted: Set[str]) -> Optional[Tuple[str, str]]:
        """("forward", identity) or ("decision", D-ID) for a backward link; None when unresolved."""
        if link in accepted:
            return ("forward", link)
        if BARE_ID_RE.match(link) and link in self.id_rows:
            return ("forward", self.id_rows[link].identity)
        if D_ID_RE.match(link) and link in self.decisions:
            return ("decision", link)
        return None

    def check_backward(self) -> None:
        """Compare the backward table with R and resolve every backward link."""
        accepted = self.accepted_identities()
        rows_by_identity: Dict[str, List[BackwardRow]] = defaultdict(list)
        links_ok: Dict[int, bool] = {}
        for row in self.matrix.backward:
            rows_by_identity[row.identity].append(row)
            label = row.identity or "(empty identity, TRACEABILITY.md:{})".format(row.lineno)
            ok = True
            if not row.links:
                self.fail("EMPTY backward", "{} (TRACEABILITY.md:{})".format(label, row.lineno))
                ok = False
            for link in row.links:
                resolved = self.resolve_link(link, accepted)
                if resolved is None:
                    self.fail("BACKWARD unresolved link", "{} -> {}".format(label, link))
                    ok = False
                elif resolved[0] == "forward":
                    self.backward_pairs.add((resolved[1], row.identity))
            links_ok[id(row)] = ok
        for identity in sorted(self.required | set(rows_by_identity)):
            rows = rows_by_identity.get(identity, [])
            if identity not in self.required:
                self.fail(
                    "EXTRA backward",
                    "{} (TRACEABILITY.md:{})".format(identity or "(empty identity)", rows[0].lineno),
                )
                self.classes.add(False)
            elif not rows:
                self.fail("MISSING backward", identity)
                self.classes.add(False)
            else:
                self.classes.add(len(rows) == 1 and links_ok[id(rows[0])])
        for identity, rows in rows_by_identity.items():
            for extra in rows[1:]:
                self.fail(
                    "DUPLICATE backward",
                    "{} (TRACEABILITY.md:{} and :{})".format(
                        identity or "(empty identity)", rows[0].lineno, extra.lineno
                    ),
                )
                self.classes.add(False)

    # -- generated types -----------------------------------------------------

    def check_generated(self) -> None:
        """Check the generated-types table against G and the jaxb2-maven-plugin xjc inputs of pom.xml.

        Each row's WSDL or XSD cell names one input; each input has exactly
        one row; each G class covered by one row comes from exactly one input
        (a package-level class from at least one), and a row whose input
        resolved names an input the class comes from, as ``generated_origins``
        derives them.

        See DECISIONS.md D-147.
        """
        accepted = self.accepted_identities()
        generated = self.compiled.generated
        inputs = jaxb_inputs(self.project)
        row_inputs: Dict[int, str] = {}
        input_lines: Dict[str, List[int]] = defaultdict(list)
        for index, row in enumerate(self.matrix.generated):
            cell = row.source.strip()
            missing = not cell or cell.lower() in NO_ENTRY
            listed = None if missing else match_jaxb_input(cell, self.example, inputs)
            if listed is not None:
                row_inputs[index] = listed
                input_lines[listed].append(row.lineno)
            elif missing:
                self.fail("GENERATED input missing", "(TRACEABILITY.md:{})".format(row.lineno))
                self.generated.add(False)
            else:
                self.fail("GENERATED input unknown", "{} (TRACEABILITY.md:{})".format(cell, row.lineno))
                self.generated.add(False)
            for entry in row.classes:
                if entry in generated:
                    self.coverage[entry].add(index)
                else:
                    self.fail("GENERATED nonexistent", "{} (TRACEABILITY.md:{})".format(entry, row.lineno))
                    self.generated.add(False)
            for source in row.replaces:
                if source not in accepted:
                    self.fail("GENERATED unresolved replaces", "{} (TRACEABILITY.md:{})".format(source, row.lineno))
                    self.generated.add(False)
        for item in inputs:
            lines = input_lines.get(item.path, [])
            if not lines:
                self.fail("GENERATED input unlisted", item.path)
                self.generated.add(False)
            elif len(lines) > 1:
                self.fail(
                    "GENERATED input listed twice",
                    "{} (TRACEABILITY.md:{})".format(item.path, ", :".join(str(lineno) for lineno in lines)),
                )
                self.generated.add(False)
        origins = generated_origins(self.project, inputs, generated)
        for name in sorted(generated):
            rows = sorted(self.coverage.get(name, set()))
            if not rows:
                self.fail("GENERATED unlisted", name)
                self.generated.add(False)
            elif len(rows) > 1:
                self.fail(
                    "GENERATED listed twice",
                    "{} (TRACEABILITY.md:{})".format(
                        name, ", :".join(str(self.matrix.generated[index].lineno) for index in rows)
                    ),
                )
                self.generated.add(False)
            else:
                ok = True
                row = self.matrix.generated[rows[0]]
                package = package_of(name)
                if package not in row.packages:
                    self.fail(
                        "GENERATED package mismatch",
                        "{} (package {} not in TRACEABILITY.md:{})".format(name, package or "(default)", row.lineno),
                    )
                    ok = False
                listed = row_inputs.get(rows[0])
                sources, package_level = origins[name]
                if len(sources) > 1 and not package_level:
                    self.fail(
                        "GENERATED input ambiguous",
                        "{} (from {}; TRACEABILITY.md:{})".format(name, ", ".join(sources), row.lineno),
                    )
                    ok = False
                elif listed is not None and listed not in sources:
                    self.fail(
                        "GENERATED input mismatch",
                        "{} (TRACEABILITY.md:{} lists {}; class comes from {})".format(
                            name, row.lineno, listed, ", ".join(sources) or "no configured input"
                        ),
                    )
                    ok = False
                self.generated.add(ok)

    def check_replaces(self) -> None:
        """Match each generated row's Replaces source with the forward edges to the classes the row covers.

        See DECISIONS.md D-145.
        """
        java_dir = self.repo / self.example / "src" / "main" / "java"
        originals = {java_fqcn(path) for path in sorted_files(java_dir, "**/*.java")}
        edges: Set[Tuple[str, str, str]] = {
            (row.identity, entry, top)
            for row, (category, entry, top) in self.resolved_entries
            if category == "g" and top is not None
        }
        for source, entry, top in sorted(edges):
            rows = self.coverage.get(top, set())
            if source not in originals or len(rows) != 1:
                continue
            covering_row = self.matrix.generated[next(iter(rows))]
            if source not in covering_row.replaces:
                self.fail(
                    "GENERATED replaces-missing",
                    "{} -> {} (TRACEABILITY.md:{})".format(source, entry, covering_row.lineno),
                )
                self.generated.add(False)
        for index, row in enumerate(self.matrix.generated):
            for source in sorted(set(row.replaces)):
                matched = any(
                    edge_source == source and index in self.coverage.get(top, set())
                    for edge_source, _entry, top in edges
                )
                if not matched:
                    self.fail(
                        "GENERATED replaces-unmatched",
                        "{} <- {} (TRACEABILITY.md:{})".format(source, row.source or "(empty)", row.lineno),
                    )
                    self.generated.add(False)

    # -- reciprocity ---------------------------------------------------------

    def check_reciprocity(self) -> None:
        """Compare the forward edges of every resolved Target and Test entry with the backward links.

        See DECISIONS.md D-145.
        """
        forward_pairs: Set[Tuple[str, str]] = {
            (row.identity, entry) for row, (_category, entry, _top) in self.resolved_entries
        }
        for source, target in sorted(forward_pairs - self.backward_pairs):
            self.fail("RECIPROCITY forward-only", "{} -> {}".format(source, target))
        for source, target in sorted(self.backward_pairs - forward_pairs):
            self.fail("RECIPROCITY backward-only", "{} <- {}".format(source, target))
        self.reciprocal.total += len(forward_pairs | self.backward_pairs)
        self.reciprocal.ok += len(forward_pairs & self.backward_pairs)



# --------------------------------------------------------------------------
# Dump, result line, command line
# --------------------------------------------------------------------------


def _is_within(path: Path, root: Path) -> bool:
    """Whether the resolved ``path`` is ``root`` or lies below it, by name or by directory identity."""
    if path == root or root in path.parents:
        return True
    try:
        root_status = os.stat(str(root))
    except OSError:
        return False
    for candidate in (path,) + tuple(path.parents):
        try:
            status = os.stat(str(candidate))
        except OSError:
            continue
        if (status.st_dev, status.st_ino) == (root_status.st_dev, root_status.st_ino):
            return True
    return False


def dump_destination(value: str, repo: Path) -> Path:
    """Return the ``--dump-sets`` destination ``value`` once it passes the refusal checks.

    The destination is refused with exit 2 when its resolved location, symbolic
    links followed, is ``repo`` or the repository that holds this script or lies
    inside either, when it is an existing directory, and when it is an existing
    file with more than one hard link. See DECISIONS.md D-138.
    """
    path = Path(value)
    try:
        resolved = path.resolve()
    except (OSError, RuntimeError) as exc:
        raise CheckError("dump destination refused: cannot resolve {}: {}".format(value, exc)) from None
    for root in (repo, Path(__file__).resolve().parent.parent):
        if _is_within(resolved, root):
            raise CheckError(
                "dump destination refused: {} resolves to {}, inside the repository {}".format(value, resolved, root)
            )
    try:
        status = os.stat(str(path))
    except (FileNotFoundError, NotADirectoryError):
        return path
    except OSError as exc:
        raise CheckError("dump destination refused: cannot inspect {}: {}".format(value, exc)) from None
    if stat.S_ISDIR(status.st_mode):
        raise CheckError("dump destination refused: {} is a directory".format(value))
    if status.st_nlink > 1:
        raise CheckError("dump destination refused: {} has {} hard links".format(value, status.st_nlink))
    return path


def _resolve_dump(value: str, path: Path) -> Path:
    """Resolve the ``--dump-sets`` destination ``path`` given as ``value``; refuse it when that fails.

    See DECISIONS.md D-138.
    """
    try:
        return path.resolve()
    except (OSError, RuntimeError) as exc:
        raise CheckError("dump destination refused: cannot resolve {}: {}".format(value, exc)) from None


def _directory_within(descriptor: int, root: Path) -> bool:
    """Whether the directory open on ``descriptor`` is ``root`` or lies below it.

    The directory and its ancestors, each reached by opening ``..`` relative to
    the previous descriptor and never through a path name, are compared with
    ``root`` by device and inode. A ``root`` that cannot be inspected matches
    nothing; a chain of more than 4096 ancestors counts as inside. See
    DECISIONS.md D-138.
    """
    try:
        root_status = os.stat(str(root))
    except OSError:
        return False
    target = (root_status.st_dev, root_status.st_ino)
    flags = getattr(os, "O_PATH", os.O_RDONLY) | getattr(os, "O_DIRECTORY", 0)
    status = os.fstat(descriptor)
    current = descriptor
    try:
        for _ in range(4096):
            identity = (status.st_dev, status.st_ino)
            if identity == target:
                return True
            parent = os.open("..", flags, dir_fd=current)
            previous, current = current, parent
            if previous != descriptor:
                os.close(previous)
            status = os.fstat(current)
            if (status.st_dev, status.st_ino) == identity:
                return False
        return True
    finally:
        if current != descriptor:
            os.close(current)


def _repository_identities(
    roots: Iterable[Path],
) -> Tuple[Dict[Tuple[int, int], Path], Dict[Tuple[int, int], Path]]:
    """Map the device and inode of every folder and regular file under ``roots`` to the root that holds it.

    Each root, itself included, is listed folder by folder without following
    symbolic links and through the mount points inside it. The first mapping
    holds folders and the second regular files, each tagged with the first root
    that reached it; a folder already mapped is not listed again. An entry whose
    listing reports neither device nor inode is inspected again by path. A root,
    folder or entry that cannot be inspected is skipped. See DECISIONS.md D-138.
    """
    folders: Dict[Tuple[int, int], Path] = {}
    files: Dict[Tuple[int, int], Path] = {}
    for root in roots:
        try:
            root_status = os.stat(str(root))
        except OSError:
            continue
        identity = (root_status.st_dev, root_status.st_ino)
        if not stat.S_ISDIR(root_status.st_mode) or identity in folders:
            continue
        folders[identity] = root
        pending = [str(root)]
        while pending:
            try:
                with os.scandir(pending.pop()) as entries:
                    for entry in entries:
                        try:
                            status = entry.stat(follow_symlinks=False)
                            if not status.st_dev and not status.st_ino:
                                status = os.stat(entry.path, follow_symlinks=False)
                        except OSError:
                            continue
                        identity = (status.st_dev, status.st_ino)
                        if stat.S_ISDIR(status.st_mode):
                            if identity not in folders:
                                folders[identity] = root
                                pending.append(entry.path)
                        elif stat.S_ISREG(status.st_mode):
                            files.setdefault(identity, root)
            except OSError:
                continue
    return folders, files


def open_dump_file(path: Path, repo: Path) -> int:
    """Open the ``--dump-sets`` destination ``path`` for writing; return its descriptor, emptied.

    The checks of ``dump_destination`` run again and the destination is resolved
    again. Where ``os.open`` accepts ``dir_fd`` and ``O_NOFOLLOW`` and
    ``O_DIRECTORY`` exist, the resolved parent folder is opened first and is
    refused unless the destination still resolves to the same path, that path's
    parent still has the opened folder's device and inode, and the folder, by
    path and by the ``..`` chain of its descriptor, is neither ``repo`` nor the
    repository holding this script nor inside either. The file is then opened by
    its resolved name relative to that folder descriptor, created when missing,
    not truncated, and refused when the name is a symbolic link. Elsewhere the
    resolved path is opened without truncation, an existing file without
    ``O_CREAT`` and a missing one with ``O_CREAT | O_EXCL``, and is refused
    unless the destination, resolved again, has the opened file's device and
    inode and lies outside both repositories. Either way the opened file must be
    a regular file with one link before it is truncated to zero length. The
    folders and regular files of both repositories are listed once by device and
    inode, and the folder that receives the file, before the file is opened, and
    the opened file, before it is truncated, are refused when they match one. A
    refusal raises ``dump destination refused: ...`` and an operating-system
    error ``cannot write <path>: ...``; neither leaves a descriptor open. See
    DECISIONS.md D-138.
    """
    value = str(path)
    dump_destination(value, repo)
    roots = (repo, Path(__file__).resolve().parent.parent)
    resolved = _resolve_dump(value, path)
    if not resolved.name:
        raise CheckError("dump destination refused: {} is a directory".format(value))
    folders, files = _repository_identities(roots)
    nofollow = getattr(os, "O_NOFOLLOW", 0)
    directory = getattr(os, "O_DIRECTORY", 0)
    nonblock = getattr(os, "O_NONBLOCK", 0)
    flags = os.O_WRONLY | nonblock | getattr(os, "O_NOCTTY", 0) | getattr(os, "O_BINARY", 0)
    changed = "dump destination refused: {} changed during the run".format(value)
    folder = -1
    descriptor = -1
    try:
        if nofollow and directory and os.open in os.supports_dir_fd:
            folder = os.open(str(resolved.parent), getattr(os, "O_PATH", os.O_RDONLY) | directory)
            opened = os.fstat(folder)
            if _resolve_dump(value, path) != resolved:
                raise CheckError(changed)
            current = os.stat(str(resolved.parent))
            if (current.st_dev, current.st_ino) != (opened.st_dev, opened.st_ino):
                raise CheckError(changed)
            for root in roots:
                if _is_within(resolved.parent, root):
                    raise CheckError(
                        "dump destination refused: {} resolves to {}, inside the repository {}".format(
                            value, resolved, root
                        )
                    )
                if _directory_within(folder, root):
                    raise CheckError(
                        "dump destination refused: the folder opened for {} is inside the repository {}".format(
                            value, root
                        )
                    )
            inside = folders.get((opened.st_dev, opened.st_ino))
            if inside is not None:
                raise CheckError(
                    "dump destination refused: the folder opened for {} is inside the repository {}".format(
                        value, inside
                    )
                )
            try:
                descriptor = os.open(resolved.name, flags | os.O_CREAT | nofollow, 0o666, dir_fd=folder)
            except OSError as exc:
                if exc.errno in (errno.ELOOP, errno.EMLINK):
                    raise CheckError("dump destination refused: {} is a symbolic link".format(value)) from None
                raise
        else:
            parent = os.stat(str(resolved.parent))
            inside = folders.get((parent.st_dev, parent.st_ino))
            if inside is not None:
                raise CheckError(
                    "dump destination refused: the folder opened for {} is inside the repository {}".format(
                        value, inside
                    )
                )
            try:
                descriptor = os.open(str(resolved), flags)
            except FileNotFoundError:
                descriptor = os.open(str(resolved), flags | os.O_CREAT | os.O_EXCL, 0o666)
            opened = os.fstat(descriptor)
            again = _resolve_dump(value, path)
            current = os.stat(str(again))
            if (current.st_dev, current.st_ino) != (opened.st_dev, opened.st_ino):
                raise CheckError(changed)
            for root in roots:
                if _is_within(again, root):
                    raise CheckError(
                        "dump destination refused: {} resolves to {}, inside the repository {}".format(
                            value, again, root
                        )
                    )
            parent = os.stat(str(again.parent))
            inside = folders.get((parent.st_dev, parent.st_ino))
            if inside is not None:
                raise CheckError(
                    "dump destination refused: the folder opened for {} is inside the repository {}".format(
                        value, inside
                    )
                )
        status = os.fstat(descriptor)
        if not stat.S_ISREG(status.st_mode):
            raise CheckError("dump destination refused: {} is not a regular file".format(value))
        if status.st_nlink != 1:
            raise CheckError("dump destination refused: {} has {} hard links".format(value, status.st_nlink))
        inside = files.get((status.st_dev, status.st_ino))
        if inside is not None:
            raise CheckError(
                "dump destination refused: the file opened for {} is inside the repository {}".format(value, inside)
            )
        if nonblock:
            os.set_blocking(descriptor, True)
        os.ftruncate(descriptor, 0)
    except OSError as exc:
        if descriptor >= 0:
            os.close(descriptor)
        raise CheckError("cannot write {}: {}".format(value, exc)) from None
    except BaseException:
        if descriptor >= 0:
            os.close(descriptor)
        raise
    finally:
        if folder >= 0:
            os.close(folder)
    return descriptor


def write_dump(
    path: Path,
    repo: Path,
    model: SourceModel,
    required: Optional[Set[str]],
    generated: Optional[Iterable[str]],
) -> None:
    """Write the computed sets as sorted JSON to the descriptor of ``open_dump_file``.

    Unknown parts are null (DECISIONS.md D-135). Text that UTF-8 cannot
    encode, such as a name read from a file name with undecodable bytes,
    raises CheckError before the destination is opened.
    """
    bind = sorted(model.bind_items, key=lambda item: (item.element.path, item.element.line, item.element.qname))
    data = {
        "example": model.example,
        "source_exact": sorted(model.exact_set),
        "source_bind": [
            {"locator": item.locator, "element": item.element.qname, "rule": item.rule} for item in bind
        ],
        "raml_scenarios_minimum": sorted(model.raml_minimum),
        "scenario_inventory": sorted(model.scenarios),
        "scenarios_file": None if model.raml_backed else sorted(entry for _, entry in model.scenario_lines),
        "branch_outcomes": {
            locator: {outcome: list(identities) for outcome, identities in outcomes.items()}
            for locator, outcomes in sorted(model.branch_map.items())
        },
        "backward_required": sorted(required) if required is not None else None,
        "generated": sorted(generated) if generated is not None else None,
    }
    try:
        payload = (json.dumps(data, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode("utf-8")
    except UnicodeEncodeError as exc:
        raise CheckError("cannot write {}: {}".format(path, exc)) from None
    descriptor = open_dump_file(path, repo)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(payload)
    except OSError as exc:
        raise CheckError("cannot write {}: {}".format(path, exc)) from None


def is_result_record(line: str) -> bool:
    """True for a result line: ``line`` starts with ``Forward: `` and holds no ``|``."""
    return line.startswith(RESULT_PREFIX) and "|" not in line


def remove_result_records(lines: List[str], end: int, records: List[int]) -> int:
    """Drop the result lines at the ascending indexes ``records`` from a section; return its new end.

    Each record lies after the section's heading and before its exclusive end
    ``end``; lines may keep their terminators. A result line with blank lines
    on both sides is removed with the blank line after it, one with a blank
    line on one side only is removed alone, and one with non-blank lines on
    both sides, the section's end counting as non-blank, becomes a blank line
    that keeps the line's terminator.
    """
    for index in reversed(records):
        before_blank = not line_content(lines[index - 1]).strip()
        after_blank = index + 1 < end and not line_content(lines[index + 1]).strip()
        if before_blank and after_blank:
            del lines[index:index + 2]
            end -= 2
        elif before_blank or after_blank:
            del lines[index]
            end -= 1
        else:
            lines[index] = lines[index][len(line_content(lines[index])):]
    return end


def section_without_results(lines: List[str]) -> List[str]:
    """The section ``lines`` (heading first, terminators removed) without result lines and trailing blank lines.

    Result lines are dropped as ``remove_result_records`` drops them; the
    remaining lines, interior blank lines included, are returned unchanged.
    """
    section = list(lines)
    records = [index for index in range(1, len(section)) if is_result_record(section[index])]
    end = remove_result_records(section, len(section), records)
    while end > 1 and not section[end - 1].strip():
        end -= 1
    return section[:end]


def updated_traceability(text: str, example: str, result: str) -> str:
    """Return ``text`` with ``result`` as the one result line that ends the section.

    A result line that is the section's last non-blank line is set to
    ``result``. Every other result line of the section is removed, together
    with the blank line after it when blank lines enclose it; one between two
    non-blank lines becomes a blank line. Without a result line at the end, the
    result goes after the section's last non-blank line, preceded by one blank
    line; the blank lines that followed stay. See DECISIONS.md D-137.
    """
    lines = split_physical_lines(text)
    bounds = find_section([line_content(line) for line in lines], example)
    if bounds is None:
        raise CheckError("section ## {}-java missing in TRACEABILITY.md".format(example))
    start, end = bounds
    newline = "\r\n" if "\r\n" in text else "\n"
    records = [index for index in range(start + 1, end) if is_result_record(line_content(lines[index]))]
    final = start
    for index in range(end - 1, start, -1):
        if line_content(lines[index]).strip():
            final = index
            break
    placed = bool(records) and records[-1] == final
    if placed:
        content = line_content(lines[final])
        lines[final] = result + lines[final][len(content):]
        records.pop()
    end = remove_result_records(lines, end, records)
    if placed:
        return "".join(lines)
    last = start
    for index in range(end - 1, start - 1, -1):
        if line_content(lines[index]).strip():
            last = index
            break
    if not lines[last].endswith("\n"):
        lines[last] += newline
    lines[last + 1:last + 1] = [newline, result + newline]
    return "".join(lines)


def lock_traceability(path: Path) -> int:
    """Return a descriptor holding an exclusive ``flock`` on the file ``path`` names.

    A lock obtained on a file that ``os.replace`` swapped out during the wait
    is released and requested again on the file now at ``path``. The lock lasts
    until the descriptor is closed; failures raise CheckError.
    """
    while True:
        try:
            descriptor = os.open(str(path), os.O_RDONLY)
        except OSError as exc:
            raise CheckError("cannot lock {}: {}".format(path, exc)) from None
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX)
            locked = os.fstat(descriptor)
            current = os.stat(str(path))
        except OSError as exc:
            os.close(descriptor)
            raise CheckError("cannot lock {}: {}".format(path, exc)) from None
        if (locked.st_dev, locked.st_ino) == (current.st_dev, current.st_ino):
            return descriptor
        os.close(descriptor)


def write_result_line(path: Path, example: str, result: str, checked: List[str]) -> None:
    """Record ``result`` in the section of TRACEABILITY.md under an exclusive lock.

    ``checked`` holds the section lines the check parsed, heading first. With
    the file locked, its text is read and updated, then read again; nothing is
    written when ``section_without_results`` of the section read again differs
    from that of ``checked`` or when the text read again differs from the text
    updated. The update goes through a temporary file in the same folder and
    ``os.replace``; the lock is released after the replacement. See DECISIONS.md
    D-137.
    """
    if fcntl is None:
        raise CheckError(
            "cannot lock {}: file locking (fcntl) is unavailable on this platform; "
            "rerun with --no-write".format(path)
        )
    descriptor = lock_traceability(path)
    try:
        _write_locked(path, example, result, checked)
    finally:
        os.close(descriptor)


def _write_locked(path: Path, example: str, result: str, checked: List[str]) -> None:
    """The read, comparison and replacement steps of ``write_result_line``."""
    text = read_text(path)
    updated = updated_traceability(text, example, result)
    current = read_text(path)
    current_lines = [line_content(line) for line in split_physical_lines(current)]
    bounds = find_section(current_lines, example)
    expected = section_without_results(checked)
    if bounds is None or section_without_results(current_lines[bounds[0]:bounds[1]]) != expected:
        raise CheckError("section ## {}-java changed during the check; rerun".format(example))
    if current != text:
        raise CheckError("{} changed during the update; rerun".format(path))
    if updated == text:
        return
    temp_name: Optional[str] = None
    try:
        with tempfile.NamedTemporaryFile(
            "w",
            encoding="utf-8",
            newline="",
            dir=str(path.parent),
            prefix=".traceability-",
            suffix=".tmp",
            delete=False,
        ) as handle:
            temp_name = handle.name
            handle.write(updated)
        shutil.copymode(str(path), temp_name)
        os.replace(temp_name, str(path))
    except OSError as exc:
        if temp_name is not None:
            try:
                os.unlink(temp_name)
            except OSError:
                pass
        raise CheckError("cannot write {}: {}".format(path, exc)) from None


EPILOG = """\
usage:
  python3 tools/traceability_check.py <example>
  Run from the repository root after `mvn -B clean package` in <example>-java/.
  <example> is the original folder name; a trailing -java is accepted. It is
  taken as given: empty, . or .., surrounding whitespace, /, \\, : or a
  non-printable character make it an invalid example name.
  --dump-sets FILE resolves outside --repo-root and the repository holding
  this script, checked again at each dump write; a directory, a file that is
  not regular or has several hard links, and a FILE whose path changes during
  the run are refused.

exit codes:
  0  every figure is complete and no failure line is printed
  1  at least one figure is incomplete or a failure line is printed; every gap is printed
  2  usage or environment error: invalid example name; example folder missing
     or outside the repository; --dump-sets FILE refused or not writable; mule-deploy.properties
     missing or without config.resources; listed config missing; XML or RAML
     parse error; line-alignment mismatch; a flow, sub-flow, batch:job,
     batch:step or munit:test without name; TRACEABILITY.md, DECISIONS.md or the
     section missing, the section present twice or a table row with more cells
     than its header; <example>-java, target/classes or target/test-classes
     missing (run mvn -B clean package in <example>-java first); javap missing,
     exiting non-zero, exceeding its time limit or printing output the script
     cannot parse; transform inventory (D-149) and parsed source disagree;
     scenario or branch inventory (D-151) without an entry for the example or
     disagreeing with the parsed source; SCENARIOS.txt of a non-RAML-backed
     project missing, unreadable or without an identity line; a Tier 1 test
     source malformed, or holding a @DisplayName on no method or type, or on a
     method with an argument other than one string literal (D-148);
     <example>-java/pom.xml missing or unparseable; a jaxb2-maven-plugin
     source missing or outside the project, an unsupported sourceType, an
     unresolved ${...} in its pom.xml configuration, an unreadable or
     unparseable WSDL or XSD input, or a missing local schemaLocation or
     wsdl:import location; a file that cannot be read; TRACEABILITY.md that
     cannot be locked (without fcntl, rerun with --no-write) or written; the
     section changed during the check (rerun)
  3  the section contains "EXCLUDED \u2014 pending sapjco3.jar" (checked before the
     source is read); nothing is written

TRACEABILITY.md grammar (section "## <example>-java"):
  tables       forward   Source identity | Target | Test | Decision
               backward  Target identity | Maps to source identity or D-ID
               generated WSDL or XSD | Generated package | Generated classes | Replaces source
  cells        backticks and surrounding ** or __ are removed; \\| is a literal pipe
  list cells   split on <br>, ";" and ","; Generated classes also on whitespace;
               n/a, none, "\u2014" and "-" are no entry; Target and Test drop a trailing ()
  source identities
    <example>/src/main/app/mule-deploy.properties
    <config>#flow:<name>  #sub-flow:<name>  #batch:job:<name>  #batch:step:<name>
    <config>#<strategy element>:<line>[/<branch n>]
    <raml>#<METHOD> <resource path>
    <FQCN> of src/main/java; <FQCN>#<method> of a JUnit @Test; <munit file>#<test name>
    <example>_<scenario>: every scenario of the example in the scenario
      inventory (D-151); a RAML-backed project adds its RAML scenarios below
    DW-nn <path>:<first line>, SC-nn <path>:<first line>: every ID of the
      example in the transform inventory (D-149), at its listed path and first
      line; a row may add -<last line>, equal to the listed one
    <path>:<line>: every MEL line of the example in the transform inventory
    <config>#<element>:<name or line>: optional; a direct child of <mule> other
      than flow, sub-flow, batch:job and *-exception-strategy
  locators     a trailing [<path>:<line>; ...] group of a Source identity cell;
               in an active config a locator names an element start-tag line or
               an inventory line, in any other file of the example folder a
               non-blank line; a locator on the start-tag line of a DataWeave
               setter, an outermost scripting element, expression-component
               or apikit:flow-mapping binds that element; branches bind as
               below
  targets      <FQCN>#<method>, <FQCN> (binary name with $) or src/test/capture/<File>.java;
               a nested class's methods resolve too, generated ones included
  decisions    D-nnn list of DECISIONS.md rows; empty or one n/a, none, "\u2014" or "-" is no decision;
               other content fails, an empty list item included; a row without Target and Test cites one
  backward     rows cover every handwritten class and public method, capture file, and
               class or method a Target or Test cell names, generated ones included;
               links are forward identities, bare DW-nn or SC-nn, or D-nnn
  generated    WSDL or XSD names one jaxb2-maven-plugin xjc input (project path, <example>-java/
               path or unique file name) that each listed class comes from (xjc episode or package)
  result line  Forward: a/b identities, c/d edges; Backward: e/f classes,
               g/h generated; Reciprocal: i/j edges (end of the section); the
               section's other lines starting with "Forward: " and holding no |
               are removed, or become blank lines between two non-blank lines

Scenarios and branches (D-151):
  SCENARIOS.txt    <example>-java/src/test/resources/fixtures/SCENARIOS.txt,
                   one identity per line (blank and # lines skipped). In a
                   non-RAML-backed project it is required and lists exactly the
                   inventory scenarios: each absent identity, each line outside
                   the inventory and each repeated line fails. A RAML-backed
                   project has none; a file there fails.
  RAML scenarios   <example>_<verb>-<path>-<status> per declared status, the
                   five router cases and console-try-it; a row equal to one or
                   extending it with -<suffix> meets it
  branch outcomes  the branch inventory maps every when and otherwise to its
                   scenarios, and every outermost *-filter (no *-filter
                   ancestor) to its rejected and accepted scenarios
  when, otherwise  bound by a row of one of its scenarios whose locator group
                   names its start-tag line
  outermost filter bound by such a row of a rejected scenario and one of an
                   accepted scenario
  nested filter    bound when its outermost filter is bound
  choice           bound when every when and otherwise child is bound
  other rows       locators of rows that are no scenario rows never bind a
                   when, otherwise, filter or choice; a scenario row locating
                   a when, otherwise or filter whose outcomes do not list it
                   fails (SCENARIO misbound)

Tier 1 scenario tests (D-148):
  Each inventory scenario identity, and each RAML scenario row, is the
  @DisplayName of exactly one test method in src/test/java outside it/ and
  capture/ (no *IT.java, no FixtureParityTest.java): a method with a body,
  neither private nor static, carrying JUnit Jupiter @Test, @RepeatedTest,
  @ParameterizedTest or @TestTemplate and returning void, or @TestFactory and
  returning a value, directly or through an @interface of those sources. An
  @interface passes its meta-annotations (@Disabled and @Nested included) on
  only with @Retention(RetentionPolicy.RUNTIME); any other retention ends the
  chain. The method and its enclosing types are not @Disabled, and it is a
  member of a top-level type or of a non-static @Nested inner class. Helper,
  lifecycle and type-level display names do not count; a count other than 1
  lists every location of the value.

manual negative self-tests:
  Removing one forward row, renaming a DW-nn or SC-nn row to an ID outside the
  transform inventory, swapping two valid Target entries, moving a scenario's
  @DisplayName from its test method to a helper, citing a D-ID absent from
  DECISIONS.md, or removing a filter's reject scenario from SCENARIOS.txt and
  the matrix each makes the script exit 1; deleting the SCENARIOS.txt of a
  non-RAML-backed project makes it exit 2; a fully mapped project (for example
  hello-world) exits 0.

See DECISIONS.md D-075 and D-132 to D-136.
"""


class _ArgumentParser(argparse.ArgumentParser):
    """Argument parser whose usage errors exit 2 with one stderr line.

    The line is ``error: <message> (usage: python3 tools/traceability_check.py
    [options] <example>; see --help)``; line breaks in the message become spaces.
    """

    usage_hint = "usage: python3 tools/traceability_check.py [options] <example>; see --help"

    def error(self, message: str) -> NoReturn:
        self.exit(2, "error: {} ({})\n".format(" ".join(message.splitlines()), self.usage_hint))


def build_parser() -> argparse.ArgumentParser:
    """Command-line interface: positional example, --repo-root, --javap, --no-write, --dump-sets."""
    parser = _ArgumentParser(
        prog="traceability_check.py",
        description="Check the ## <example>-java section of TRACEABILITY.md against the original\n"
        "example and the compiled output of <example>-java.",
        epilog=EPILOG,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("example", help="original example folder name, with or without a trailing -java")
    parser.add_argument(
        "--repo-root",
        metavar="DIR",
        default=str(Path(__file__).resolve().parent.parent),
        help="repository root (default: the parent of the tools folder)",
    )
    parser.add_argument(
        "--javap",
        metavar="PATH",
        default=None,
        help="javap executable (default: $JAVA_HOME/bin/javap, else javap on PATH)",
    )
    parser.add_argument("--no-write", action="store_true", help="never rewrite TRACEABILITY.md")
    parser.add_argument(
        "--dump-sets",
        metavar="FILE",
        default=None,
        help="write the computed sets as JSON to FILE and continue the run; FILE must resolve outside "
        "--repo-root and the repository holding this script, and must be a new file or a regular file with one link",
    )
    return parser


def normalize_example(repo: Path, name: str) -> str:
    """Map ``name`` (optionally ending in -java) to an existing original folder name.

    ``name`` is one folder-name component, used as given with its case. It is an
    invalid example name when it is empty, ``.`` or ``..``, has leading or
    trailing whitespace, or contains ``/``, ``\\``, ``:`` or a non-printable
    character. The folder must resolve to a direct child of the resolved ``repo``. See DECISIONS.md D-138.
    """
    if (
        not name
        or name in (".", "..")
        or name != name.strip()
        or any(char in "/\\:" or not char.isprintable() for char in name)
    ):
        raise CheckError("invalid example name: {!r}".format(name))
    value = name
    base = name[: -len("-java")]
    if name.endswith("-java") and base and os.path.isdir(str(repo / base)):
        value = base
    folder = repo / value
    if not os.path.isdir(str(folder)):
        raise CheckError("example folder missing: {}".format(folder))
    try:
        resolved = folder.resolve()
        root = repo.resolve()
    except (OSError, RuntimeError) as exc:
        raise CheckError("cannot resolve {}: {}".format(folder, exc)) from None
    if resolved.parent != root:
        raise CheckError("example folder outside the repository: {} resolves to {}".format(folder, resolved))
    return value


def run(args: argparse.Namespace) -> int:
    """Run the check: example and dump destination, excluded section, source, section, decisions, project,
    compiled output, checks."""
    repo = Path(args.repo_root).resolve()
    example = normalize_example(repo, args.example)
    dump_path = dump_destination(args.dump_sets, repo) if args.dump_sets else None
    project = repo / "{}-java".format(example)
    trace_path = repo / "TRACEABILITY.md"
    lines: Optional[List[str]] = None
    bounds: Optional[Tuple[int, int]] = None
    if trace_path.is_file():
        lines = [line_content(line) for line in split_physical_lines(read_text(trace_path))]
        bounds = find_section(lines, example)
    if lines is not None and bounds is not None and any(EXCLUDED_TEXT in lines[index] for index in range(*bounds)):
        raise CheckError(EXCLUDED_TEXT, code=3)
    model = build_source_model(repo, example, project)
    if dump_path is not None:
        write_dump(dump_path, repo, model, None, None)

    if lines is None:
        raise CheckError("TRACEABILITY.md missing at {}".format(repo))
    if bounds is None:
        raise CheckError("section ## {}-java missing in TRACEABILITY.md".format(example))
    start, end = bounds
    matrix = parse_matrix([(index + 1, lines[index]) for index in range(start + 1, end)], example)

    decisions_path = repo / "DECISIONS.md"
    if not decisions_path.is_file():
        raise CheckError("DECISIONS.md missing at {}".format(repo))
    decisions = parse_decisions(read_text(decisions_path))

    if not project.is_dir():
        raise CheckError(
            "{}-java missing: {}".format(example, BUILD_HINT.format(project="{}-java".format(example)))
        )
    javap = resolve_javap(args.javap)
    compiled = load_compiled(project, example, javap)

    checker = Checker(repo, example, project, model, matrix, decisions, compiled, javap)
    checker.run()
    if dump_path is not None:
        write_dump(dump_path, repo, model, checker.required, compiled.generated)
    if not args.no_write:
        write_result_line(trace_path, example, checker.result_line(), lines[start:end])
    for line in checker.report_lines():
        print(line)
    return 0 if checker.passed else 1


def main(argv: Optional[List[str]] = None) -> int:
    """Command-line entry point; returns the exit status."""
    args = build_parser().parse_args(argv)
    try:
        return run(args)
    except CheckError as exc:
        if exc.code == 3:
            print(str(exc))
        else:
            print("error: {}".format(exc), file=sys.stderr)
        return exc.code
    except OSError as exc:
        print("error: {}".format(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())

