import type { Key, ReactNode } from 'react';

export interface Column<T> {
  header: string;
  cell: (row: T) => ReactNode;
  /** Right-aligns the column with even-width figures: use it for credits and counts. */
  numeric?: boolean;
}

interface TableProps<T> {
  /** The table's accessible name, such as "Requests". */
  label: string;
  columns: Column<T>[];
  rows: readonly T[];
  rowKey: (row: T) => Key;
  /** Shown instead of the table when there are no rows, usually an EmptyState. */
  empty?: ReactNode;
}

/**
 * A data table. Columns say how to draw a cell from a row:
 * `{ header: 'Quote', cell: (line) => formatCredits(line.quote), numeric: true }`.
 */
export function Table<T>({ label, columns, rows, rowKey, empty }: TableProps<T>) {
  if (rows.length === 0 && empty !== undefined) return <>{empty}</>;
  return (
    <div className="table-wrap">
      <table className="table" aria-label={label}>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column.header} scope="col" className={column.numeric ? 'numeric' : undefined}>
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={rowKey(row)}>
              {columns.map((column) => (
                <td key={column.header} className={column.numeric ? 'numeric' : undefined}>
                  {column.cell(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
