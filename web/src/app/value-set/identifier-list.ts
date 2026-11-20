import { IdentifierEntry } from '../core/value-set-api';

/**
 * Reads a pasted or uploaded list of identifiers in the browser. Only the identifiers and
 * terms picked out here are sent to the server; the file itself never leaves the browser.
 *
 * Understood: one identifier per line, "id |term|" lines as written in ECL, CSV or TSV with
 * or without a header (this app's own downloads included), a single line of identifiers
 * separated by spaces or commas, and a FHIR ValueSet in JSON.
 */
export interface ParsedList {
  rows: string[][];
  /** Column names, from the header row or made up when there is none. */
  columns: string[];
  hasHeader: boolean;
  idColumn: number;
  /** Null when no column looks like it holds terms. */
  termColumn: number | null;
}

const IDENTIFIER = /^\d{6,18}$/;
const ECL_STYLE = /^\s*(\d{6,18})\s*\|([^|]*)\|/;
const TERM_HEADER = /\bpt\b|preferred|display|term|description|name|fsn/i;
const DELIMITERS = ['\t', ',', ';'] as const;

export function parseList(text: string): ParsedList {
  const content = text.replace(/^﻿/, '').trim();
  const fhir = content.startsWith('{') ? fhirRows(content) : null;
  if (fhir !== null) {
    return { rows: fhir, columns: ['Code', 'Display'], hasHeader: false, idColumn: 0, termColumn: 1 };
  }
  const lines = content.split(/\r?\n/).filter((line) => line.trim() !== '');
  if (lines.length === 0) {
    return { rows: [], columns: [], hasHeader: false, idColumn: 0, termColumn: null };
  }

  let rows: string[][];
  if (lines.filter((line) => ECL_STYLE.test(line)).length > lines.length / 2) {
    rows = lines.map((line) => {
      const match = ECL_STYLE.exec(line);
      return match ? [match[1], match[2].trim()] : [line.trim()];
    });
  } else if (lines.length === 1 && /^[\d\s,;]+$/.test(lines[0])) {
    rows = lines[0].split(/[\s,;]+/).filter((cell) => cell !== '').map((cell) => [cell]);
  } else {
    const delimiter = delimiterOf(lines);
    rows = lines.map((line) => (delimiter === null ? splitOnFirstSpace(line) : splitDelimited(line, delimiter)));
  }

  const width = Math.max(...rows.map((row) => row.length));
  const idColumn = mostIdentifierLike(rows, width);
  const hasHeader = rows.length > 1 && !IDENTIFIER.test(rows[0][idColumn] ?? '') && IDENTIFIER.test(rows[1][idColumn] ?? '');
  const columns = Array.from({ length: width }, (_, index) =>
    hasHeader && rows[0][index] ? rows[0][index] : `Column ${index + 1}`,
  );
  return {
    rows: hasHeader ? rows.slice(1) : rows,
    columns,
    hasHeader,
    idColumn,
    termColumn: termColumnOf(rows.slice(hasHeader ? 1 : 0), columns, idColumn, hasHeader),
  };
}

/** The entries to check, given the columns the person chose. */
export function entriesOf(list: ParsedList, idColumn: number, termColumn: number | null): IdentifierEntry[] {
  return list.rows
    .map((row) => ({
      identifier: (row[idColumn] ?? '').trim(),
      term: termColumn === null ? null : (row[termColumn] ?? '').trim() || null,
    }))
    .filter((entry) => entry.identifier !== '');
}

function fhirRows(content: string): string[][] | null {
  let resource: unknown;
  try {
    resource = JSON.parse(content);
  } catch {
    return null;
  }
  if (!isRecord(resource) || resource['resourceType'] !== 'ValueSet') {
    return null;
  }
  const expansion = isRecord(resource['expansion']) ? resource['expansion']['contains'] : undefined;
  const compose = isRecord(resource['compose']) ? resource['compose']['include'] : undefined;
  const codings = Array.isArray(expansion)
    ? expansion
    : Array.isArray(compose)
      ? compose.flatMap((include) => (isRecord(include) && Array.isArray(include['concept']) ? include['concept'] : []))
      : [];
  return codings
    .filter(isRecord)
    .filter((coding) => typeof coding['code'] === 'string')
    .map((coding) => [coding['code'] as string, typeof coding['display'] === 'string' ? coding['display'] : '']);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** The delimiter found on most of the first lines, or null for plain lines. */
export function delimiterOf(lines: string[]): string | null {
  const sample = lines.slice(0, 20);
  for (const delimiter of DELIMITERS) {
    if (sample.filter((line) => line.includes(delimiter)).length > sample.length / 2) {
      return delimiter;
    }
  }
  return null;
}

/** "195967001 Asthma" becomes an identifier and a term; a line not starting with a number stays whole. */
function splitOnFirstSpace(line: string): string[] {
  const match = /^(\d+)\s+(.*)$/.exec(line.trim());
  return match ? [match[1], match[2].trim()] : [line.trim()];
}

/** Splits one line of CSV or TSV, honouring double quotes. */
export function splitDelimited(line: string, delimiter: string): string[] {
  const cells: string[] = [];
  let cell = '';
  let quoted = false;
  for (let index = 0; index < line.length; index++) {
    const character = line[index];
    if (quoted) {
      if (character === '"' && line[index + 1] === '"') {
        cell += '"';
        index++;
      } else if (character === '"') {
        quoted = false;
      } else {
        cell += character;
      }
    } else if (character === '"' && cell.trim() === '') {
      quoted = true;
      cell = '';
    } else if (character === delimiter) {
      cells.push(clean(cell));
      cell = '';
    } else {
      cell += character;
    }
  }
  cells.push(clean(cell));
  return cells;
}

/** Spreadsheet downloads often start a cell with an apostrophe so it is not read as a formula. */
function clean(cell: string): string {
  return cell.trim().replace(/^'(?=\d)/, '');
}

function mostIdentifierLike(rows: string[][], width: number): number {
  let best = 0;
  let bestCount = -1;
  for (let column = 0; column < width; column++) {
    const count = rows.filter((row) => IDENTIFIER.test(row[column] ?? '')).length;
    if (count > bestCount) {
      best = column;
      bestCount = count;
    }
  }
  return best;
}

function termColumnOf(rows: string[][], columns: string[], idColumn: number, hasHeader: boolean): number | null {
  const others = columns.map((_, index) => index).filter((index) => index !== idColumn);
  const textual = others.filter((index) => rows.some((row) => /\p{L}/u.test(row[index] ?? '')));
  if (textual.length === 0) {
    return null;
  }
  if (hasHeader) {
    // A preferred term is the closest match to what the server compares against, so it wins over an FSN.
    const preferred = textual.find((index) => /\bpt\b|preferred|display/i.test(columns[index]));
    const named = textual.find((index) => TERM_HEADER.test(columns[index]));
    return preferred ?? named ?? textual[0];
  }
  return textual[0];
}
