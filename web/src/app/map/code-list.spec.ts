import { parseCodeList, repeatedCodes, sourceRowsOf } from './code-list';

describe('parseCodeList', () => {
  it('reads a CSV with a header and finds the code and term columns', () => {
    const list = parseCodeList('Ward,Local code,Description\nA,D01,"Asthma, severe"\nA,D02,Common cold\n');

    expect(list.hasHeader).toBe(true);
    expect(list.columns).toEqual(['Ward', 'Local code', 'Description']);
    expect(list.codeColumn).toBe(1);
    expect(list.termColumn).toBe(2);
    expect(sourceRowsOf(list, list.codeColumn, list.termColumn)).toEqual([
      { code: 'D01', term: 'Asthma, severe' },
      { code: 'D02', term: 'Common cold' },
    ]);
  });

  it('treats the first row as data when it does not look like a header', () => {
    const list = parseCodeList('D01\tAsthma\nD02\tCommon cold');

    expect(list.hasHeader).toBe(false);
    expect(sourceRowsOf(list, 0, 1)).toHaveLength(2);
  });

  it('lets the person overrule the guess about the header', () => {
    const list = parseCodeList('D01\tAsthma\nD02\tCommon cold', true);

    expect(list.columns).toEqual(['D01', 'Asthma']);
    expect(sourceRowsOf(list, 0, 1)).toEqual([{ code: 'D02', term: 'Common cold' }]);
  });

  it('reads plain lines as a code followed by its term', () => {
    const list = parseCodeList('D01 Asthma attack\nD02 Common cold');

    expect(sourceRowsOf(list, list.codeColumn, list.termColumn)).toEqual([
      { code: 'D01', term: 'Asthma attack' },
      { code: 'D02', term: 'Common cold' },
    ]);
  });

  it('sends only the chosen columns and skips lines with no code', () => {
    const list = parseCodeList('code,term,patient\nD01,Asthma,Jane Doe\n,Orphan term,John Roe\n');

    expect(sourceRowsOf(list, 0, 1)).toEqual([{ code: 'D01', term: 'Asthma' }]);
    expect(sourceRowsOf(list, 0, null)).toEqual([{ code: 'D01', term: '' }]);
  });
});

describe('repeatedCodes', () => {
  it('names each repeated code once, and tells codes apart by case', () => {
    expect(
      repeatedCodes([
        { code: 'A1', term: '' },
        { code: 'a1', term: '' },
        { code: 'A1', term: '' },
        { code: 'A1', term: '' },
      ]),
    ).toEqual(['A1']);
  });
});
