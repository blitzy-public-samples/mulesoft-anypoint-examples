#!/usr/bin/env python3
"""Identity check of one project section of the root TRACEABILITY.md.

The script parses the original Mule example (deploy descriptor, active
configurations, RAML files, Java sources, JUnit and MUnit tests), the scenario
list of the converted project and the compiled output of ``<example>-java``
read through ``javap -p -v``. It compares them with the forward, backward and
generated-types tables of the ``## <example>-java`` section, prints every gap,
writes the result line at the end of the section and exits 0 only when every
figure is complete.

See DECISIONS.md D-075.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, NoReturn, Optional, Set, Tuple

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

# TRACEABILITY.md cell grammar.
LOCATOR_RE = re.compile(r"^(?P<path>[^\s:#\[\]]+):(?P<line>\d+)(?:-\d+)?$")
ID_ROW_RE = re.compile(r"^(?P<kind>DW|SC)-(?P<num>\d+)\s+(?P<loc>[^\s:#\[\]]+:\d+(?:-\d+)?)$")
BARE_ID_RE = re.compile(r"^(?:DW|SC)-\d+$")
SCENARIO_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9-]*_[A-Za-z0-9][A-Za-z0-9-]*$")
LOCATOR_GROUP_RE = re.compile(r"^(?P<identity>.*?)\s*\[(?P<group>[^\[\]]*)\]$", re.S)
D_ID_RE = re.compile(r"^D-\d{3,}$")
D_TOKEN_RE = re.compile(r"(?<![A-Za-z0-9_])D-\d{3,}")
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
DISPLAY_NAME_RE = re.compile(
    r'@(?:org\.junit\.jupiter\.api\.)?DisplayName\s*\(\s*"((?:[^"\\\n]|\\.)*)"\s*\)'
)
TYPE_KEYWORD_RE = re.compile(r"\b(?:class|interface|enum|record)\b")

# RAML 0.8 indentation scan.
RAML_KEY_RE = re.compile(
    r"^(?P<indent>\s*)(?P<dash>-\s+)?(?P<key>\"[^\"]*\"|'[^']*'|[^\s:#][^:]*?)\s*:(?:\s+(?P<value>.*?))?\s*$"
)
RAML_RESOURCE_RE = re.compile(r"^(\s*)(/[^\s:]*)\s*:")
RAML_STATUS_RE = re.compile(r"^\s*(\d{3})\s*:")
BLOCK_SCALAR_RE = re.compile(r"^[|>](?:[+-]?\d?|\d[+-])$")

# javap -p -v output.
THIS_CLASS_RE = re.compile(r"^\s*this_class:\s*#\d+\s*//\s*(\S+)")
SUPER_CLASS_RE = re.compile(r"^\s*super_class:\s*#\d+\s*//\s*(\S+)")


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
    except ET.ParseError as exc:
        raise CheckError("XML parse error in {}: {}".format(rel_path, exc)) from None
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

    Blank lines, ``#`` comments and ``---`` are skipped; block scalars skip the
    lines indented deeper than their key; flow collections skip lines until
    their brackets balance; ``!include`` values are not followed.
    """
    lines = text.replace("\r", "").split("\n")
    resources: List[_RamlResource] = []
    methods: List[RamlMethod] = []
    context: Optional[_RamlMethodContext] = None
    index = 0
    while index < len(lines):
        line = lines[index].rstrip()
        lineno = index + 1
        index += 1
        content = line.lstrip()
        if not content or content.startswith("#") or content == "---":
            continue
        match = RAML_KEY_RE.match(line)
        if match is None:
            continue
        key_indent = len(match.group("indent")) + len(match.group("dash") or "")
        key = match.group("key").strip("\"'")
        value = match.group("value") or ""
        value = "" if value.startswith("#") else re.sub(r"\s+#.*$", "", value)
        resource = None if match.group("dash") else RAML_RESOURCE_RE.match(line)

        while resources and resources[-1].indent >= key_indent:
            resources.pop()
        if context is not None and key_indent <= context.indent:
            context = None
        if resources and resources[-1].child is None:
            resources[-1].child = key_indent

        if resource is not None:
            parent = resources[-1].path if resources else ""
            resources.append(_RamlResource(key_indent, parent + resource.group(2)))
        elif context is not None:
            if context.child is None:
                context.child = key_indent
            if context.responses is not None and key_indent <= context.responses:
                context.responses = None
                context.status_indent = None
            if context.responses is not None:
                if context.status_indent is None:
                    context.status_indent = key_indent
                if key_indent == context.status_indent:
                    status = RAML_STATUS_RE.match(content)
                    if status and status.group(1) not in context.record.statuses:
                        context.record.statuses.append(status.group(1))
            elif key == "responses" and key_indent == context.child:
                context.responses = key_indent
        elif resources and key in RAML_METHODS and key_indent == resources[-1].child:
            record = RamlMethod(rel_path, key.upper(), resources[-1].path)
            methods.append(record)
            context = _RamlMethodContext(key_indent, record)

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
    """Identities parsed from the original example and the scenario list."""

    example: str
    exact: List[str]
    ambiguous: List[str]
    bind_items: List[BindItem]
    anchorable: Set[str]
    raml_files: List[str]
    raml_methods: List[RamlMethod]
    scenarios: Optional[List[str]]

    def __post_init__(self) -> None:
        self.exact_set: Set[str] = set(self.exact)
        self.unique_exact: List[str] = list(dict.fromkeys(self.exact))
        self.bind_by_line: Dict[Tuple[str, int], List[BindItem]] = defaultdict(list)
        for item in self.bind_items:
            self.bind_by_line[(item.element.path, item.element.line)].append(item)
        self.scenario_set: Set[str] = set(self.scenarios or [])
        self.raml_backed = bool(self.raml_files)
        self.raml_minimum: List[str] = (
            raml_minimum(self.example, self.raml_methods) if self.raml_backed else []
        )


def rel_path(repo: Path, path: Path) -> str:
    """Repository-relative POSIX path of ``path``."""
    return path.relative_to(repo).as_posix()


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
    if element.ns == CORE_NS and element.local in ("expression-component", "when", "otherwise"):
        return "one-or-more"
    if element.local.endswith("-filter"):
        return "one-or-more"
    if element.ns == CORE_NS and element.local == "choice":
        return "choice"
    if element.ns.endswith("/apikit") and element.local == "flow-mapping":
        return "flow-mapping"
    return None


def build_source_model(repo: Path, example: str, project: Path) -> SourceModel:
    """Parse the original example into S_exact, S_bind and the anchorable set."""
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
    for name in resources:
        config = app_dir / name
        config_rel = rel_path(repo, config)
        if not config.is_file():
            raise CheckError("config {} listed in config.resources is missing".format(config_rel))
        elements.extend(parse_mule_xml(config, config_rel))

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
        except ET.ParseError as exc:
            raise CheckError("XML parse error in {}: {}".format(munit_rel, exc)) from None
        for test in root.iter("{{{}}}test".format(MUNIT_NS)):
            name = test.get("name")
            if name is None:
                raise CheckError("munit:test without name in {}".format(munit_rel))
            exact.append("{}#{}".format(munit_rel, name))

    scenarios: Optional[List[str]] = None
    scenarios_path = project / "src" / "test" / "resources" / "fixtures" / "SCENARIOS.txt"
    if scenarios_path.is_file():
        scenarios = []
        for raw in read_text(scenarios_path, encoding="utf-8-sig").split("\n"):
            entry = raw.strip()
            if entry and not entry.startswith("#"):
                scenarios.append(entry)
        exact.extend(scenarios)

    seen: Set[str] = set()
    ambiguous: List[str] = []
    for identity in exact:
        if identity in seen:
            ambiguous.append(identity)
        seen.add(identity)

    bind_items: List[BindItem] = []
    anchorable: Set[str] = set()
    for element in elements:
        rule = bind_rule(element)
        if rule is not None:
            bind_items.append(BindItem(element, rule))
        anchorable.add("{}#{}:{}".format(element.path, element.qname, element.line))
        if "name" in element.attrib:
            anchorable.add("{}#{}:{}".format(element.path, element.qname, element.attrib["name"]))

    return SourceModel(
        example=example,
        exact=exact,
        ambiguous=ambiguous,
        bind_items=bind_items,
        anchorable=anchorable,
        raml_files=raml_files,
        raml_methods=raml_methods,
        scenarios=scenarios,
    )



# --------------------------------------------------------------------------
# TRACEABILITY.md section
# --------------------------------------------------------------------------


@dataclass(eq=False)
class ForwardRow:
    """A forward-table row; ``kind`` is exact, id, locator, scenario, anchor or extra."""

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
    """Members of one class file as listed by ``javap -p -v``."""

    name: str
    kind: str
    methods: Set[str] = field(default_factory=set)
    public_methods: Set[str] = field(default_factory=set)


def _is_member_declaration(line: str) -> bool:
    """True for a two-space-indented javap member declaration ending with ``;``."""
    return len(line) > 2 and line.startswith("  ") and not line[2].isspace() and line.endswith(";")


def parse_javap_block(block: str) -> ClassInfo:
    """Parse the text of one ``Classfile`` block of ``javap -p -v``."""
    lines = [line.rstrip() for line in block.split("\n")]
    origin = lines[0].strip() if lines else "(unknown class file)"
    this_name: Optional[str] = None
    super_name: Optional[str] = None
    header = ""
    open_index: Optional[int] = None
    for index in range(1, len(lines)):
        line = lines[index]
        if line == "{":
            open_index = index
            break
        this_match = THIS_CLASS_RE.match(line)
        if this_match and this_name is None:
            this_name = this_match.group(1).replace("/", ".")
        super_match = SUPER_CLASS_RE.match(line)
        if super_match and super_name is None:
            super_name = super_match.group(1).replace("/", ".")
        if not header and line and not line[0].isspace() and line != "Constant pool:":
            header = line
    if this_name is None or open_index is None:
        raise CheckError("unparseable javap output for {}: this_class or member list missing".format(origin))
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

    methods: List[Tuple[str, str, bool]] = []
    instance_fields: Set[str] = set()
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
        index = cursor
        if descriptor is None or flags is None:
            raise CheckError(
                "unparseable javap output for {}: member '{}' lacks descriptor or flags".format(this_name, declaration)
            )
        flag_set = set(re.findall(r"ACC_[A-Z_]+", flags))
        if "ACC_SYNTHETIC" in flag_set or "ACC_BRIDGE" in flag_set:
            continue
        if descriptor.startswith("("):
            if declaration == "static {};":
                continue
            head = declaration.split("(", 1)[0].split()
            name = head[-1] if head else ""
            if not name or "." in name or name == this_name:
                continue
            methods.append((name, descriptor, "ACC_PUBLIC" in flag_set))
        else:
            tokens = declaration[:-1].split()
            if tokens and "ACC_STATIC" not in flag_set:
                instance_fields.add(tokens[-1])

    info = ClassInfo(this_name, kind)
    for name, descriptor, public in methods:
        if kind == "enum" and name in ("values", "valueOf"):
            continue
        if kind == "record" and (
            name in ("equals", "hashCode", "toString")
            or (public and descriptor.startswith("()") and name in instance_fields)
        ):
            continue
        info.methods.add(name)
        if public:
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


def load_compiled(project: Path, example: str, javap: str) -> CompiledModel:
    """Build G from the JAXB sources and run javap over the handwritten classes."""
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
    for root in (classes_dir, test_classes_dir):
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
    for info in run_javap(javap, handwritten_files, "handwritten"):
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


# Resolved Target/Test entry: (category "h" or "g", normalized entry, top-level G class or None).
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
        self._display_counts: Counter = Counter()

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
        self._display_counts = self.scan_display_names()
        active = self.index_forward_rows()
        bindings = self.resolve_forward_rows(active)
        self.check_bindings(bindings)
        self.identity_items(active)
        names = [
            entry.split("#", 1)[0]
            for row in self.matrix.forward
            for entry in row.targets + row.tests
            if "#" in entry
        ]
        load_generated_members(
            self.compiled,
            self.javap,
            [name for name in names if name not in self.compiled.handwritten and name in self.compiled.generated],
        )
        self.check_edges()
        self.required = self.backward_required()
        self.check_backward()
        self.check_generated()
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
        """Set ``row.kind``: exact, id, locator, scenario, anchor or extra (first match wins)."""
        identity = row.identity
        id_match = ID_ROW_RE.match(identity)
        if not identity:
            row.kind = "extra"
        elif identity in self.model.exact_set:
            row.kind = "exact"
        elif id_match is not None:
            row.kind = "id"
            row.id_key = "{}-{}".format(id_match.group("kind"), id_match.group("num"))
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
            row.kind = "locator"
            row.locators = [identity] + row.locators
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
            target = self.repo / path
            self._file_lines[path] = (
                read_text(target, errors="replace").replace("\r\n", "\n").replace("\r", "\n").split("\n")
                if target.is_file()
                else None
            )
        lines = self._file_lines[path]
        return lines is not None and line <= len(lines) and bool(lines[line - 1].strip())

    def resolve_locator(self, locator: str) -> Optional[List[BindItem]]:
        """Bindable elements on the locator line, [] for a plain anchor, None when unresolved."""
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
        return [] if self.valid_anchor(path, line) else None

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

    def check_bindings(self, bindings: Dict[int, List[ForwardRow]]) -> None:
        """Apply the binding rule of every S_bind element."""
        item_by_element: Dict[int, BindItem] = {id(item.element): item for item in self.model.bind_items}
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
            if item.rule == "one-or-more":
                bound = bool(rows)
            elif item.rule == "choice":
                branches = [
                    item_by_element[id(child)]
                    for child in item.element.children
                    if id(child) in item_by_element and child.ns == CORE_NS and child.local in ("when", "otherwise")
                ]
                bound = bool(rows) or (bool(branches) and all(bindings.get(id(branch)) for branch in branches))
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

    def scan_display_names(self) -> Counter:
        """Count ``@DisplayName`` values on methods of the Tier 1 test sources."""
        counts: Counter = Counter()
        base = self.project / "src" / "test" / "java"
        for path in sorted_files(base, "**/*.java"):
            relative = path.relative_to(base)
            if any(part in ("it", "capture") for part in relative.parts[:-1]):
                continue
            if path.name.endswith("IT.java") or path.name == "FixtureParityTest.java":
                continue
            text = strip_java_comments(read_text(path, errors="replace"))
            for match in DISPLAY_NAME_RE.finditer(text):
                cut = re.compile(r"[{;]").search(text, match.end())
                header = text[match.end():cut.start() if cut else len(text)]
                if "(" in header and not TYPE_KEYWORD_RE.search(header):
                    counts[re.sub(r'\\(["\\])', r"\1", match.group(1))] += 1
        return counts

    def display_ok(self, identity: str) -> bool:
        """True when exactly one Tier 1 test method carries ``identity`` as its display name."""
        count = self._display_counts.get(identity, 0)
        if count != 1:
            self.fail("SCENARIO display-name", "count={}: {}".format(count, identity), separator=" ")
        return count == 1

    def identity_items(self, active: List[ForwardRow]) -> None:
        """Add the S_exact, row, duplicate and RAML-minimum items of the identities figure."""
        for identity in self.model.unique_exact:
            row = self.rows_by_identity.get(identity)
            scenario_ok = self.display_ok(identity) if identity in self.model.scenario_set else True
            if row is None:
                self.fail("MISSING forward", identity)
                self.identities.add(False)
                continue
            self.identities.add(row.valid and scenario_ok)
        for identity in self.model.ambiguous:
            self.fail("AMBIGUOUS source identity", identity)
            self.identities.add(False)
        for row in active:
            if row.kind == "exact":
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

    # -- edges ---------------------------------------------------------------

    def resolve_entry(self, entry: str) -> Optional[Resolved]:
        """Resolve a Target/Test entry against the compiled output and capture harnesses."""
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
            if owner in compiled.generated and generated_info is not None and method in generated_info.methods:
                return ("g", entry, owner)
            return None
        if entry in compiled.handwritten:
            return ("h", entry, None)
        top = compiled.covering(entry)
        if top is not None and (entry in compiled.generated or entry in compiled.generated_files):
            return ("g", entry, top)
        return None

    def check_edges(self) -> None:
        """Resolve every Target and Test entry and every Decision D-ID of the forward table."""
        for row in self.matrix.forward:
            for entry in row.targets + row.tests:
                resolved = self.resolve_entry(entry)
                self.edges.add(resolved is not None)
                if resolved is None:
                    self.fail("EDGE unresolved", "{} -> {}".format(row.label, entry))
                else:
                    self.resolved_entries.append((row, resolved))
            tokens = D_TOKEN_RE.findall(row.decision)
            for token in tokens:
                known = token in self.decisions
                self.edges.add(known)
                if not known:
                    self.fail("DECISION unknown", "{} -> {}".format(row.label, token))
            if not row.targets and not row.tests and not tokens:
                self.fail("DECISION required", row.label)
                self.edges.add(False)

    # -- backward table ------------------------------------------------------

    def backward_required(self) -> Set[str]:
        """R: handwritten classes, their public methods, named handwritten methods, capture files."""
        required: Set[str] = set(self.compiled.handwritten)
        for info in self.compiled.handwritten.values():
            required.update("{}#{}".format(info.name, method) for method in info.public_methods)
        for _, (category, entry, _top) in self.resolved_entries:
            if category == "h" and "#" in entry:
                required.add(entry)
        capture_dir = self.project / "src" / "test" / "capture"
        for path in sorted_files(capture_dir, "**/*.java"):
            required.add("src/test/capture/" + path.relative_to(capture_dir).as_posix())
        return required

    def accepted_identities(self) -> Set[str]:
        """Identities of forward rows classified exact, id, locator, scenario or anchor."""
        return {identity for identity, row in self.rows_by_identity.items() if identity and row.kind != "extra"}

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
        """Check the generated-types table against G."""
        accepted = self.accepted_identities()
        generated = self.compiled.generated
        for index, row in enumerate(self.matrix.generated):
            for entry in row.classes:
                if entry.endswith(".*"):
                    package = entry[:-2]
                    matched = [name for name in generated if package_of(name) == package]
                    if not matched:
                        self.fail("GENERATED nonexistent", "{} (TRACEABILITY.md:{})".format(entry, row.lineno))
                        self.generated.add(False)
                    for name in matched:
                        self.coverage[name].add(index)
                elif entry in generated:
                    self.coverage[entry].add(index)
                else:
                    self.fail("GENERATED nonexistent", "{} (TRACEABILITY.md:{})".format(entry, row.lineno))
                    self.generated.add(False)
            for source in row.replaces:
                if source not in accepted:
                    self.fail("GENERATED unresolved replaces", "{} (TRACEABILITY.md:{})".format(source, row.lineno))
                    self.generated.add(False)
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
            elif package_of(name) not in self.matrix.generated[rows[0]].packages:
                self.fail(
                    "GENERATED package mismatch",
                    "{} (package {} not in TRACEABILITY.md:{})".format(
                        name, package_of(name) or "(default)", self.matrix.generated[rows[0]].lineno
                    ),
                )
                self.generated.add(False)
            else:
                self.generated.add(True)

    # -- reciprocity ---------------------------------------------------------

    def check_reciprocity(self) -> None:
        """Compare forward edges with backward links and generated classes with Replaces source."""
        handwritten_pairs: Set[Tuple[str, str]] = set()
        generated_pairs: Set[Tuple[str, str]] = set()
        for row, (category, entry, top) in self.resolved_entries:
            if category == "h":
                handwritten_pairs.add((row.identity, entry))
            elif top is not None:
                generated_pairs.add((row.identity, top))
        for source, target in sorted(handwritten_pairs - self.backward_pairs):
            self.fail("RECIPROCITY forward-only", "{} -> {}".format(source, target))
        for source, target in sorted(self.backward_pairs - handwritten_pairs):
            self.fail("RECIPROCITY backward-only", "{} <- {}".format(source, target))
        self.reciprocal.total += len(handwritten_pairs | self.backward_pairs)
        self.reciprocal.ok += len(handwritten_pairs & self.backward_pairs)
        for source, target in sorted(generated_pairs):
            rows = self.coverage.get(target, set())
            satisfied = len(rows) == 1 and source in self.matrix.generated[next(iter(rows))].replaces
            if not satisfied:
                self.fail("RECIPROCITY replaces-missing", "{} -> {}".format(source, target))
            self.reciprocal.add(satisfied)
        for index, row in enumerate(self.matrix.generated):
            for source in row.replaces:
                satisfied = any(
                    pair_source == source and index in self.coverage.get(target, set())
                    for pair_source, target in generated_pairs
                )
                if not satisfied:
                    self.fail(
                        "RECIPROCITY replaces-unmatched",
                        "{} <- {} (TRACEABILITY.md:{})".format(source, row.source or "(empty)", row.lineno),
                    )
                self.reciprocal.add(satisfied)



# --------------------------------------------------------------------------
# Dump, result line, command line
# --------------------------------------------------------------------------


def write_dump(
    path: Path,
    model: SourceModel,
    required: Optional[Set[str]],
    generated: Optional[Iterable[str]],
) -> None:
    """Write the computed sets as sorted JSON; unknown parts are null."""
    bind = sorted(model.bind_items, key=lambda item: (item.element.path, item.element.line, item.element.qname))
    data = {
        "example": model.example,
        "source_exact": sorted(model.exact_set),
        "source_bind": [
            {"locator": item.locator, "element": item.element.qname, "rule": item.rule} for item in bind
        ],
        "raml_scenarios_minimum": sorted(model.raml_minimum),
        "scenarios_file": sorted(model.scenarios) if model.scenarios is not None else None,
        "backward_required": sorted(required) if required is not None else None,
        "generated": sorted(generated) if generated is not None else None,
    }
    try:
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(data, handle, indent=2, sort_keys=True, ensure_ascii=False)
            handle.write("\n")
    except OSError as exc:
        raise CheckError("cannot write {}: {}".format(path, exc)) from None


def updated_traceability(text: str, example: str, result: str) -> str:
    """Return ``text`` with the section's last ``Forward: `` line set to ``result``.

    Without such a line, the result goes after the section's last non-blank
    line, preceded by one blank line; the blank lines that followed stay.
    """
    lines = split_physical_lines(text)
    bounds = find_section([line_content(line) for line in lines], example)
    if bounds is None:
        raise CheckError("section ## {}-java missing in TRACEABILITY.md".format(example))
    start, end = bounds
    newline = "\r\n" if "\r\n" in text else "\n"
    for index in range(end - 1, start, -1):
        content = line_content(lines[index])
        if content.startswith(RESULT_PREFIX):
            lines[index] = result + lines[index][len(content):]
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


def write_result_line(path: Path, example: str, result: str) -> None:
    """Rewrite TRACEABILITY.md through a temporary file and ``os.replace``."""
    text = read_text(path)
    updated = updated_traceability(text, example, result)
    if updated == text:
        return
    handle = tempfile.NamedTemporaryFile(
        "w", encoding="utf-8", newline="", dir=str(path.parent), prefix=".traceability-", suffix=".tmp", delete=False
    )
    try:
        with handle:
            handle.write(updated)
        shutil.copymode(str(path), handle.name)
        os.replace(handle.name, str(path))
    except OSError as exc:
        if os.path.exists(handle.name):
            os.unlink(handle.name)
        raise CheckError("cannot write {}: {}".format(path, exc)) from None


EPILOG = """\
usage:
  python3 tools/traceability_check.py <example>
  Run from the repository root after `mvn -B clean package` in <example>-java/.
  <example> is the original folder name; a trailing -java is accepted.

exit codes:
  0  every figure is complete
  1  at least one figure is incomplete; every gap is printed
  2  usage or environment error: example folder missing; mule-deploy.properties
     missing or without config.resources; listed config missing; XML parse
     error; line-alignment mismatch; TRACEABILITY.md, DECISIONS.md or the
     section missing; <example>-java, target/classes or target/test-classes
     missing (run mvn -B clean package in <example>-java first); javap missing
     or exiting non-zero
  3  the section contains "EXCLUDED \u2014 pending sapjco3.jar"; nothing is written

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
    <example>_<scenario> (SCENARIOS.txt line, or RAML scenario of a RAML project)
    DW-nn <path>:<line>, SC-nn <path>:<line>; <path>:<line> (MEL anchor)
    <config>#<element>:<name or line> (any other element of an active config)
  locators     a trailing [<path>:<line>; ...] group of a Source identity cell;
               a locator on the start-tag line of a DataWeave setter, an outermost
               scripting element, expression-component, when, otherwise, *-filter,
               choice or apikit:flow-mapping binds that element; any other locator
               names a non-blank line of a file in the example folder
  targets      <FQCN>#<method>, <FQCN> (binary name with $) or src/test/capture/<File>.java
  decisions    D-nnn rows of DECISIONS.md; a row without Target and Test cites one
  backward     links are forward identities, bare DW-nn or SC-nn, or D-nnn
  result line  Forward: a/b identities, c/d edges; Backward: e/f classes,
               g/h generated; Reciprocal: i/j edges (end of the section)

manual negative self-tests:
  Removing one forward row, swapping two valid Target entries, or citing a D-ID
  absent from DECISIONS.md each makes the script exit 1; a fully mapped project
  (for example hello-world) exits 0.

See DECISIONS.md D-075.
"""


class _ArgumentParser(argparse.ArgumentParser):
    """Argument parser whose usage errors print one line and exit 2."""

    def error(self, message: str) -> NoReturn:
        self.exit(2, "error: {}\n".format(message))


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
        help="write the computed sets as JSON to FILE and continue the run",
    )
    return parser


def normalize_example(repo: Path, name: str) -> str:
    """Map ``name`` (optionally ending in -java) to an existing original folder name."""
    value = name.strip().rstrip("/")
    if value.startswith("./"):
        value = value[2:]
    if not value or "/" in value or value in (".", ".."):
        raise CheckError("invalid example name: {!r}".format(name))
    if value.endswith("-java") and (repo / value[: -len("-java")]).is_dir():
        value = value[: -len("-java")]
    if not (repo / value).is_dir():
        raise CheckError("example folder missing: {}".format(repo / value))
    return value


def run(args: argparse.Namespace) -> int:
    """Run the check in the order: source, section, decisions, project, compiled output, checks."""
    repo = Path(args.repo_root).resolve()
    example = normalize_example(repo, args.example)
    project = repo / "{}-java".format(example)
    model = build_source_model(repo, example, project)
    dump_path = Path(args.dump_sets) if args.dump_sets else None
    if dump_path is not None:
        write_dump(dump_path, model, None, None)

    trace_path = repo / "TRACEABILITY.md"
    if not trace_path.is_file():
        raise CheckError("TRACEABILITY.md missing at {}".format(repo))
    lines = [line_content(line) for line in split_physical_lines(read_text(trace_path))]
    bounds = find_section(lines, example)
    if bounds is None:
        raise CheckError("section ## {}-java missing in TRACEABILITY.md".format(example))
    start, end = bounds
    if any(EXCLUDED_TEXT in lines[index] for index in range(start, end)):
        raise CheckError(EXCLUDED_TEXT, code=3)
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
        write_dump(dump_path, model, checker.required, compiled.generated)
    if not args.no_write:
        write_result_line(trace_path, example, checker.result_line())
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


if __name__ == "__main__":
    sys.exit(main())

