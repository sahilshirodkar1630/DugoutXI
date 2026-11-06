import type { FixtureRow } from '../api/types';

/**
 * Reading a fixture list for import.
 *
 * Organizers usually have the schedule in a spreadsheet, so the import takes CSV (Excel and Google Sheets both save
 * it). The columns are found by their headings, in any order:
 *
 *   date, time, team a, team b, [name], [match]
 *
 * Dates may be 2026-04-05, 05/04/2026 or 05-04-2026 (day first, as written in India); times 19:30 or 7:30 PM.
 * Times are read in the device's own time zone. Teams may be short codes (MI) or full names (Mumbai Indians); the
 * backend matches them against the tournament's teams.
 */

export const FIXTURE_TEMPLATE =
  'date,time,team a,team b,name,match\n' +
  '2027-03-26,19:30,MI,CSK,Opening match,1\n' +
  '27/03/2027,3:30 PM,RCB,KKR,,\n';

export interface ParsedFixtures {
  rows: FixtureRow[];
  /** Problems found before anything is sent, by line number in the file (the heading is line 1). */
  problems: { line: number; message: string }[];
}

/** Splits CSV text into cells. Handles quoted cells with commas, doubled quotes and new lines inside them. */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  // Excel puts a byte-order mark at the start of a UTF-8 CSV.
  const source = text.startsWith(String.fromCharCode(0xfeff)) ? text.slice(1) : text;

  for (let i = 0; i < source.length; i++) {
    const ch = source[i];
    if (quoted) {
      if (ch === '"' && source[i + 1] === '"') {
        cell += '"';
        i++;
      } else if (ch === '"') {
        quoted = false;
      } else {
        cell += ch;
      }
    } else if (ch === '"') {
      quoted = true;
    } else if (ch === ',' || ch === ';' || ch === '\t') {
      row.push(cell);
      cell = '';
    } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && source[i + 1] === '\n') i++;
      row.push(cell);
      rows.push(row);
      row = [];
      cell = '';
    } else {
      cell += ch;
    }
  }
  if (cell !== '' || row.length > 0) {
    row.push(cell);
    rows.push(row);
  }
  return rows.filter((r) => r.some((c) => c.trim() !== ''));
}

const HEADINGS: Record<string, keyof Columns> = {
  date: 'date',
  day: 'date',
  time: 'time',
  start: 'time',
  'start time': 'time',
  'team a': 'teamA',
  teama: 'teamA',
  team1: 'teamA',
  'team 1': 'teamA',
  home: 'teamA',
  'team b': 'teamB',
  teamb: 'teamB',
  team2: 'teamB',
  'team 2': 'teamB',
  away: 'teamB',
  name: 'name',
  title: 'name',
  match: 'match',
  'match no': 'match',
  'match number': 'match',
  number: 'match',
  no: 'match',
};

interface Columns {
  date: number;
  time: number;
  teamA: number;
  teamB: number;
  name: number;
  match: number;
}

export function readFixtures(text: string): ParsedFixtures {
  const table = parseCsv(text);
  const problems: ParsedFixtures['problems'] = [];
  const rows: FixtureRow[] = [];
  if (table.length === 0) {
    return { rows, problems: [{ line: 1, message: 'The file is empty.' }] };
  }

  const columns: Columns = { date: -1, time: -1, teamA: -1, teamB: -1, name: -1, match: -1 };
  table[0].forEach((heading, index) => {
    const key = HEADINGS[heading.trim().toLowerCase().replace(/[_.]/g, ' ').replace(/\s+/g, ' ')];
    if (key && columns[key] === -1) columns[key] = index;
  });
  const missing = (['date', 'time', 'teamA', 'teamB'] as const).filter((key) => columns[key] === -1);
  if (missing.length > 0) {
    return {
      rows,
      problems: [
        {
          line: 1,
          message: `The first line needs the headings date, time, team a and team b (missing: ${missing
            .map((m) => (m === 'teamA' ? 'team a' : m === 'teamB' ? 'team b' : m))
            .join(', ')}).`,
        },
      ],
    };
  }

  table.slice(1).forEach((cells, index) => {
    const line = index + 2;
    const cell = (column: number) => (column >= 0 ? (cells[column] ?? '').trim() : '');
    const date = parseDate(cell(columns.date));
    const time = parseTime(cell(columns.time));
    const teamA = cell(columns.teamA);
    const teamB = cell(columns.teamB);
    const matchText = cell(columns.match);

    if (!date) return problems.push({ line, message: `"${cell(columns.date)}" is not a date I can read.` });
    if (!time) return problems.push({ line, message: `"${cell(columns.time)}" is not a time I can read.` });
    if (!teamA || !teamB) return problems.push({ line, message: 'Both teams are needed.' });
    let contestNumber: number | undefined;
    if (matchText) {
      contestNumber = Number(matchText);
      if (!Number.isInteger(contestNumber) || contestNumber < 1) {
        return problems.push({ line, message: `"${matchText}" is not a match number.` });
      }
    }

    const local = new Date(date.year, date.month - 1, date.day, time.hours, time.minutes);
    rows.push({
      teamA,
      teamB,
      startTime: local.toISOString(),
      ...(cell(columns.name) ? { name: cell(columns.name) } : {}),
      ...(contestNumber ? { contestNumber } : {}),
    });
  });

  if (rows.length === 0 && problems.length === 0) {
    problems.push({ line: 2, message: 'There are no matches under the headings.' });
  }
  return { rows, problems };
}

function parseDate(text: string): { year: number; month: number; day: number } | null {
  const iso = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(text);
  const dayFirst = /^(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})$/.exec(text);
  const result = iso
    ? { year: +iso[1], month: +iso[2], day: +iso[3] }
    : dayFirst
      ? { year: +dayFirst[3], month: +dayFirst[2], day: +dayFirst[1] }
      : null;
  if (!result) return null;
  const check = new Date(result.year, result.month - 1, result.day);
  return check.getMonth() === result.month - 1 && check.getDate() === result.day ? result : null;
}

function parseTime(text: string): { hours: number; minutes: number } | null {
  const m = /^(\d{1,2})[:.](\d{2})(?::\d{2})?\s*([ap]\.?m\.?)?$/i.exec(text.trim());
  if (!m) return null;
  let hours = +m[1];
  const minutes = +m[2];
  const half = m[3]?.toLowerCase().replace(/\./g, '');
  if (half) {
    if (hours < 1 || hours > 12) return null;
    if (half === 'pm' && hours !== 12) hours += 12;
    if (half === 'am' && hours === 12) hours = 0;
  }
  if (hours > 23 || minutes > 59) return null;
  return { hours, minutes };
}
