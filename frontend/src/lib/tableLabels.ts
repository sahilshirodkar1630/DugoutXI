/**
 * Copies each column's heading onto its cells as `data-label`, so that on a phone, where a table is shown as a stack
 * of cards (see index.css), every value says what it is. Doing it here, once, keeps the tables themselves ordinary
 * markup instead of repeating each heading on every cell by hand.
 *
 * Only writes an attribute when it would change, so running it from a MutationObserver cannot loop.
 */
export function labelTables(root: ParentNode) {
  root.querySelectorAll('table').forEach((table) => {
    const headings = Array.from(table.querySelectorAll('thead th')).map((th) => th.textContent?.trim() ?? '');
    if (headings.length === 0) return;
    table.querySelectorAll('tbody tr').forEach((row) => {
      let column = 0;
      Array.from(row.children).forEach((cell) => {
        const span = (cell as HTMLTableCellElement).colSpan || 1;
        const label = span > 1 ? '' : headings[column] ?? '';
        if (cell.getAttribute('data-label') !== label) cell.setAttribute('data-label', label);
        column += span;
      });
    });
  });
}
