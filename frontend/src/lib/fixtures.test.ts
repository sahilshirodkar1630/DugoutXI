import { describe, expect, it } from 'vitest';
import { FIXTURE_TEMPLATE, parseCsv, readFixtures } from './fixtures';

/** Reading an organizer's fixture spreadsheet (saved as CSV) before it is sent for import. */
describe('fixture files', () => {
  it('splits CSV, keeping quoted commas and doubled quotes', () => {
    expect(parseCsv('a,b\r\n"x, y","say ""hi"""\n\n')).toEqual([
      ['a', 'b'],
      ['x, y', 'say "hi"'],
    ]);
  });

  it('reads the template, with Indian day-first dates and 12-hour times', () => {
    const { rows, problems } = readFixtures(FIXTURE_TEMPLATE);
    expect(problems).toEqual([]);
    expect(rows).toHaveLength(2);
    expect(rows[0]).toMatchObject({ teamA: 'MI', teamB: 'CSK', name: 'Opening match', contestNumber: 1 });
    expect(new Date(rows[0].startTime).getHours()).toBe(19);
    expect(new Date(rows[1].startTime).getDate()).toBe(27);
    expect(new Date(rows[1].startTime).getHours()).toBe(15);
    expect(rows[1].name).toBeUndefined();
  });

  it('finds columns by heading in any order', () => {
    const { rows } = readFixtures('Team 2,Team 1,Start Time,Date\nCSK,MI,20:00,2027-04-01\n');
    expect(rows[0]).toMatchObject({ teamA: 'MI', teamB: 'CSK' });
  });

  it('names the line of every row it cannot read, and keeps the rest', () => {
    const { rows, problems } = readFixtures(
      'date,time,team a,team b,match\n31/02/2027,19:30,MI,CSK,\n2027-04-01,25:00,MI,CSK,\n2027-04-01,19:30,,CSK,\n2027-04-02,19:30,MI,CSK,x\n2027-04-03,19:30,RR,GT,\n',
    );
    expect(problems.map((p) => p.line)).toEqual([2, 3, 4, 5]);
    expect(rows).toHaveLength(1);
  });

  it('says which headings are missing', () => {
    const { problems } = readFixtures('date,home\n2027-04-01,MI\n');
    expect(problems[0].message).toContain('missing: time, team b');
  });
});
