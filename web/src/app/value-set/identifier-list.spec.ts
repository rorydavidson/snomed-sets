import { entriesOf, parseList } from './identifier-list';

function entries(text: string) {
  const list = parseList(text);
  return entriesOf(list, list.idColumn, list.termColumn);
}

describe('parseList', () => {
  it('reads one identifier per line, ignoring blank lines', () => {
    expect(entries('195967001\n\n  233678006  \r\n')).toEqual([
      { identifier: '195967001', term: null },
      { identifier: '233678006', term: null },
    ]);
  });

  it('reads identifiers written with their terms as in ECL', () => {
    expect(entries('195967001 |Asthma|\n233678006 |Childhood asthma| OR')).toEqual([
      { identifier: '195967001', term: 'Asthma' },
      { identifier: '233678006', term: 'Childhood asthma' },
    ]);
  });

  it("reads this app's own download, skipping the header and preferring the PT column", () => {
    const list = parseList('SNOMED CT ID\tFSN (en-us)\tPT (en-us)\tActive\n195967001\tAsthma (disorder)\tAsthma\ttrue\n');
    expect(list.hasHeader).toBe(true);
    expect(list.columns).toEqual(['SNOMED CT ID', 'FSN (en-us)', 'PT (en-us)', 'Active']);
    expect(list.idColumn).toBe(0);
    expect(list.termColumn).toBe(2);
    expect(entriesOf(list, 0, 2)).toEqual([{ identifier: '195967001', term: 'Asthma' }]);
  });

  it('finds the identifier column wherever it is, and honours quotes and formula guards', () => {
    const list = parseList('"Name, as given",Code\n"Asthma, unspecified",\'195967001\n"Say ""hi""",233678006');
    expect(list.idColumn).toBe(1);
    expect(list.termColumn).toBe(0);
    expect(entriesOf(list, 1, 0)).toEqual([
      { identifier: '195967001', term: 'Asthma, unspecified' },
      { identifier: '233678006', term: 'Say "hi"' },
    ]);
  });

  it('splits a single line of identifiers separated by commas or spaces', () => {
    expect(entries('195967001, 233678006 389145006').map((entry) => entry.identifier)).toEqual([
      '195967001',
      '233678006',
      '389145006',
    ]);
  });

  it('reads the codes of a FHIR ValueSet, from its expansion or its compose', () => {
    const expanded = { resourceType: 'ValueSet', expansion: { contains: [{ code: '195967001', display: 'Asthma' }] } };
    const composed = { resourceType: 'ValueSet', compose: { include: [{ concept: [{ code: '233678006' }] }] } };
    expect(entries(JSON.stringify(expanded))).toEqual([{ identifier: '195967001', term: 'Asthma' }]);
    expect(entries(JSON.stringify(composed))).toEqual([{ identifier: '233678006', term: null }]);
  });

  it('keeps lines that are not identifiers, so the check can say what is wrong with them', () => {
    expect(entries('195967001\nnot a code').map((entry) => entry.identifier)).toEqual(['195967001', 'not a code']);
  });
});
