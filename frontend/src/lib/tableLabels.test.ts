import { describe, expect, it } from 'vitest';
import { labelTables } from './tableLabels';

/** On a phone a table is a stack of cards, and each value is labelled with its column's heading. */
describe('labelTables', () => {
  it('copies each heading onto its cells, leaving a cell that spans columns unlabelled', () => {
    const root = document.createElement('div');
    root.innerHTML = `
      <table>
        <thead><tr><th>Player</th><th>Points</th><th></th></tr></thead>
        <tbody>
          <tr><td>Riya</td><td>3</td><td><button>Remove</button></td></tr>
          <tr><td colspan="3">Nobody else yet</td></tr>
        </tbody>
      </table>`;

    labelTables(root);

    const cells = Array.from(root.querySelectorAll('td')).map((td) => td.getAttribute('data-label'));
    expect(cells).toEqual(['Player', 'Points', '', '']);
  });
});
