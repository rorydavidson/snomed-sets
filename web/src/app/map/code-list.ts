import { SourceRow } from '../core/map-api';
import { delimiterOf, splitDelimited } from '../value-set/identifier-list';

/**
 * Reads a pasted or uploaded list of local codes in the browser. Only the code and term columns
 * picked out here are sent to the server; the file itself, and any other column in it, never
 * leaves the browser.
 *
 * Understood: CSV or TSV, with or without a header row, and plain lines of a code followed by
 * its term.
 */
export interface ParsedCodeList {
  rows: string[][];
  /** Column names, from the header row or made up when there is none. */
  columns: string[];
  hasHeader: boolean;
  codeColumn: number;
  /** Null when there is only one column. */
  termColumn: number | null;
}

const CODE_HEADER = /\bcode\b|\bid\b|identifier/i;
const TERM_HEADER = /term|description|display|name|label|text/i;

/** @param firstRowIsHeader the person's own answer; left out to guess from the first row */
export function parseCodeList(text: string, firstRowIsHeader?: boolean): ParsedCodeList {
  const lines = text
    .replace(/^﻿/, '')
    .split(/\r?\n/)
    .filter((line) => line.trim() !== '');
  if (lines.length === 0) {
    return { rows: [], columns: [], hasHeader: false, codeColumn: 0, termColumn: null };
  }
  const delimiter = delimiterOf(lines);
  const rows = lines.map((line) => (delimiter === null ? splitOnFirstSpace(line) : splitDelimited(line, delimiter)));
  const width = Math.max(...rows.map((row) => row.length));
  const hasHeader = firstRowIsHeader ?? looksLikeHeader(rows[0]);
  const columns = Array.from({ length: width }, (_, index) => (hasHeader && rows[0][index] ? rows[0][index] : `Column ${index + 1}`));

  const named = (pattern: RegExp) => (hasHeader ? columns.findIndex((column) => pattern.test(column)) : -1);
  const codeColumn = Math.max(named(CODE_HEADER), 0);
  const namedTerm = columns.findIndex((column, index) => index !== codeColumn && hasHeader && TERM_HEADER.test(column));
  const firstOther = columns.findIndex((_, index) => index !== codeColumn);
  return {
    rows: hasHeader ? rows.slice(1) : rows,
    columns,
    hasHeader,
    codeColumn,
    termColumn: namedTerm >= 0 ? namedTerm : firstOther >= 0 ? firstOther : null,
  };
}

/** The rows to map, given the columns the person chose. Lines with no code are left out. */
export function sourceRowsOf(list: ParsedCodeList, codeColumn: number, termColumn: number | null): SourceRow[] {
  return list.rows
    .map((row) => ({
      code: (row[codeColumn] ?? '').trim(),
      term: termColumn === null ? '' : (row[termColumn] ?? '').replace(/\s+/g, ' ').trim(),
    }))
    .filter((row) => row.code !== '');
}

/** Codes that appear more than once, each named once. The server refuses a list that has any. */
export function repeatedCodes(rows: SourceRow[]): string[] {
  const seen = new Set<string>();
  const repeated = new Set<string>();
  for (const row of rows) {
    if (seen.has(row.code)) {
      repeated.add(row.code);
    }
    seen.add(row.code);
  }
  return [...repeated];
}

/** Every cell of a header names a column, so none of them is a number and one says what it holds. */
function looksLikeHeader(row: string[]): boolean {
  return row.every((cell) => !/^\d/.test(cell)) && row.some((cell) => CODE_HEADER.test(cell) || TERM_HEADER.test(cell));
}

/** "A12 Asthma" becomes a code and a term. */
function splitOnFirstSpace(line: string): string[] {
  const match = /^(\S+)\s+(.*)$/.exec(line.trim());
  return match ? [match[1], match[2].trim()] : [line.trim()];
}
